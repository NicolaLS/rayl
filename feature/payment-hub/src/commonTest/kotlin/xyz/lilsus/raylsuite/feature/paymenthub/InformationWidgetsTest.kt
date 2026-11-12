package xyz.lilsus.raylsuite.feature.paymenthub

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import xyz.lilsus.raylsuite.core.settings.SecureStringStore
import xyz.lilsus.raylsuite.feature.paymenthub.host.PaymentHubController
import xyz.lilsus.raylsuite.feature.paymenthub.live.HubPpqAccount
import xyz.lilsus.raylsuite.feature.paymenthub.live.HubPpqProblem
import xyz.lilsus.raylsuite.feature.paymenthub.live.HubPpqTopUpError
import xyz.lilsus.raylsuite.feature.paymenthub.live.HubRate
import xyz.lilsus.raylsuite.feature.paymenthub.ppq.HubPpqService
import xyz.lilsus.raylsuite.feature.paymenthub.ppq.PpqApi
import xyz.lilsus.raylsuite.feature.paymenthub.ppq.PpqResult

/** MOB-117, MOB-119 and MOB-120: the exchange rate, sats and ppq.ai widgets. */
@OptIn(ExperimentalCoroutinesApi::class)
class InformationWidgetsTest {
    private class Secrets : SecureStringStore {
        val values = mutableMapOf<String, String>()

        /** The iOS Keychain refuses to be read while the device is locked. */
        var locked = false
        override fun putString(key: String, value: String) {
            values[key] = value
        }
        override fun getStringOrNull(key: String): String? {
            check(!locked) { "Unable to read secure value: Keychain status -25308" }
            return values[key]
        }
        override fun remove(key: String) {
            values.remove(key)
        }
        override fun clear() = values.clear()
    }

    private class FakePpq : PpqApi {
        var balance: PpqResult<Double> = PpqResult.Ok(12.5)
        var topUp: PpqResult<String> = PpqResult.Ok("lnbc1invoice")
        val topUps = mutableListOf<Pair<String, Double>>()
        var closed = false

        override suspend fun balanceUsd(apiKey: String) = if (apiKey in GOOD_KEYS) balance else PpqResult.InvalidKey

        override suspend fun createLightningTopUp(apiKey: String, usd: Double): PpqResult<String> {
            topUps += apiKey to usd
            return topUp
        }

        override fun close() {
            closed = true
        }
    }

    private class Hub(val scope: TestScope, displayCurrency: String = "USD") {
        val prices = mutableMapOf<String, Double?>("USD" to 100_000.0, "EUR" to 90_000.0)
        val history = mutableMapOf<String, List<Double>?>("USD" to listOf(80_000.0, 90_000.0))
        val secrets = Secrets()
        val api = FakePpq()
        val settings = MapSettings()
        val repository = DefaultPaymentHubRepository(settings)
        val host = PaymentHubController(
            repository,
            scope.backgroundScope,
            prices = { prices[it] },
            priceHistory = { history[it] }
        )
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val vm = WidgetHubViewModel(
            repository,
            host,
            { displayCurrency },
            ppq = HubPpqService(secrets, api),
            dispatcher = dispatcher,
            background = dispatcher
        )
        val state get() = vm.state.value

        /** Places a PPQ.AI widget without a key and returns its id. */
        fun placePpq(): String {
            vm.selectDefinition("local.ppq")
            vm.continueFromVariants()
            scope.advanceUntilIdle()
            return state.widgets.last().id
        }

        /** Places a PPQ.AI widget and gives it [key] through its settings. */
        fun addPpq(key: String): String {
            val id = placePpq()
            vm.editWidget(id)
            vm.updateApiKey(key)
            vm.saveWidget()
            scope.advanceUntilIdle()
            return id
        }

        fun storedKeys() = secrets.values.toMap()
    }

    @Test
    fun aRateWidgetIsAddedFromTheLayoutChoiceInTheDisplayCurrency() = runTest {
        val hub = Hub(this, displayCurrency = "EUR")
        advanceUntilIdle()
        hub.vm.selectDefinition("local.rate")
        assertEquals(true, hub.state.addsWithoutSetup)
        hub.vm.selectVariant(LocalHubWidgets.Card.id)
        hub.vm.continueFromVariants()
        advanceUntilIdle()

        assertEquals(HubWidgetScreen.Hub, hub.state.screen)
        val tile = hub.state.widgets.single()
        assertEquals(HubWidgetKind.Rate, tile.kind)
        assertEquals("EUR", tile.currencyCode)
        assertEquals(LocalHubWidgets.Card, tile.variant)
        assertEquals(HubRate("EUR", 90_000.0), hub.state.rates["EUR"])
        hub.vm.clear()
    }

