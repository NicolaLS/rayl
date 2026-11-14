package xyz.lilsus.raylsuite.feature.paymenthub

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import xyz.lilsus.raylsuite.feature.paymenthub.claro.ClaroApi
import xyz.lilsus.raylsuite.feature.paymenthub.claro.ClaroCatalogue
import xyz.lilsus.raylsuite.feature.paymenthub.claro.ClaroInvoice
import xyz.lilsus.raylsuite.feature.paymenthub.claro.ClaroLine
import xyz.lilsus.raylsuite.feature.paymenthub.claro.ClaroProduct
import xyz.lilsus.raylsuite.feature.paymenthub.claro.ClaroResult
import xyz.lilsus.raylsuite.feature.paymenthub.host.PaymentHubController
import xyz.lilsus.raylsuite.feature.paymenthub.live.HubClaroProblem
import xyz.lilsus.raylsuite.feature.paymenthub.live.HubClaroShop

/** The Claro widget: one number's top-ups and packages, paid by the wallet. */
@OptIn(ExperimentalCoroutinesApi::class)
class ClaroWidgetTest {
    private class FakeClaro : ClaroApi {
        var catalogue: ClaroResult<ClaroCatalogue> =
            ClaroResult.Ok(ClaroCatalogue(ClaroLine(PHONE, "1"), listOf(TOP_UP, PACK)))

        /** What Claro answers an order with, once it answers. */
        var invoice = CompletableDeferred<ClaroResult<ClaroInvoice>>(ClaroResult.Ok(invoice()))
        val asked = mutableListOf<String>()
        val orders = mutableListOf<Pair<String, String>>()

        override suspend fun catalogue(phone: String): ClaroResult<ClaroCatalogue> {
            asked += phone
            return catalogue
        }

        override suspend fun invoice(line: ClaroLine, product: ClaroProduct): ClaroResult<ClaroInvoice> {
            orders += line.phone to product.id
            return invoice.await()
        }
    }

    private class Hub(scope: TestScope, val settings: MapSettings = MapSettings()) {
        /** The wallet's own bitcoin price; at this one the USD 5 top-up is worth 5,000 sats. */
        var usdPerBitcoin: Double? = 100_000.0
        val api = FakeClaro()

        /** What reached the usage data, as `product step`. */
        val reported = mutableListOf<String>()
        val repository = DefaultPaymentHubRepository(settings)
        val host = PaymentHubController(
            repository,
            scope.backgroundScope,
            prices = { usdPerBitcoin.takeIf { _ -> it == "USD" } }
        )
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val vm = WidgetHubViewModel(
            repository,
            host,
            { "USD" },
            claro = api,
            dispatcher = dispatcher,
            background = dispatcher,
            telemetry = { event ->
                assertEquals("hub_purchase", event.name)
                assertEquals("claro", event.dimensions["service"])
                reported += event.dimensions["product"] + " " + event.dimensions["step"]
            }
        )
        val state get() = vm.state.value
        val paid = mutableListOf<String>()

        // Collected in the foreground: runTest does not run to idle for background work alone.
        val paying = scope.launch(dispatcher) { host.invoiceRequests.collect { paid += it } }

        suspend fun placeWidget(phone: String? = PHONE): String = checkNotNull(
            repository.saveWidget(
                HubWidgetDraft(
                    "local.claro",
                    HubWidgetKind.Claro,
                    LocalHubWidgets.Single,
                    phone = phone
                )
            )
        ).id

        fun close() {
            paying.cancel()
            vm.clear()
        }
    }

    @Test
    fun theWidgetIsAddedWithoutANumberAndKeepsTheOneItIsGiven() = runTest {
        val hub = Hub(this)
        advanceUntilIdle()
        hub.vm.selectDefinition("local.claro")
        hub.vm.continueFromVariants()
        advanceUntilIdle()
        val tile = hub.state.widgets.single()
        assertEquals(HubWidgetKind.Claro, tile.kind)
        assertNull(tile.phone)
        // Without a number there is nothing to shop for.
        hub.vm.openClaro(tile.id)
        assertEquals(HubClaroShop(), hub.state.claro)

        hub.vm.editWidget(tile.id)
        hub.vm.updatePhone("+502 7123 4567")
        assertEquals(HubWidgetError.PhoneRequired, hub.state.editorValidationError())
        hub.vm.saveWidget()
        advanceUntilIdle()
        assertNull(hub.state.widgets.single().phone)

        hub.vm.updatePhone("+503 7123 4567")
        assertNull(hub.state.editorValidationError())
        hub.vm.saveWidget()
        advanceUntilIdle()

        assertEquals(PHONE, hub.state.widgets.single().phone)
        assertEquals(PHONE, DefaultPaymentHubRepository(hub.settings).hub.value.widgets.single().phone)
        hub.vm.editWidget(tile.id)
        assertEquals(PHONE, hub.state.editor?.phoneInput)
        hub.close()
    }

