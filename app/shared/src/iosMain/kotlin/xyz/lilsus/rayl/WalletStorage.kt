package xyz.lilsus.rayl

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.lilsus.rayl.providers.blink.clearSavedBlinkWallet
import xyz.lilsus.rayl.providers.nwc.clearSavedNwcWallet
import xyz.lilsus.rayl.providers.spark.clearSavedSparkWallet

/** Rayl's own wallet is never cleared here; it survives external wallets. See SPEC-wallet-lifecycle. */
internal suspend fun clearSavedWallet(wallet: ExternalWallet): Boolean =
    withContext(Dispatchers.Default) {
        when (wallet) {
            ExternalWallet.Blink -> clearSavedBlinkWallet(RAYL_BLINK)
            ExternalWallet.Nwc -> clearSavedNwcWallet(RAYL_NWC)
            ExternalWallet.Spark -> return@withContext clearSavedSparkWallet()
        }
        true
    }
