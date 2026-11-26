package xyz.lilsus.rayl

import android.os.Bundle
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.Feature
import fr.acinq.lightning.FeatureSupport
import fr.acinq.lightning.Features
import fr.acinq.lightning.payment.Bolt11Invoice
import fr.acinq.lightning.utils.currentTimestampSeconds
import fr.acinq.lightning.utils.toByteVector32
import xyz.lilsus.raylsuite.core.camera.TestQrInput

/**
 * Benchmark-only subclass of [MainActivity] that injects a single synthetic QR read via
 * [TestQrInput], letting Macrobenchmark measure the manual-amount keypad UI without a physical QR.
 */
class BenchmarkScanActivity : MainActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_ZERO_AMOUNT_BOLT11, false)) {
            TestQrInput.install(createZeroAmountInvoice())
        }
        super.onCreate(savedInstanceState)
    }

    private fun createZeroAmountInvoice(): String = Bolt11Invoice.create(
        chain = Chain.Mainnet,
        amount = null,
        paymentHash = Crypto.sha256(
            "benchmark-${System.nanoTime()}".encodeToByteArray()
        ).toByteVector32(),
        privateKey = PrivateKey(ByteArray(32) { 1 }),
        description = Either.Left("Benchmark coffee"),
        minFinalCltvExpiryDelta = Bolt11Invoice.DEFAULT_MIN_FINAL_EXPIRY_DELTA,
        features = Features(
            Feature.VariableLengthOnion to FeatureSupport.Optional,
            Feature.PaymentSecret to FeatureSupport.Optional
        ),
        timestampSeconds = currentTimestampSeconds(),
        expirySeconds = 3_600L
    ).write()

    private companion object {
        const val EXTRA_ZERO_AMOUNT_BOLT11 = "scanZeroAmountInvoice"
    }
}