    @Test
    fun aChosenProductIsOrderedForTheWidgetsNumberAndItsInvoiceGoesToTheWallet() = runTest {
        val hub = Hub(this)
        val widget = hub.placeWidget()
        advanceUntilIdle()

        hub.vm.openClaro(widget)
        assertEquals(HubClaroShop(widget, PHONE, loading = true), hub.state.claro)
        advanceUntilIdle()
        assertEquals(listOf(PHONE), hub.api.asked)
        assertEquals(HubClaroShop(widget, PHONE, products = listOf(TOP_UP, PACK)), hub.state.claro)

        hub.vm.buyClaro(TOP_UP.id)
        assertEquals(true, hub.state.claro.ordering)
        // A second tap while Claro is still answering orders nothing more.
        hub.vm.buyClaro(PACK.id)
        advanceUntilIdle()

        assertEquals(listOf(PHONE to TOP_UP.id), hub.api.orders)
        assertEquals(listOf("lnbc1claro"), hub.paid)
        // The shop makes room for the wallet's payment screen.
        assertEquals(HubClaroShop(), hub.state.claro)
        hub.close()
    }

    @Test
    fun aShopThatCannotOpenSaysWhyAndCanBeTriedAgain() = runTest {
        val hub = Hub(this)
        val widget = hub.placeWidget()
        val shelves = hub.api.catalogue
        advanceUntilIdle()

        hub.api.catalogue = ClaroResult.NotEligible
        hub.vm.openClaro(widget)
        advanceUntilIdle()
        assertEquals(HubClaroProblem.NotEligible, hub.state.claro.problem)
        hub.vm.buyClaro(TOP_UP.id)
        advanceUntilIdle()

        hub.api.catalogue = ClaroResult.Unavailable
        hub.vm.openClaro(widget)
        advanceUntilIdle()
        assertEquals(HubClaroProblem.Unreachable, hub.state.claro.problem)
        assertEquals(false, hub.state.claro.loading)

        hub.api.catalogue = shelves
        hub.vm.openClaro(widget)
        advanceUntilIdle()
        assertNull(hub.state.claro.problem)
        assertEquals(listOf(TOP_UP, PACK), hub.state.claro.products)
        assertEquals(emptyList(), hub.api.orders)
        hub.close()
    }

    @Test
    fun anOrderWithoutAnInvoiceKeepsTheShopOpenAndPaysNothing() = runTest {
        val hub = Hub(this)
        val widget = hub.placeWidget()
        hub.api.invoice = CompletableDeferred(ClaroResult.Unavailable)
        advanceUntilIdle()
        hub.vm.openClaro(widget)
        advanceUntilIdle()

        hub.vm.buyClaro(TOP_UP.id)
        advanceUntilIdle()

        assertEquals(
            HubClaroShop(
                widget,
                PHONE,
                products = listOf(TOP_UP, PACK),
                problem = HubClaroProblem.OrderFailed
            ),
            hub.state.claro
        )
        assertEquals(emptyList(), hub.paid)

        // The user decides whether to order again.
        hub.api.invoice = CompletableDeferred(ClaroResult.Ok(invoice("lnbc1second")))
        hub.vm.buyClaro(TOP_UP.id)
        advanceUntilIdle()
        assertEquals(listOf("lnbc1second"), hub.paid)
        assertEquals(listOf("top_up order_failed", "top_up ordered"), hub.reported)
        hub.close()
    }

