package xyz.lilsus.rayl.providers.spark.application.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import xyz.lilsus.rayl.providers.spark.application.AppBootstrapConfig
import xyz.lilsus.rayl.providers.spark.application.AppEnvironment
import xyz.lilsus.rayl.providers.spark.application.payment.PaymentSessionLifecycle
import xyz.lilsus.rayl.providers.spark.application.payment.SparkPaymentClient
import xyz.lilsus.rayl.providers.spark.application.payment.SparkPayments
import xyz.lilsus.rayl.providers.spark.application.payment.SparkQuote
import xyz.lilsus.raylsuite.core.model.WalletStartMode

@OptIn(ExperimentalCoroutinesApi::class)
class WalletLifecycleTest {
    @Test
    fun failedImportCannotReuseASessionWhoseCleanupFailed() = runTest {
        val fixture = LifecycleFixture(backgroundScope, WalletStartMode.Fresh)
        fixture.vault.storeResult = CredentialStoreResult.FAILED
        fixture.session.disconnectAction = { throw IllegalStateException("SDK still running") }
        assertEquals(ImportWalletResult.CREDENTIAL_STORE_FAILED, fixture.access.importWallet(TEST_MNEMONIC))
        assertEquals(WalletAccessState.ResetRequired, fixture.access.state.value)
        assertTrue(fixture.directories.pending)
        assertFalse(fixture.access.removed.value)
        assertEquals(0, fixture.directories.deletions)
        val anotherPhrase =
            "legal winner thank year wave sausage worth useful legal winner thank yellow"
        assertEquals(ImportWalletResult.ALREADY_CONFIGURED, fixture.access.importWallet(anotherPhrase))
        assertEquals(1, fixture.vault.stores)
        assertEquals(1, fixture.connections)
        fixture.session.disconnectAction = {}
        fixture.access.retryConnection()
        assertEquals(WalletAccessState.NoWallet, fixture.access.state.value)
        assertFalse(fixture.directories.pending)
        fixture.vault.storeResult = CredentialStoreResult.STORED
        assertEquals(ImportWalletResult.IMPORTED, fixture.access.importWallet(anotherPhrase))
        assertEquals(2, fixture.connections)
        assertEquals(anotherPhrase, fixture.vault.credential?.mnemonic)
    }

    @Test
    fun freshSetupNeverReadsCredentialsOnStartupRetryOrImport() = runTest {
        val fixture = LifecycleFixture(backgroundScope, WalletStartMode.Fresh)
        fixture.vault.loadAction = { error("Fresh onboarding must never read old credentials") }
        fixture.access.start()
        runCurrent()
        fixture.access.retryConnection()
        assertEquals(WalletAccessState.NoWallet, fixture.access.state.value)
        assertEquals(0, fixture.connections)
        assertEquals(ImportWalletResult.IMPORTED, fixture.access.importWallet(TEST_MNEMONIC))
        assertEquals(0, fixture.vault.loads)
        assertEquals(1, fixture.connections)
        assertEquals(WalletAccessState.Connected, fixture.access.state.value)
    }

    @Test
    fun freshSetupCannotResurrectAnUnexpectedExistingCredential() = runTest {
        val fixture = LifecycleFixture(backgroundScope, WalletStartMode.Fresh)
        fixture.vault.storeResult = CredentialStoreResult.ALREADY_EXISTS
        fixture.vault.loadAction = { error("Conflicting credentials must not be restored") }
        assertEquals(ImportWalletResult.CREDENTIAL_STORE_FAILED, fixture.access.importWallet(TEST_MNEMONIC))
        assertEquals(WalletAccessState.NoWallet, fixture.access.state.value)
        assertEquals(0, fixture.vault.loads)
        assertEquals(1, fixture.session.disconnections)
    }

