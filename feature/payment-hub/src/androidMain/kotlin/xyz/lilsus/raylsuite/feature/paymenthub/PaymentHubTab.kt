package xyz.lilsus.raylsuite.feature.paymenthub

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationEventHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import kotlinx.coroutines.delay
import xyz.lilsus.raylsuite.core.settings.createSecureSettings
import xyz.lilsus.raylsuite.core.ui.components.BackIconButton
import xyz.lilsus.raylsuite.feature.paymenthub.feedback.KtorHubFeedbackApi
import xyz.lilsus.raylsuite.feature.paymenthub.host.PaymentHubController
import xyz.lilsus.raylsuite.feature.paymenthub.ppq.HubPpqService
import xyz.lilsus.raylsuite.feature.paymenthub.ppq.createHubPpqService
import xyz.lilsus.raylsuite.feature.paymenthub.widget.HubWidgetCanvas
import xyz.lilsus.raylsuite.feature.paymenthub.widget.HubWidgetEditorScreen
import xyz.lilsus.raylsuite.feature.paymenthub.widget.HubWidgetGallery
import xyz.lilsus.raylsuite.feature.paymenthub.widget.HubWidgetVariants

/** Native gallery, configuration, and canvas. Payment policy stays with the host. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentHubTab(
    repository: PaymentHubRepository,
    controller: PaymentHubController,
    preferredCurrencyCode: () -> String,
    modifier: Modifier = Modifier
) {
    val currency by rememberUpdatedState(preferredCurrencyCode)
    val context = LocalContext.current.applicationContext
    val viewModel = remember(repository, controller) {
        WidgetHubViewModel(
            repository = repository,
            host = controller,
            defaultCurrencyCode = { currency() },
            ppq = createHubPpqService(
                createSecureSettings(context, HubPpqService.STORAGE_NAME)
            ),
            feedbackApi = KtorHubFeedbackApi(platform = "android")
        )
    }
    DisposableEffect(viewModel) {
        onDispose {
            viewModel.clear()
        }
    }
    // Prices and balances are fetched while the Hub is on screen, and again when it comes back.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                viewModel.refreshLive()
                delay(LIVE_REFRESH_MS)
            }
        }
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    NavigationEventHandler(
        state = rememberNavigationEventState(currentInfo = WidgetHubNavigationInfo(state.screen)),
        isForwardEnabled = false,
        isBackEnabled = state.screen != HubWidgetScreen.Hub || state.arranging,
        onBackCompleted = {
            if (state.arranging &&
                state.screen == HubWidgetScreen.Hub
            ) {
                viewModel.setArranging(false)
            } else {
                viewModel.back()
            }
        }
    )
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            if (state.screen != HubWidgetScreen.Hub) {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            stringResource(
                                when (state.screen) {
                                    HubWidgetScreen.Gallery -> R.string.hub_widget_gallery_title

                                    HubWidgetScreen.Variants -> R.string.hub_widget_select_variant

                                    HubWidgetScreen.Configure ->
                                        if (state.editor?.existingWidgetId != null) {
                                            R.string.hub_widget_edit
                                        } else {
                                            R.string.hub_configure_title
                                        }

                                    HubWidgetScreen.Hub -> R.string.hub_widget_gallery_title
                                }
                            )
                        )
                    },
                    navigationIcon = { BackIconButton(onClick = { viewModel.back() }) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                )
            }
        }
    ) { padding ->
        val content = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
        when (state.screen) {
            HubWidgetScreen.Hub -> HubWidgetCanvas(state, viewModel, content)
            HubWidgetScreen.Gallery -> HubWidgetGallery(state, viewModel, content)
            HubWidgetScreen.Variants -> HubWidgetVariants(state, viewModel, content)
            HubWidgetScreen.Configure -> HubWidgetEditorScreen(state, viewModel, content)
        }
    }
}

private const val LIVE_REFRESH_MS = 60_000L

private data class WidgetHubNavigationInfo(val screen: HubWidgetScreen) : NavigationEventInfo()
