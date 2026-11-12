package xyz.lilsus.rayl

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.lilsus.rayl.providers.blink.clearSavedBlinkWallet
import xyz.lilsus.rayl.providers.nwc.clearSavedNwcWallet
import xyz.lilsus.rayl.providers.spark.clearSavedSparkWallet

/** Erases one card's storage. The pocket is never cleared here. See SPEC-wallet-lifecycle. */
internal suspend fun clearSavedCard(context: Context, card: RaylCard): Boolean =
    withContext(Dispatchers.Default) {
        when (card.wallet) {
            ExternalWallet.Blink -> clearSavedBlinkWallet(context, card.blinkConfiguration())

            ExternalWallet.Nwc -> clearSavedNwcWallet(context, card.nwcConfiguration())

            ExternalWallet.Spark ->
                return@withContext clearSavedSparkWallet(
                    context,
                    card.sparkConfiguration().storageName
                )
        }
        true
    }
