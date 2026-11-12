package xyz.lilsus.rayl.providers.rayl.integration.pocket

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class RaylPocketTest {
    @Test
    fun needsAKeyInsteadOfCreatingOne() = runTest {
        val keys = FakeKeyStore()
        val connector = FakeConnector()
        val pocket = pocket(keys, connector)

        pocket.connect()

        assertEquals(PocketStatus.NeedsKey, pocket.status.value)
        assertEquals(0, keys.adds)
        assertTrue(connector.keys.isEmpty())
        assertFalse(pocket.hasKey())
    }

    @Test
    fun installStoresTheKeyAndConnectsWithIt() = runTest {
        val keys = FakeKeyStore()
        val connector = FakeConnector()
        val pocket = pocket(keys, connector)
        pocket.connect()

        assertTrue(pocket.install(PocketKey("derived")))
        advanceUntilIdle()

        assertEquals("derived", (keys.load() as PocketKeyLoad.Present).key.phrase)
        assertEquals("derived", connector.keys.single().phrase)
        assertEquals(PocketStatus.Ready, pocket.status.value)
        assertTrue(pocket.hasKey())
    }

    @Test
    fun reusesTheStoredKey() = runTest {
        val existing = PocketKey("existing")
        val keys = FakeKeyStore(existing)
        val connector = FakeConnector()

        pocket(keys, connector).connect()

        assertSame(existing, connector.keys.single())
        assertEquals(0, keys.adds)
    }

    @Test
    fun neverReplacesAKeyThatCannotBeRead() = runTest {
        val keys = FakeKeyStore(unavailable = true)
        val connector = FakeConnector()
        val pocket = pocket(keys, connector)

        pocket.connect()

        assertEquals(PocketStatus.KeyUnreadable, pocket.status.value)
        assertEquals(0, keys.adds)
        assertTrue(connector.keys.isEmpty())
    }

    @Test
    fun anUnreadableKeyStillCountsAsAKey() = runTest {
        // Onboarding asks hasKey() to choose between setup and recovery. A key that exists but
        // can't be read (e.g. after the OS invalidated the Keystore) needs recovery: setup would
        // only fail because the store refuses to replace it (PR-09).
        val pocket = pocket(FakeKeyStore(unavailable = true), FakeConnector())

        assertTrue(pocket.hasKey())
    }

    @Test
    fun onlyAKeyGeneratedWithoutAPasskeyShowsItsPhrase() = runTest {
        // A passkey-derived phrase is never shown or exported: its passkey is the backup.
        val derived = pocket(FakeKeyStore(PocketKey("derived")), FakeConnector())
        val generated = pocket(
            FakeKeyStore(PocketKey("generated", withoutPasskey = true)),
            FakeConnector()
        )

        assertNull(derived.recoveryPhrase())
        assertEquals("generated", generated.recoveryPhrase())
    }

    @Test
    fun aStoredKeyRemembersWhetherAPasskeyDerivedIt() {
        // Keys stored by 1.1 are the bare phrase, and all of them came from a passkey.
        val legacy = PocketKey.fromStored("abandon ability able")
        assertEquals("abandon ability able", legacy.phrase)
        assertFalse(legacy.withoutPasskey)
        assertEquals("abandon ability able", legacy.stored())

        val generated = PocketKey.fromStored(PocketKey("abandon ability", true).stored())
        assertEquals("abandon ability", generated.phrase)
        assertTrue(generated.withoutPasskey)
    }

    @Test
    fun doesNotConnectWithAKeyThatWasNotStored() = runTest {
        val keys = FakeKeyStore(acceptAdds = false)
        val connector = FakeConnector()
        val pocket = pocket(keys, connector)

        assertFalse(pocket.install(PocketKey("derived")))
        advanceUntilIdle()

        assertTrue(connector.keys.isEmpty())
    }

    @Test
    fun retryAfterConnectionFailureKeepsTheSameKey() = runTest {
        val keys = FakeKeyStore(PocketKey("stored"))
        val connector = FakeConnector(failures = 1)
        val pocket = pocket(keys, connector)

        pocket.connect()
        assertEquals(PocketStatus.Failed, pocket.status.value)
        pocket.connect()

        assertEquals(PocketStatus.Ready, pocket.status.value)
        assertEquals(0, keys.adds)
        assertSame(connector.keys[0], connector.keys[1])
    }

    @Test
    fun closeKeepsTheKeyAndEraseDeletesIt() = runTest {
        val keys = FakeKeyStore(PocketKey("stored"))
        val connector = FakeConnector()
        var dataErased = false
        val pocket = pocket(keys, connector, eraseData = {
            dataErased = true
            true
        })
        pocket.connect()

        pocket.close()
        assertTrue(connector.wallet.disconnected)
        assertTrue(keys.load() is PocketKeyLoad.Present)
        assertEquals(PocketStatus.Closed, pocket.status.value)

        assertTrue(pocket.erase())
        assertEquals(PocketKeyLoad.Absent, keys.load())
        assertTrue(dataErased)
    }

    @Test
    fun aPaymentDuringStartupWaitsForThePocket() = runTest {
        val pocket = pocket(FakeKeyStore(PocketKey("stored")), FakeConnector())
        pocket.start()

        assertEquals(PocketQuoteResult.Rejected(PocketPaymentProblem.Other, "wallet"), pocket.quote("lnbcrt1", null))
        assertEquals(PocketStatus.Ready, pocket.status.value)
    }

    @Test
    fun aPaymentAfterAFailedStartRetriesTheConnection() = runTest {
        val connector = FakeConnector(failures = 1)
        val pocket = pocket(FakeKeyStore(PocketKey("stored")), connector)
        pocket.start()
        advanceUntilIdle()
        assertEquals(PocketStatus.Failed, pocket.status.value)

        assertEquals(PocketQuoteResult.Rejected(PocketPaymentProblem.Other, "wallet"), pocket.quote("lnbcrt1", null))
        assertEquals(2, connector.keys.size)
    }

    @Test
    fun reportsBalanceAndPaidInvoices() = runTest {
        val connector = FakeConnector()
        val pocket = pocket(FakeKeyStore(PocketKey("stored")), connector)
        val received = mutableListOf<PocketReceipt>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            pocket.receivedPayments.collect { received += it }
        }
        assertNull(pocket.balanceSats.value)

        pocket.connect()
        advanceUntilIdle()
        assertEquals(0L, pocket.balanceSats.value)

        connector.wallet.balance = 2_100
        connector.wallet.events.emit(PocketEvent.Received("lnbcrt1invoice", 2_100))
        advanceUntilIdle()

        assertEquals(2_100L, pocket.balanceSats.value)
        assertEquals(listOf(PocketReceipt("lnbcrt1invoice", 2_100)), received)
    }

    private fun TestScope.pocket(keys: FakeKeyStore, connector: FakeConnector, eraseData: suspend () -> Boolean = { true }) =
        RaylPocket(keys, connector, eraseData, StandardTestDispatcher(testScheduler))
}

