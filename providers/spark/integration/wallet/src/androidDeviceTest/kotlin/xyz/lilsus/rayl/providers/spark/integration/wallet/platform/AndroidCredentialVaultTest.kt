package xyz.lilsus.rayl.providers.spark.integration.wallet.platform

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import xyz.lilsus.rayl.providers.spark.application.wallet.CredentialDeleteResult
import xyz.lilsus.rayl.providers.spark.application.wallet.CredentialLoadResult
import xyz.lilsus.rayl.providers.spark.application.wallet.CredentialStoreResult
import xyz.lilsus.rayl.providers.spark.application.wallet.WalletCredential

/**
 * The Spark recovery phrase on a real Android Keystore (SPEC-wallet-lifecycle;
 * qa/stub-assumptions.md A10). The host tests use `TestVault` with these rules. The vault uses
 * the app's real storage location, so the tests clear it before and after.
 */
@RunWith(AndroidJUnit4::class)
class AndroidCredentialVaultTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val vault = AndroidCredentialVault(context)
    private val credential = WalletCredential.fromMnemonic(PHRASE, passphrase = "Exactly As Typed ")

    @BeforeTest
    @AfterTest
    fun clean() = runTest {
        vault.delete()
        Unit
    }

    @Test
    fun aStoredPhraseAndPassphraseAreReadBackExactly() = runTest {
        assertEquals(CredentialLoadResult.Absent, vault.load())

        assertEquals(CredentialStoreResult.STORED, vault.store(credential))

        val loaded = assertIs<CredentialLoadResult.Available>(
            AndroidCredentialVault(context).load()
        ).credential
        assertEquals(PHRASE, loaded.mnemonic)
        assertEquals("Exactly As Typed ", loaded.passphrase)
    }

    @Test
    fun anExistingPhraseIsNeverReplaced() = runTest {
        vault.store(credential)

        assertEquals(
            CredentialStoreResult.ALREADY_EXISTS,
            vault.store(WalletCredential.fromMnemonic(OTHER))
        )
        assertEquals(
            PHRASE,
            assertIs<CredentialLoadResult.Available>(vault.load()).credential.mnemonic
        )
    }

    @Test
    fun aPhraseWhoseKeystoreEntryIsGoneIsCorruptNotAbsent() = runTest {
        // "Absent" would look like no wallet was ever set up; "corrupt" leads to recovery.
        vault.store(credential)
        keyStore().deleteEntry(KEY_ALIAS)

        assertEquals(CredentialLoadResult.Corrupt, vault.load())
    }

    @Test
    fun aDamagedFileIsCorruptNotAbsent() = runTest {
        vault.store(credential)
        File(context.noBackupFilesDir, "wallet-credential/v1.bin").writeBytes(ByteArray(40) { 7 })

        assertEquals(CredentialLoadResult.Corrupt, vault.load())
    }

    @Test
    fun deleteRemovesThePhraseAndItsKeystoreEntry() = runTest {
        vault.store(credential)

        assertEquals(CredentialDeleteResult.DELETED, vault.delete())

        assertEquals(CredentialLoadResult.Absent, vault.load())
        assertEquals(false, keyStore().containsAlias(KEY_ALIAS))
        assertEquals(CredentialDeleteResult.ABSENT, vault.delete())
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private companion object {
        const val KEY_ALIAS = "spark.wallet.credential.v1"
        const val PHRASE =
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        const val OTHER = "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong"
    }
}