    @Test
    fun credentialFailureCanBeRetriedAndRemovedWithoutDecrypting() = runTest {
        val fixture = LifecycleFixture(backgroundScope)
        fixture.vault.loadAction = { CredentialLoadResult.Unavailable }
        fixture.access.start()
        runCurrent()
        fixture.access.retryConnection()
        assertEquals(2, fixture.vault.loads)
        assertEquals(WalletAccessState.CredentialProblem(CredentialProblemKind.UNAVAILABLE), fixture.access.state.value)
        assertEquals(RemoveWalletResult.REMOVED, fixture.access.removeWallet())
        assertEquals(2, fixture.vault.loads)
        assertEquals(WalletAccessState.NoWallet, fixture.access.state.value)
        assertTrue(fixture.access.removed.value)
    }

    @Test
    fun standaloneCleanupDoesNotReadCredentialsOrConnect() = runTest {
        val fixture = LifecycleFixture(backgroundScope)
        fixture.vault.loadAction = { error("Cleanup cannot require decryption") }
        assertTrue(clearWalletStorage(fixture.vault, fixture.directories))
        assertEquals(1, fixture.vault.deletions)
        assertEquals(1, fixture.directories.deletions)
        assertEquals(0, fixture.connections)
        assertFalse(fixture.directories.pending)
    }

    @Test
    fun initialAbsenceIsNotRemovalAndNewImportClearsCompletedRemoval() = runTest {
        val fixture = LifecycleFixture(backgroundScope)
        fixture.access.start()
        runCurrent()

        assertEquals(WalletAccessState.NoWallet, fixture.access.state.value)
        assertFalse(fixture.access.removed.value)

        assertEquals(RemoveWalletResult.REMOVED, fixture.access.removeWallet())
        assertTrue(fixture.access.removed.value)
        assertEquals(ImportWalletResult.IMPORTED, fixture.access.importWallet(TEST_MNEMONIC))
        assertFalse(fixture.access.removed.value)
        assertEquals(WalletAccessState.Connected, fixture.access.state.value)
    }

    @Test
    fun closeDisconnectsWithoutWipingAndRejectsFurtherOperations() = runTest {
        val fixture = LifecycleFixture(backgroundScope)
        val credential = WalletCredential.fromMnemonic(TEST_MNEMONIC)
        fixture.vault.credential = credential
        fixture.access.start()
        runCurrent()
        assertEquals(WalletAccessState.Connected, fixture.access.state.value)

        fixture.access.close()
        fixture.access.close()
        fixture.access.start()
        assertFailsWith<CancellationException> { fixture.access.importWallet(TEST_MNEMONIC) }
        assertFailsWith<CancellationException> { fixture.access.retryConnection() }
        assertFailsWith<CancellationException> { fixture.access.removeWallet() }
        runCurrent()

        assertEquals(1, fixture.connections)
        assertEquals(1, fixture.lifecycle.attachments)
        assertEquals(1, fixture.lifecycle.detachments)
        assertEquals(1, fixture.session.disconnections)
        assertSame(credential, fixture.vault.credential)
        fixture.assertNoRemoval()
        assertTrue(backgroundScope.isActive)
        val sibling = backgroundScope.async { "parent still works" }
        assertEquals("parent still works", sibling.await())
    }

    @Test
    fun closeCancelsStartupBeforeCredentialsFinishLoading() = runTest {
        val fixture = LifecycleFixture(backgroundScope)
        var loadingCancelled = false
        fixture.vault.loadAction = {
            try {
                awaitCancellation()
            } finally {
                loadingCancelled = true
            }
        }
        fixture.access.start()
        runCurrent()
        assertEquals(1, fixture.vault.loads)

        fixture.access.close()

        assertTrue(loadingCancelled)
        assertEquals(0, fixture.connections)
        fixture.assertNoRemoval()
        assertTrue(backgroundScope.isActive)
    }

