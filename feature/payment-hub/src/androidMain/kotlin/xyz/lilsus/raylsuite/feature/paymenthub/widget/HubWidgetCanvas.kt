package xyz.lilsus.raylsuite.feature.paymenthub.widget

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xyz.lilsus.raylsuite.feature.paymenthub.HubWidgetKind
import xyz.lilsus.raylsuite.feature.paymenthub.HubWidgetTile
import xyz.lilsus.raylsuite.feature.paymenthub.HubWidgetVariant
import xyz.lilsus.raylsuite.feature.paymenthub.R
import xyz.lilsus.raylsuite.feature.paymenthub.WidgetHubState
import xyz.lilsus.raylsuite.feature.paymenthub.WidgetHubViewModel
import xyz.lilsus.raylsuite.feature.paymenthub.canvas.HubGridSpan
import xyz.lilsus.raylsuite.feature.paymenthub.canvas.gridRowCount
import xyz.lilsus.raylsuite.feature.paymenthub.canvas.packHubGrid
import xyz.lilsus.raylsuite.feature.paymenthub.live.HubPpqProblem

@Composable
internal fun HubWidgetCanvas(
    state: WidgetHubState,
    viewModel: WidgetHubViewModel,
    modifier: Modifier = Modifier
) {
    DisposableEffect(viewModel) {
        onDispose { viewModel.setArranging(false) }
    }
    var convertingCode by remember { mutableStateOf<String?>(null) }
    var toppingUpId by remember { mutableStateOf<String?>(null) }
    val open: (HubWidgetTile) -> Unit = { tile ->
        when (tile.kind) {
            // Both bitcoin widgets open the same converter, in the widget's own currency.
            HubWidgetKind.Rate, HubWidgetKind.Sats ->
                convertingCode = tile.currencyCode ?: state.defaultFiatCode

            // A widget that was added but not set up yet opens its settings.
            HubWidgetKind.Contacts, HubWidgetKind.Shortcut ->
                if (tile.people.isEmpty()) viewModel.editWidget(tile.id)

            // Without a usable key there is nothing to top up; the widget's settings ask for one.
            HubWidgetKind.Ppq -> {
                val account = state.ppqAccount(tile.id)
                if (account.connected && account.problem != HubPpqProblem.InvalidKey) {
                    viewModel.clearPpqTopUpError(tile.id)
                    toppingUpId = tile.id
                } else {
                    viewModel.editWidget(tile.id)
                }
            }

            // Before it knows a number there is nothing to shop for; the settings ask for one.
            HubWidgetKind.Claro ->
                if (tile.phone != null) {
                    viewModel.openClaro(tile.id)
                } else {
                    viewModel.editWidget(tile.id)
                }

            else -> Unit
        }
    }
    if (state.claro.widgetId != null) {
        HubClaroSheet(
            shop = state.claro,
            onBuy = viewModel::buyClaro,
            onRetry = { state.claro.widgetId?.let(viewModel::openClaro) },
            onDismiss = viewModel::closeClaro
        )
    }
    convertingCode?.let { code ->
        // The converter closes by itself if its price disappears.
        state.rates[code]?.let { rate ->
            HubSatsConverterDialog(rate, onDismiss = { convertingCode = null })
        }
    }
    toppingUpId?.let { widgetId ->
        HubPpqTopUpSheet(
            account = state.ppqAccount(widgetId),
            onTopUp = { viewModel.topUpPpq(widgetId, it) },
            onClearError = { viewModel.clearPpqTopUpError(widgetId) },
            onDismiss = { toppingUpId = null }
        )
    }
    // The canvas runs edge to edge: tiles scroll under the status bar, which a fade keeps legible.
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val page = MaterialTheme.colorScheme.surfaceContainer
    // Pulling down refreshes every widget at once, with a tick when the pull takes; the spinner
    // stays until each price and balance has arrived or failed.
    var refreshing by remember { mutableStateOf(false) }
    val pull = rememberPullToRefreshState()
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
            refreshing = true
            scope.launch {
                try {
                    viewModel.refreshNow()
                } finally {
                    refreshing = false
                }
            }
        },
        state = pull,
        modifier = modifier,
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = pull,
                isRefreshing = refreshing,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = statusBar)
            )
        }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            state.error?.let { error ->
                Text(
                    error.label(state.selectedVariant?.capacity ?: 1),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = statusBar)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite }
                )
            }
            Box(contentAlignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize()) {
                BoxWithConstraints(Modifier.widthIn(max = 1100.dp).fillMaxWidth()) {
                    val scrollState = rememberScrollState()
                    if (state.widgets.isEmpty()) {
                        // A lone dashed square in an empty screen reads as a loading failure, so the
                        // first run says what the canvas is for.
                        HubEmptyCanvas(
                            enabled = !state.busy,
                            onAdd = viewModel::openGallery,
                            modifier = Modifier.fillMaxSize().padding(top = statusBar)
                                .verticalScroll(scrollState)
                        )
                    } else {
                        // Nothing above the grid moves while arranging, so a widget being dragged
                        // stays under the finger.
                        val top = (if (state.error == null) statusBar else 0.dp) + 16.dp
                        WidgetGrid(
                            state = state,
                            viewModel = viewModel,
                            onOpen = open,
                            columns = if (maxWidth >= 700.dp) 4 else 2,
                            scrollState = scrollState,
                            viewportHeight = constraints.maxHeight.toFloat(),
                            topPadding = top,
                            modifier = Modifier.fillMaxWidth()
                                .verticalScroll(scrollState)
                                .padding(
                                    start = 16.dp,
                                    end = 16.dp,
                                    top = top,
                                    // Room to scroll the last widgets clear of the Done button.
                                    bottom = if (state.arranging) 96.dp else 16.dp
                                )
                        )
                    }
                }
            }
        }
        Box(
            Modifier.fillMaxWidth().height(statusBar + FADE).background(
                Brush.verticalGradient(
                    // Solid behind the status bar, fading out just below it.
                    0f to page,
                    statusBar / (statusBar + FADE) to page,
                    1f to page.copy(alpha = 0f)
                )
            )
        )
        // Arranging ends with Done where the thumb is, and the Hub above it never moves.
        AnimatedVisibility(
            visible = state.arranging && state.widgets.isNotEmpty(),
            enter = scaleIn() + fadeIn(),
            exit = scaleOut() + fadeOut(),
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        ) {
            ExtendedFloatingActionButton(
                onClick = { viewModel.setArranging(false) },
                icon = { Icon(Icons.Filled.Check, contentDescription = null) },
                text = { Text(stringResource(R.string.hub_canvas_done)) }
            )
        }
    }
}

