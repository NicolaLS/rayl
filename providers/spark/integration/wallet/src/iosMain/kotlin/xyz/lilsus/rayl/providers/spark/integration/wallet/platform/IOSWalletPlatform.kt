@file:OptIn(kotlinx.cinterop.BetaInteropApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)

package xyz.lilsus.rayl.providers.spark.integration.wallet.platform

import kotlinx.cinterop.CPointerVarOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.kCFBooleanTrue
import platform.Foundation.CFBridgingRelease
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileProtectionCompleteUntilFirstUserAuthentication
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.writeToFile
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecAuthFailed
import platform.Security.errSecDecode
import platform.Security.errSecDuplicateItem
import platform.Security.errSecInteractionNotAllowed
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleWhenUnlockedThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import xyz.lilsus.rayl.providers.spark.application.AppBootstrapConfig
import xyz.lilsus.rayl.providers.spark.application.payment.SparkPayments
import xyz.lilsus.rayl.providers.spark.application.wallet.CredentialDeleteResult
import xyz.lilsus.rayl.providers.spark.application.wallet.CredentialLoadResult
import xyz.lilsus.rayl.providers.spark.application.wallet.CredentialStoreResult
import xyz.lilsus.rayl.providers.spark.application.wallet.CredentialVault
import xyz.lilsus.rayl.providers.spark.application.wallet.DefaultWalletAccess
import xyz.lilsus.rayl.providers.spark.application.wallet.WalletAccess
import xyz.lilsus.rayl.providers.spark.application.wallet.WalletCredential
import xyz.lilsus.rayl.providers.spark.application.wallet.WalletDirectories
import xyz.lilsus.rayl.providers.spark.application.wallet.clearWalletStorage
import xyz.lilsus.rayl.providers.spark.integration.wallet.spark.BreezSparkSdkConnector
import xyz.lilsus.raylsuite.core.model.WalletStartMode
import xyz.lilsus.raylsuite.core.settings.withKeychainDictionary

/**
 * [storageName] keeps one wallet's credential and SDK data apart from other wallets'; `null` is
 * the storage Rayl 1.1 used for its single Spark wallet.
 */
fun createIOSWalletAccess(
    bootstrapConfig: AppBootstrapConfig,
    startMode: WalletStartMode,
    storageName: String? = null
): WalletAccess {
    val directories = IOSWalletDirectories(storageName)
    val vault = IOSCredentialVault(sparkCredentialService(storageName))
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val payments = SparkPayments()
    val walletAccess = DefaultWalletAccess(
        bootstrapConfig = bootstrapConfig,
        vault = vault,
        directories = directories,
        connector = BreezSparkSdkConnector,
        payments = payments,
        paymentLifecycle = payments,
        applicationScope = applicationScope,
        startMode = startMode
    ).also(WalletAccess::start)
    return walletAccess
}

private class IOSWalletDirectories(storageName: String?) : WalletDirectories {
    private val fileManager = NSFileManager.defaultManager
    private val baseDirectory = (
        NSSearchPathForDirectoriesInDomains(
            NSApplicationSupportDirectory,
            NSUserDomainMask,
            true
        ).first() as String
        ) + "/spark-wallet" + (storageName?.let { ".$it" } ?: "")
    private val resetMarker = "$baseDirectory/reset-pending"

    override val sdkDirectory: String
        get() = "$baseDirectory/breez-spark".also { path ->
            check(fileManager.createDirectoryAtPath(path, true, null, null))
        }

    init {
        fileManager.createDirectoryAtPath(
            baseDirectory,
            withIntermediateDirectories = true,
            attributes = mapOf(
                "NSFileProtectionKey" to NSFileProtectionCompleteUntilFirstUserAuthentication
            ),
            error = null
        )
    }

    override fun sdkStorageIsEmpty(): Boolean =
        fileManager.contentsOfDirectoryAtPath("$baseDirectory/breez-spark", null)
            .isNullOrEmpty()

    override fun beginReset(): Boolean = NSData().writeToFile(resetMarker, atomically = true)