    @Test
    fun aDisplayCurrencyThatIsNotFiatStartsTheWidgetInDollars() = runTest {
        val hub = Hub(this, displayCurrency = "SAT")
        advanceUntilIdle()
        hub.vm.selectDefinition("local.sats")
        hub.vm.continueFromVariants()
        advanceUntilIdle()
        assertEquals("USD", hub.state.widgets.single().currencyCode)
        hub.vm.clear()
    }

    @Test
    fun eachWidgetKeepsTheCurrencyItWasGivenAndSurvivesARestart() = runTest {
        val hub = Hub(this)
        advanceUntilIdle()
        repeat(2) {
            hub.vm.selectDefinition("local.rate")
            hub.vm.continueFromVariants()
            advanceUntilIdle()
        }
        val second = hub.state.widgets[1].id
        hub.vm.editWidget(second)
        assertEquals("USD", hub.state.editor?.currencyCode)
        hub.vm.selectCurrency("SAT")
        assertEquals("USD", hub.state.editor?.currencyCode)
        hub.vm.selectCurrency("EUR")
        hub.vm.saveWidget()
        advanceUntilIdle()

        assertEquals(listOf("USD", "EUR"), hub.state.widgets.map { it.currencyCode })
        assertEquals(setOf("USD", "EUR"), hub.state.rates.keys)
        val reloaded = DefaultPaymentHubRepository(hub.settings).hub.value.widgets
        assertEquals(listOf("USD", "EUR"), reloaded.map { it.currencyCode })
        assertEquals(listOf(HubWidgetKind.Rate, HubWidgetKind.Rate), reloaded.map { it.kind })
        hub.vm.clear()
    }

    @Test
    fun theChartArrivesAfterThePriceAndAMissingOneLeavesThePrice() = runTest {
        val hub = Hub(this)
        hub.repository.saveWidget(
            HubWidgetDraft("local.rate", HubWidgetKind.Rate, LocalHubWidgets.Single, currencyCode = "USD")
        )
        hub.repository.saveWidget(
            HubWidgetDraft("local.rate", HubWidgetKind.Rate, LocalHubWidgets.Single, currencyCode = "EUR")
        )
        hub.vm.refreshLive()
        advanceUntilIdle()

        val usd = assertNotNull(hub.state.rates["USD"])
        assertEquals(listOf(80_000.0, 90_000.0), usd.history)
        assertEquals(25.0, usd.changePercent!!, 1e-9)
        // No chart for euros: the price is shown and no change is claimed.
        assertEquals(HubRate("EUR", 90_000.0), hub.state.rates["EUR"])
        assertEquals(false, hub.state.ratesUnavailable)
        hub.vm.clear()
    }

    @Test
    fun aFailedRefreshKeepsTheLastPriceAndSaysSoOnlyWhereNoneIsKnown() = runTest {
        val hub = Hub(this)
        hub.repository.saveWidget(
            HubWidgetDraft("local.sats", HubWidgetKind.Sats, LocalHubWidgets.Single, currencyCode = "USD")
        )
        hub.vm.refreshLive()
        advanceUntilIdle()
        hub.prices["USD"] = null
        hub.vm.refreshLive()
        advanceUntilIdle()

        assertEquals(100_000.0, hub.state.rates["USD"]?.price)
        assertEquals(true, hub.state.ratesUnavailable)

        hub.prices["USD"] = 110_000.0
        hub.vm.refreshLive()
        advanceUntilIdle()
        assertEquals(110_000.0, hub.state.rates["USD"]?.price)
        assertEquals(false, hub.state.ratesUnavailable)
        hub.vm.clear()
    }

    @Test
    fun aRateWidgetNeedsACurrencyTheAppCanQuote() = runTest {
        val repository = DefaultPaymentHubRepository(MapSettings())
        fun draft(code: String?) = HubWidgetDraft("local.rate", HubWidgetKind.Rate, LocalHubWidgets.Single, currencyCode = code)
        assertNull(repository.saveWidget(draft(null)))
        assertNull(repository.saveWidget(draft("SAT")))
        assertNull(repository.saveWidget(draft("XXX")))
        assertEquals("CHF", repository.saveWidget(draft(" chf "))?.currencyCode)
    }