    @Test
    fun closeAwaitsSuspendedStartupConnectionAndReleasesIt() = runTest {
        val fixture = LifecycleFixture(backgroundScope)
        fixture.vault.credential = WalletCredential.fromMnemonic(TEST_MNEMONIC)
        val connected = CompletableDeferred<Unit>()
        fixture.connectAction = {
            connected.await()
            fixture.session
        }
        fixture.access.start()
        runCurrent()
        assertEquals(1, fixture.connections)

        // The SDK keeps running after a cancelled connect, so close owns the late session.
        val closing = async { fixture.access.close() }
        runCurrent()
        assertFalse(closing.isCompleted)
        connected.complete(Unit)
        closing.await()
        fixture.access.start()
        runCurrent()

        assertEquals(1, fixture.connections)
        assertEquals(1, fixture.session.disconnections)
        assertEquals(0, fixture.lifecycle.attachments)
        fixture.assertNoRemoval()
    }

    @Test
    fun timedOutConnectionIsReleasedBeforeAnotherStarts() = runTest {
        val fixture = LifecycleFixture(backgroundScope)
        fixture.vault.credential = WalletCredential.fromMnemonic(TEST_MNEMONIC)
        val connected = CompletableDeferred<Unit>()
        fixture.connectAction = {
            connected.await()
            fixture.session
        }
        fixture.access.start()
        advanceTimeBy(30_001)
        runCurrent()
        assertEquals(WalletAccessState.ReconnectRequired, fixture.access.state.value)

        val retry = async { fixture.access.retryConnection() }
        runCurrent()
        assertFalse(retry.isCompleted)
        assertEquals(1, fixture.connections)

        connected.complete(Unit)
        retry.await()
        assertEquals(1, fixture.session.disconnections)
        assertEquals(2, fixture.connections)
        assertEquals(WalletAccessState.Connected, fixture.access.state.value)
    }

    @Test
    fun closeFailsWhileAConnectionAttemptStillRuns() = runTest {
        val fixture = LifecycleFixture(backgroundScope)
        fixture.vault.credential = WalletCredential.fromMnemonic(TEST_MNEMONIC)
        val connected = CompletableDeferred<Unit>()
        fixture.connectAction = {
            connected.await()
            fixture.session
        }
        fixture.access.start()
        runCurrent()

        assertFailsWith<IllegalStateException> { fixture.access.close() }
        connected.complete(Unit)
        fixture.access.close()

        assertEquals(1, fixture.session.disconnections)
        assertEquals(1, fixture.connections)
        fixture.assertNoRemoval()
    }

    @Test
    fun closeCancelsImportAndOwnsSessionWhoseAttachmentWasInterrupted() = runTest {
        val fixture = LifecycleFixture(backgroundScope)
        fixture.lifecycle.attachAction = { awaitCancellation() }
        val importing = async { fixture.access.importWallet(TEST_MNEMONIC) }
        runCurrent()
        assertEquals(1, fixture.lifecycle.attachments)
        assertFalse(importing.isCompleted)

        fixture.access.close()

        assertFailsWith<CancellationException> { importing.await() }
        assertEquals(1, fixture.lifecycle.detachments)
        assertEquals(1, fixture.session.disconnections)
        assertEquals(0, fixture.vault.stores)
        fixture.assertNoRemoval()
    }

    @Test
    fun failedCloseRemainsTerminalAndCanRetryWithoutReattaching() = runTest {
        val fixture = LifecycleFixture(backgroundScope)
        fixture.vault.credential = WalletCredential.fromMnemonic(TEST_MNEMONIC)
        fixture.access.start()
        runCurrent()
        val failure = IllegalStateException("SDK disconnect failed")
        fixture.session.disconnectAction = { throw failure }

        assertEquals(
            failure.message,
            assertFailsWith<IllegalStateException> { fixture.access.close() }.message
        )
        fixture.access.start()
        assertFailsWith<CancellationException> { fixture.access.retryConnection() }
        assertEquals(1, fixture.lifecycle.attachments)
        fixture.assertNoRemoval()

        fixture.session.disconnectAction = {}
        fixture.access.close()
        fixture.access.close()

        assertEquals(2, fixture.session.disconnections)
        assertEquals(2, fixture.lifecycle.detachments)
        assertEquals(1, fixture.lifecycle.attachments)
        assertEquals(1, fixture.connections)
        fixture.assertNoRemoval()
        assertTrue(backgroundScope.isActive)
    }

