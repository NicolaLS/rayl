package xyz.lilsus.raylsuite.feature.paymenthub.widget

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import xyz.lilsus.raylsuite.feature.paymenthub.R
import xyz.lilsus.raylsuite.feature.paymenthub.bills.HubBill
import xyz.lilsus.raylsuite.feature.paymenthub.bills.HubBillKind

/** A bill in the gallery, laid out like a widget there. It reports when it comes into view. */
@Composable
internal fun HubBillEntry(bill: HubBill, onSeen: () -> Unit, onClick: () -> Unit) {
    LaunchedEffect(bill) { onSeen() }
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)
    ) {
        HubBillFace(bill, Modifier.fillMaxWidth().aspectRatio(1f).clearAndSetSemantics {})
        Text(bill.title, style = MaterialTheme.typography.titleMedium)
        Text(
            bill.body(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp)
        )
    }
}

/** The biller's mark on a tile's face, with what the bill is for. */
@Composable
private fun HubBillFace(bill: HubBill, modifier: Modifier) {
    val face = hubFaceColors()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        modifier = modifier.hubFace(face, bill.glow).padding(14.dp)
    ) {
        ServiceMark(bill.mark, size = 64.dp)
        Text(
            stringResource(bill.kind.label),
            style = MaterialTheme.typography.bodyMedium,
            color = face.quiet,
            maxLines = 1
        )
    }
}

/** What a tap on a bill shows: paying it is on its way. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HubBillSheet(bill: HubBill, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp)
                .navigationBarsPadding()
        ) {
            ServiceMark(bill.mark, size = 56.dp)
            Text(
                stringResource(R.string.hub_bill_soon_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() }
            )
            Text(
                stringResource(R.string.hub_bill_soon_body, bill.title),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(stringResource(R.string.hub_bill_soon_ok))
            }
        }
    }
}

@get:DrawableRes
private val HubBill.mark: Int
    get() = when (this) {
        HubBill.Anda -> R.drawable.hub_bill_anda
        HubBill.Caess, HubBill.Clesa, HubBill.Eeo, HubBill.Deusem -> R.drawable.hub_bill_aes
        HubBill.Delsur -> R.drawable.hub_bill_delsur
        HubBill.Tigo -> R.drawable.hub_bill_tigo
        HubBill.ClaroResidencial -> R.drawable.hub_claro
    }

private val HubBill.glow: Color
    get() = when (this) {
        HubBill.Anda -> Color(0xFF005CAE)
        HubBill.Caess, HubBill.Clesa, HubBill.Eeo, HubBill.Deusem -> Color(0xFF9A70FF)
        HubBill.Delsur -> Color(0xFF339ECD)
        HubBill.Tigo -> Color(0xFF476CAB)
        HubBill.ClaroResidencial -> ClaroRed
    }

private val HubBillKind.label: Int
    get() = when (this) {
        HubBillKind.Water -> R.string.hub_bill_water
        HubBillKind.Electricity -> R.string.hub_bill_electricity
        HubBillKind.Telecom -> R.string.hub_bill_telecom
    }

@Composable
private fun HubBill.body(): String = stringResource(
    when (this) {
        HubBill.Anda -> R.string.hub_bill_anda_body
        HubBill.Caess -> R.string.hub_bill_caess_body
        HubBill.Delsur -> R.string.hub_bill_delsur_body
        HubBill.Clesa -> R.string.hub_bill_clesa_body
        HubBill.Eeo -> R.string.hub_bill_eeo_body
        HubBill.Deusem -> R.string.hub_bill_deusem_body
        HubBill.Tigo -> R.string.hub_bill_tigo_body
        HubBill.ClaroResidencial -> R.string.hub_bill_claroresidencial_body
    }
)
