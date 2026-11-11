package xyz.lilsus.raylsuite.feature.paymenthub.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xyz.lilsus.raylsuite.core.model.CurrencyCatalog
import xyz.lilsus.raylsuite.feature.paymenthub.HubContact
import xyz.lilsus.raylsuite.feature.paymenthub.HubWidgetEditor
import xyz.lilsus.raylsuite.feature.paymenthub.HubWidgetKind
import xyz.lilsus.raylsuite.feature.paymenthub.LocalHubWidgets
import xyz.lilsus.raylsuite.feature.paymenthub.R
import xyz.lilsus.raylsuite.feature.paymenthub.WidgetHubState
import xyz.lilsus.raylsuite.feature.paymenthub.WidgetHubViewModel
import xyz.lilsus.raylsuite.feature.paymenthub.editorValidationError

@Composable
internal fun HubWidgetEditorScreen(
    state: WidgetHubState,
    viewModel: WidgetHubViewModel,
    modifier: Modifier = Modifier
) {
    val editor = state.editor ?: return
    val variant = state.selectedVariant ?: return
    val definition = state.selectedDefinition ?: return
    val choosesContacts = editor.kind == HubWidgetKind.Contacts ||
        editor.kind == HubWidgetKind.Shortcut
    val selectedContacts = editor.contactIds.mapNotNull { id ->
        state.contacts.firstOrNull { it.id == id }
    }
    val validationError = state.editorValidationError()
    var pickingContacts by remember(editor.definitionId, editor.existingWidgetId) {
        mutableStateOf(false)
    }
    Box(contentAlignment = Alignment.TopCenter, modifier = modifier) {
        // The screen Scaffold consumes system bars; this handles only the remaining keyboard inset.
        Column(modifier = Modifier.widthIn(max = 640.dp).fillMaxSize().imePadding()) {
            Column(
                verticalArrangement = Arrangement.spacedBy(22.dp),
                modifier = Modifier.weight(1f).fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 20.dp)
            ) {
                if (editor.kind.showsInformation) {
                    // These widgets show no name, so the form opens on the widget itself.
                    HubWidgetFace(
                        tile = previewTile(definition, variant, state),
                        state = state,
                        onPay = {},
                        preview = true,
                        interactive = false,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                            .then(
                                if (variant.columns > 1) {
                                    Modifier.fillMaxWidth()
                                } else {
                                    Modifier.width(168.dp)
                                }
                            )
                            .height(168.dp)
                            .clearAndSetSemantics {}
                    )
                } else {
                    OutlinedTextField(
                        value = editor.title,
                        onValueChange = viewModel::updateTitle,
                        enabled = !state.busy,
                        singleLine = true,
                        label = { Text(stringResource(R.string.hub_widget_name)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (editor.kind.quotesBitcoin) {
                    CurrencyChoice(editor, viewModel, enabled = !state.busy)
                }
                if (editor.kind == HubWidgetKind.Ppq) {
                    ApiKeyField(editor, state.ppq.connected, viewModel, enabled = !state.busy)
                }
                if (choosesContacts) {
                    EditorSection(stringResource(R.string.hub_widget_contacts)) {
                        selectedContacts.forEach { contact ->
                            SelectedContact(contact, enabled = !state.busy) {
                                pickingContacts = true
                            }
                        }
                        if (selectedContacts.size < variant.capacity) {
                            ChooseContactsRow(
                                label = stringResource(
                                    if (variant.capacity == 1) {
                                        R.string.hub_widget_pick_contact
                                    } else {
                                        R.string.hub_widget_pick_contacts
                                    }
                                ),
                                enabled = !state.busy,
                                onClick = { pickingContacts = true }
                            )
                        }
                        // "0 of 1 selected" only restates the single row above it.
                        if (variant.capacity > 1) {
                            Text(
                                stringResource(
                                    R.string.hub_widget_selected_count,
                                    selectedContacts.size,
                                    variant.capacity
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp).semantics {
                                    liveRegion = LiveRegionMode.Polite
                                }
                            )
                        }
                    }
                } else if (editor.kind == HubWidgetKind.Favorites ||
                    editor.kind == HubWidgetKind.Recents
                ) {
                    EditorSection(definition.label()) {
                        Text(
                            definition.body(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            stringResource(R.string.hub_widget_recents_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (editor.kind == HubWidgetKind.Shortcut) {
                    ShortcutFields(editor, viewModel, enabled = !state.busy)
                }
                editor.existingWidgetId?.let { widgetId ->
                    TextButton(
                        onClick = { viewModel.removeWidget(widgetId) },
                        enabled = !state.busy,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Text(
                            stringResource(R.string.hub_configure_delete),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            HorizontalDivider()
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(horizontal = 16.dp)
                    .padding(top = 12.dp, bottom = 16.dp)
            ) {
                (state.error ?: validationError)?.let {
                    Text(
                        text = it.label(variant.capacity),
                        color = if (state.error != null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().semantics {
                            liveRegion = LiveRegionMode.Polite
                        }
                    )
                }
                Button(
                    onClick = viewModel::saveWidget,
                    enabled = !state.busy && validationError == null,
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) {
                    Text(
                        stringResource(
                            if (editor.existingWidgetId == null) {
                                R.string.hub_widget_add
                            } else {
                                R.string.hub_editor_save
                            }
                        )
                    )
                }
            }
        }
    }
    if (pickingContacts) {
        HubContactPicker(state, viewModel, onClose = { pickingContacts = false })
    }
}

/** Groups related inputs under one heading so the form reads as a few decisions, not a stack. */
@Composable
private fun EditorSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            // Neutral, so the accent stays reserved for amounts and the primary action.
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp).semantics { heading() }
        )
        content()
    }
}

/** The fiat currency a Rate or Sats widget quotes bitcoin in. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CurrencyChoice(
    editor: HubWidgetEditor,
    viewModel: WidgetHubViewModel,
    enabled: Boolean
) {
    EditorSection(stringResource(R.string.hub_widget_currency)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LocalHubWidgets.fiatCodes.forEach { code ->
                FilterChip(
                    selected = code == editor.currencyCode,
                    enabled = enabled,
                    onClick = { viewModel.selectCurrency(code) },
                    label = { Text(code) }
                )
            }
        }
    }
}

@Composable
private fun ApiKeyField(
    editor: HubWidgetEditor,
    keySaved: Boolean,
    viewModel: WidgetHubViewModel,
    enabled: Boolean
) {
    EditorSection(stringResource(R.string.hub_ppq_api_key)) {
        OutlinedTextField(
            value = editor.apiKeyInput,
            onValueChange = viewModel::updateApiKey,
            enabled = enabled,
            singleLine = true,
            placeholder = { Text("sk-…") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                autoCorrectEnabled = false
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            stringResource(
                if (keySaved) R.string.hub_ppq_api_key_saved else R.string.hub_ppq_api_key_hint
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp)
        )
    }
}

@Composable
private fun ChooseContactsRow(label: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().padding(12.dp)
        ) {
            // Matches the portrait on a chosen contact, so both rows share one rhythm.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(40.dp).background(
                    MaterialTheme.colorScheme.primaryContainer,
                    CircleShape
                )
            ) {
                Icon(
                    Icons.Filled.PersonAdd,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp)
                )
            }
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}

@Composable
private fun SelectedContact(contact: HubContact, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().padding(12.dp)
        ) {
            ContactAvatar(contact.title)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    contact.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    contact.address.full,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}

@Composable
private fun ShortcutFields(
    editor: HubWidgetEditor,
    viewModel: WidgetHubViewModel,
    enabled: Boolean
) {
    var currencies by remember { mutableStateOf(false) }
    EditorSection(stringResource(R.string.hub_widget_shortcut_amount)) {
        OutlinedTextField(
            value = editor.amountInput,
            enabled = enabled,
            onValueChange = viewModel::updateAmount,
            placeholder = { Text("0") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (
                    CurrencyCatalog.infoFor(editor.currencyCode).fractionDigits == 0
                ) {
                    KeyboardType.Number
                } else {
                    KeyboardType.Decimal
                }
            ),
            // The currency belongs to the amount; a separate pill beside the field only looked
            // like a second, unrelated control.
            trailingIcon = {
                Box {
                    TextButton(
                        onClick = { currencies = true },
                        enabled = enabled,
                        contentPadding = PaddingValues(start = 12.dp, end = 8.dp)
                    ) {
                        Text(editor.currencyCode, style = MaterialTheme.typography.labelLarge)
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(expanded = currencies, onDismissRequest = { currencies = false }) {
                        CurrencyCatalog.supportedCodes.forEach { code ->
                            DropdownMenuItem(text = { Text(code) }, onClick = {
                                currencies = false
                                viewModel.selectCurrency(code)
                            })
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        if (editor.currencyCode != "SAT") {
            Text(
                stringResource(R.string.hub_target_amount_fiat_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }
    OutlinedTextField(
        value = editor.comment,
        enabled = enabled,
        onValueChange = viewModel::updateComment,
        label = { Text(stringResource(R.string.hub_target_comment_label)) },
        modifier = Modifier.fillMaxWidth()
    )
}
