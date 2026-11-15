package xyz.lilsus.raylsuite.feature.paymenthub

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import xyz.lilsus.raylsuite.feature.paymenthub.claro.ClaroApi
import xyz.lilsus.raylsuite.feature.paymenthub.claro.ClaroCatalogue
import xyz.lilsus.raylsuite.feature.paymenthub.claro.ClaroInvoice
import xyz.lilsus.raylsuite.feature.paymenthub.claro.ClaroLine
import xyz.lilsus.raylsuite.feature.paymenthub.claro.ClaroProduct
import xyz.lilsus.raylsuite.feature.paymenthub.claro.ClaroResult
import xyz.lilsus.raylsuite.feature.paymenthub.host.PaymentHubController
import xyz.lilsus.raylsuite.feature.paymenthub.live.HubClaroShop
import xyz.lilsus.raylsuite.feature.paymenthub.switches.HubWidgetKillSwitch

/** Widgets the developer turns off remotely, as the Claro widget had to be (MOB-154). */
@OptIn(ExperimentalCoroutinesApi::class)
class HubKillSwitchTest {
    private class Backend {
        /** What the backend answers; null is no usable answer. */
        var off: Set<String>? = emptySet()
        var asked = 0
    }

    private class Claro : ClaroApi {
        val asked = mutableListOf<String>()
        val pending = CompletableDeferred<ClaroResult<ClaroCatalogue>>()

        override suspend fun catalogue(phone: String): ClaroResult<ClaroCatalogue> {
            asked += phone
            return pending.await()
        }

        override suspend fun invoice(line: ClaroLine, product: ClaroProduct) = ClaroResult.Ok(ClaroInvoice("lnbc1", 1))
    }

    private class Hub(scope: TestScope, val stored: MapSettings = MapSettings()) {
        val backend = Backend()
        var nowMs = 1_800_000_000_000
        val claro = Claro()
        val switch = HubWidgetKillSwitch(
            {
                backend.asked++
                backend.off
            },
            stored
        )
        val repository = DefaultPaymentHubRepository(MapSettings())
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val vm = WidgetHubViewModel(
            repository,
            PaymentHubController(repository, scope.backgroundScope),
            { "USD" },
            claro = claro,
            dispatcher = dispatcher,
            background = dispatcher,
            telemetry = {},
            killSwitch = switch,
            clock = { nowMs }
        )
        val state get() = vm.state.value

        suspend fun placeClaro(): String = checkNotNull(
            repository.saveWidget(
                HubWidgetDraft("local.claro", HubWidgetKind.Claro, LocalHubWidgets.Single, phone = "71234567")
            )
        ).id

        fun TestScope.refresh(afterMinutes: Int = 0) {
            nowMs += afterMinutes * 60_000L
            vm.refreshLive()
            advanceUntilIdle()
        }
    }

    @Test
    fun aWidgetTurnedOffLeavesTheGalleryAndItsPlacedCopiesAreOffUntilItIsOnAgain() = runTest {
        val hub = Hub(this)
        val widget = hub.placeClaro()
        hub.vm.openClaro(widget)
        advanceUntilIdle()
        assertEquals(listOf("71234567"), hub.claro.asked)

        hub.backend.off = setOf("local.claro")
        with(hub) { refresh() }

        assertEquals(false, hub.state.gallery.any { it.id == "local.claro" })
        assertEquals(true, hub.state.gallery.any { it.id == "local.ppq" })
        assertEquals(true, hub.state.widgets.single().disabled)
        // The open shop closes, and the widget opens, edits and resizes to nothing.
        assertEquals(HubClaroShop(), hub.state.claro)
        hub.vm.openClaro(widget)
        hub.vm.editWidget(widget)
        hub.vm.resizeWidget(widget, LocalHubWidgets.Row.id)
        advanceUntilIdle()
        assertEquals(listOf("71234567"), hub.claro.asked)
        assertNull(hub.state.editor)
        assertEquals(LocalHubWidgets.Single, hub.repository.hub.value.widgets.single().variant)
        // It can still be removed.
        hub.vm.removeWidget(widget)
        advanceUntilIdle()
        assertEquals(emptyList(), hub.state.widgets)

        val again = hub.placeClaro()
        hub.backend.off = emptySet()
        with(hub) { refresh(afterMinutes = 5) }
        assertEquals(false, hub.state.widgets.single { it.id == again }.disabled)
        assertEquals(true, hub.state.gallery.any { it.id == "local.claro" })
        hub.vm.clear()
    }

    @Test
    fun theBackendIsAskedEveryFiveMinutesAtMostAndItsLastWordHoldsOfflineAndAfterARestart() = runTest {
        val first = Hub(this)
        first.backend.off = setOf("local.claro")
        with(first) {
            refresh()
            refresh(afterMinutes = 1)
            refresh(afterMinutes = 3)
        }
        assertEquals(1, first.backend.asked)

        // Offline: what it said last still holds.
        first.backend.off = null
        with(first) { refresh(afterMinutes = 1) }
        assertEquals(2, first.backend.asked)
        assertEquals(false, first.state.gallery.any { it.id == "local.claro" })
        first.vm.clear()

        // After a restart, before the backend was asked again.
        val second = Hub(this, first.stored)
        advanceUntilIdle()
        assertEquals(false, second.state.gallery.any { it.id == "local.claro" })
        second.placeClaro()
        advanceUntilIdle()
        assertEquals(true, second.state.widgets.single().disabled)
        second.vm.clear()
    }
}
