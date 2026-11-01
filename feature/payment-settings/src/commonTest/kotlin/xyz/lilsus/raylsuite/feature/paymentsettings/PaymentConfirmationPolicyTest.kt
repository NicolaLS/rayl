package xyz.lilsus.raylsuite.feature.paymentsettings

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import xyz.lilsus.raylsuite.core.model.PaymentConfirmationMode
import xyz.lilsus.raylsuite.core.model.PaymentPreferences

/**
 * Decision table for whether a payment needs the user's confirmation (risk PR-02):
 *
 * | Rule | Manual entry | Preset target | Mode   | Amount vs threshold | Confirm |
 * |------|--------------|---------------|--------|---------------------|---------|
 * | R1   | yes          | any           | any    | any                 | no      |
 * | R2   | no           | yes           | any    | any                 | yes     |
 * | R3   | no           | no            | Always | any                 | yes     |
 * | R4   | no           | no            | Above  | below               | no      |
 * | R5   | no           | no            | Above  | equal or above      | yes     |
 *
 * R5 includes the threshold itself: confirmation starts *at* the threshold.
 */
class PaymentConfirmationPolicyTest {
    private enum class AmountBand { Below, Equal, Above }

    @Test
    fun everyCombinationOfConditionsFollowsTheDecisionTable() = runTest {
        val thresholdSats = PaymentPreferences.DEFAULT_CONFIRMATION_THRESHOLD_SATS
        for (mode in PaymentConfirmationMode.entries) {
            val policy = policyWith(mode = mode, thresholdSats = thresholdSats)
            for (isManualEntry in listOf(true, false)) {
                for (isPresetTarget in listOf(true, false)) {
                    for (band in AmountBand.entries) {
                        val amountSats = when (band) {
                            AmountBand.Below -> thresholdSats - 1
                            AmountBand.Equal -> thresholdSats
                            AmountBand.Above -> thresholdSats + 1
                        }
                        val expected = when {
                            isManualEntry -> false
                            isPresetTarget -> true
                            mode == PaymentConfirmationMode.Always -> true
                            else -> band != AmountBand.Below
                        }

                        assertEquals(
                            expected,
                            policy.shouldConfirm(
                                amountMsats = amountSats * MSATS_PER_SAT,
                                isManualEntry = isManualEntry,
                                isPresetTarget = isPresetTarget
                            ),
                            "mode=$mode manual=$isManualEntry preset=$isPresetTarget amount=$band"
                        )
                    }
                }
            }
        }
    }

    @Test
    fun confirmationStartsAtTheExactThresholdForEverySelectableStep() = runTest {
        // Amounts arrive in msats and the threshold is in sats, so the boundary must hold to
        // the last msat: one msat below the threshold pays automatically, the threshold asks.
        for (thresholdSats in PaymentPreferences.THRESHOLD_STEPS) {
            val policy = policyWith(PaymentConfirmationMode.Above, thresholdSats)
            val thresholdMsats = thresholdSats * MSATS_PER_SAT

            assertFalse(
                policy.shouldConfirm(amountMsats = thresholdMsats - 1, isManualEntry = false),
                "1 msat below $thresholdSats sats"
            )
            assertTrue(
                policy.shouldConfirm(amountMsats = thresholdMsats, isManualEntry = false),
                "exactly $thresholdSats sats"
            )
        }
    }

    @Test
    fun aZeroAmountPaysWithoutConfirmationInAboveMode() = runTest {
        val policy = policyWith(PaymentConfirmationMode.Above, thresholdSats = 500L)

        assertFalse(policy.shouldConfirm(amountMsats = 0L, isManualEntry = false))
    }

    @Test
    fun aNegativeAmountIsRejected() = runTest {
        val policy = policyWith(PaymentConfirmationMode.Above, thresholdSats = 500L)

        assertFailsWith<IllegalArgumentException> {
            policy.shouldConfirm(amountMsats = -1L, isManualEntry = false)
        }
    }

    @Test
    fun aStoredThresholdAboveTheMaximumStillAsksFromTheMaximum() = runTest {
        // A corrupted or outdated stored threshold must not let large payments skip confirmation.
        val settings = MapSettings().apply {
            putLong("payments.confirmationThresholdSats", Long.MAX_VALUE)
        }
        val policy = PaymentConfirmationPolicy(DefaultPaymentPreferencesRepository(settings))
        val maximumMsats = PaymentPreferences.MAX_CONFIRMATION_THRESHOLD_SATS * MSATS_PER_SAT

        assertTrue(policy.shouldConfirm(amountMsats = maximumMsats, isManualEntry = false))
    }

    @Test
    fun aThresholdChangeAppliesToTheNextDecision() = runTest {
        val repository = DefaultPaymentPreferencesRepository(MapSettings())
        val policy = PaymentConfirmationPolicy(repository)
        val amountMsats = 2_000L * MSATS_PER_SAT

        assertFalse(policy.shouldConfirm(amountMsats = amountMsats, isManualEntry = false))

        repository.setConfirmationThreshold(1_000L)

        assertTrue(policy.shouldConfirm(amountMsats = amountMsats, isManualEntry = false))
    }

    private suspend fun policyWith(mode: PaymentConfirmationMode, thresholdSats: Long): PaymentConfirmationPolicy {
        val repository = DefaultPaymentPreferencesRepository(MapSettings())
        repository.setConfirmationMode(mode)
        repository.setConfirmationThreshold(thresholdSats)
        return PaymentConfirmationPolicy(repository)
    }

    private companion object {
        const val MSATS_PER_SAT = 1_000L
    }
}
