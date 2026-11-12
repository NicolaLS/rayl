package xyz.lilsus.raylsuite.feature.paymenthub

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import xyz.lilsus.raylsuite.feature.paymenthub.claro.KtorClaroApi
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
            claro = KtorClaroApi(),
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
        isBackEnabled = state.screen == HubWidgetScreen.Hub && state.arranging,
        onBackCompleted = { viewModel.setArranging(false) }
    )
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer)) {
        HubWidgetCanvas(state, viewModel, Modifier.fillMaxSize())
        HubEditorSheet(state, viewModel)
    }
}

/**
 * Adding or editing a widget happens in a sheet over the Hub, like Android's own widget picker:
 * the tabs stay out of the way and the Hub stays in sight above it. Back steps through the sheet's
 * screens before it closes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HubEditorSheet(state: WidgetHubState, viewModel: WidgetHubViewModel) {
    val editing = state.screen != HubWidgetScreen.Hub
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var open by remember { mutableStateOf(editing) }
    // What the sheet showed last, so it keeps its content while it slides away.
    var shown by remember { mutableStateOf(state.screen) }
    if (editing) shown = state.screen
    LaunchedEffect(editing) {
        if (editing) {
            open = true
        } else if (open) {
            sheetState.hide()
            open = false
        }
    }
    if (!open && !editing) return
    // Closing from the first screen leaves the sheet; every other screen steps back.
    val closes = state.screen == HubWidgetScreen.Gallery ||
        (state.screen == HubWidgetScreen.Variants && state.editor?.existingWidgetId != null)
    ModalBottomSheet(
        onDismissRequest = { if (editing) viewModel.close() },
        sheetState = sheetState,
        sheetGesturesEnabled = !state.busy,
        // No room for the status bar: the sheet rests just below it, and the padding would make
        // the sheet taller whenever it springs up into it. A taller sheet moves its resting place,
        // and the sheet then springs again with the whole fling that settled it, so a fling at
        // the end of the gallery set it bouncing forever.
        contentWindowInsets = { WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom) },
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false)
    ) {
        NavigationEventHandler(
            state = rememberNavigationEventState(
                currentInfo = WidgetHubNavigationInfo(state.screen)
            ),
            isForwardEnabled = false,
            isBackEnabled = editing && !state.busy,
            onBackCompleted = { viewModel.back() }
        )
        // A little of the Hub stays in sight above the sheet, so it reads as a layer over it.
        Column(Modifier.fillMaxHeight(SHEET_HEIGHT)) {
            Box(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp)) {
                if (closes) {
                    IconButton(
                        onClick = { viewModel.close() },
                        enabled = !state.busy,
                        modifier = Modifier.align(Alignment.CenterStart)
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.hub_canvas_remove_cancel)
                        )
                    }
                } else {
                    BackIconButton(
                        onClick = { if (!state.busy) viewModel.back() },
                        modifier = Modifier.align(Alignment.CenterStart)
                    )
                }
                Text(
                    stringResource(sheetTitle(shown, state)),
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 56.dp)
                        .semantics { heading() }
                )
            }
            val content = Modifier.fillMaxWidth().weight(1f)
            when (shown) {
                HubWidgetScreen.Gallery -> HubWidgetGallery(state, viewModel, content)
                HubWidgetScreen.Variants -> HubWidgetVariants(state, viewModel, content)
                HubWidgetScreen.Configure -> HubWidgetEditorScreen(state, viewModel, content)
                HubWidgetScreen.Hub -> Unit
            }
        }
    }
}

private fun sheetTitle(screen: HubWidgetScreen, state: WidgetHubState): Int = when (screen) {
    HubWidgetScreen.Variants -> R.string.hub_widget_select_variant

    HubWidgetScreen.Configure ->
        if (state.editor?.existingWidgetId != null) {
            R.string.hub_widget_edit
        } else {
            R.string.hub_configure_title
        }

    else -> R.string.hub_widget_gallery_title
}

private const val LIVE_REFRESH_MS = 60_000L

private data class WidgetHubNavigationInfo(val screen: HubWidgetScreen) : NavigationEventInfo()

/** How much of the screen the editing sheet takes. */
private const val SHEET_HEIGHT = 0.94f
