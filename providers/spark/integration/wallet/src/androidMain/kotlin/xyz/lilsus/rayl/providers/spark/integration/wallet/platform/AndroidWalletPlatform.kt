package xyz.lilsus.rayl.providers.spark.integration.wallet.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

fun createAndroidWalletAccess(
    context: Context,
    bootstrapConfig: AppBootstrapConfig,
    startMode: WalletStartMode
): WalletAccess {
    val appContext = context.applicationContext
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val payments = SparkPayments()
    val walletAccess = DefaultWalletAccess(
        bootstrapConfig = bootstrapConfig,
        vault = AndroidCredentialVault(appContext),
        directories = AndroidWalletDirectories(appContext),
        connector = BreezSparkSdkConnector,
        payments = payments,
        paymentLifecycle = payments,
        applicationScope = applicationScope,
        startMode = startMode
    ).also(WalletAccess::start)
    return walletAccess
}

private class AndroidWalletDirectories(context: Context) : WalletDirectories {
    private val filesDirectory = context.filesDir
    private val resetMarker = File(context.noBackupFilesDir, "wallet-reset-pending")

    override val sdkDirectory: String
        get() = File(filesDirectory, "breez-spark").also {
            check(it.isDirectory || it.mkdirs()) { "Could not create wallet directory" }
        }.absolutePath

    override fun sdkStorageIsEmpty(): Boolean =
        File(filesDirectory, "breez-spark").list().isNullOrEmpty()

    override fun beginReset(): Boolean = runCatching {
        resetMarker.parentFile?.mkdirs()
        resetMarker.createNewFile() || resetMarker.isFile
    }.getOrDefault(false)

    override fun resetPending(): Boolean = resetMarker.isFile

    override fun deleteWalletData(): Boolean = File(filesDirectory, "breez-spark").let {
        !it.exists() ||
            it.deleteRecursively()
    }

    override fun finishReset(): Boolean = !resetMarker.exists() || resetMarker.delete()
}

internal class AndroidCredentialVault(private val context: Context) : CredentialVault {
    private val payloadDirectory = File(context.noBackupFilesDir, "wallet-credential")
    private val payloadFile = File(payloadDirectory, "v1.bin")
    private val associatedData =
        "${context.packageName}:spark-wallet-credential-v1".encodeToByteArray()

    override suspend fun load(): CredentialLoadResult {
        if (!payloadFile.isFile) return CredentialLoadResult.Absent
        return try {
            val payload = payloadFile.readBytes()
            if (payload.size <= IV_SIZE) return CredentialLoadResult.Corrupt
            val iv = payload.copyOfRange(0, IV_SIZE)
            val ciphertext = payload.copyOfRange(IV_SIZE, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
                updateAAD(associatedData)
            }
            val mnemonic = cipher.doFinal(ciphertext).decodeToString()
            CredentialLoadResult.Available(WalletCredential.decode(mnemonic))
        } catch (_: KeyPermanentlyInvalidatedException) {
            CredentialLoadResult.Invalidated
        } catch (_: UserNotAuthenticatedException) {
            CredentialLoadResult.Unavailable
        } catch (_: AEADBadTagException) {
            CredentialLoadResult.Corrupt
        } catch (_: IllegalArgumentException) {
            CredentialLoadResult.Corrupt
        } catch (_: Throwable) {
            CredentialLoadResult.Corrupt
        }
    }

    override suspend fun store(credential: WalletCredential): CredentialStoreResult {
        if (payloadFile.exists()) return CredentialStoreResult.ALREADY_EXISTS
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, getOrCreateKey())
                updateAAD(associatedData)
            }
            val payload = cipher.iv + cipher.doFinal(credential.encoded().encodeToByteArray())
            check(cipher.iv.size == IV_SIZE)
            payloadDirectory.mkdirs()
            val temporary = File(payloadDirectory, "v1.tmp")
            FileOutputStream(temporary).use {
                it.write(payload)
                it.fd.sync()
            }
            check(temporary.renameTo(payloadFile))
            CredentialStoreResult.STORED
        } catch (_: KeyPermanentlyInvalidatedException) {
            CredentialStoreResult.INVALIDATED
        } catch (_: UserNotAuthenticatedException) {
            CredentialStoreResult.UNAVAILABLE
        } catch (_: Throwable) {
            CredentialStoreResult.FAILED
        }
    }

    override suspend fun delete(): CredentialDeleteResult = try {
        val existed = payloadFile.exists() || keyStore().containsAlias(KEY_ALIAS)
        payloadDirectory.deleteRecursively()
        keyStore().deleteEntry(KEY_ALIAS)
        if (payloadFile.exists() || keyStore().containsAlias(KEY_ALIAS)) {
            CredentialDeleteResult.FAILED
        } else if (existed) {
            CredentialDeleteResult.DELETED
        } else {
            CredentialDeleteResult.ABSENT
        }
    } catch (_: Throwable) {
        CredentialDeleteResult.FAILED
    }

    private fun getOrCreateKey(): SecretKey {
        (keyStore().getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generateKey()
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    companion object {
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "spark.wallet.credential.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
        private const val TAG_LENGTH_BITS = 128
    }
}

suspend fun clearAndroidWalletStorage(context: Context): Boolean = clearWalletStorage(
    AndroidCredentialVault(context.applicationContext),
    AndroidWalletDirectories(context.applicationContext)
)
