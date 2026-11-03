package xyz.lilsus.raylsuite.feature.paymentflow

import com.russhwolf.settings.MapSettings
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.Feature
import fr.acinq.lightning.FeatureSupport
import fr.acinq.lightning.Features
import fr.acinq.lightning.payment.Bolt11Invoice
import fr.acinq.lightning.utils.currentTimestampSeconds
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.utils.toByteVector32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import xyz.lilsus.raylsuite.core.model.DisplayAmount
import xyz.lilsus.raylsuite.core.model.DisplayCurrency
import xyz.lilsus.raylsuite.core.model.LightningAddress
import xyz.lilsus.raylsuite.core.model.PaymentConfirmationMode
import xyz.lilsus.raylsuite.core.model.StoredAmount
import xyz.lilsus.raylsuite.core.payment.LnurlPayClient
import xyz.lilsus.raylsuite.core.payment.LnurlPayMetadata
import xyz.lilsus.raylsuite.core.payment.LnurlPayParams
import xyz.lilsus.raylsuite.core.payment.LnurlResult
import xyz.lilsus.raylsuite.core.ui.platform.HapticFeedbackManager
import xyz.lilsus.raylsuite.feature.currencysettings.DefaultCurrencyPreferences
import xyz.lilsus.raylsuite.feature.paymenthub.DefaultPaymentHubRepository
import xyz.lilsus.raylsuite.feature.paymenthub.HubWidgetDraft
import xyz.lilsus.raylsuite.feature.paymenthub.HubWidgetKind
import xyz.lilsus.raylsuite.feature.paymenthub.LocalHubWidgets
import xyz.lilsus.raylsuite.feature.paymenthub.host.PaymentHubController
import xyz.lilsus.raylsuite.feature.paymenthub.host.PaymentHubIntent
import xyz.lilsus.raylsuite.feature.paymentsettings.DefaultPaymentPreferencesRepository
import xyz.lilsus.raylsuite.feature.paymentui.PaymentIntent
import xyz.lilsus.raylsuite.feature.paymentui.amount.ManualAmountKey