@Composable
private fun HubEmptyCanvas(enabled: Boolean, onAdd: () -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(contentAlignment = Alignment.Center, modifier = modifier) {
        val tileSize = minOf(maxWidth * 0.45f, 180.dp)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp)
        ) {
            Text(
                stringResource(R.string.hub_widget_empty_title),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center
            )
            Text(
                stringResource(R.string.hub_widget_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 320.dp)
            )
            Spacer(Modifier.height(14.dp))
            AddWidgetTile(enabled = enabled, onClick = onAdd, modifier = Modifier.size(tileSize))
        }
    }
}

/**
 * The placed widgets. A long press lifts a widget and opens its menu; moving on from there, or a
 * long press while arranging, picks it up, and the others make room as it passes. The order is
 * saved once it is let go.
 */
@Composable
private fun WidgetGrid(
    state: WidgetHubState,
    viewModel: WidgetHubViewModel,
    onOpen: (HubWidgetTile) -> Unit,
    columns: Int,
    scrollState: ScrollState,
    viewportHeight: Float,
    topPadding: Dp,
    modifier: Modifier
) {
    // The widget whose menu is open; it stays lifted above the others until the menu closes.
    var menuId by remember { mutableStateOf<String?>(null) }
    var draggingId by remember { mutableStateOf<String?>(null) }
    // Where the finger is in the grid, where on the widget it took hold, and where it was when
    // the long press took.
    var pointer by remember { mutableStateOf(Offset.Zero) }
    var grab by remember { mutableStateOf(Offset.Zero) }
    var pressedAt by remember { mutableStateOf(Offset.Zero) }
    val haptics = LocalHapticFeedback.current
    val baseConfiguration = LocalViewConfiguration.current
    val touchSlop = baseConfiguration.touchSlop
    LaunchedEffect(state.arranging) {
        if (!state.arranging) draggingId = null
    }
    val gap = 12.dp
    BoxWithConstraints(modifier) {
        val unit = (maxWidth - gap * (columns - 1)) / columns
        val placements = remember(state.widgets, columns) {
            packHubGrid(state.widgets, columns) { HubGridSpan(it.variant.columns, it.variant.rows) }
        }
        val occupiedRows = placements.gridRowCount()
        // The Add tile stays after the widgets, using a free cell on the final occupied row.
        val lastRow = (occupiedRows - 1).coerceAtLeast(0)
        val freeColumn = (0 until columns).firstOrNull { column ->
            placements.none { placement ->
                column in placement.column until placement.column + placement.columns &&
                    lastRow in placement.row until placement.row + placement.rows
            }
        }
        val addColumn = freeColumn ?: 0
        val addRow = if (freeColumn != null) lastRow else occupiedRows
        val rows = maxOf(occupiedRows, addRow + 1)
        val density = LocalDensity.current
        val stridePx = with(density) { (unit + gap).toPx() }
        val gapPx = with(density) { gap.toPx() }
        val topPx = with(density) { topPadding.toPx() }
        val scrollEdgePx = with(density) { 64.dp.toPx() }
        val maxScrollStepPx = with(density) { 12.dp.toPx() }

        fun originOf(id: String): Offset? = placements.firstOrNull { it.value.id == id }?.let {
            Offset(stridePx * it.column, stridePx * it.row)
        }

        // The others make room for the widget where it is held; nothing is saved until it is let
        // go. The view model takes its middle in cells, a cell and the gap after it counting as one.
        fun followPointer() {
            val dragged = placements.firstOrNull { it.value.id == draggingId } ?: return
            val size = Offset(
                stridePx * dragged.columns - gapPx,
                stridePx * dragged.rows - gapPx
            )
            val middle = pointer - grab + size / 2f + Offset(gapPx, gapPx) / 2f
            viewModel.previewMove(
                dragged.value.id,
                middle.x / stridePx,
                middle.y / stridePx,
                columns
            )
        }

        fun pickUp(id: String) {
            menuId = null
            draggingId = id
            if (!state.arranging) viewModel.setArranging(true)
        }

        // The scroll below runs for the whole drag, longer than this composition: it reaches the
        // current layout through these, not the one the drag started in.
        val follow by rememberUpdatedState(::followPointer)
        val currentTopPx by rememberUpdatedState(topPx)
        val currentViewportHeight by rememberUpdatedState(viewportHeight)

        // Near the top or bottom edge a dragged widget scrolls the Hub along.
        LaunchedEffect(draggingId) {
            while (draggingId != null) {
                val y = pointer.y + currentTopPx - scrollState.value
                val bottomEdge = currentViewportHeight - scrollEdgePx
                val step = when {
                    y < scrollEdgePx -> -((scrollEdgePx - y) / scrollEdgePx).coerceIn(0f, 1f)
                    y > bottomEdge -> ((y - bottomEdge) / scrollEdgePx).coerceIn(0f, 1f)
                    else -> 0f
                }
                if (step != 0f) {
                    val moved = scrollState.scrollBy(step * maxScrollStepPx)
                    pointer += Offset(0f, moved)
                    follow()
                }
                delay(16L)
            }
        }
        Box(Modifier.fillMaxWidth().height(unit * rows + gap * (rows - 1).coerceAtLeast(0))) {
            placements.forEachIndexed { index, placement ->
                val tile = placement.value
                key(tile.id) {
                    val dragging = draggingId == tile.id
                    val slot = IntOffset(
                        (stridePx * placement.column).roundToInt(),
                        (stridePx * placement.row).roundToInt()
                    )
                    // A dragged widget follows the finger; every other one glides to its place.
                    // The finger is followed outside composition, so a move only moves the
                    // widget instead of composing the whole grid again.
                    val position = remember { Animatable(slot, IntOffset.VectorConverter) }
                    LaunchedEffect(dragging) {
                        if (dragging) {
                            snapshotFlow { (pointer - grab).round() }
                                .collect { position.snapTo(it) }
                        }
                    }
                    LaunchedEffect(slot, dragging) {
                        if (!dragging) {
                            position.animateTo(
                                slot,
                                spring(stiffness = Spring.StiffnessMediumLow)
                            )
                        }
                    }
                    WidgetCanvasTile(
                        tile = tile,
                        state = state,
                        onOpen = onOpen,
                        menuOpen = menuId == tile.id,
                        dragging = dragging,
                        variants = state.gallery.firstOrNull { it.id == tile.definitionId }
                            ?.variants.orEmpty(),
                        canMoveUp = index > 0,
                        canMoveDown = index < placements.lastIndex,
                        viewModel = viewModel,
                        onCloseMenu = { if (menuId == tile.id) menuId = null },
                        modifier = Modifier
                            .offset { position.value }
                            .width(unit * placement.columns + gap * (placement.columns - 1))
                            .height(unit * placement.rows + gap * (placement.rows - 1))
                            .zIndex(
                                when {
                                    dragging -> 2f
                                    menuId == tile.id -> 1f
                                    else -> 0f
                                }
                            )
                            .tileGestures(
                                enabled =
                                    !state.busy &&
                                        draggingId.let { it == null || it == tile.id },
                                // While arranging, a widget is picked up sooner than a long
                                // press opens its menu.
                                holdMillis = if (state.arranging) {
                                    QUICK_PICK_UP_MS
                                } else {
                                    baseConfiguration.longPressTimeoutMillis
                                },
                                onTap = if (state.arranging) {
                                    { viewModel.editWidget(tile.id) }
                                } else {
                                    null
                                },
                                onLongPress = { at ->
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    grab = at
                                    pointer = (originOf(tile.id) ?: Offset.Zero) + at
                                    pressedAt = pointer
                                    if (state.arranging) pickUp(tile.id) else menuId = tile.id
                                },
                                onDrag = { delta ->
                                    pointer += delta
                                    // Moving on from the menu picks the widget up and starts
                                    // arranging, the way the home screen does. Only how far
                                    // the finger is from where it pressed counts, so the
                                    // tremor of a resting finger never adds up to a move.
                                    if (draggingId == null && menuId == tile.id &&
                                        (pointer - pressedAt).getDistance() > touchSlop
                                    ) {
                                        pickUp(tile.id)
                                    }
                                    if (draggingId == tile.id) followPointer()
                                },
                                onRelease = {
                                    if (draggingId == tile.id) {
                                        draggingId = null
                                        viewModel.finishMove()
                                    }
                                },
                                onCancel = {
                                    if (draggingId == tile.id) {
                                        draggingId = null
                                        viewModel.cancelMove()
                                    }
                                }
                            )
                    )
                }
            }
            // Arranging is about the widgets already placed, so adding one waits.
            AnimatedVisibility(
                visible = !state.arranging,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.offset(
                    x = (unit + gap) * addColumn,
                    y = (unit + gap) * addRow
                )
            ) {
                AddWidgetTile(
                    enabled = !state.busy,
                    onClick = viewModel::openGallery,
                    modifier = Modifier.size(unit)
                )
            }
        }
    }
}

