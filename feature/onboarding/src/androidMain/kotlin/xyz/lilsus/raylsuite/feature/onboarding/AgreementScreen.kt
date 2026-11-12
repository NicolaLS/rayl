package xyz.lilsus.raylsuite.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import xyz.lilsus.raylsuite.core.ui.components.AppPrimaryButton
import xyz.lilsus.raylsuite.feature.onboarding.R

/** A wallet's terms: what the user takes on, their consent, and the action it unlocks. */
@Composable
fun AgreementScreen(
    body: String,
    hasAgreed: Boolean,
    onAgreementChanged: (Boolean) -> Unit,
    onContinue: () -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    isWorking: Boolean = false,
    message: String? = null,
    /** A second way to continue, shown under the primary one. */
    secondaryAction: String? = null,
    onSecondaryAction: () -> Unit = {}
) {
    OnboardingScaffold(onBack = onBack) {
        Column(
            modifier = modifier.fillMaxSize().testTag(OnboardingTestTags.AGREEMENT_SCREEN),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.onboarding_agreement_title),
                    style = MaterialTheme.typography.headlineMedium
                )
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            message?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }

            // Consent sits with the action it unlocks.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .heightIn(min = 56.dp)
                    .toggleable(
                        value = hasAgreed,
                        enabled = !isWorking,
                        role = Role.Checkbox,
                        onValueChange = onAgreementChanged
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .testTag(OnboardingTestTags.AGREEMENT_CHECKBOX),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = hasAgreed, onCheckedChange = null)
                Text(
                    text = stringResource(R.string.onboarding_agreement_checkbox),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 12.dp)
                )
            }

            AppPrimaryButton(
                text = stringResource(R.string.onboarding_agreement_continue),
                onClick = onContinue,
                enabled = hasAgreed && !isWorking,
                isWorking = isWorking,
                modifier = Modifier.testTag(OnboardingTestTags.AGREEMENT_CONTINUE)
            )
            secondaryAction?.let {
                TextButton(
                    onClick = onSecondaryAction,
                    enabled = hasAgreed && !isWorking,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(OnboardingTestTags.AGREEMENT_SECONDARY)
                ) { Text(it) }
            }
        }
    }
}
