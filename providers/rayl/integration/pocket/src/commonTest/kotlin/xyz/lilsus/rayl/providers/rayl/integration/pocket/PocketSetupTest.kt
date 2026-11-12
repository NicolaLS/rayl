package xyz.lilsus.rayl.providers.rayl.integration.pocket

import fr.acinq.bitcoin.MnemonicCode
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class PocketSetupTest {
    @Test
    fun aPasskeyWithoutAccountsUsesItsDefaultWallet() = runTest {
        val keys = FakeKeyStore()
        val setup = setup(keys, FakeAccounts())

        assertEquals(PocketSetupResult.Done, setup.start())

        assertEquals("phrase Default", keys.storedPhrase())
    }

    @Test
    fun aNewPasskeyNeverAsksForAnAccount() = runTest {
        val keys = FakeKeyStore()
        // The default label may already be listed while it is published in the background.
        val accounts = FakeAccounts(created = true, listed = listOf("Default"))

        assertEquals(PocketSetupResult.Done, setup(keys, accounts).start())

        assertEquals("phrase Default", keys.storedPhrase())
        assertEquals(0, accounts.lists)
    }

    @Test
    fun existingAccountsAreOfferedWithRaylsOwn() = runTest {
        val accounts = FakeAccounts(labels = listOf("Default", "spending", "Default"))

        val result = setup(FakeKeyStore(), accounts).start()

        assertEquals(PocketSetupResult.ChooseAccount(listOf("Default", "spending"), true), result)
    }

    @Test
    fun accountsHiddenByARelayFailureWhileConnectingAreListedAgain() = runTest {
        val accounts = FakeAccounts(listed = listOf("Rayl"))

        val result = setup(FakeKeyStore(), accounts).start()

        assertEquals(PocketSetupResult.ChooseAccount(listOf("Rayl"), canCreate = false), result)
    }

    @Test
    fun aRelayFailureFailsInsteadOfOpeningTheDefaultWallet() = runTest {
        val keys = FakeKeyStore()
        val accounts = FakeAccounts(listFailure = PasskeyAccountsException.Other("relays"))

        assertEquals(PocketSetupResult.Failed("relays"), setup(keys, accounts).start())

        assertEquals(PocketKeyLoad.Absent, keys.load())
    }

    @Test
    fun choosingTheOpenedAccountNeedsNoOtherPrompt() = runTest {
        val keys = FakeKeyStore()
        val accounts = FakeAccounts(labels = listOf("Default", "spending"))
        val setup = setup(keys, accounts)
        setup.start()

        assertEquals(PocketSetupResult.Done, setup.choose("Default"))

        assertEquals("phrase Default", keys.storedPhrase())
        assertEquals(emptyList(), accounts.opened)
    }

    @Test
    fun choosingAnotherAccountOpensItOnTheSamePasskey() = runTest {
        val keys = FakeKeyStore()
        val accounts = FakeAccounts(labels = listOf("spending"), credentialId = byteArrayOf(7))
        val setup = setup(keys, accounts)
        setup.start()

        assertEquals(PocketSetupResult.Done, setup.choose("spending"))

        assertEquals("phrase spending", keys.storedPhrase())
        assertEquals("spending", accounts.opened.single().first)
        assertContentEquals(byteArrayOf(7), accounts.opened.single().second)
    }

    @Test
    fun creatingRaylsAccountPublishesIt() = runTest {
        val keys = FakeKeyStore()
        val accounts = FakeAccounts(labels = listOf("Default"))
        val setup = setup(keys, accounts)
        setup.start()

        assertEquals(PocketSetupResult.Done, setup.createAccount())

        assertEquals("phrase Rayl", keys.storedPhrase())
        assertEquals(listOf("Rayl"), accounts.published)
    }

    @Test
    fun aFailedPublicationStillUsesRaylsAccount() = runTest {
        val keys = FakeKeyStore()
        val accounts = FakeAccounts(
            labels = listOf("Default"),
            publishFailure = PasskeyAccountsException.Other("relays")
        )
        val setup = setup(keys, accounts)
        setup.start()

        assertEquals(PocketSetupResult.Done, setup.createAccount())

        assertEquals("phrase Rayl", keys.storedPhrase())
    }

    @Test
    fun aDismissedPromptOnlyCancels() = runTest {
        val keys = FakeKeyStore()
        val accounts = FakeAccounts(connectFailure = PasskeyAccountsException.Cancelled)

        assertEquals(PocketSetupResult.Cancelled, setup(keys, accounts).start())

        assertEquals(PocketKeyLoad.Absent, keys.load())
    }

    @Test
    fun aPasskeyWithoutPrfIsSignedInAgainUnpinned() = runTest {
        val accounts = FakeAccounts(
            connectFailure = PasskeyAccountsException.CreatedButNotDerived(byteArrayOf(9), "flaky")
        )
        val setup = setup(FakeKeyStore(), accounts)
        setup.start()
        accounts.connectFailure = PasskeyAccountsException.WithoutPrf

        assertEquals(PocketSetupResult.PasskeyWithoutPrf, setup.start())
        accounts.connectFailure = null
        assertEquals(PocketSetupResult.Done, setup.start())

        // Signing in again lets the user pick another passkey; it never creates one.
        assertNull(accounts.connected.last())
        assertEquals(0, accounts.registrations.size)
    }

    @Test
    fun aPasskeyCreatedWithoutPrfIsReplacedByANewOne() = runTest {
        val keys = FakeKeyStore()
        val accounts = FakeAccounts(
            connectFailure = PasskeyAccountsException.CreatedWithoutPrf(byteArrayOf(5))
        )
        val setup = setup(keys, accounts)

        assertEquals(PocketSetupResult.PasskeyWithoutPrf, setup.start())
        assertEquals(PocketSetupResult.Done, setup.start())

        assertContentEquals(byteArrayOf(5), accounts.registrations.single().single())
        assertEquals("phrase Default", keys.storedPhrase())
    }

    @Test
    fun aPasskeyCreatedButNotDerivedIsReusedOnRetry() = runTest {
        val accounts = FakeAccounts(
            connectFailure = PasskeyAccountsException.CreatedButNotDerived(byteArrayOf(9), "prf")
        )
        val setup = setup(FakeKeyStore(), accounts)

        assertIs<PocketSetupResult.Failed>(setup.start())
        accounts.connectFailure = null
        assertEquals(PocketSetupResult.Done, setup.start())

        assertNull(accounts.connected.first())
        assertContentEquals(byteArrayOf(9), accounts.connected.last())
    }

    @Test
    fun anAvailabilityErrorMeansUnavailable() = runTest {
        val accounts = FakeAccounts(availabilityFailure = PasskeyAccountsException.Other("config"))

        assertFalse(setup(FakeKeyStore(), accounts).isAvailable())
    }

    @Test
    fun withoutAPasskeyTheKeyIsGeneratedOnThisDevice() = runTest {
        val keys = FakeKeyStore()
        val accounts = FakeAccounts()
        val setup = setup(keys, accounts)

        assertEquals(PocketSetupResult.Done, setup.createWithoutPasskey())

        val key = (keys.load() as PocketKeyLoad.Present).key
        assertTrue(key.withoutPasskey)
        // A standard twelve-word phrase, so any Spark wallet can import it.
        assertEquals(12, key.phrase.split(" ").size)
        MnemonicCode.validate(key.phrase)
        assertTrue(setup.hasKeyWithoutPasskey())
        // No passkey prompt, and nothing published to the passkey's relays.
        assertTrue(accounts.connected.isEmpty() && accounts.registrations.isEmpty())
        assertTrue(accounts.published.isEmpty())
    }

    @Test
    fun everyKeyGeneratedWithoutAPasskeyIsDifferent() = runTest {
        val first = FakeKeyStore().also { setup(it, FakeAccounts()).createWithoutPasskey() }
        val second = FakeKeyStore().also { setup(it, FakeAccounts()).createWithoutPasskey() }

        assertNotEquals(first.storedPhrase(), second.storedPhrase())
    }

    @Test
    fun aPasskeyDerivedKeyHasNoRecoveryPhrase() = runTest {
        val setup = setup(FakeKeyStore(), FakeAccounts())

        setup.start()

        assertFalse(setup.hasKeyWithoutPasskey())
    }

    private fun TestScope.setup(keys: FakeKeyStore, accounts: FakeAccounts): PocketSetup = PasskeyPocketSetup(
        RaylPocket(keys, FakeConnector(), { true }, StandardTestDispatcher(testScheduler)),
        accounts
    )

    private suspend fun FakeKeyStore.storedPhrase(): String = (load() as PocketKeyLoad.Present).key.phrase
}