@Composable
private fun AddWidgetTile(enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = HubTileShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.drawWithContent {
            drawContent()
            val stroke = 1.5.dp.toPx()
            drawRoundRect(
                color = borderColor,
                topLeft = Offset(stroke / 2, stroke / 2),
                size = Size(size.width - stroke, size.height - stroke),
                cornerRadius = CornerRadius(28.dp.toPx()),
                style = Stroke(
                    width = stroke,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))
                )
            )
        }
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
            modifier = Modifier.padding(12.dp)
        ) {
            // The disc sits where a contact portrait would, so the placeholder lines up with its
            // neighbours instead of floating.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(46.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(24.dp))
            }
            Text(
                stringResource(R.string.hub_widget_add),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun WidgetCanvasTile(
    tile: HubWidgetTile,
    state: WidgetHubState,
    onOpen: (HubWidgetTile) -> Unit,
    menuOpen: Boolean,
    dragging: Boolean,
    variants: List<HubWidgetVariant>,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    viewModel: WidgetHubViewModel,
    onCloseMenu: () -> Unit,
    modifier: Modifier
) {
    val edit = stringResource(R.string.hub_widget_edit)
    val remove = stringResource(R.string.hub_configure_delete)
    val title = tile.title?.takeIf(String::isNotBlank) ?: tile.kind.label()
    val moveUp = stringResource(R.string.hub_action_move_up)
    val moveDown = stringResource(R.string.hub_action_move_down)
    val arranging = state.arranging
    // Lifted while its menu is open or while it is carried; settled a little smaller while
    // arranging, so the "−" sits clear of its corner.
    val scale by animateFloatAsState(
        when {
            dragging -> 1.05f
            menuOpen -> 1.03f
            arranging -> 0.96f
            else -> 1f
        },
        spring(stiffness = Spring.StiffnessMediumLow),
        label = "tile scale"
    )
    val lift by animateDpAsState(
        if (dragging || menuOpen) 12.dp else 0.dp,
        spring(stiffness = Spring.StiffnessMediumLow),
        label = "tile lift"
    )
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                shadowElevation = lift.toPx()
                shape = HubTileShape
                clip = false
            }
            .semantics {
                customActions = buildList {
                    add(
                        CustomAccessibilityAction(edit) {
                            viewModel.editWidget(tile.id)
                            true
                        }
                    )
                    add(
                        CustomAccessibilityAction(remove) {
                            viewModel.removeWidget(tile.id)
                            true
                        }
                    )
                    if (canMoveUp) {
                        add(
                            CustomAccessibilityAction(moveUp) {
                                viewModel.moveWidgetBy(tile.id, -1)
                                true
                            }
                        )
                    }
                    if (canMoveDown) {
                        add(
                            CustomAccessibilityAction(moveDown) {
                                viewModel.moveWidgetBy(tile.id, 1)
                                true
                            }
                        )
                    }
                }
            }
    ) {
        HubWidgetFace(
            tile,
            state,
            onPay = viewModel::pay,
            modifier = Modifier.fillMaxSize(),
            interactive = !state.busy && !arranging && !menuOpen,
            onOpen = onOpen
        )
        AnimatedVisibility(
            visible = arranging && !dragging,
            enter = scaleIn() + fadeIn(),
            exit = scaleOut() + fadeOut(),
            modifier = Modifier.align(Alignment.TopStart).offset(x = (-18).dp, y = (-18).dp)
        ) {
            IconButton(onClick = { viewModel.removeWidget(tile.id) }, enabled = !state.busy) {
                Icon(
                    Icons.Filled.Remove,
                    contentDescription = "$remove, $title",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .shadow(2.dp, CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
                        .padding(4.dp)
                        .size(18.dp)
                )
            }
        }
        WidgetMenu(
            expanded = menuOpen,
            tile = tile,
            variants = variants,
            onDismiss = onCloseMenu,
            onResize = { variantId ->
                onCloseMenu()
                viewModel.resizeWidget(tile.id, variantId)
            },
            onEdit = {
                onCloseMenu()
                viewModel.editWidget(tile.id)
            },
            onArrange = {
                onCloseMenu()
                viewModel.setArranging(true)
            },
            onRemove = {
                onCloseMenu()
                viewModel.removeWidget(tile.id)
            }
        )
    }
}