    @Test
    fun ppqAsksForItsKeyAndStoresOnlyAKeyTheServiceAccepts() = runTest {
        val hub = Hub(this)
        advanceUntilIdle()
        // The widget is placed before it has a key; its settings then ask for one.
        val id = hub.placePpq()
        assertEquals(HubWidgetScreen.Hub, hub.state.screen)
        assertEquals(false, hub.state.ppqAccount(id).connected)
        hub.vm.editWidget(id)
        assertEquals(HubWidgetScreen.Configure, hub.state.screen)
        assertEquals(HubWidgetError.ApiKeyRequired, hub.state.editorValidationError())

        hub.vm.updateApiKey(" sk-bad ")
        hub.vm.saveWidget()
        advanceUntilIdle()
        assertEquals(HubWidgetError.InvalidApiKey, hub.state.error)
        assertEquals(emptyMap(), hub.storedKeys())

        hub.api.balance = PpqResult.Unreachable
        hub.vm.updateApiKey("sk-good")
        hub.vm.saveWidget()
        advanceUntilIdle()
        assertEquals(HubWidgetError.ServiceUnreachable, hub.state.error)
        assertEquals(emptyMap(), hub.storedKeys())

        hub.api.balance = PpqResult.Ok(12.5)
        hub.vm.saveWidget()
        advanceUntilIdle()
        assertEquals(HubWidgetScreen.Hub, hub.state.screen)
        assertEquals(HubWidgetKind.Ppq, hub.state.widgets.single().kind)
        assertEquals(listOf("sk-good"), hub.storedKeys().values.toList())
        assertEquals(HubPpqAccount(connected = true, balanceUsd = 12.5), hub.state.ppqAccount(id))
        // The key never reaches the Hub document, which is stored in plain settings.
        assertEquals(
            false,
            hub.settings.keys.any { hub.settings.getStringOrNull(it).orEmpty().contains("sk-good") }
        )
        hub.vm.clear()
        assertEquals(true, hub.api.closed)
    }

    @Test
    fun aPpqWidgetAddedWhileAnotherHasAKeyStillNeedsItsOwn() = runTest {
        val hub = Hub(this)
        advanceUntilIdle()
        hub.addPpq("sk-good")

        hub.vm.selectDefinition("local.ppq")
        assertEquals(true, hub.state.addsWithoutSetup)
        hub.vm.continueFromVariants()
        advanceUntilIdle()
        val second = hub.state.widgets.last().id
        assertEquals(2, hub.state.widgets.size)
        assertEquals(false, hub.state.ppqAccount(second).connected)
        hub.vm.editWidget(second)
        assertEquals(HubWidgetError.ApiKeyRequired, hub.state.editorValidationError())
        hub.vm.clear()
    }

    @Test
    fun eachPpqWidgetKeepsItsOwnKeyAndForgetsItWithTheWidget() = runTest {
        val hub = Hub(this)
        advanceUntilIdle()
        val first = hub.addPpq("sk-good")
        val second = hub.addPpq("sk-other")
        assertEquals(
            mapOf("ppq.apiKey.$first" to "sk-good", "ppq.apiKey.$second" to "sk-other"),
            hub.storedKeys()
        )

        // Replacing one widget's key leaves the other's alone.
        hub.vm.editWidget(first)
        hub.vm.updateApiKey("sk-other")
        hub.vm.saveWidget()
        advanceUntilIdle()
        assertEquals(
            mapOf("ppq.apiKey.$first" to "sk-other", "ppq.apiKey.$second" to "sk-other"),
            hub.storedKeys()
        )

        hub.vm.removeWidget(first)
        advanceUntilIdle()
        assertEquals(mapOf("ppq.apiKey.$second" to "sk-other"), hub.storedKeys())
        assertEquals(true, hub.state.ppqAccount(second).connected)
        assertEquals(HubPpqAccount(), hub.state.ppqAccount(first))

        hub.vm.removeWidget(second)
        advanceUntilIdle()
        assertEquals(emptyMap(), hub.storedKeys())
        assertEquals(emptyMap(), hub.state.ppq)
        hub.vm.clear()
    }

    @Test
    fun eachPpqWidgetShowsItsOwnAccount() = runTest {
        val hub = Hub(this)
        advanceUntilIdle()
        val first = hub.addPpq("sk-good")
        val second = hub.placePpq()
        hub.secrets.putString("ppq.apiKey.$second", "sk-rejected")
        hub.vm.refreshLive()
        advanceUntilIdle()

        assertEquals(HubPpqAccount(connected = true, balanceUsd = 12.5), hub.state.ppqAccount(first))
        assertEquals(HubPpqProblem.InvalidKey, hub.state.ppqAccount(second).problem)
        assertNull(hub.state.ppqAccount(first).problem)
        hub.vm.clear()
    }