    @Test
    fun concurrentCloseCallsShareOneSuccessfulCleanup() = runTest {
        val fixture = LifecycleFixture(backgroundScope)
        fixture.vault.credential = WalletCredential.fromMnemonic(TEST_MNEMONIC)
        fixture.access.start()
        runCurrent()
        val disconnected = CompletableDeferred<Unit>()
        fixture.session.disconnectAction = { disconnected.await() }

        val first = async { fixture.access.close() }
        val second = async { fixture.access.close() }
        runCurrent()
        assertEquals(1, fixture.session.disconnections)
        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)

        disconnected.complete(Unit)
        first.await()
        second.await()
        assertEquals(1, fixture.lifecycle.detachments)
        assertEquals(1, fixture.session.disconnections)
    }
}

private class LifecycleFixture(scope: CoroutineScope, startMode: WalletStartMode = WalletStartMode.Restore) {
    val vault = LifecycleVault()
    val directories = LifecycleDirectories()
    val session = LifecycleSession()
    val lifecycle = TestPaymentLifecycle()
    var connections = 0
    var connectAction: suspend () -> SparkSdkSession = { session }
    val access = DefaultWalletAccess(
        bootstrapConfig = AppBootstrapConfig(AppEnvironment.DEBUG),
        vault = vault,
        directories = directories,
        connector = SparkSdkConnector { _, _, _, _ ->
            connections += 1
            connectAction()
        },
        payments = SparkPayments(),
        paymentLifecycle = lifecycle,
        applicationScope = scope,
        startMode = startMode
    )

    fun assertNoRemoval() {
        assertEquals(0, vault.deletions)
        assertEquals(0, directories.deletions)
        assertFalse(directories.pending)
        assertFalse(access.removed.value)
    }
}

private class LifecycleVault : CredentialVault {
    var credential: WalletCredential? = null
    var loads = 0
    var stores = 0
    var storeResult = CredentialStoreResult.STORED
    var deletions = 0
    var loadAction: suspend () -> CredentialLoadResult = {
        credential?.let { CredentialLoadResult.Available(it) } ?: CredentialLoadResult.Absent
    }

    override suspend fun load(): CredentialLoadResult {
        loads += 1
        return loadAction()
    }

    override suspend fun store(credential: WalletCredential): CredentialStoreResult {
        stores += 1
        if (storeResult != CredentialStoreResult.STORED) return storeResult
        this.credential = credential
        return CredentialStoreResult.STORED
    }

    override suspend fun delete(): CredentialDeleteResult {
        deletions += 1
        credential = null
        return CredentialDeleteResult.DELETED
    }
}

private class LifecycleDirectories : WalletDirectories {
    override val sdkDirectory = "test-sdk-directory"

    override fun sdkStorageIsEmpty(): Boolean = false
    var pending = false
    var deletions = 0

    override fun beginReset(): Boolean {
        pending = true
        return true
    }

    override fun resetPending(): Boolean = pending

    override fun deleteWalletData(): Boolean {
        deletions += 1
        return true
    }

    override fun finishReset(): Boolean {
        pending = false
        return true
    }
}

private class LifecycleSession : SparkSdkSession {
    override val paymentClient = UnusedPaymentClient
    var disconnections = 0
    var disconnectAction: suspend () -> Unit = {}

    override suspend fun disconnect() {
        disconnections += 1
        disconnectAction()
    }
}

private class TestPaymentLifecycle : PaymentSessionLifecycle {
    var attachments = 0
    var detachments = 0
    var attachAction: suspend () -> Unit = {}

    override suspend fun attach(client: SparkPaymentClient) {
        attachments += 1
        attachAction()
    }

    override suspend fun detach() {
        detachments += 1
    }
}

private object UnusedPaymentClient : SparkPaymentClient {
    override suspend fun quote(invoice: String, amountSats: Long?): Nothing = error("No payment expected")
    override suspend fun pay(quote: SparkQuote): Nothing = error("No payment expected")
}

private const val TEST_MNEMONIC =
    "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
