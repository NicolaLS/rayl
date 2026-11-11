package xyz.lilsus.raylsuite.feature.paymenthub.widget

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
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
    var toppingUp by remember { mutableStateOf(false) }
    val open: (HubWidgetTile) -> Unit = { tile ->
        when (tile.kind) {
            // Both bitcoin widgets open the same converter, in the widget's own currency.
            HubWidgetKind.Rate, HubWidgetKind.Sats ->
                convertingCode = tile.currencyCode ?: state.defaultFiatCode

            // A widget that was added but not set up yet opens its settings.
            HubWidgetKind.Contacts, HubWidgetKind.Shortcut ->
                if (tile.people.isEmpty()) viewModel.editWidget(tile.id)

            // Without a usable key there is nothing to top up; the widget's settings ask for one.
            HubWidgetKind.Ppq -> if (state.ppq.connected &&
                state.ppq.problem != HubPpqProblem.InvalidKey
            ) {
                viewModel.clearPpqTopUpError()
                toppingUp = true
            } else {
                viewModel.editWidget(tile.id)
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
    if (toppingUp) {
        HubPpqTopUpSheet(
            account = state.ppq,
            onTopUp = viewModel::topUpPpq,
            onClearError = viewModel::clearPpqTopUpError,
            onDismiss = { toppingUp = false }
        )
    }
    Column(modifier = modifier) {
        if (state.widgets.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            ) {
                if (state.arranging) {
                    Text(
                        stringResource(R.string.hub_edit_hub),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { heading() }
                    )
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = viewModel::openGallery, enabled = !state.busy) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = stringResource(R.string.hub_widget_add)
                    )
                }
                TextButton(
                    onClick = { viewModel.setArranging(!state.arranging) },
                    enabled = !state.busy
                ) {
                    Text(
                        stringResource(
                            if (state.arranging) {
                                R.string.hub_canvas_done
                            } else {
                                R.string.hub_canvas_edit
                            }
                        )
                    )
                }
            }
        }
        state.error?.let { error ->
            Text(
                error.label(state.selectedVariant?.capacity ?: 1),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics {
                    liveRegion = LiveRegionMode.Polite
                }
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
                        modifier = Modifier.fillMaxSize().verticalScroll(scrollState)
                    )
                } else {
                    WidgetGrid(
                        state = state,
                        viewModel = viewModel,
                        onOpen = open,
                        columns = if (maxWidth >= 700.dp) 4 else 2,
                        scrollState = scrollState,
                        viewportHeight = constraints.maxHeight.toFloat(),
                        modifier = Modifier.fillMaxWidth()
                            .verticalScroll(scrollState)
                            .padding(horizontal = 16.dp, vertical = 16.dp)
                    )
                }
            }
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