/**
 * The money path through the shared flow: scan → admission → confirmation or Auto Pay →
 * settlement → outcome and session history (risks PR-01 to PR-04). Only the edges are faked:
 * the wallet's settlement, LNURL, haptics and time. Preferences, history and the Hub are real.
 *
 * Default settings: confirmation from 10,000 sats, display in sats.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PaymentFlowCoordinatorTest {
    @Test
    fun aScanBelowTheThresholdPaysWithoutConfirmation() = flowTest {
        scan(invoice(sats = 9_999))

        assertEquals(1, settlement.settled.size)
        assertIs<PaymentUiState.Success>(coordinator.uiState.value)
        assertEquals(TransactionStatus.Success, coordinator.sessionTransactions.value.single().status)
    }

    @Test
    fun aScanAtTheThresholdPaysOnlyAfterConfirmation() = flowTest {
        scan(invoice(sats = 10_000))

        assertIs<PaymentUiState.Confirm>(coordinator.uiState.value)
        assertEquals(0, settlement.settled.size)

        dispatch(PaymentIntent.ConfirmPaymentSubmit)

        assertEquals(1, settlement.settled.size)
        assertIs<PaymentUiState.Success>(coordinator.uiState.value)
    }

    @Test
    fun dismissingTheConfirmationPaysNothing() = flowTest {
        scan(invoice(sats = 10_000))

        dispatch(PaymentIntent.ConfirmPaymentDismiss)
        dispatch(PaymentIntent.ConfirmPaymentSubmit)

        assertEquals(0, settlement.settled.size)
        assertEquals(PaymentUiState.Active, coordinator.uiState.value)
    }

    @Test
    fun alwaysModeAsksEvenForTheSmallestAmount() = flowTest {
        preferences.setConfirmationMode(PaymentConfirmationMode.Always)

        scan(invoice(sats = 1))

        assertIs<PaymentUiState.Confirm>(coordinator.uiState.value)
        assertEquals(0, settlement.settled.size)
    }

    @Test
    fun theReceiptShowsTheAmountPaidAndTheFee() = flowTest {
        settlement.outcome = SettlementOutcome.Paid(feeMsats = 3_000, preimage = null)

        scan(invoice(sats = 9_999))

        assertEquals(
            PaymentUiState.Success(
                amountPaid = DisplayAmount(9_999, DisplayCurrency.Satoshi),
                feePaid = DisplayAmount(3, DisplayCurrency.Satoshi)
            ),
            coordinator.uiState.value
        )
    }

    @Test
    fun aFeeTheWalletDidNotReportIsUnknownNotZero() = flowTest {
        // MOB-94: Blink and NWC may omit the fee. Showing "0 sat" would state a fee nobody knows.
        settlement.outcome = SettlementOutcome.Paid(feeMsats = null, preimage = null)

        scan(invoice(sats = 9_999))

        assertNull(assertIs<PaymentUiState.Success>(coordinator.uiState.value).feePaid)
        val recorded = coordinator.sessionTransactions.value.single()
        assertNull(recorded.fee)
        assertNull(assertNotNull(recorded.receipt).fee)
        assertNull(assertNotNull(recorded.receipt).feeSats)
        assertNull(assertIs<PaymentUiState.Success>(recorded.toDetailUiState()).feePaid)
    }

    @Test
    fun payingANewLightningAddressOffersToSaveItAsAContact() = flowTest {
        dispatch(PaymentIntent.QrCodeScanned("bob@example.com"))
        listOf(1, 0, 0).forEach { dispatch(PaymentIntent.ManualAmountKeyPress(ManualAmountKey.Digit(it))) }
        dispatch(PaymentIntent.ManualAmountSubmit)

        assertIs<PaymentUiState.Success>(coordinator.uiState.value)
        assertEquals("bob@example.com", hub.state.value.savePrompt?.address?.full)
    }

    @Test
    fun anEnteredAmountIsPaidExactlyAndCountsAsTheConfirmation() = flowTest {
        // An amountless invoice asks for the amount. Submitting it is the authorization, even
        // above the threshold.
        scan(invoice(sats = null))
        assertIs<PaymentUiState.EnterAmount>(coordinator.uiState.value)

        listOf(2, 0, 0, 0, 0).forEach { dispatch(PaymentIntent.ManualAmountKeyPress(ManualAmountKey.Digit(it))) }
        dispatch(PaymentIntent.ManualAmountSubmit)

        assertEquals(20_000_000L, settlement.settled.single().amountOverrideMsats)
        assertIs<PaymentUiState.Success>(coordinator.uiState.value)
    }

    @Test
    fun anUnknownOutcomeIsShownButNotRecordedAsFailed() = flowTest {
        // It may have paid: no failure is recorded that would invite paying again (PR-04).
        settlement.outcome = SettlementOutcome.Unknown(PaymentUiError.NetworkUnavailable)

        scan(invoice(sats = 100))

        assertEquals(PaymentUiState.Error(PaymentUiError.NetworkUnavailable), coordinator.uiState.value)
        assertTrue(coordinator.sessionTransactions.value.isEmpty())
    }

    @Test
    fun aDefinitelyFailedPaymentIsRecordedAndTheSameInvoiceCanBeRetried() = flowTest {
        settlement.outcome = SettlementOutcome.Failed(PaymentUiError.NetworkUnavailable)
        val invoice = invoice(sats = 100)

        scan(invoice)
        assertEquals(TransactionStatus.Failure, coordinator.sessionTransactions.value.single().status)
        dispatch(PaymentIntent.DismissResult)
        settlement.outcome = SettlementOutcome.Paid(feeMsats = 0, preimage = null)
        scan(invoice)

        assertEquals(2, settlement.settled.size)
        assertIs<PaymentUiState.Success>(coordinator.uiState.value)
    }

    @Test
    fun aPaidInvoiceScannedAgainOpensItsReceiptInsteadOfPaying() = flowTest {
        // PR-03: the session's own duplicate protection, before any wallet is asked.
        val invoice = invoice(sats = 100)
        scan(invoice)
        dispatch(PaymentIntent.DismissResult)

        scan(invoice)

        assertEquals(1, settlement.settled.size)
        assertNotNull(coordinator.transactionDetailNavigationTarget.value)
    }

    @Test
    fun aSecondScanWhileAPaymentIsInFlightIsIgnored() = flowTest {
        // PR-03: a double scan or a second QR code during payment never starts another one.
        val inFlight = CompletableDeferred<Unit>()
        settlement.gate = inFlight
        scan(invoice(sats = 100))
        assertEquals(1, settlement.settled.size)

        scan(invoice(sats = 200))
        inFlight.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertEquals(1, settlement.settled.size)
        assertEquals(100_000L, settlement.settled.single().invoice.amount?.msat)
    }

    @Test
    fun aWalletThatCannotPreparePaysNothing() = flowTest {
        settlement.preparation = { SettlementPreparation.Unavailable(PaymentUiError.NetworkUnavailable) }

        scan(invoice(sats = 100))

        assertEquals(0, settlement.settled.size)
        assertEquals(PaymentUiState.Error(PaymentUiError.NetworkUnavailable), coordinator.uiState.value)
    }

    @Test
    fun aWalletThatKnowsTheInvoiceIsPaidShowsAlreadyPaidWithoutPaying() = flowTest {
        // MOB-90: e.g. after a restart, when the session no longer remembers the payment.
        settlement.preparation = { SettlementPreparation.AlreadyPaid }
        val invoice = invoice(sats = 100)

        scan(invoice)

        assertEquals(0, settlement.settled.size)
        assertEquals(true, (coordinator.uiState.value as? PaymentUiState.Success)?.wasAlreadyPaid)
        // Recorded, so the next scan in this session opens the receipt.
        dispatch(PaymentIntent.DismissResult)
        scan(invoice)
        assertNotNull(coordinator.transactionDetailNavigationTarget.value)
    }

    @Test
    fun anExpiredInvoiceIsRejectedBeforeTheWalletIsAsked() = flowTest {
        scan(invoice(sats = 100, createdSecondsAgo = 7_200, expirySeconds = 3_600))

        assertEquals(0, settlement.prepared)
        assertIs<PaymentUiState.Error>(coordinator.uiState.value)
    }

    @Test
    fun aHubPresetAlwaysAsksForConfirmationEvenBelowTheThreshold() = flowTest {
        // PR-02: a preset pays a stored amount to a saved recipient, so it is always reviewed.
        val alice = hubRepository.saveContact(assertNotNull(LightningAddress.parse("alice@example.com")), "Alice")
        val shortcut = assertNotNull(
            hubRepository.saveWidget(
                HubWidgetDraft(
                    "local.shortcut",
                    HubWidgetKind.Shortcut,
                    LocalHubWidgets.Single,
                    contactIds = listOf(alice.id),
                    amount = StoredAmount(500, "SAT")
                )
            )
        )

        hub.dispatch(PaymentHubIntent.SelectItem(assertNotNull(shortcut.targetId)))
        testScheduler.advanceUntilIdle()

        assertIs<PaymentUiState.Confirm>(coordinator.uiState.value)
        assertEquals(0, settlement.settled.size)

        dispatch(PaymentIntent.ConfirmPaymentSubmit)

        assertEquals(500_000L, settlement.settled.single().invoice.amount?.msat)
        assertIs<PaymentUiState.Success>(coordinator.uiState.value)
    }

    private class Flow(val test: TestScope) {
        private val settings = MapSettings()
        val preferences = DefaultPaymentPreferencesRepository(settings)
        val settlement = RecordingSettlement()
        val hubRepository = DefaultPaymentHubRepository(settings)
        val hub = PaymentHubController(hubRepository, test.backgroundScope)
        val coordinator = PaymentFlowCoordinator(
            settlement = settlement,
            lnurlPayClient = LnurlServer,
            bitcoinPriceProvider = { null },
            currencyPreferences = DefaultCurrencyPreferences(settings),
            paymentPreferences = preferences,
            paymentHub = hub,
            haptics = NoHaptics,
            coroutineContext = StandardTestDispatcher(test.testScheduler)
        )
        val testScheduler get() = test.testScheduler

        fun dispatch(intent: PaymentIntent) {
            coordinator.dispatch(intent)
            test.testScheduler.advanceUntilIdle()
        }

        fun scan(invoice: Bolt11Invoice) = dispatch(PaymentIntent.QrCodeScanned(invoice.write()))
    }

    private fun flowTest(block: suspend Flow.() -> Unit) = runTest {
        val flow = Flow(this)
        testScheduler.advanceUntilIdle()
        try {
            flow.block()
        } finally {
            flow.coordinator.clear()
        }
    }

    private var invoices = 0

    private fun invoice(sats: Long?, createdSecondsAgo: Long = 0, expirySeconds: Long? = null): Bolt11Invoice {
        invoices += 1
        return testInvoice(sats?.let { it * 1_000 }, "payment-$invoices", createdSecondsAgo, expirySeconds)
    }
}

private fun testInvoice(amountMsats: Long?, seed: String, createdSecondsAgo: Long = 0, expirySeconds: Long? = null): Bolt11Invoice =
    Bolt11Invoice.create(
        chain = Chain.Mainnet,
        amount = amountMsats?.msat,
        paymentHash = Crypto.sha256(seed.encodeToByteArray()).toByteVector32(),
        privateKey = PrivateKey(ByteArray(32) { 1 }),
        description = Either.Left("coffee"),
        minFinalCltvExpiryDelta = Bolt11Invoice.DEFAULT_MIN_FINAL_EXPIRY_DELTA,
        features =
            Features(
                Feature.VariableLengthOnion to FeatureSupport.Optional,
                Feature.PaymentSecret to FeatureSupport.Optional
            ),
        timestampSeconds = currentTimestampSeconds() - createdSecondsAgo,
        expirySeconds = expirySeconds
    )

private class RecordingSettlement : PaymentSettlement<PreparedPayment> {
    var preparation: (PreparedPayment) -> SettlementPreparation<PreparedPayment> = { SettlementPreparation.Ready(it) }
    var outcome: SettlementOutcome = SettlementOutcome.Paid(feeMsats = 1_000, preimage = null)
    var gate: CompletableDeferred<Unit>? = null
    var prepared = 0
    val settled = mutableListOf<PreparedPayment>()

    override suspend fun prepare(request: PreparedPayment): SettlementPreparation<PreparedPayment> {
        prepared += 1
        return preparation(request)
    }

    override suspend fun settle(payment: PreparedPayment): SettlementOutcome {
        settled += payment
        gate?.await()
        return outcome
    }
}

/** A well-behaved LNURL server for any Lightning address: "coffee", 1 sat to 1,000,000 sats. */
private object LnurlServer : LnurlPayClient {
    private const val METADATA = "[[\"text/plain\",\"coffee\"]]"

    override suspend fun fetchPayParams(endpoint: String): LnurlResult<LnurlPayParams> = error("not used")

    override suspend fun fetchPayParams(address: LightningAddress): LnurlResult<LnurlPayParams> = LnurlResult.Success(
        LnurlPayParams(
            callback = "https://${address.domain}/callback",
            minSendable = 1_000L,
            maxSendable = 1_000_000_000L,
            metadataRaw = METADATA,
            metadata = LnurlPayMetadata(
                plainText = "coffee",
                longText = null,
                imagePng = null,
                imageJpeg = null,
                identifier = null,
                email = null,
                tag = null
            ),
            commentAllowed = null,
            domain = address.domain
        )
    )

    override suspend fun requestInvoice(callback: String, amountMsats: Long, comment: String?): LnurlResult<String> =
        LnurlResult.Success(testInvoice(amountMsats, "lnurl-$amountMsats").write())
}

private object NoHaptics : HapticFeedbackManager {
    override fun notifyScanSuccess() = Unit

    override fun notifyPaymentSuccess() = Unit
}