private class FakeAccounts(
    private val labels: List<String> = emptyList(),
    private val listed: List<String> = emptyList(),
    private val created: Boolean = false,
    private val credentialId: ByteArray? = null,
    var connectFailure: Exception? = null,
    private val listFailure: Exception? = null,
    private val publishFailure: Exception? = null,
    private val availabilityFailure: Exception? = null
) : PasskeyAccounts {
    val connected = mutableListOf<ByteArray?>()
    val opened = mutableListOf<Pair<String, ByteArray?>>()
    val published = mutableListOf<String>()
    val registrations = mutableListOf<List<ByteArray>>()
    var lists = 0

    override suspend fun isAvailable(): Boolean {
        availabilityFailure?.let { throw it }
        return true
    }

    override suspend fun connect(credentialId: ByteArray?): PasskeyConnection {
        connected += credentialId
        connectFailure?.let { throw it }
        return PasskeyConnection(
            label = "Default",
            phrase = "phrase Default",
            labels = labels,
            created = created,
            credentialId = this.credentialId
        )
    }

    override suspend fun register(excludeCredentials: List<ByteArray>): PasskeyConnection {
        registrations += excludeCredentials
        return PasskeyConnection("Default", "phrase Default", emptyList(), true, null)
    }

    override suspend fun open(label: String, credentialId: ByteArray?): String {
        opened += label to credentialId
        return "phrase $label"
    }

    override suspend fun list(): List<String> {
        lists++
        listFailure?.let { throw it }
        return listed
    }

    override suspend fun publish(label: String) {
        publishFailure?.let { throw it }
        published += label
    }

    override fun close() = Unit
}
