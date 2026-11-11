package xyz.lilsus.raylsuite.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import xyz.lilsus.raylsuite.core.ui.components.AppPrimaryButton
import xyz.lilsus.raylsuite.core.ui.components.RaylMark

/** One thing the app does for the user, shown on the welcome. */
data class OnboardingBenefit(val icon: OnboardingBenefitIcon, val title: String, val body: String)

enum class OnboardingBenefitIcon(internal val image: ImageVector) {
    Scan(Icons.Filled.QrCodeScanner),
    AutoPay(Icons.Filled.Bolt),
    Balance(Icons.Filled.AccountBalanceWallet)
}

/** The first screen: who Rayl is and, in a few lines, what to expect from it. */
@Composable
fun WelcomeScreen(
    title: String,
    description: String,
    benefits: List<OnboardingBenefit>,
    actionTitle: String,
    onGetStarted: () -> Unit,
    modifier: Modifier = Modifier
) {
    OnboardingScaffold {
        Column(modifier = modifier.fillMaxSize().testTag(OnboardingTestTags.WELCOME_SCREEN)) {
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Spacer(modifier = Modifier.height(16.dp))
                RaylMark(size = 88.dp)
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(20.dp))
                benefits.forEach { BenefitRow(it) }
                Spacer(modifier = Modifier.height(12.dp))
            }
            AppPrimaryButton(
                text = actionTitle,
                onClick = onGetStarted,
                modifier = Modifier.testTag(OnboardingTestTags.WELCOME_CONTINUE)
            )
        }
    }
}

@Composable
private fun BenefitRow(benefit: OnboardingBenefit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            modifier = Modifier.size(40.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = benefit.icon.image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(22.dp)
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = benefit.title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = benefit.body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
