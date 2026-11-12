package xyz.lilsus.rayl

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import xyz.lilsus.raylsuite.feature.walletmanagement.WalletCard
import xyz.lilsus.raylsuite.feature.walletmanagement.WalletCardChange
import xyz.lilsus.raylsuite.feature.walletmanagement.WalletCardProvider

class RaylWalletCardsHostTest {
    @Test
    fun aChangeThatNoLongerAppliesMountsTheClosedWalletAgain() = runTest {
        // The mounted wallet closed its session before asking: leaving it closed would strand it.
        val registry = RaylCards(MapSettings()).also { it.prepare({ true }) }
        var released = 0
        val host = host(registry) { released++ }
        val session = registry.sessionId

        host.apply(WalletCardChange.PayWith(WalletCard.POCKET_ID))
        host.apply(WalletCardChange.PayWith("removed-card"))

        assertEquals(2, released)
        assertNotEquals(session, registry.sessionId)
        assertEquals(null, registry.paying.value)
    }

    @Test
    fun onlyThePocketShowsARecoveryPhrase() = runTest {
        val registry = RaylCards(MapSettings()).also { it.prepare({ true }) }
        val host = host(registry)
        registry.add(ExternalWallet.Spark)
        registry.connected(registry.paying.value!!)

        assertEquals("pocket words", host.recoveryPhrase(WalletCard.POCKET_ID))
        assertEquals(null, host.recoveryPhrase(registry.cards.value.single().id))
    }

    private fun TestScope.host(registry: RaylCards, beforeChange: () -> Unit = {}) = RaylWalletCardsHost(
        registry = registry,
        scope = backgroundScope,
        provider = { WalletCardProvider(it.id, it.brand, "") },
        clearCard = { true },
        pocketRecoveryPhrase = { "pocket words" },
        beforeChange = beforeChange
    )
}