internal class FakeKeyStore(
    private var key: PocketKey? = null,
    private val unavailable: Boolean = false,
    private val acceptAdds: Boolean = true
) : PocketKeyStore {
    var adds = 0

    override suspend fun load(): PocketKeyLoad = when {
        unavailable -> PocketKeyLoad.Unavailable
        else -> key?.let(PocketKeyLoad::Present) ?: PocketKeyLoad.Absent
    }

    override suspend fun add(key: PocketKey): Boolean {
        adds++
        if (!acceptAdds || this.key != null) return false
        this.key = key
        return true
    }

    override suspend fun delete(): Boolean {
        key = null
        return true
    }
}

internal class FakeConnector(private var failures: Int = 0) : PocketConnector {
    val keys = mutableListOf<PocketKey>()
    val wallet = FakeWallet()

    override suspend fun connect(key: PocketKey): PocketWallet {
        keys += key
        if (failures-- > 0) error("offline")
        return wallet
    }
}

internal class FakeWallet : PocketWallet {
    override val events = MutableSharedFlow<PocketEvent>()
    var balance = 0L
    var disconnected = false

    override suspend fun balanceSats(): Long = balance

    override suspend fun quote(invoice: String, amountSats: Long?): PocketQuoteResult =
        PocketQuoteResult.Rejected(PocketPaymentProblem.Other, "wallet")

    override suspend fun pay(quote: PocketQuote): PocketPayOutcome = PocketPayOutcome.Failed(PocketPaymentProblem.Other, null)

    override suspend fun createInvoice(amountSats: Long?, description: String): String = "lnbcrt1"

    override suspend fun disconnect() {
        disconnected = true
    }

    override suspend fun identityPubkey(): String = "pocket-identity"
}
