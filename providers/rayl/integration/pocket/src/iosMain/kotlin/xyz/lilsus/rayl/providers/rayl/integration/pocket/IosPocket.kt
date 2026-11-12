@file:OptIn(kotlinx.cinterop.BetaInteropApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)

package xyz.lilsus.rayl.providers.rayl.integration.pocket

import kotlinx.cinterop.CPointerVarOf
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.kCFBooleanTrue
import platform.Foundation.CFBridgingRelease
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.dataWithBytes
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecRandomDefault
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.posix.memcpy
import xyz.lilsus.raylsuite.core.settings.withKeychainDictionary

fun createIosRaylPocket(config: PocketConfig): RaylPocket {
    val directory = (
        NSSearchPathForDirectoriesInDomains(
            NSApplicationSupportDirectory,
            NSUserDomainMask,
            true
        ).first() as String
        ) + "/rayl-pocket/${config.network.storageName}/sdk"
    val fileManager = NSFileManager.defaultManager
    return RaylPocket(
        keys = IosPocketKeyStore(config.network.storageName),
        connector = BreezPocketConnector(
            config,
            storageIsEmpty = {
                fileManager.contentsOfDirectoryAtPath(directory, null).isNullOrEmpty()
            }
        ) {
            directory.also {
                check(fileManager.createDirectoryAtPath(it, true, null, null))
                // Like Android's no-backup directory: wallet data stays out of device backups.
                NSURL.fileURLWithPath(it).setResourceValue(true, NSURLIsExcludedFromBackupKey, null)
            }
        },
        eraseWalletData = {
            !fileManager.fileExistsAtPath(directory) ||
                fileManager.removeItemAtPath(directory, null)
        }
    )
}

/**
 * The setup of the pocket [pocket]: derives its key from the device's passkeys through
 * [passkeys], or from the stand-in [FilePrfProvider] without one.
 */
fun createIosPocketSetup(
    config: PocketConfig,
    pocket: RaylPocket,
    passkeys: RaylPasskeyAuthenticator?
): PocketSetup {
    if (passkeys != null) {
        return PasskeyPocketSetup(
            pocket,
            BreezPasskeyAccounts(IosPrfProvider(passkeys), config.apiKey)
        )
    }
    // Kept apart from the pocket so erasing the pocket in a debug build keeps the "passkey".
    val directory = (
        NSSearchPathForDirectoriesInDomains(
            NSApplicationSupportDirectory,
            NSUserDomainMask,
            true
        ).first() as String
        ) + "/rayl-passkey"
    val path = "$directory/file-prf-secret"
    val fileManager = NSFileManager.defaultManager
    val secret = filePrfSecret(
        read = {
            NSData.dataWithContentsOfFile(path)?.toByteArray()?.also {
                check(it.size == FilePrfProvider.SECRET_BYTES)
            }
        },
        write = { bytes ->
            check(fileManager.createDirectoryAtPath(directory, true, null, null))
            NSURL.fileURLWithPath(
                directory
            ).setResourceValue(true, NSURLIsExcludedFromBackupKey, null)
            check(bytes.toNSData().writeToFile(path, atomically = true))
        }
    )
    return PasskeyPocketSetup(pocket, BreezPasskeyAccounts(FilePrfProvider(secret), config.apiKey))
}

private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    val bytes = ByteArray(size)
    if (size > 0) bytes.usePinned { memcpy(it.addressOf(0), this.bytes, length) }
    return bytes
}

private fun ByteArray.toNSData(): NSData = usePinned {
    NSData.dataWithBytes(it.addressOf(0), size.convert())
}

internal actual fun secureRandomBytes(size: Int): ByteArray {
    val bytes = ByteArray(size)
    val status = bytes.usePinned {
        SecRandomCopyBytes(kSecRandomDefault, size.convert(), it.addressOf(0))
    }
    check(status == errSecSuccess) { "Secure random generation failed: $status" }
    return bytes
}

/**
 * A device-only Keychain item: it survives reinstalling but never restores to another device,
 * where the passkey derives the key again. A key generated without a passkey has no such way
 * back, unless the user kept its recovery phrase.
 */
private class IosPocketKeyStore(private val network: String) : PocketKeyStore {
    override suspend fun load(): PocketKeyLoad = withKeychainDictionary(
        baseQuery() + mapOf(kSecMatchLimit to kSecMatchLimitOne, kSecReturnData to kCFBooleanTrue)
    ) { query ->
        memScoped {
            val result = alloc<CPointerVarOf<CFTypeRef>>()
            when (SecItemCopyMatching(query, result.ptr)) {
                errSecSuccess -> {
                    val data = CFBridgingRelease(result.value) as? NSData
                    val phrase = data?.let { NSString.create(it, NSUTF8StringEncoding)?.toString() }
                    if (phrase == null) {
                        PocketKeyLoad.Unavailable
                    } else {
                        PocketKeyLoad.Present(PocketKey.fromStored(phrase))
                    }
                }

                errSecItemNotFound -> PocketKeyLoad.Absent

                else -> PocketKeyLoad.Unavailable
            }
        }
    }

    override suspend fun add(key: PocketKey): Boolean {
        val data = NSString.create(string = key.stored()).dataUsingEncoding(NSUTF8StringEncoding)
            ?: return false
        val attributes = baseQuery() + mapOf(
            kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            kSecValueData to data
        )
        return withKeychainDictionary(attributes) { SecItemAdd(it, null) == errSecSuccess }
    }

    override suspend fun delete(): Boolean = withKeychainDictionary(baseQuery()) {
        when (SecItemDelete(it)) {
            errSecSuccess, errSecItemNotFound -> true
            else -> false
        }
    }

    private fun baseQuery(): Map<CFStringRef?, Any?> = mapOf(
        kSecClass to kSecClassGenericPassword,
        kSecAttrService to NSString.create(string = "xyz.lilsus.rayl.pocket"),
        kSecAttrAccount to NSString.create(string = "pocket-key-v1-$network")
    )
}
