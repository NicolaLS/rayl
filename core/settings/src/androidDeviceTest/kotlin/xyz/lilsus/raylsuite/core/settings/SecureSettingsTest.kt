package xyz.lilsus.raylsuite.core.settings

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyStore
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.runner.RunWith

/**
 * The encrypted settings that hold the Blink API key and the NWC connection, on a real Android
 * Keystore (qa/stub-assumptions.md A10). The host tests use an in-memory store.
 */
@RunWith(AndroidJUnit4::class)
class SecureSettingsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "qa_secure_settings_test"

    private fun store() = createSecureSettings(context, name)

    @BeforeTest
    @AfterTest
    fun clean() {
        store().clear()
        keyStore().deleteEntry("xyz.lilsus.raylsuite.$name")
    }

    @Test
    fun aStoredSecretIsReadBackAfterARestartAndRemoved() {
        store().putString("credentials", SECRET)

        assertEquals(SECRET, store().getStringOrNull("credentials"))

        store().remove("credentials")
        assertNull(store().getStringOrNull("credentials"))
    }

    @Test
    fun aSecretWhoseKeystoreEntryIsGoneReadsAsAbsent() {
        // Characterises QA observation O-11: an undecryptable credential is indistinguishable from
        // a missing one, so Blink and NWC show setup rather than a recovery message. If this
        // changes, update O-11 and the wallet lifecycle traceability (L6).
        store().putString("credentials", SECRET)
        keyStore().deleteEntry("xyz.lilsus.raylsuite.$name")

        assertNull(store().getStringOrNull("credentials"))
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private companion object {
        const val SECRET = "{\"apiKey\":\"blink_qa_secret\"}"
    }
}
