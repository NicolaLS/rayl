package xyz.lilsus.rayl

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.lilsus.rayl.providers.blink.clearSavedBlinkWallet
import xyz.lilsus.rayl.providers.nwc.clearSavedNwcWallet
import xyz.lilsus.rayl.providers.spark.clearSavedSparkWallet

/** Rayl's own wallet is never cleared here; it survives external wallets. See SPEC-wallet-lifecycle. */
internal suspend fun clearSavedWallet(context: Context, wallet: ExternalWallet): Boolean =
    withContext(Dispatchers.Default) {
        when (wallet) {
            ExternalWallet.Blink -> clearSavedBlinkWallet(context, RAYL_BLINK)
            ExternalWallet.Nwc -> clearSavedNwcWallet(context, RAYL_NWC)
            ExternalWallet.Spark -> return@withContext clearSavedSparkWallet(context)
        }
        true
    }
