package xyz.lilsus.rayl

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.lilsus.rayl.providers.blink.clearSavedBlinkWallet
import xyz.lilsus.rayl.providers.nwc.clearSavedNwcWallet
import xyz.lilsus.rayl.providers.spark.SPARK_CREDENTIAL_STORE_PREFIX
import xyz.lilsus.rayl.providers.spark.clearSavedSparkWallet
import xyz.lilsus.rayl.providers.spark.sparkCredentialStores
import xyz.lilsus.raylsuite.core.settings.purgeSecureStores

/** Erases one card's storage. The pocket is never cleared here. See SPEC-wallet-lifecycle. */
internal suspend fun clearSavedCard(card: RaylCard): Boolean = withContext(Dispatchers.Default) {
    when (card.wallet) {
        ExternalWallet.Blink -> clearSavedBlinkWallet(card.blinkConfiguration())

        ExternalWallet.Nwc -> clearSavedNwcWallet(card.nwcConfiguration())

        ExternalWallet.Spark ->
            return@withContext clearSavedSparkWallet(card.sparkConfiguration().storageName)
    }
    true
}

/**
 * Keychain items survive an uninstall, but the cards that owned them don't: erases the credentials
 * of every card that is not in [cards].
 */
internal suspend fun purgeUnownedCredentials(cards: List<RaylCard>): Boolean =
    withContext(Dispatchers.Default) {
        purgeSecureStores(prefixes = CREDENTIAL_STORE_PREFIXES, keep = credentialStores(cards))
        val sparkCards = cards.filter { it.wallet == ExternalWallet.Spark }
        purgeSecureStores(
            prefixes = listOf(SPARK_CREDENTIAL_STORE_PREFIX),
            keep = sparkCredentialStores(sparkCards.map { it.sparkConfiguration().storageName })
        )
        true
    }
