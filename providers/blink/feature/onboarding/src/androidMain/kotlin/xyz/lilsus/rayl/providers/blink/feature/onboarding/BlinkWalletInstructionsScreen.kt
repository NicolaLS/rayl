package xyz.lilsus.rayl.providers.blink.feature.onboarding

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import xyz.lilsus.rayl.providers.blink.feature.onboarding.R
import xyz.lilsus.raylsuite.core.ui.components.AppPrimaryButton
import xyz.lilsus.raylsuite.feature.onboarding.OnboardingScaffold
import xyz.lilsus.raylsuite.feature.onboarding.OnboardingTestTags

@Composable
fun BlinkWalletInstructionsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    val pages = blinkDashboardInstructionPages()
    val pagerState = rememberPagerState { pages.size }
    val coroutineScope = rememberCoroutineScope()

    OnboardingScaffold(onBack = onBack) {
        Column(
            modifier =
                modifier
                    .fillMaxSize()
                    .testTag(OnboardingTestTags.WALLET_INSTRUCTIONS_SCREEN)
        ) {
            Text(
                text = stringResource(R.string.blink_onboarding_add_wallet_title),
                style = MaterialTheme.typography.headlineMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(
                    R.string.blink_onboarding_add_wallet_intro,
                    xyz.lilsus.raylsuite.core.ui.platform.LocalProductName.current
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))

            HorizontalPager(
                state = pagerState,
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
            ) { pageIndex ->
                BlinkDashboardInstructionCard(
                    page = pages[pageIndex],
                    stepNumber = pageIndex + 1,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = {
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage - 1)
                        }
                    },
                    enabled = pagerState.currentPage > 0
                ) {
                    Text(stringResource(R.string.blink_onboarding_add_wallet_previous_step))
                }
                Text(
                    text =
                        stringResource(
                            R.string.blink_onboarding_add_wallet_step_progress,
                            pagerState.currentPage + 1,
                            pages.size
                        ),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                TextButton(
                    onClick = {
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    },
                    enabled = pagerState.currentPage < pages.lastIndex
                ) {
                    Text(stringResource(R.string.blink_onboarding_add_wallet_next_step))
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            AppPrimaryButton(
                text = stringResource(R.string.blink_onboarding_add_wallet_dashboard_button),
                onClick = { uriHandler.openUri(BLINK_DASHBOARD_URL) }
            )
        }
    }
}

@Composable
private fun BlinkDashboardInstructionCard(
    page: BlinkDashboardInstructionPage,
    stepNumber: Int,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.padding(horizontal = 4.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stepNumber.toString(),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = page.title,
                    style = MaterialTheme.typography.titleMedium
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .background(Color.White)
                        .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(page.image),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = page.body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun blinkDashboardInstructionPages(): List<BlinkDashboardInstructionPage> = listOf(
    BlinkDashboardInstructionPage(
        image = R.drawable.blink_dashboard_email,
        title = stringResource(R.string.blink_onboarding_add_wallet_step1_title),
        body = stringResource(R.string.blink_onboarding_add_wallet_step1_body)
    ),
    BlinkDashboardInstructionPage(
        image = R.drawable.blink_dashboard_api_keys,
        title = stringResource(R.string.blink_onboarding_add_wallet_step2_title),
        body = stringResource(R.string.blink_onboarding_add_wallet_step2_body)
    ),
    BlinkDashboardInstructionPage(
        image = R.drawable.blink_dashboard_key_settings,
        title = stringResource(R.string.blink_onboarding_add_wallet_step3_title),
        body = stringResource(R.string.blink_onboarding_add_wallet_step3_body)
    ),
    BlinkDashboardInstructionPage(
        image = R.drawable.blink_dashboard_copy_key,
        title = stringResource(R.string.blink_onboarding_add_wallet_step4_title),
        body = stringResource(
            R.string.blink_onboarding_add_wallet_step4_body,
            xyz.lilsus.raylsuite.core.ui.platform.LocalProductName.current
        )
    )
)

private data class BlinkDashboardInstructionPage(
    @DrawableRes
    val image: Int,
    val title: String,
    val body: String
)

private const val BLINK_DASHBOARD_URL = "https://dashboard.blink.sv/api-keys"