    override fun resetPending(): Boolean = fileManager.fileExistsAtPath(resetMarker)

    override fun deleteWalletData(): Boolean = deleteIfPresent("$baseDirectory/breez-spark")

    override fun finishReset(): Boolean = deleteIfPresent(resetMarker)

    private fun deleteIfPresent(path: String): Boolean =
        !fileManager.fileExistsAtPath(path) || fileManager.removeItemAtPath(path, null)
}

private class IOSCredentialVault(private val service: String) : CredentialVault {
    override suspend fun load(): CredentialLoadResult = withKeychainDictionary(loadMap()) { query ->
        memScoped {
            val result = alloc<CPointerVarOf<CFTypeRef>>()
            when (val status = SecItemCopyMatching(query, result.ptr)) {
                errSecSuccess -> {
                    val data = CFBridgingRelease(result.value) as? NSData
                        ?: return@memScoped CredentialLoadResult.Corrupt
                    val value = NSString.create(data, NSUTF8StringEncoding)?.toString()
                        ?: return@memScoped CredentialLoadResult.Corrupt
                    try {
                        CredentialLoadResult.Available(WalletCredential.decode(value))
                    } catch (_: IllegalArgumentException) {
                        CredentialLoadResult.Corrupt
                    }
                }

                errSecItemNotFound -> CredentialLoadResult.Absent

                errSecInteractionNotAllowed, errSecAuthFailed -> CredentialLoadResult.Unavailable

                errSecDecode -> CredentialLoadResult.Corrupt

                else -> {
                    platform.Foundation.NSLog(
                        "Spark credential read failed (Keychain status: $status)"
                    )
                    CredentialLoadResult.Unavailable
                }
            }
        }
    }

    override suspend fun store(credential: WalletCredential): CredentialStoreResult {
        val data =
            NSString.create(string = credential.encoded()).dataUsingEncoding(NSUTF8StringEncoding)
                ?: return CredentialStoreResult.FAILED
        val attributes = baseMap() + mapOf(
            kSecAttrAccessible to kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
            kSecValueData to data
        )
        return withKeychainDictionary(attributes) { query ->
            when (SecItemAdd(query, null)) {
                errSecSuccess -> CredentialStoreResult.STORED
                errSecDuplicateItem -> CredentialStoreResult.ALREADY_EXISTS
                errSecInteractionNotAllowed, errSecAuthFailed -> CredentialStoreResult.UNAVAILABLE
                else -> CredentialStoreResult.FAILED
            }
        }
    }

    override suspend fun delete(): CredentialDeleteResult =
        withKeychainDictionary(baseMap()) { query ->
            when (SecItemDelete(query)) {
                errSecSuccess -> CredentialDeleteResult.DELETED
                errSecItemNotFound -> CredentialDeleteResult.ABSENT
                else -> CredentialDeleteResult.FAILED
            }
        }

    private fun baseMap(): Map<CFStringRef?, Any?> = mapOf(
        kSecClass to kSecClassGenericPassword,
        kSecAttrService to NSString.create(string = service),
        kSecAttrAccount to NSString.create(string = "singleton-mnemonic-v1")
    )

    private fun loadMap(): Map<CFStringRef?, Any?> = baseMap() + mapOf(
        kSecMatchLimit to kSecMatchLimitOne,
        kSecReturnData to kCFBooleanTrue
    )
}

suspend fun clearIOSWalletStorage(storageName: String? = null): Boolean = clearWalletStorage(
    IOSCredentialVault(sparkCredentialService(storageName)),
    IOSWalletDirectories(storageName)
)

/** The Keychain service of one wallet's recovery phrase; `null` is Rayl 1.1's single wallet. */
fun sparkCredentialService(storageName: String?): String =
    SPARK_CREDENTIAL_SERVICE + (storageName?.let { ".$it" } ?: "")

/** The Keychain service prefix of every Spark wallet's recovery phrase. */
const val SPARK_CREDENTIAL_SERVICE = "xyz.lilsus.rayl.providers.spark.wallet"