/**
 * What a long press on a widget offers: its layouts as a row of their shapes, then editing it,
 * arranging the Hub, and removing it.
 */
@Composable
private fun WidgetMenu(
    expanded: Boolean,
    tile: HubWidgetTile,
    variants: List<HubWidgetVariant>,
    onDismiss: () -> Unit,
    onResize: (String) -> Unit,
    onEdit: () -> Unit,
    onArrange: () -> Unit,
    onRemove: () -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        offset = DpOffset(0.dp, 8.dp),
        shape = RoundedCornerShape(20.dp)
    ) {
        if (variants.size > 1) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                variants.forEach { variant ->
                    LayoutChoice(
                        variant = variant,
                        label = variant.label(tile.kind),
                        selected = variant.id == tile.variant.id,
                        onClick = { onResize(variant.id) }
                    )
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.hub_widget_edit)) },
            leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
            onClick = onEdit
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.hub_edit_hub)) },
            leadingIcon = { Icon(Icons.Outlined.GridView, contentDescription = null) },
            onClick = onArrange
        )
        val error = MaterialTheme.colorScheme.error
        DropdownMenuItem(
            text = { Text(stringResource(R.string.hub_configure_delete), color = error) },
            leadingIcon = {
                Icon(Icons.Outlined.RemoveCircleOutline, contentDescription = null, tint = error)
            },
            onClick = onRemove
        )
    }
}

