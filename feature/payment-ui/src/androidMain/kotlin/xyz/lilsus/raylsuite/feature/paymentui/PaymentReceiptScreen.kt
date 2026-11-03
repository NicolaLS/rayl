package xyz.lilsus.raylsuite.feature.paymentui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.PersistableBundle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import xyz.lilsus.raylsuite.core.model.DisplayAmount
import xyz.lilsus.raylsuite.core.model.DisplayCurrency
import xyz.lilsus.raylsuite.core.ui.components.BackIconButton
import xyz.lilsus.raylsuite.core.ui.format.AmountFormatter
import xyz.lilsus.raylsuite.core.ui.format.rememberAmountFormatter
import xyz.lilsus.raylsuite.core.ui.hero.RaylHeroPhase
import xyz.lilsus.raylsuite.feature.paymentui.components.PaymentHero

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PaymentReceiptScreen(
    receipt: PaymentReceiptDetails,
    feeInformation: String?,
    onBack: () -> Unit
) {
    val formatter = rememberAmountFormatter()
    val timestamp = remember(receipt.createdAtMs) {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date(receipt.createdAtMs))
    }
    Scaffold(
        modifier = Modifier.testTag(PaymentTestTags.RECEIPT),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.result_receipt_title)) },
                navigationIcon = { BackIconButton(onClick = onBack) }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                ReceiptField(
                    label = stringResource(R.string.receipt_amount),
                    value = formatter.formatReceiptAmount(receipt.amount, receipt.amountSats)
                )
                Column {
                    ReceiptField(
                        label = stringResource(R.string.receipt_fee),
                        value = receipt.fee?.let { fee ->
                            receipt.feeSats?.let { formatter.formatReceiptAmount(fee, it) }
                        } ?: stringResource(R.string.receipt_fee_unknown)
                    )
                    feeInformation?.let {
                        Text(
                            modifier = Modifier.padding(top = 6.dp),
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                HorizontalDivider()
                receipt.recipient?.takeIf(String::isNotBlank)?.let {
                    ReceiptField(label = stringResource(R.string.receipt_recipient), value = it)
                }
                receipt.domain?.takeIf(String::isNotBlank)?.let {
                    ReceiptField(label = stringResource(R.string.receipt_domain), value = it)
                }
                ReceiptField(label = stringResource(R.string.receipt_timestamp), value = timestamp)
                receipt.preimage?.trim()?.takeIf(String::isNotEmpty)?.let { preimage ->
                    HorizontalDivider()
                    ReceiptPreimage(preimage)
                }
            }
        }
    }
}

@Composable
private fun ReceiptField(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun ReceiptPreimage(preimage: String) {
    val context = LocalContext.current
    val clipboard = remember(context) {
        checkNotNull(context.getSystemService(ClipboardManager::class.java))
    }
    val preimageLabel = stringResource(R.string.receipt_preimage)
    var copied by remember(preimage) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2_000)
            copied = false
        }
    }
    val copyPreimage: () -> Unit = {
        val clip = ClipData.newPlainText(preimageLabel, preimage).apply {
            description.extras = PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        }
        clipboard.setPrimaryClip(clip)
        copied = true
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            modifier = Modifier.fillMaxWidth(),
            text = preimageLabel,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        PaymentHero(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .fillMaxWidth()
                .aspectRatio(1f)
                .clickable(onClick = copyPreimage),
            phase = RaylHeroPhase.Succeeded,
            receiptPreimage = preimage
        )
        SelectionContainer {
            Text(
                text = preimage,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center
            )
        }
        TextButton(
            modifier = Modifier.testTag(PaymentTestTags.RECEIPT_COPY_PREIMAGE),
            onClick = copyPreimage
        ) {
            Icon(imageVector = Icons.Filled.ContentCopy, contentDescription = null)
            Text(
                modifier = Modifier.padding(start = 8.dp),
                text = stringResource(
                    if (copied) R.string.receipt_copied else R.string.receipt_copy_preimage
                )
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        ReceiptExplanation()
    }
}

@Composable
private fun ReceiptExplanation() {
    val receiptBody = buildAnnotatedString {
        append(stringResource(R.string.result_receipt_body_prefix).trim())
        append(' ')
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            append(stringResource(R.string.result_receipt_body_preimage).trim())
        }
        append(' ')
        append(stringResource(R.string.result_receipt_body_middle).trim())
        append(' ')
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            append(stringResource(R.string.result_receipt_body_only).trim())
        }
        append(' ')
        append(stringResource(R.string.result_receipt_body_suffix).trim())
    }
    Text(
        text = receiptBody,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
}

private fun AmountFormatter.formatReceiptAmount(
    amount: DisplayAmount,
    satoshis: DisplayAmount
): String = when (amount.currency) {
    DisplayCurrency.Satoshi -> format(amount).uppercase(Locale.ROOT)
    is DisplayCurrency.Fiat -> "${format(amount)} (${format(satoshis).uppercase(Locale.ROOT)})"
}