    @Test
    fun anInvoiceAboveThePriceAtTheWalletsOwnRateIsNotPaid() = runTest {
        suspend fun TestScope.paidFor(sats: Long, usdPerBitcoin: Double? = 100_000.0): List<String> {
            val hub = Hub(this)
            hub.usdPerBitcoin = usdPerBitcoin
            hub.api.invoice = CompletableDeferred(ClaroResult.Ok(invoice(sats = sats)))
            val widget = hub.placeWidget()
            advanceUntilIdle()
            hub.vm.openClaro(widget)
            advanceUntilIdle()
            hub.vm.buyClaro(TOP_UP.id)
            advanceUntilIdle()
            if (hub.paid.isEmpty()) {
                assertEquals(HubClaroProblem.OrderFailed, hub.state.claro.problem)
            }
            hub.close()
            return hub.paid
        }

        // USD 5 is worth 5,000 sats. Claro's provider sets its own rate, up to 5 % above.
        assertEquals(listOf("lnbc1claro"), paidFor(5_000))
        assertEquals(listOf("lnbc1claro"), paidFor(5_240))
        assertEquals(emptyList(), paidFor(5_260))
        assertEquals(emptyList(), paidFor(500_000))
        // Without a rate of its own the app cannot judge; the wallet's confirmation shows the sum.
        assertEquals(listOf("lnbc1claro"), paidFor(500_000, usdPerBitcoin = null))
    }

    @Test
    fun leavingTheShopDropsAnOrderThatHasNoInvoiceYet() = runTest {
        val hub = Hub(this)
        val widget = hub.placeWidget()
        hub.api.invoice = CompletableDeferred()
        advanceUntilIdle()
        hub.vm.openClaro(widget)
        advanceUntilIdle()
        hub.vm.buyClaro(TOP_UP.id)
        advanceUntilIdle()
        assertEquals(true, hub.state.claro.ordering)

        hub.vm.closeClaro()
        hub.api.invoice.complete(ClaroResult.Ok(invoice("lnbc1late")))
        advanceUntilIdle()

        assertEquals(HubClaroShop(), hub.state.claro)
        assertEquals(emptyList(), hub.paid)
        assertEquals(emptyList(), hub.reported)
        hub.close()
    }

    @Test
    fun onceTheWalletHasTheInvoiceNobodyIsAskedAboutTheOrder() = runTest {
        val hub = Hub(this)
        val widget = hub.placeWidget()
        advanceUntilIdle()
        hub.vm.openClaro(widget)
        advanceUntilIdle()
        hub.vm.buyClaro(PACK.id)
        advanceUntilIdle()
        assertEquals(listOf("lnbc1claro"), hub.paid)

        // The Hub refreshes about once a minute, and again whenever it comes back (MOB-154).
        repeat(3) {
            hub.vm.refreshLive()
            advanceUntilIdle()
        }

        assertEquals(listOf(PHONE), hub.api.asked)
        assertEquals(listOf(PHONE to PACK.id), hub.api.orders)
        assertEquals(listOf("package ordered"), hub.reported)
        hub.close()
    }

    @Test
    fun ordersAnEarlierBuildKeptAreDroppedAndTheWidgetStays() = runTest {
        // Builds before MOB-154 kept an order, with its number, to ask about it later.
        val stored = """{"contacts":[],"actions":[],"widgets":[{"id":"widget:1",""" +
            """"definitionId":"local.claro","kind":"Claro","variantId":"single","columns":1,""" +
            """"rows":1,"capacity":1,"phone":"$PHONE"}],"claroOrders":[{"transactionId":"MCE1",""" +
            """"checkout":"0123456789abcdef0123456789abcdef","phone":"$PHONE","topUp":true,""" +
            """"orderedAt":1800000000000}]}"""
        val settings = MapSettings("paymentHub.widgets.document" to stored)
        val hub = Hub(this, settings)
        advanceUntilIdle()

        assertEquals(PHONE, hub.state.widgets.single().phone)
        // The first change to the Hub writes the document anew, without them.
        hub.placeWidget()
        assertEquals(false, "claroOrders" in settings.getString("paymentHub.widgets.document", ""))
        assertEquals(2, DefaultPaymentHubRepository(settings).hub.value.widgets.size)
        hub.close()
    }

    private companion object {
        const val PHONE = "71234567"

        fun invoice(bolt11: String = "lnbc1claro", sats: Long = 5_000) = ClaroInvoice(bolt11, sats)
        val TOP_UP = ClaroProduct("R:R5:", "Recarga $5", "Saldo de $5", 500, true, JsonObject(emptyMap()))
        val PACK = ClaroProduct("P:INT:9002", "Internet 7 días", "6 GB", 550, false, JsonObject(emptyMap()))
    }
}