/** One layout of a widget, drawn as its shape: a small square, a wide one, a large one. */
@Composable
private fun LayoutChoice(
    variant: HubWidgetVariant,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val ink = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(width = 56.dp, height = 44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
            )
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = label }
    ) {
        Canvas(Modifier.size(26.dp)) {
            val cell = size.minDimension / 2.2f
            val width = cell * variant.columns + (variant.columns - 1) * cell * 0.2f
            val height = cell * variant.rows + (variant.rows - 1) * cell * 0.2f
            val stroke = 1.6.dp.toPx()
            drawRoundRect(
                color = ink,
                topLeft = Offset((size.width - width) / 2, (size.height - height) / 2),
                size = Size(width, height),
                cornerRadius = CornerRadius(cell * 0.3f),
                style = Stroke(stroke)
            )
        }
    }
}

/**
 * A widget's own gestures: a tap, while given [onTap]; a press held for [holdMillis], and what the
 * finger does after it. A held press keeps the face beneath from also taking the touch as a tap.
 *
 * Whether it is [enabled] counts when a press starts. A press under way always ends with
 * [onRelease] or [onCancel], also when the Hub becomes busy meanwhile or the widget goes away.
 */
private fun Modifier.tileGestures(
    enabled: Boolean,
    holdMillis: Long,
    onTap: (() -> Unit)?,
    onLongPress: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onRelease: () -> Unit,
    onCancel: () -> Unit
): Modifier = composed {
    val accepts by rememberUpdatedState(enabled)
    val hold by rememberUpdatedState(holdMillis)
    val tap by rememberUpdatedState(onTap)
    val longPress by rememberUpdatedState(onLongPress)
    val move by rememberUpdatedState(onDrag)
    val release by rememberUpdatedState(onRelease)
    val cancel by rememberUpdatedState(onCancel)
    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (!accepts) return@awaitEachGesture
            var at = down.position
            var up: PointerInputChange? = null
            // Null once the press has been held long enough; otherwise it ended or moved away.
            val ended = withTimeoutOrNull(hold) {
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                    if (change == null) break
                    if (change.changedToUpIgnoreConsumed()) {
                        up = change
                        break
                    }
                    val wandered =
                        (change.position - down.position).getDistance() >
                            viewConfiguration.touchSlop
                    if (change.isConsumed || wandered) break
                    at = change.position
                }
                true
            }
            if (ended != null) {
                val change = up
                val onTapNow = tap
                if (change != null && !change.isConsumed && onTapNow != null) {
                    change.consume()
                    onTapNow()
                }
                return@awaitEachGesture
            }
            longPress(at)
            var putDown = false
            try {
                val released = drag(down.id) { change ->
                    move(change.positionChange())
                    change.consume()
                }
                currentEvent.changes.forEach { it.consume() }
                putDown = true
                if (released) release() else cancel()
            } finally {
                // Stopped from outside, as when the widget leaves the Hub: put it down all the same.
                if (!putDown) cancel()
            }
        }
    }
}

/** How far below the status bar the canvas fades out. */
private val FADE = 24.dp

/**
 * How long a press takes to pick a widget up while arranging: sooner than a long press, but
 * longer than a slow tap, which opens the widget's settings.
 */
private const val QUICK_PICK_UP_MS = 300L
