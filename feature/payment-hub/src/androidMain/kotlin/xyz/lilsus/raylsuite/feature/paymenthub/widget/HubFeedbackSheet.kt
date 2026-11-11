package xyz.lilsus.raylsuite.feature.paymenthub.widget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import xyz.lilsus.raylsuite.core.raylapi.FeedbackCatalog
import xyz.lilsus.raylsuite.core.ui.platform.enableTestTagsAsResourceId
import xyz.lilsus.raylsuite.feature.paymenthub.R
import xyz.lilsus.raylsuite.feature.paymenthub.feedback.HubFeedbackKind
import xyz.lilsus.raylsuite.feature.paymenthub.live.HubFeedbackState

/** Where a tester tells the developer what the Hub is missing. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HubFeedbackSheet(
    feedback: HubFeedbackState,
    onSend: (HubFeedbackKind, String, String) -> Unit,
    onClearError: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var kind by rememberSaveable { mutableStateOf(HubFeedbackKind.Widget) }
    var message by rememberSaveable { mutableStateOf("") }
    var contact by rememberSaveable { mutableStateOf("") }
    val openedAt = remember { feedback.sentSerial }
    LaunchedEffect(feedback.sentSerial) {
        if (feedback.sentSerial != openedAt) onDismiss()
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.enableTestTagsAsResourceId()
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp)
                .navigationBarsPadding()
                .imePadding()
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.hub_feedback_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() }
                )
                Text(
                    stringResource(R.string.hub_feedback_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HubFeedbackKind.entries.forEach { option ->
                    FilterChip(
                        selected = kind == option,
                        enabled = !feedback.sending,
                        onClick = { kind = option },
                        label = { Text(stringResource(option.label())) }
                    )
                }
            }
            OutlinedTextField(
                value = message,
                onValueChange = {
                    message = it.take(FeedbackCatalog.MAX_MESSAGE_LENGTH)
                    onClearError()
                },
                enabled = !feedback.sending,
                label = { Text(stringResource(R.string.hub_feedback_message)) },
                minLines = 4,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = contact,
                onValueChange = { contact = it.take(FeedbackCatalog.MAX_CONTACT_LENGTH) },
                enabled = !feedback.sending,
                label = { Text(stringResource(R.string.hub_feedback_contact)) },
                supportingText = { Text(stringResource(R.string.hub_feedback_contact_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth()
            )
            if (feedback.failed) {
                Text(
                    stringResource(R.string.hub_feedback_error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
            }
            Button(
                onClick = { onSend(kind, message, contact) },
                enabled = message.isNotBlank() && !feedback.sending,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            ) {
                if (feedback.sending) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                } else {
                    Text(stringResource(R.string.hub_feedback_send))
                }
            }
        }
    }
}

private fun HubFeedbackKind.label() = when (this) {
    HubFeedbackKind.Widget -> R.string.hub_feedback_kind_widget
    HubFeedbackKind.Idea -> R.string.hub_feedback_kind_idea
    HubFeedbackKind.Problem -> R.string.hub_feedback_kind_problem
    HubFeedbackKind.Other -> R.string.hub_feedback_kind_other
}
