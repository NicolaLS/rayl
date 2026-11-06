package xyz.lilsus.rayl.providers.spark.feature.walletconnection

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import xyz.lilsus.rayl.providers.spark.application.wallet.CredentialProblemKind
import xyz.lilsus.rayl.providers.spark.application.wallet.WalletAccessState
import xyz.lilsus.raylsuite.core.ui.platform.LocalProductName

/**
 * Android's Spark recovery screen (PR-08, PR-12; the iOS side is e2e/flows/spark): an unreadable
 * recovery phrase is explained, retrying keeps it, and removing it, which deletes the phrase,
 * always needs a confirmation that can be cancelled.
 */
@RunWith(RobolectricTestRunner::class)
class WalletRecoveryScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val actions = mutableListOf<WalletAction>()

    @Test
    fun anUnreadablePhraseIsExplainedAndTryAgainOnlyRetries() {
        show(
            WalletUiState(
                access = WalletAccessState.CredentialProblem(CredentialProblemKind.CORRUPT)
            )
        )

        compose.onNodeWithText("The protected recovery phrase or its device key is inconsistent.")
            .assertExists()
        compose.onNodeWithText("Try again").performClick()

        assertEquals(listOf<WalletAction>(WalletAction.Retry), actions)
    }

    @Test
    fun removingTheWalletFirstAsksForConfirmation() {
        show(
            WalletUiState(
                access = WalletAccessState.CredentialProblem(CredentialProblemKind.CORRUPT)
            )
        )

        compose.onNodeWithText("Remove wallet from Rayl").performClick()

        assertEquals(listOf<WalletAction>(WalletAction.RequestRemoval), actions)
    }

    @Test
    fun theConfirmationSaysWhatIsDeletedAndCanBeCancelled() {
        show(
            WalletUiState(
                access = WalletAccessState.CredentialProblem(CredentialProblemKind.CORRUPT),
                confirmRemoval = true
            )
        )

        compose.onNodeWithText("Remove this wallet from Rayl?").assertExists()
        compose.onNodeWithText(
            "This deletes its protected phrase, payment activity, and local SDK data."
        ).assertExists()
        compose.onNodeWithText("Cancel").performClick()

        assertEquals(listOf<WalletAction>(WalletAction.CancelRemoval), actions)
    }

    @Test
    fun onlyConfirmingRemovesTheWallet() {
        show(
            WalletUiState(
                access = WalletAccessState.CredentialProblem(CredentialProblemKind.CORRUPT),
                confirmRemoval = true
            )
        )

        compose.onNodeWithText("Remove").performClick()

        assertEquals(listOf<WalletAction>(WalletAction.ConfirmRemoval), actions)
    }

    @Test
    fun whileTheWalletCannotBeRemovedConfirmingIsOff() {
        compose.setContent {
            CompositionLocalProvider(LocalProductName provides "Rayl") {
                WalletRemovalConfirmation(dispatch = { actions += it }, canRemove = false)
            }
        }

        compose.onNodeWithText("Remove").assertIsNotEnabled()
    }

    private fun show(state: WalletUiState) = compose.setContent {
        CompositionLocalProvider(LocalProductName provides "Rayl") {
            WalletConnectionContent(state = state, dispatch = { actions += it })
        }
    }
}
