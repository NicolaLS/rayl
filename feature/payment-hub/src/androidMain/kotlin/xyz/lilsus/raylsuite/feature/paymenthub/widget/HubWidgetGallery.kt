package xyz.lilsus.raylsuite.feature.paymenthub.widget

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import xyz.lilsus.raylsuite.feature.paymenthub.HubWidgetDefinition
import xyz.lilsus.raylsuite.feature.paymenthub.HubWidgetKind
import xyz.lilsus.raylsuite.feature.paymenthub.HubWidgetPerson
import xyz.lilsus.raylsuite.feature.paymenthub.HubWidgetTile
import xyz.lilsus.raylsuite.feature.paymenthub.HubWidgetVariant
import xyz.lilsus.raylsuite.feature.paymenthub.R
import xyz.lilsus.raylsuite.feature.paymenthub.WidgetHubState
import xyz.lilsus.raylsuite.feature.paymenthub.WidgetHubViewModel
import xyz.lilsus.raylsuite.feature.paymenthub.bills.HubBill
import xyz.lilsus.raylsuite.feature.paymenthub.claro.claroPhone

@Composable
internal fun HubWidgetGallery(
    state: WidgetHubState,
    viewModel: WidgetHubViewModel,
    modifier: Modifier = Modifier
) {
    var feedbackOpen by rememberSaveable { mutableStateOf(false) }
    var openBill by rememberSaveable { mutableStateOf<HubBill?>(null) }
    openBill?.let { bill -> HubBillSheet(bill, onDismiss = { openBill = null }) }
    if (feedbackOpen) {
        HubFeedbackSheet(
            feedback = state.feedback,
            onSend = viewModel::sendFeedback,
            onClearError = viewModel::clearFeedbackError,
            onDismiss = {
                feedbackOpen = false
                viewModel.clearFeedbackError()
            }
        )
    }
    Box(contentAlignment = Alignment.TopCenter, modifier = modifier) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            contentPadding = PaddingValues(20.dp),
            modifier = Modifier.widthIn(max = 700.dp).fillMaxSize()
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    stringResource(R.string.hub_widget_gallery_body),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            listOf(
                R.string.hub_widget_section_people to
                    listOf(HubWidgetKind.Contacts, HubWidgetKind.Shortcut),
                R.string.hub_widget_section_collections to
                    listOf(HubWidgetKind.Favorites, HubWidgetKind.Recents),
                R.string.hub_widget_section_info to
                    listOf(HubWidgetKind.Rate, HubWidgetKind.Sats),
                R.string.hub_widget_section_ai to listOf(HubWidgetKind.Ppq),
                R.string.hub_widget_section_mobile to listOf(HubWidgetKind.Claro)
            ).forEach { (title, kinds) ->
                item(span = { GridItemSpan(maxLineSpan) }) { GallerySectionTitle(title) }
                items(
                    state.gallery.filter {
                        it.kind in kinds
                    },
                    key = HubWidgetDefinition::id
                ) { definition ->
                    GalleryEntry(definition, state, onClick = {
                        viewModel.selectDefinition(definition.id)
                    })
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                GallerySectionTitle(R.string.hub_widget_section_bills)
            }
            items(HubBill.entries, key = { "bill.${it.name}" }) { bill ->
                HubBillEntry(
                    bill,
                    onSeen = { viewModel.billSeen(bill) },
                    onClick = {
                        viewModel.billTapped(bill)
                        openBill = bill
                    }
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                FeedbackPrompt(thanked = state.feedback.sentSerial > 0) { feedbackOpen = true }
            }
        }
    }
}

@Composable
private fun GallerySectionTitle(@StringRes title: Int) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp).semantics { heading() }
    )
}