@Composable
private fun WidgetGrid(
    state: WidgetHubState,
    viewModel: WidgetHubViewModel,
    onOpen: (HubWidgetTile) -> Unit,
    columns: Int,
    scrollState: ScrollState,
    viewportHeight: Float,
    modifier: Modifier
) {
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var dragStart by remember { mutableStateOf(Offset.Zero) }
    var dropId by remember { mutableStateOf<String?>(null) }
    var dragScrollStart by remember { mutableStateOf(0) }
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
        val gutterPx = with(density) { 16.dp.toPx() }
        val scrollEdgePx = with(density) { 64.dp.toPx() }
        val maxScrollStepPx = with(density) { 12.dp.toPx() }

        fun updateDropTarget() {
            val dragged = placements.firstOrNull { it.value.id == draggingId } ?: return
            val point = Offset(stridePx * dragged.column, stridePx * dragged.row) +
                dragStart + dragOffset + Offset(0f, (scrollState.value - dragScrollStart).toFloat())
            dropId = placements.firstOrNull { other ->
                val left = stridePx * other.column
                val top = stridePx * other.row
                val right = stridePx * (other.column + other.columns) - gapPx
                val bottom = stridePx * (other.row + other.rows) - gapPx
                other.value.id != draggingId && point.x in left..right && point.y in top..bottom
            }?.value?.id
        }

        LaunchedEffect(draggingId) {
            while (draggingId != null) {
                val dragged = placements.firstOrNull { it.value.id == draggingId } ?: break
                val pointerY = stridePx * dragged.row + dragStart.y + dragOffset.y +
                    gutterPx - dragScrollStart
                val scroll = when {
                    pointerY < scrollEdgePx ->
                        -((scrollEdgePx - pointerY) / scrollEdgePx).coerceIn(0f, 1f)

                    pointerY > viewportHeight - scrollEdgePx ->
                        ((pointerY - viewportHeight + scrollEdgePx) / scrollEdgePx).coerceIn(0f, 1f)

                    else -> 0f
                }
                if (scroll != 0f) {
                    scrollState.scrollBy(scroll * maxScrollStepPx)
                    updateDropTarget()
                }
                delay(16L)
            }
        }
        Box(Modifier.fillMaxWidth().height(unit * rows + gap * (rows - 1).coerceAtLeast(0))) {
            placements.forEachIndexed { index, placement ->
                val tile = placement.value
                key(tile.id) {
                    val dragging = draggingId == tile.id
                    val dragModifier = Modifier.widgetDrag(
                        onStart = { offset ->
                            viewModel.setArranging(true)
                            draggingId = tile.id
                            dragStart = offset
                            dragScrollStart = scrollState.value
                            dragOffset = Offset.Zero
                            dropId = null
                        },
                        onDrag = { offset ->
                            dragOffset = offset
                            updateDropTarget()
                        },
                        onEnd = {
                            dropId?.let { viewModel.moveWidget(tile.id, it) }
                            draggingId = null
                            dragOffset = Offset.Zero
                            dropId = null
                        },
                        onCancel = {
                            draggingId = null
                            dragOffset = Offset.Zero
                            dropId = null
                        }
                    )
                    WidgetCanvasTile(
                        tile = tile,
                        state = state,
                        onOpen = onOpen,
                        interactive = !state.busy && !state.arranging && draggingId == null,
                        arranging = state.arranging,
                        enabled = !state.busy && draggingId == null,
                        variants = state.gallery.firstOrNull { it.id == tile.definitionId }
                            ?.variants.orEmpty(),
                        canMoveUp = index > 0,
                        canMoveDown = index < placements.lastIndex,
                        dropTarget = dropId == tile.id,
                        viewModel = viewModel,
                        modifier = Modifier.offset(
                            x = (unit + gap) * placement.column,
                            y =
                                (unit + gap) * placement.row
                        )
                            .width(unit * placement.columns + gap * (placement.columns - 1))
                            .height(unit * placement.rows + gap * (placement.rows - 1))
                            .zIndex(if (dragging) 2f else 0f)
                            .graphicsLayer {
                                translationX = if (dragging) dragOffset.x else 0f
                                translationY = if (dragging) {
                                    dragOffset.y + scrollState.value - dragScrollStart
                                } else {
                                    0f
                                }
                                scaleX = if (dragging) 1.03f else 1f
                                scaleY = if (dragging) 1.03f else 1f
                                alpha = if (dragging) 0.9f else 1f
                            }.then(if (state.busy) Modifier else dragModifier)
                    )
                }
            }
            AddWidgetTile(
                enabled = !state.busy,
                onClick = viewModel::openGallery,
                modifier = Modifier.offset(
                    x = (unit + gap) * addColumn,
                    y = (unit + gap) * addRow
                ).size(unit)
            )
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
    interactive: Boolean,
    arranging: Boolean,
    enabled: Boolean,
    variants: List<HubWidgetVariant>,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    dropTarget: Boolean,
    viewModel: WidgetHubViewModel,
    modifier: Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    var choosingLayout by remember { mutableStateOf(false) }
    val edit = stringResource(R.string.hub_widget_edit)
    val remove = stringResource(R.string.hub_configure_delete)
    val title = tile.title?.takeIf(String::isNotBlank) ?: tile.kind.label()
    val moveUp = stringResource(R.string.hub_action_move_up)
    val moveDown = stringResource(R.string.hub_action_move_down)
    Box(
        modifier = modifier.semantics {
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
        }.then(
            if (dropTarget) {
                Modifier.border(
                    2.dp,
                    MaterialTheme.colorScheme.primary,
                    HubTileShape
                )
            } else {
                Modifier
            }
        )
    ) {
        HubWidgetFace(
            tile,
            state,
            onPay = viewModel::pay,
            modifier = Modifier.fillMaxSize(),
            interactive = interactive,
            onOpen = onOpen
        )
        if (arranging) {
            IconButton(
                onClick = { viewModel.removeWidget(tile.id) },
                enabled = enabled,
                modifier = Modifier.align(Alignment.TopStart).offset(x = (-8).dp, y = (-8).dp)
            ) {
                Icon(
                    Icons.Filled.Remove,
                    contentDescription = "$remove, $title",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.background(
                        MaterialTheme.colorScheme.errorContainer,
                        CircleShape
                    )
                        .padding(4.dp).size(20.dp)
                )
            }
        }
        Box(modifier = Modifier.align(Alignment.TopEnd)) {
            if (arranging) {
                IconButton(
                    onClick = {
                        choosingLayout = false
                        expanded = true
                    },
                    enabled = enabled,
                    modifier = Modifier.offset(x = 8.dp, y = (-8).dp).size(48.dp)
                ) {
                    Icon(
                        Icons.Filled.MoreHoriz,
                        contentDescription =
                            stringResource(R.string.hub_widget_options) + ", $title",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.background(
                            MaterialTheme.colorScheme.surfaceContainerHigh,
                            CircleShape
                        )
                            .padding(4.dp).size(20.dp)
                    )
                }
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                if (choosingLayout) {
                    DropdownMenuItem(text = {
                        Text(stringResource(R.string.hub_new_back))
                    }, onClick = {
                        choosingLayout = false
                    })
                    variants.forEach { variant ->
                        DropdownMenuItem(
                            text = { Text(variant.label(tile.kind)) },
                            enabled = variant.id != tile.variant.id,
                            onClick = {
                                expanded = false
                                viewModel.editWidget(tile.id)
                                if (viewModel.state.value.editor?.existingWidgetId == tile.id) {
                                    viewModel.selectVariant(variant.id)
                                    viewModel.configureSelected()
                                }
                            }
                        )
                    }
                } else {
                    DropdownMenuItem(text = { Text(edit) }, onClick = {
                        expanded = false
                        viewModel.editWidget(tile.id)
                    })
                    if (variants.size > 1) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.hub_widget_select_variant)) },
                            onClick = { choosingLayout = true }
                        )
                    }
                    DropdownMenuItem(text = { Text(moveUp) }, enabled = canMoveUp, onClick = {
                        expanded = false
                        viewModel.moveWidgetBy(tile.id, -1)
                    })
                    DropdownMenuItem(text = { Text(moveDown) }, enabled = canMoveDown, onClick = {
                        expanded = false
                        viewModel.moveWidgetBy(tile.id, 1)
                    })
                    DropdownMenuItem(text = { Text(remove) }, onClick = {
                        expanded = false
                        viewModel.removeWidget(tile.id)
                    })
                }
            }
        }
    }
}

@Composable
private fun Modifier.widgetDrag(
    onStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onEnd: () -> Unit,
    onCancel: () -> Unit
): Modifier {
    val start by rememberUpdatedState(onStart)
    val drag by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onEnd)
    val cancel by rememberUpdatedState(onCancel)
    return pointerInput(Unit) {
        var total = Offset.Zero
        detectDragGesturesAfterLongPress(
            onDragStart = {
                total = Offset.Zero
                start(it)
            },
            onDrag = { change, delta ->
                change.consume()
                total += delta
                drag(total)
            },
            onDragEnd = { end() },
            onDragCancel = { cancel() }
        )
    }
}
