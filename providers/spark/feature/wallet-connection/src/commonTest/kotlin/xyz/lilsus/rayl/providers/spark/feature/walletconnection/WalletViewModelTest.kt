package xyz.lilsus.rayl.providers.spark.feature.walletconnection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import xyz.lilsus.rayl.providers.spark.application.payment.SparkPayments
import xyz.lilsus.rayl.providers.spark.application.wallet.CredentialProblemKind
import xyz.lilsus.rayl.providers.spark.application.wallet.ImportWalletResult
import xyz.lilsus.rayl.providers.spark.application.wallet.RemoveWalletResult
import xyz.lilsus.rayl.providers.spark.application.wallet.WalletAccess
import xyz.lilsus.rayl.providers.spark.application.wallet.WalletAccessState

@OptIn(ExperimentalCoroutinesApi::class)
class WalletViewModelTest {
    @Test
    fun credentialErrorActionsRetryConfirmAndReportRemovalFailure() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val access = ErrorWalletAccess()
        val model = WalletViewModel(access)
        try {
            model.dispatch(WalletAction.Retry)
            runCurrent()
            assertEquals(1, access.retries)
            model.dispatch(WalletAction.RequestRemoval)
            runCurrent()
            assertTrue(model.state.value.confirmRemoval)
            model.dispatch(WalletAction.CancelRemoval)
            runCurrent()
            assertFalse(model.state.value.confirmRemoval)
            assertEquals(0, access.removals)
            model.dispatch(WalletAction.RequestRemoval)
            model.dispatch(WalletAction.ConfirmRemoval)
            runCurrent()
            assertFalse(model.state.value.confirmRemoval)
            assertEquals(1, access.removals)
            assertEquals(WalletMessage.RESET_REQUIRED, model.state.value.message)
            assertEquals(WalletAccessState.ResetRequired, model.state.value.access)
        } finally {
            model.dispose()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun aPastedPhraseFillsEveryFieldAndSetsItsLength() = withModel { model ->
        model.dispatch(WalletAction.RecoveryPhraseChanged(" ${PHRASE_24.uppercase()}\n"))
        runCurrent()

        assertEquals(PHRASE_24.split(" "), model.state.value.words)
        assertTrue(model.state.value.isPhraseComplete)
    }

    private fun withModel(block: suspend kotlinx.coroutines.test.TestScope.(WalletViewModel) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val access = ErrorWalletAccess().apply { state.value = WalletAccessState.NoWallet }
        val model = WalletViewModel(access)
        try {
            block(model)
        } finally {
            model.dispose()
            Dispatchers.resetMain()
        }
    }
}

private class ErrorWalletAccess : WalletAccess {
    override val state = MutableStateFlow<WalletAccessState>(WalletAccessState.CredentialProblem(CredentialProblemKind.UNAVAILABLE))
    override val removed = MutableStateFlow(false)
    override val payments = SparkPayments()
    var retries = 0
    var removals = 0
    override fun start() = Unit
    override suspend fun importWallet(mnemonic: String, passphrase: String) = ImportWalletResult.CONNECTION_FAILED
    override suspend fun retryConnection() {
        retries++
    }
    override suspend fun removeWallet(): RemoveWalletResult {
        removals++
        state.value = WalletAccessState.ResetRequired
        return RemoveWalletResult.RESET_REQUIRED
    }
    override suspend fun close() = Unit
}

private const val PHRASE_24 =
    "legal winner thank year wave sausage worth useful legal winner thank year " +
        "wave sausage worth useful legal winner thank year wave sausage worth title"
