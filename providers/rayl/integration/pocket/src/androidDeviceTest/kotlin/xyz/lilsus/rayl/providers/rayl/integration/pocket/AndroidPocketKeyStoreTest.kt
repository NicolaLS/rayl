package xyz.lilsus.rayl.providers.rayl.integration.pocket

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/**
 * The pocket's key copy on a real Android Keystore (SPEC-wallet-lifecycle "The Rayl pocket";
 * qa/stub-assumptions.md A10). The host tests use a fake store with these rules.
 */
@RunWith(AndroidJUnit4::class)
class AndroidPocketKeyStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.noBackupFilesDir, "rayl-pocket-test")
    private val network = "qa-test"
    private val alias = "rayl.pocket.key.v1.$network"

    private fun store() = AndroidPocketKeyStore(context, directory, network)

    @BeforeTest
    @AfterTest
    fun clean() {
        directory.deleteRecursively()
        keyStore().deleteEntry(alias)
    }

    @Test
    fun aStoredKeyIsReadBackAfterARestart() = runTest {
        assertEquals(PocketKeyLoad.Absent, store().load())

        assertTrue(store().add(PocketKey(PHRASE)))

        val loaded = assertIs<PocketKeyLoad.Present>(store().load())
        assertEquals(PHRASE, loaded.key.phrase)
    }

    @Test
    fun aKeyGeneratedWithoutAPasskeyIsReadBackAsOne() = runTest {
        // Its phrase is the only backup, shown in Settings: a restart must not hide it.
        assertTrue(store().add(PocketKey(PHRASE, withoutPasskey = true)))

        val loaded = assertIs<PocketKeyLoad.Present>(store().load())
        assertEquals(PHRASE, loaded.key.phrase)
        assertTrue(loaded.key.withoutPasskey)
    }

    @Test
    fun anExistingKeyIsNeverReplaced() = runTest {
        store().add(PocketKey(PHRASE))

        assertFalse(store().add(PocketKey("another phrase")))
        assertEquals(PHRASE, assertIs<PocketKeyLoad.Present>(store().load()).key.phrase)
    }

    @Test
    fun aKeyWhoseKeystoreEntryIsGoneIsUnreadableNotAbsent() = runTest {
        // E.g. the Keystore was reset: the copy exists but can't be read. "Absent" would let the
        // passkey set up the wallet over it silently; "unavailable" keeps it and offers recovery.
        store().add(PocketKey(PHRASE))
        keyStore().deleteEntry(alias)

        assertEquals(PocketKeyLoad.Unavailable, store().load())
        assertFalse(store().add(PocketKey("another phrase")))
    }

    @Test
    fun aDamagedKeyFileIsUnreadableNotAbsent() = runTest {
        store().add(PocketKey(PHRASE))
        File(directory, "key.v1").writeBytes(ByteArray(40) { 7 })

        assertEquals(PocketKeyLoad.Unavailable, store().load())
    }

    @Test
    fun deleteRemovesTheCopyAndItsKeystoreEntry() = runTest {
        store().add(PocketKey(PHRASE))

        assertTrue(store().delete())

        assertEquals(PocketKeyLoad.Absent, store().load())
        assertFalse(keyStore().containsAlias(alias))
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private companion object {
        const val PHRASE =
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    }
}
