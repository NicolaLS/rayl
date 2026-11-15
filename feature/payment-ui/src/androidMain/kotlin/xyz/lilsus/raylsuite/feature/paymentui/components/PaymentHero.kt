package xyz.lilsus.raylsuite.feature.paymentui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import xyz.lilsus.raylsuite.core.ui.hero.RaylHero
import xyz.lilsus.raylsuite.core.ui.hero.RaylHeroPhase
import xyz.lilsus.raylsuite.core.ui.hero.RaylHeroQrContent
import xyz.lilsus.raylsuite.feature.paymentui.R

@Composable
fun PaymentHero(
    phase: RaylHeroPhase,
    modifier: Modifier = Modifier,
    receiptPreimage: String? = null,
    rejections: Int = 0
) {
    val qrContent =
        receiptPreimage
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let {
                RaylHeroQrContent(
                    data = it,
                    contentDescription = stringResource(
                        R.string.payment_receipt_qr_content_description
                    )
                )
            }
    RaylHero(
        phase = phase,
        modifier = modifier,
        qrContent = qrContent,
        rejections = rejections
    )
}
