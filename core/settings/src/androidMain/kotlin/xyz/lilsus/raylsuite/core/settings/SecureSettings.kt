package xyz.lilsus.raylsuite.core.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.russhwolf.settings.Settings
import com.russhwolf.settings.SharedPreferencesSettings
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Composable
fun rememberSecureSettings(storageName: String): SecureStringStore {
    require(storageName.isNotBlank()) { "Secure storage name cannot be blank" }
    val context = LocalContext.current.applicationContext
    return remember(context, storageName) {
        createSecureSettings(context, storageName)
    }
}

fun createSecureSettings(context: Context, storageName: String): SecureStringStore {
    require(storageName.isNotBlank()) { "Secure storage name cannot be blank" }
    val preferences =
        SharedPreferencesSettings(
            context.getSharedPreferences(
                "${storageName}_encrypted",
                Context.MODE_PRIVATE
            )
        )
    return EncryptedStringSettings(
        delegate = preferences,
        keyAlias = "xyz.lilsus.raylsuite.$storageName"
    )
}

private class EncryptedStringSettings(
    private val delegate: Settings,
    private val keyAlias: String
) : SecureStringStore {
    @Volatile
    private var cachedKey: SecretKey? = null

    override fun putString(key: String, value: String) {
        delegate.putString(key, encrypt(value, secretKey()))
    }

    override fun getStringOrNull(key: String): String? {
        val encrypted = delegate.getStringOrNull(key) ?: return null
        return runCatching { decrypt(encrypted, secretKey()) }.getOrElse {
            // Re-fetch once in case the cached key reference became stale.
            cachedKey = null
            runCatching { decrypt(encrypted, secretKey()) }.getOrNull()
        }
    }

    override fun remove(key: String) {
        delegate.remove(key)
    }

    override fun clear() {
        delegate.clear()
    }

    private fun secretKey(): SecretKey =
        cachedKey ?: getOrCreateSecretKey(keyAlias).also { cachedKey = it }
}

private fun encrypt(value: String, secretKey: SecretKey): String {
    val cipher =
        Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, secretKey)
        }
    val combined = cipher.iv + cipher.doFinal(value.encodeToByteArray())
    return Base64.encodeToString(combined, Base64.NO_WRAP)
}

private fun decrypt(encoded: String, secretKey: SecretKey): String {
    val combined = Base64.decode(encoded, Base64.NO_WRAP)
    require(combined.size > GCM_IV_SIZE_BYTES) { "Invalid encrypted value" }

    val cipher = Cipher.getInstance(TRANSFORMATION)
    val iv = combined.copyOfRange(0, GCM_IV_SIZE_BYTES)
    val ciphertext = combined.copyOfRange(GCM_IV_SIZE_BYTES, combined.size)
    cipher.init(
        Cipher.DECRYPT_MODE,
        secretKey,
        GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
    )
    return cipher.doFinal(ciphertext).decodeToString()
}

private fun getOrCreateSecretKey(keyAlias: String): SecretKey {
    val keyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply {
            load(null)
        }
    val existing = keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry
    return existing?.secretKey ?: createSecretKey(keyAlias)
}

private fun createSecretKey(keyAlias: String): SecretKey {
    val keyGenerator =
        KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
    val specification =
        KeyGenParameterSpec
            .Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
    keyGenerator.init(specification)
    return keyGenerator.generateKey()
}

private const val TRANSFORMATION = "AES/GCM/NoPadding"
private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val GCM_IV_SIZE_BYTES = 12
private const val GCM_TAG_LENGTH_BITS = 128
