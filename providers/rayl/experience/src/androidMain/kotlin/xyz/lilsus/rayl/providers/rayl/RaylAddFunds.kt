package xyz.lilsus.rayl.providers.rayl

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.alexzhirkevich.qrose.rememberQrCodePainter
import xyz.lilsus.rayl.providers.rayl.experience.R
import xyz.lilsus.raylsuite.core.model.DisplayAmount
import xyz.lilsus.raylsuite.core.model.DisplayCurrency
import xyz.lilsus.raylsuite.core.ui.format.rememberAmountFormatter

/**
 * Hands a new funding invoice to a wallet app, and presents the invoice when none can take
 * it, a payment arrives, or creating the invoice failed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RaylFundingEffects(funding: RaylFunding) {
    val state by funding.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val current = state
    LaunchedEffect(current) {
        if (current is FundingState.Awaiting &&
            current.presentation == FundingPresentation.OpenWallet
        ) {
            if (openInWallet(context, current.invoice)) {
                funding.openedInWallet()
            } else {
                funding.showInvoice()
            }
        }
    }
    when {
        current == FundingState.Failed -> AlertDialog(
            onDismissRequest = funding::reset,
            text = { Text(stringResource(R.string.rayl_invoice_failed)) },
            confirmButton = {
                TextButton(onClick = funding::reset) { Text(stringResource(R.string.rayl_done)) }
            }
        )

        current is FundingState.Received ||
            (
                current is FundingState.Awaiting &&
                    current.presentation == FundingPresentation.Shown
                ) -> ModalBottomSheet(
            onDismissRequest = funding::reset,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (current is FundingState.Received) {
                    FundsAdded(current.amountSats, onDone = funding::reset)
                } else if (current is FundingState.Awaiting) {
                    InvoiceToPay(current.invoice)
                }
            }
        }
    }
}

@Composable
private fun InvoiceToPay(invoice: String) {
    val context = LocalContext.current
    Text(
        text = stringResource(R.string.rayl_add_funds),
        style = MaterialTheme.typography.headlineSmall
    )
    Text(text = stringResource(R.string.rayl_add_funds_body), textAlign = TextAlign.Center)
    Image(
        painter = rememberQrCodePainter(invoice.uppercase()),
        contentDescription = null,
        modifier = Modifier.size(240.dp)
    )
    Text(
        text = invoice,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 2,
        overflow = TextOverflow.MiddleEllipsis
    )
    OutlinedButton(
        onClick = {
            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                ClipData.newPlainText("Lightning invoice", invoice)
            )
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(stringResource(R.string.rayl_copy_invoice))
    }
    Text(
        text = stringResource(R.string.rayl_waiting_for_payment),
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun FundsAdded(amountSats: Long, onDone: () -> Unit) {
    val formatter = rememberAmountFormatter()
    Icon(
        imageVector = Icons.Filled.CheckCircle,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(72.dp)
    )
    Text(
        text = stringResource(R.string.rayl_funds_added),
        style = MaterialTheme.typography.headlineSmall
    )
    Text(
        text = formatter.format(DisplayAmount(amountSats, DisplayCurrency.Satoshi)),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.rayl_done))
    }
}

/**
 * Opens `lightning:<invoice>` in the user's wallet app; Android picks their default or asks which
 * one. Rayl does not handle such links itself. Returns false when no app can open it.
 */
private fun openInWallet(context: Context, invoice: String): Boolean = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("lightning:$invoice")))
    true
} catch (_: ActivityNotFoundException) {
    false
}
