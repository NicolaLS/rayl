package xyz.lilsus.raylsuite.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import xyz.lilsus.raylsuite.core.model.DisplayAmount
import xyz.lilsus.raylsuite.core.model.DisplayCurrency
import xyz.lilsus.raylsuite.core.model.PaymentConfirmationMode
import xyz.lilsus.raylsuite.core.model.PaymentPreferences
import xyz.lilsus.raylsuite.core.ui.components.AppPrimaryButton
import xyz.lilsus.raylsuite.core.ui.format.rememberAmountFormatter
import xyz.lilsus.raylsuite.feature.onboarding.R

@Composable
fun AutoPaySettingsScreen(
    body: String,
    confirmationMode: PaymentConfirmationMode,
    thresholdSats: Long,
    currencyEquivalent: String?,
    onConfirmationModeChanged: (PaymentConfirmationMode) -> Unit,
    onThresholdChanged: (Long) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val formatter = rememberAmountFormatter()

    OnboardingScaffold(onBack = onBack) {
        Column(
            modifier =
                modifier
                    .fillMaxSize()
                    .testTag(OnboardingTestTags.AUTO_PAY_SCREEN),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.onboarding_autopay_title),
                style = MaterialTheme.typography.headlineMedium
            )

            Text(
                text = body,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            Card(
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
            ) {
                Column(
                    modifier =
                        Modifier
                            .padding(16.dp)
                            .selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ConfirmationModeRow(
                        selected = confirmationMode == PaymentConfirmationMode.Always,
                        label = stringResource(R.string.onboarding_autopay_always),
                        onClick = {
                            onConfirmationModeChanged(PaymentConfirmationMode.Always)
                        }
                    )
                    ConfirmationModeRow(
                        selected = confirmationMode == PaymentConfirmationMode.Above,
                        label = stringResource(R.string.onboarding_autopay_threshold),
                        onClick = {
                            onConfirmationModeChanged(PaymentConfirmationMode.Above)
                        }
                    )

                    if (confirmationMode == PaymentConfirmationMode.Above) {
                        Column(modifier = Modifier.padding(start = 48.dp)) {
                            val satsFormatted =
                                formatter.format(
                                    DisplayAmount(
                                        thresholdSats,
                                        DisplayCurrency.Satoshi
                                    )
                                )
                            val thresholdLabel =
                                stringResource(
                                    R.string.onboarding_autopay_threshold_label,
                                    currencyEquivalent?.let { "$satsFormatted ($it)" }
                                        ?: satsFormatted
                                )
                            Text(
                                text = thresholdLabel,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )

                            ThresholdSlider(
                                thresholdSats = thresholdSats,
                                onThresholdChanged = onThresholdChanged
                            )
                        }
                    }
                }
            }

            Text(
                text = stringResource(R.string.onboarding_autopay_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.weight(1f))

            AppPrimaryButton(
                text = stringResource(R.string.onboarding_autopay_continue),
                onClick = onContinue,
                modifier = Modifier.testTag(OnboardingTestTags.AUTO_PAY_CONTINUE)
            )
        }
    }
}

@Composable
private fun ConfirmationModeRow(selected: Boolean, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = null
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

@Composable
private fun ThresholdSlider(thresholdSats: Long, onThresholdChanged: (Long) -> Unit) {
    val thresholds = PaymentPreferences.THRESHOLD_STEPS
    Slider(
        value = PaymentPreferences.thresholdToStepIndex(thresholdSats).toFloat(),
        onValueChange = { index ->
            onThresholdChanged(thresholds[index.toInt()])
        },
        valueRange = 0f..thresholds.lastIndex.toFloat(),
        steps = thresholds.size - 2
    )
}
