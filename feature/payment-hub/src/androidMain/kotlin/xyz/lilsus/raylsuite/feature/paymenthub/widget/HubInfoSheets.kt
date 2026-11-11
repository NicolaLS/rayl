package xyz.lilsus.raylsuite.feature.paymenthub.widget

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import xyz.lilsus.raylsuite.core.model.CurrencyCatalog
import xyz.lilsus.raylsuite.core.ui.format.rememberAmountFormatter
import xyz.lilsus.raylsuite.core.ui.platform.enableTestTagsAsResourceId
import xyz.lilsus.raylsuite.feature.paymenthub.R
import xyz.lilsus.raylsuite.feature.paymenthub.live.HubConversion
import xyz.lilsus.raylsuite.feature.paymenthub.live.HubPpqAccount
import xyz.lilsus.raylsuite.feature.paymenthub.live.HubPpqTopUpError
import xyz.lilsus.raylsuite.feature.paymenthub.live.HubRate
import xyz.lilsus.raylsuite.feature.paymenthub.live.convertFiat
import xyz.lilsus.raylsuite.feature.paymenthub.live.convertSats
import xyz.lilsus.raylsuite.feature.paymenthub.live.satsLabel

/**
 * A two-field calculator: type in either field and the other follows. It opens empty with one
 * unit of the currency as the example, so there is nothing to clear before typing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HubSatsConverterDialog(rate: HubRate, onDismiss: () -> Unit) {
    var values by remember(rate.currencyCode) { mutableStateOf(HubConversion("", "")) }
    var typedFiat by remember(rate.currencyCode) { mutableStateOf(true) }
    // A refreshed price keeps what the user typed and updates the other side.
    LaunchedEffect(rate.price) {
        values = if (typedFiat) rate.convertFiat(values.fiat) else rate.convertSats(values.sats)
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val wholeUnits = CurrencyCatalog.infoFor(rate.currencyCode).fractionDigits == 0
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 12.dp)
            ) {
                Text(
                    stringResource(R.string.hub_converter_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() }
                )
                ConverterField(
                    value = values.fiat,
                    placeholder = "1",
                    unit = rate.currencyCode,
                    keyboardType = if (wholeUnits) KeyboardType.Number else KeyboardType.Decimal,
                    onChange = {
                        typedFiat = true
                        values = rate.convertFiat(it)
                    },
                    onDone = onDismiss,
                    modifier = Modifier.focusRequester(focus)
                )
                ConverterField(
                    value = values.sats,
                    placeholder = rate.satsLabel(),
                    unit = stringResource(R.string.hub_sats_unit),
                    keyboardType = KeyboardType.Number,
                    onChange = {
                        typedFiat = false
                        values = rate.convertSats(it)
                    },
                    onDone = onDismiss
                )
                Text(
                    "1 BTC = " + rememberAmountFormatter().format(rate.priceAmount()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp)
                )
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.hub_canvas_done))
                }
            }
        }
    }
}

@Composable
private fun ConverterField(
    value: String,
    placeholder: String,
    unit: String,
    keyboardType: KeyboardType,
    onChange: (String) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val figures = MaterialTheme.typography.headlineSmall.copy(
        fontWeight = FontWeight.SemiBold,
        fontFeatureSettings = "tnum"
    )
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        textStyle = figures,
        placeholder = {
            Text(placeholder, style = figures, color = LocalContentColor.current.copy(alpha = 0.4f))
        },
        suffix = { Text(unit, style = MaterialTheme.typography.titleMedium) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = modifier.fillMaxWidth()
    )
}

/** Picks an amount; the wallet's own payment screen takes over once ppq.ai sent the invoice. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HubPpqTopUpSheet(
    account: HubPpqAccount,
    onTopUp: (String) -> Unit,
    onClearError: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var amount by rememberSaveable { mutableStateOf("10") }
    val openedAt = remember { account.topUpSerial }
    LaunchedEffect(account.topUpSerial) {
        if (account.topUpSerial != openedAt) onDismiss()
    }
    val balance = account.balanceAmount()?.let { rememberAmountFormatter().format(it) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.enableTestTagsAsResourceId()
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp)
                .navigationBarsPadding()
                .imePadding()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Image(
                    painterResource(R.drawable.hub_ppq),
                    contentDescription = null,
                    modifier = Modifier.size(44.dp)
                )
                Column {
                    Text(
                        stringResource(R.string.hub_ppq_top_up_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading() }
                    )
                    if (balance != null) {
                        Text(
                            stringResource(R.string.hub_ppq_balance) + " · " + balance,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HubPpqAccount.TOP_UP_PRESETS_USD.forEach { preset ->
                    FilterChip(
                        selected = amount == preset.toString(),
                        enabled = !account.toppingUp,
                        onClick = {
                            amount = preset.toString()
                            onClearError()
                        },
                        label = {
                            Text(
                                "$$preset",
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            OutlinedTextField(
                value = amount,
                onValueChange = { typed ->
                    amount = typed.filter { it.isDigit() || it == '.' || it == ',' }.take(8)
                    onClearError()
                },
                enabled = !account.toppingUp,
                label = { Text(stringResource(R.string.hub_ppq_amount)) },
                prefix = { Text("$") },
                singleLine = true,
                isError = account.topUpError == HubPpqTopUpError.Amount,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
            val error = account.topUpError
            Text(
                stringResource(
                    when (error) {
                        HubPpqTopUpError.Amount -> R.string.hub_ppq_error_amount
                        HubPpqTopUpError.InvalidKey -> R.string.hub_ppq_error_key
                        HubPpqTopUpError.Unreachable -> R.string.hub_ppq_error_unreachable
                        null -> R.string.hub_ppq_top_up_body
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (error == null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
            Button(
                onClick = { onTopUp(amount) },
                enabled = !account.toppingUp,
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                if (account.toppingUp) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        color = LocalContentColor.current,
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    Icon(Icons.Filled.Bolt, contentDescription = null, Modifier.size(18.dp))
                    Text(
                        stringResource(R.string.hub_ppq_top_up_action),
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }
    }
}
