package xyz.lilsus.rayl.providers.rayl

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.alexzhirkevich.qrose.rememberQrCodePainter
import kotlinx.coroutines.delay
import xyz.lilsus.rayl.providers.rayl.experience.R
import xyz.lilsus.raylsuite.core.model.DisplayAmount
import xyz.lilsus.raylsuite.core.model.DisplayCurrency
import xyz.lilsus.raylsuite.core.ui.format.rememberAmountFormatter

/**
 * The add funds sheet, open for as long as funding is: the invoice to scan, copy, or open in a
 * wallet app on this phone, then the amount that arrived.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RaylFundingSheet(funding: RaylFunding) {
    val state by funding.state.collectAsStateWithLifecycle()
    val current = state
    if (current == FundingState.Idle) return
    ModalBottomSheet(
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
            when (current) {
                is FundingState.Received -> FundsAdded(current.amountSats, onDone = funding::reset)

                FundingState.Failed -> {
                    Title()
                    Text(
                        text = stringResource(R.string.rayl_invoice_failed),
                        textAlign = TextAlign.Center
                    )
                    Button(onClick = funding::request, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.rayl_retry))
                    }
                }

                is FundingState.Awaiting -> InvoiceToPay(current.invoice)

                else -> {
                    Title()
                    Box(modifier = Modifier.size(QrSize), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }
}

@Composable
private fun Title() {
    Text(
        text = stringResource(R.string.rayl_add_funds),
        style = MaterialTheme.typography.headlineSmall
    )
}

@Composable
private fun InvoiceToPay(invoice: String) {
    val context = LocalContext.current
    val hasWalletApp = remember { walletIntent(invoice).canBeOpened(context) }
    Title()
    Text(text = stringResource(R.string.rayl_add_funds_body), textAlign = TextAlign.Center)
    // Scanners need dark modules on a light field with a quiet zone, whatever the theme is.
    Surface(
        modifier = Modifier.size(QrSize).testTag("rayl_funding_qr"),
        shape = MaterialTheme.shapes.large,
        color = Color.White
    ) {
        Image(
            painter = rememberQrCodePainter(invoice.uppercase()),
            contentDescription = stringResource(R.string.rayl_add_funds),
            modifier = Modifier.fillMaxSize().padding(16.dp)
        )
    }
    Text(
        text = stringResource(R.string.rayl_waiting_for_payment),
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    if (hasWalletApp) {
        Button(
            onClick = { openInWallet(context, invoice) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.rayl_open_in_wallet))
        }
    }
    var copied by remember(invoice) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2_000)
            copied = false
        }
    }
    OutlinedButton(
        onClick = {
            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                ClipData.newPlainText("Lightning invoice", invoice)
            )
            copied = true
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(stringResource(if (copied) R.string.rayl_copied else R.string.rayl_copy_invoice))
    }
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

private val QrSize = 240.dp

/** Rayl does not handle `lightning:` links itself, so only other wallet apps match. */
private fun walletIntent(invoice: String) =
    Intent(Intent.ACTION_VIEW, Uri.parse("lightning:$invoice"))

/** Needs the `lightning` query declared in this module's manifest. */
private fun Intent.canBeOpened(context: Context): Boolean =
    context.packageManager.queryIntentActivities(
        this,
        PackageManager.MATCH_DEFAULT_ONLY
    ).isNotEmpty()

/**
 * Always asks which wallet app to use: a default chosen with "Always" would otherwise tie adding
 * funds to that one wallet.
 */
private fun openInWallet(context: Context, invoice: String) {
    try {
        context.startActivity(Intent.createChooser(walletIntent(invoice), null))
    } catch (_: ActivityNotFoundException) {
        // The wallet app was removed while the sheet was open; the invoice is still on screen.
    }
}