/** The way out of the gallery for someone who did not find their widget. */
@Composable
private fun FeedbackPrompt(thanked: Boolean, onClick: () -> Unit) {
    // A card a step above the sheet, which is surfaceContainerLow itself, with a chevron that says
    // it leads somewhere.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large)
            .clickable(role = Role.Button, onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.hub_feedback_prompt),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                stringResource(
                    if (thanked) R.string.hub_feedback_thanks else R.string.hub_feedback_prompt_body
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun GalleryEntry(
    definition: HubWidgetDefinition,
    state: WidgetHubState,
    onClick: () -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)
    ) {
        val variant = definition.variants.first()
        HubWidgetFace(
            tile = previewTile(definition, variant, state),
            state = state,
            onPay = {},
            preview = true,
            interactive = false,
            compactPreview = true,
            // Square like the tile it becomes, so a chart keeps its room.
            modifier = Modifier.fillMaxWidth().aspectRatio(1f).clearAndSetSemantics {}
        )
        Text(definition.label(), style = MaterialTheme.typography.titleMedium)
        val body = definition.body()
        if (body.isNotBlank()) {
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
    }
}

internal fun previewTile(
    definition: HubWidgetDefinition,
    variant: HubWidgetVariant,
    state: WidgetHubState
) = HubWidgetTile(
    id = "preview:${definition.id}:${variant.id}",
    definitionId = definition.id,
    kind = definition.kind,
    variant = variant,
    title = definition.title,
    // The preview quotes the currency being configured, or the one a new widget starts with.
    currencyCode = state.editor?.takeIf { it.kind.quotesBitcoin }?.currencyCode
        ?: state.defaultFiatCode,
    phone = state.editor?.takeIf { it.kind == HubWidgetKind.Claro }?.phoneInput?.let(::claroPhone),
    people = state.contacts.take(variant.capacity).map { contact ->
        HubWidgetPerson(actionId = "", contactId = contact.id, title = contact.title, address = "")
    }
)

@Composable
internal fun HubWidgetVariants(
    state: WidgetHubState,
    viewModel: WidgetHubViewModel,
    modifier: Modifier = Modifier
) {
    val definition = state.selectedDefinition ?: return
    val variants = definition.variants
    val pager = rememberPagerState(
        initialPage = variants.indexOfFirst {
            it.id ==
                state.editor?.variantId
        }.coerceAtLeast(0)
    ) {
        variants.size
    }
    LaunchedEffect(pager, definition.id) {
        snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { page ->
            variants.getOrNull(page)?.let { viewModel.selectVariant(it.id) }
        }
    }
    // Every page reserves the tallest layout, so the previews keep their relative sizes and paging
    // never resizes the screen around them.
    val previewRows = variants.maxOf { it.rows }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp)
                ) {
                    Text(
                        definition.label(),
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                    Text(
                        definition.body(),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.widthIn(max = 480.dp).padding(horizontal = 24.dp)
                    )
                    HorizontalPager(
                        state = pager,
                        pageSpacing = 16.dp,
                        contentPadding = PaddingValues(horizontal = 24.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) { page ->
                        val variant = variants[page]
                        val description = stringResource(
                            R.string.hub_widget_preview_page,
                            page + 1,
                            variants.size
                        )
                        BoxWithConstraints(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxWidth().semantics {
                                contentDescription = description
                            }
                        ) {
                            val gaps = previewGap * (previewRows - 1)
                            val unit = (maxWidth.coerceAtMost(400.dp) - previewGap) / 2
                            Box(
                                Modifier.fillMaxWidth().height(unit * previewRows + gaps),
                                contentAlignment = Alignment.Center
                            ) {
                                HubWidgetFace(
                                    tile = previewTile(definition, variant, state),
                                    state = state,
                                    onPay = {},
                                    preview = true,
                                    interactive = false,
                                    modifier = Modifier
                                        .width(
                                            unit * variant.columns +
                                                previewGap * (variant.columns - 1)
                                        )
                                        .height(
                                            unit * variant.rows + previewGap * (variant.rows - 1)
                                        )
                                )
                            }
                        }
                    }
                    if (variants.size > 1) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            variants.indices.forEach { page ->
                                Box(
                                    Modifier.size(7.dp).background(
                                        if (page == pager.currentPage) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.outlineVariant
                                        },
                                        CircleShape
                                    )
                                )
                            }
                        }
                    }
                    variants.getOrNull(pager.currentPage)?.let { variant ->
                        Text(
                            variant.label(definition.kind),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            variant.body(definition.kind),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.widthIn(max = 480.dp)
                                .padding(horizontal = 24.dp)
                        )
                    }
                }
            }
        }
        Button(
            onClick = {
                variants.getOrNull(pager.currentPage)?.let { viewModel.selectVariant(it.id) }
                viewModel.continueFromVariants()
            },
            enabled = !state.busy,
            modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth()
                .padding(horizontal = 24.dp).padding(top = 8.dp, bottom = 16.dp).height(56.dp)
        ) {
            // A widget that needs no setup is added from here, so the button says so.
            Text(
                stringResource(
                    if (state.addsWithoutSetup) {
                        R.string.hub_widget_add
                    } else {
                        R.string.hub_widget_continue
                    }
                )
            )
        }
    }
}

private val previewGap = 12.dp
