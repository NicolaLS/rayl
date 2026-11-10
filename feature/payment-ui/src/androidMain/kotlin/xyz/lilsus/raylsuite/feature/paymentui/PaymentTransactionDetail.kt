package xyz.lilsus.raylsuite.feature.paymentui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import xyz.lilsus.raylsuite.core.ui.components.BackIconButton
import xyz.lilsus.raylsuite.feature.paymentui.components.PaymentHero
import xyz.lilsus.raylsuite.feature.paymentui.components.ResultLayout

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentTransactionDetail(
    detail: PaymentTransactionDetail,
    receiptFeeInformation: String?,
    onIntent: (PaymentIntent) -> Unit,
    onBack: () -> Unit
) {
    DisposableEffect(detail.id) {
        onDispose { onIntent(PaymentIntent.DismissResult) }
    }
    detail.receipt?.let { receipt ->
        PaymentReceiptScreen(
            receipt = receipt,
            feeInformation = receiptFeeInformation,
            onBack = onBack
        )
        return
    }
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {},
                navigationIcon = { BackIconButton(onClick = onBack) }
            )
        }
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            PaymentHero(
                modifier = Modifier.fillMaxWidth().fillMaxHeight(0.5f),
                phase = detail.state.toHeroPhase()
            )
            ResultLayout(
                modifier = Modifier.fillMaxSize(),
                result = detail.state.toResultPresentation(),
                onContinue = onBack
            )
        }
    }
}