    @Test
    fun aRefreshKeepsTheLastBalanceWhenTheServiceCannotBeReached() = runTest {
        val hub = Hub(this)
        val id = hub.addPpq("sk-good")
        assertEquals(HubPpqAccount(connected = true, balanceUsd = 12.5), hub.state.ppqAccount(id))

        hub.api.balance = PpqResult.Unreachable
        hub.vm.refreshLive()
        advanceUntilIdle()
        assertEquals(12.5, hub.state.ppqAccount(id).balanceUsd)
        assertEquals(HubPpqProblem.Unreachable, hub.state.ppqAccount(id).problem)

        hub.api.balance = PpqResult.InvalidKey
        hub.vm.refreshLive()
        advanceUntilIdle()
        assertEquals(HubPpqProblem.InvalidKey, hub.state.ppqAccount(id).problem)
        hub.vm.clear()
    }

    @Test
    fun aLockedCredentialStoreIsReadAgainLaterInsteadOfCrashing() = runTest {
        // The Hub keeps refreshing while it is on screen, also when the device locks under it.
        val hub = Hub(this)
        val id = hub.placePpq()
        hub.secrets.putString("ppq.apiKey.$id", "sk-good")
        hub.secrets.locked = true
        hub.vm.refreshLive()
        advanceUntilIdle()
        assertEquals(HubPpqAccount(), hub.state.ppqAccount(id))

        hub.vm.topUpPpq(id, "5")
        advanceUntilIdle()
        assertEquals(HubPpqTopUpError.Unreachable, hub.state.ppqAccount(id).topUpError)
        assertEquals(emptyList(), hub.api.topUps)

        hub.secrets.locked = false
        hub.vm.refreshLive()
        advanceUntilIdle()
        assertEquals(12.5, hub.state.ppqAccount(id).balanceUsd)

        // What is on screen stays while the store is locked again.
        hub.secrets.locked = true
        hub.vm.refreshLive()
        advanceUntilIdle()
        assertEquals(12.5, hub.state.ppqAccount(id).balanceUsd)
        assertNull(hub.state.ppqAccount(id).problem)
        hub.vm.clear()
    }

    @Test
    fun aTopUpHandsTheInvoiceToTheWalletUsingTheKeyOfItsWidget() = runTest {
        val hub = Hub(this)
        val first = hub.addPpq("sk-good")
        val second = hub.addPpq("sk-other")
        val invoices = mutableListOf<String>()
        // Collected in the foreground: runTest does not run to idle for background work alone.
        val collecting = launch(hub.dispatcher) { hub.host.invoiceRequests.collect { invoices += it } }

        hub.vm.topUpPpq(second, "25")
        assertEquals(true, hub.state.ppqAccount(second).toppingUp)
        assertEquals(false, hub.state.ppqAccount(first).toppingUp)
        advanceUntilIdle()

        assertEquals(listOf("sk-other" to 25.0), hub.api.topUps)
        assertEquals(listOf("lnbc1invoice"), invoices)
        assertEquals(false, hub.state.ppqAccount(second).toppingUp)
        assertEquals(1, hub.state.ppqAccount(second).topUpSerial)
        assertEquals(0, hub.state.ppqAccount(first).topUpSerial)
        collecting.cancel()
        hub.vm.clear()
    }

    @Test
    fun aTopUpOutsideTheLimitsOrWithoutServiceAsksForNothing() = runTest {
        val hub = Hub(this)
        val id = hub.addPpq("sk-good")
        val invoices = mutableListOf<String>()
        val collecting = launch(hub.dispatcher) { hub.host.invoiceRequests.collect { invoices += it } }
        advanceUntilIdle()

        for (amount in listOf("", "0.09", "1000.01", "abc")) {
            hub.vm.topUpPpq(id, amount)
            advanceUntilIdle()
            assertEquals(HubPpqTopUpError.Amount, hub.state.ppqAccount(id).topUpError, amount)
        }
        assertEquals(emptyList(), hub.api.topUps)

        hub.api.topUp = PpqResult.Unreachable
        hub.vm.topUpPpq(id, "0.10")
        advanceUntilIdle()
        assertEquals(listOf("sk-good" to 0.1), hub.api.topUps)
        assertEquals(HubPpqTopUpError.Unreachable, hub.state.ppqAccount(id).topUpError)
        assertEquals(0, hub.state.ppqAccount(id).topUpSerial)
        assertEquals(emptyList(), invoices)

        hub.vm.clearPpqTopUpError(id)
        assertNull(hub.state.ppqAccount(id).topUpError)
        collecting.cancel()
        hub.vm.clear()
    }

    private companion object {
        /** The keys the fake ppq.ai accepts; any other is rejected. */
        val GOOD_KEYS = setOf("sk-good", "sk-other")
    }
}
