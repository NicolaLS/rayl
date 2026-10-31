package xyz.lilsus.rayl.providers.rayl.integration.pocket

import android.app.Activity
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import breez_sdk_spark.PasskeyProviderOptions
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import technology.breez.spark.passkey.PasskeyProvider

fun createAndroidRaylPocket(context: Context, config: PocketConfig): RaylPocket {
    val appContext = context.applicationContext
    val network = config.network.storageName
    val directory = File(appContext.noBackupFilesDir, "rayl-pocket/$network")
    val sdkDirectory = File(directory, "sdk")
    return RaylPocket(
        keys = AndroidPocketKeyStore(appContext, directory, network),
        connector = BreezPocketConnector(config) {
            sdkDirectory.also { check(it.isDirectory || it.mkdirs()) }.absolutePath
        },
        eraseWalletData = {
            withContext(Dispatchers.IO) {
                !sdkDirectory.exists() || sdkDirectory.deleteRecursively()
            }
        }
    )
}

/**
 * The setup of the pocket [pocket]: derives its key from a passkey for the relying party [rpId],
 * prompting over [activity], which must be the resumed one. Without [rpId] it uses the stand-in
 * [FilePrfProvider], which works on emulators and needs no relying party.
 */
fun createAndroidPocketSetup(
    context: Context,
    config: PocketConfig,
    pocket: RaylPocket,
    rpId: String?,
    activity: () -> Activity
): PocketSetup {
    val provider = if (rpId != null) {
        PasskeyProvider(
            activityProvider = activity,
            options = PasskeyProviderOptions(
                rpId = rpId,
                rpName = PASSKEY_NAME,
                userName = PASSKEY_NAME,
                userDisplayName = PASSKEY_NAME
            )
        )
    } else {
        filePrfProvider(context)
    }
    return PasskeyPocketSetup(pocket, BreezPasskeyAccounts(provider, config.apiKey))
}

/** How the passkey is named in the system's passkey manager. */
private const val PASSKEY_NAME = "Rayl"

private fun filePrfProvider(context: Context): FilePrfProvider {
    // Kept apart from the pocket so erasing the pocket in a debug build keeps the "passkey".
    val secretFile =
        File(context.applicationContext.noBackupFilesDir, "rayl-passkey/file-prf-secret")
    val secret = filePrfSecret(
        read = {
            if (secretFile.isFile) {
                secretFile.readBytes().also { check(it.size == FilePrfProvider.SECRET_BYTES) }
            } else {
                null
            }
        },
        write = { bytes ->
            val directory = checkNotNull(secretFile.parentFile)
            check(directory.isDirectory || directory.mkdirs())
            val temporary = File(directory, "file-prf-secret.tmp")
            FileOutputStream(temporary).use {
                it.write(bytes)
                it.fd.sync()
            }
            check(temporary.renameTo(secretFile))
        }
    )
    return FilePrfProvider(secret)
}

internal actual fun secureRandomBytes(size: Int): ByteArray =
    ByteArray(size).also(SecureRandom()::nextBytes)

/**
 * Encrypts the phrase with a non-exportable Android Keystore key, outside Auto Backup, so this
 * copy is lost on uninstall or a new phone; the passkey derives it again.
 */
private class AndroidPocketKeyStore(
    context: Context,
    private val directory: File,
    network: String
) : PocketKeyStore {
    private val payload = File(directory, "key.v1")
    private val associatedData =
        "${context.packageName}:rayl-pocket-key-v1:$network".encodeToByteArray()
    private val keyAlias = "rayl.pocket.key.v1.$network"

    override suspend fun load(): PocketKeyLoad = withContext(Dispatchers.IO) {
        if (!payload.isFile) return@withContext PocketKeyLoad.Absent
        try {
            val bytes = payload.readBytes()
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    existingKey() ?: return@withContext PocketKeyLoad.Unavailable,
                    GCMParameterSpec(TAG_BITS, bytes, 0, IV_SIZE)
                )
                updateAAD(associatedData)
            }
            val phrase = cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE).decodeToString()
            PocketKeyLoad.Present(PocketKey(phrase))
        } catch (_: Exception) {
            PocketKeyLoad.Unavailable
        }
    }

    override suspend fun add(key: PocketKey): Boolean = withContext(Dispatchers.IO) {
        if (payload.exists()) return@withContext false
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, existingKey() ?: generateKey())
                updateAAD(associatedData)
            }
            val bytes = cipher.iv + cipher.doFinal(key.phrase.encodeToByteArray())
            check(cipher.iv.size == IV_SIZE)
            check(directory.isDirectory || directory.mkdirs())
            val temporary = File(directory, "key.v1.tmp")
            FileOutputStream(temporary).use {
                it.write(bytes)
                it.fd.sync()
            }
            temporary.renameTo(payload)
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun delete(): Boolean = withContext(Dispatchers.IO) {
        try {
            payload.delete()
            keyStore().deleteEntry(keyAlias)
            !payload.exists() && !keyStore().containsAlias(keyAlias)
        } catch (_: Exception) {
            false
        }
    }

    private fun existingKey(): SecretKey? = keyStore().getKey(keyAlias, null) as? SecretKey

    private fun generateKey(): SecretKey =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generateKey()
        }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_BITS = 128
    }
}
