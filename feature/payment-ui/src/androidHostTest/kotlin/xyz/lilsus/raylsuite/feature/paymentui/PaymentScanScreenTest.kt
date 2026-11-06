package xyz.lilsus.raylsuite.feature.paymentui

import android.Manifest
import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import xyz.lilsus.raylsuite.core.camera.TestQrInput
import xyz.lilsus.raylsuite.core.model.DisplayAmount
import xyz.lilsus.raylsuite.core.model.DisplayCurrency
import xyz.lilsus.raylsuite.feature.paymentui.amount.ManualAmountKey
import xyz.lilsus.raylsuite.feature.paymentui.amount.ManualAmountUiState
import xyz.lilsus.raylsuite.feature.paymentui.amount.RangeStatus

/**
 * The Android payment screen per payment state (qa/test-plan.md §6.8): amounts, fee and
 * confirmation are shown as the state says, and paying needs the user's confirmation.
 */
@RunWith(RobolectricTestRunner::class)
class PaymentScanScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val intents = mutableListOf<PaymentIntent>()

    @Before
    fun withoutCamera() {
        TestQrInput.install(null)
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.CAMERA)
    }

    @Test
    fun aConfirmationShowsTheEstimateAndTheExactAmountAndPaysOnlyWhenConfirmed() {
        show(
            PaymentScreenState.Confirm(
                PaymentConfirmationAmount(
                    primary = DisplayAmount(1_234, DisplayCurrency.Fiat("EUR")),
                    exactSats = sats(20_000),
                    primaryIsEstimate = true
                )
            )
        )

        compose.onNodeWithText("≈ €12.34", substring = true).assertExists()
        compose.onNodeWithText("Exact amount: 20,000 sat").assertExists()
        assertEquals(emptyList<PaymentIntent>(), intents)

        compose.onNodeWithTag(PaymentTestTags.CONFIRMATION_PAY_BUTTON).performClick()

        assertEquals(listOf<PaymentIntent>(PaymentIntent.ConfirmPaymentSubmit), intents)
    }

    @Test
    fun dismissingAConfirmationDoesNotPay() {
        show(PaymentScreenState.Confirm(PaymentConfirmationAmount(primary = sats(20_000))))

        compose.onNodeWithTag(PaymentTestTags.CONFIRMATION_DISMISS_BUTTON).performClick()

        assertEquals(listOf<PaymentIntent>(PaymentIntent.ConfirmPaymentDismiss), intents)
    }

    @Test
    fun whilePayingThereIsNothingToConfirmAgain() {
        show(PaymentScreenState.Loading(PaymentLoadingKind.Paying))

        compose.onAllNodesWithTag(PaymentTestTags.CONFIRMATION_PAY_BUTTON).assertCountEquals(0)
        compose.onAllNodesWithText("Pay").assertCountEquals(0)
    }

    @Test
    fun aPaidPaymentShowsItsAmountAndTheFeeTheWalletReported() {
        show(PaymentScreenState.Success(sats(100), feePaid = sats(2), wasAlreadyPaid = false))

        compose.onNodeWithText("Paid", substring = true).assertExists()
        compose.onNodeWithText("100 sat", substring = true).assertExists()
        compose.onNodeWithText("Fee: 2 sat").assertExists()
    }

    @Test
    fun aFeeTheWalletDidNotReportIsShownAsUnknownNotAsZero() {
        show(PaymentScreenState.Success(sats(100), feePaid = null, wasAlreadyPaid = false))

        compose.onNodeWithText("Fee: unknown").assertExists()
        compose.onAllNodesWithText("Fee: 0 sat").assertCountEquals(0)
    }

    @Test
    fun anAlreadyPaidInvoiceSaysThatNothingWasSent() {
        show(PaymentScreenState.Success(sats(100), feePaid = null, wasAlreadyPaid = true))

        compose.onNodeWithText("Already Paid", substring = true).assertExists()
        compose.onNodeWithText("No payment was sent. This invoice was already paid.").assertExists()
    }

    @Test
    fun anErrorShowsItsMessage() {
        show(PaymentScreenState.Error("Insufficient balance in your Blink wallet."))

        compose.onNodeWithText("Insufficient balance in your Blink wallet.").assertExists()
    }

    @Test
    fun typedKeysReachThePaymentFlowInOrder() {
        show(PaymentScreenState.EnterAmount(ManualAmountUiState(null, DisplayCurrency.Satoshi)))

        listOf("1", "5", "0", "backspace").forEach {
            compose.onNodeWithTag("payment_manual_amount_key_$it").performClick()
        }

        assertEquals(
            listOf(
                ManualAmountKey.Digit(1),
                ManualAmountKey.Digit(5),
                ManualAmountKey.Digit(0),
                ManualAmountKey.Backspace
            ).map(PaymentIntent::ManualAmountKeyPress),
            intents
        )
    }

    @Test
    fun anEnteredAmountIsReviewedAndThenPaidOnce() {
        show(
            PaymentScreenState.EnterAmount(ManualAmountUiState(sats(150), DisplayCurrency.Satoshi))
        )

        compose.onNodeWithTag(PaymentTestTags.MANUAL_AMOUNT_CONTINUE_BUTTON).performClick()
        compose.onNodeWithText("150 sat", substring = true).assertExists()
        assertEquals(emptyList<PaymentIntent>(), intents)
        compose.onNodeWithTag(PaymentTestTags.MANUAL_AMOUNT_PAY_BUTTON).performClick()

        assertEquals(listOf<PaymentIntent>(PaymentIntent.ManualAmountSubmit), intents)
    }

    @Test
    fun anAmountBelowTheReceiversMinimumCannotContinue() {
        show(
            PaymentScreenState.EnterAmount(
                ManualAmountUiState(
                    sats(5),
                    DisplayCurrency.Satoshi,
                    rangeStatus = RangeStatus.BelowMin
                )
            )
        )

        compose.onNodeWithText("Amount is too low.").assertExists()
        compose.onNodeWithTag(PaymentTestTags.MANUAL_AMOUNT_CONTINUE_BUTTON).assertIsNotEnabled()
    }

    private fun show(payment: PaymentScreenState) = compose.setContent {
        PaymentScanScreen(
            state = PaymentFlowState(payment, sessionItems = emptyList()),
            messageEvents = emptyFlow(),
            appTitle = "Rayl",
            savePrompt = null,
            onIntent = { intents += it },
            onHubIntent = {},
            onOpenTransaction = {}
        )
    }

    private fun sats(amount: Long) = DisplayAmount(amount, DisplayCurrency.Satoshi)
}
