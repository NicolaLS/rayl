package xyz.lilsus.raylsuite.feature.paymenthub

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import xyz.lilsus.raylsuite.core.model.LightningAddress
import xyz.lilsus.raylsuite.core.raylapi.TelemetryFakeDoor
import xyz.lilsus.raylsuite.core.raylapi.TelemetryFakeDoorAction
import xyz.lilsus.raylsuite.feature.paymenthub.bills.HubBill
import xyz.lilsus.raylsuite.feature.paymenthub.host.PaymentHubController

@OptIn(ExperimentalCoroutinesApi::class)
class WidgetHubViewModelTest {
    @Test
    fun contactSelectionReplacesSingleAndEnforcesEachLayoutCapacity() = runTest {
        // Regression test for the owner-reported Hub editor defects fixed in MOB-54.
        val repo = DefaultPaymentHubRepository(MapSettings())
        val host = PaymentHubController(repo, this)
        val vm = WidgetHubViewModel(repo, host, { "USD" }, dispatcher = StandardTestDispatcher(testScheduler))
        val contacts = (1..7).map {
            repo.saveContact(assertNotNull(LightningAddress.parse("person$it@example.com")), "Person $it")
        }
        advanceUntilIdle()
        vm.selectDefinition("local.contacts")
        vm.toggleContact(contacts[0].id)
        vm.toggleContact(contacts[1].id)
        assertEquals(listOf(contacts[1].id), vm.state.value.editor?.contactIds)
        vm.addContact("New", "new@example.com")
        advanceUntilIdle()
        assertEquals(1, vm.state.value.editor?.contactIds?.size)
        for (variant in listOf(LocalHubWidgets.Row, LocalHubWidgets.Card)) {
            vm.selectDefinition("local.contacts")
            vm.selectVariant(variant.id)
            contacts.take(variant.capacity).forEach { vm.toggleContact(it.id) }
            vm.toggleContact(contacts[variant.capacity].id)
            assertEquals(variant.capacity, vm.state.value.editor?.contactIds?.size)
            assertEquals(HubWidgetError.TooManyContacts, vm.state.value.error)
            vm.toggleContact(contacts[0].id)
            vm.toggleContact(contacts[variant.capacity].id)
            assertEquals(variant.capacity, vm.state.value.editor?.contactIds?.size)
            assertNull(vm.state.value.editorValidationError())
        }
        vm.clear()
    }

    @Test
    fun aDraggedWidgetShowsItsNewPlaceAndKeepsOnlyTheOneItIsLetGoIn() = runTest {
        val repo = DefaultPaymentHubRepository(MapSettings())
        val vm = WidgetHubViewModel(
            repo,
            PaymentHubController(repo, this),
            { "USD" },
            dispatcher = StandardTestDispatcher(testScheduler)
        )
        listOf("USD", "EUR", "GBP").forEach { code ->
            repo.saveWidget(
                HubWidgetDraft("local.sats", HubWidgetKind.Sats, LocalHubWidgets.Single, currencyCode = code)
            )
        }
        advanceUntilIdle()
        val (a, b, c) = repo.hub.value.widgets.map { it.id }
        fun shown() = vm.state.value.widgets.map { it.id }

        // Two columns: a b / c. The others make room as it passes; nothing is saved meanwhile.
        vm.previewMove(a, x = 1.5f, y = 0.5f, columns = 2)
        assertEquals(listOf(b, a, c), shown())
        vm.previewMove(a, x = 0.5f, y = 1.5f, columns = 2)
        assertEquals(listOf(b, c, a), shown())
        assertEquals(listOf(a, b, c), repo.hub.value.widgets.map { it.id })
        assertEquals(false, vm.state.value.busy)

        vm.finishMove()
        advanceUntilIdle()
        assertEquals(listOf(b, c, a), repo.hub.value.widgets.map { it.id })
        assertEquals(listOf(b, c, a), shown())

        // A drag that ends elsewhere, or with arranging, puts the widget back.
        vm.previewMove(c, x = 0.5f, y = 0.5f, columns = 2)
        assertEquals(listOf(c, b, a), shown())
        vm.cancelMove()
        assertEquals(listOf(b, c, a), shown())
        vm.previewMove(c, x = 0.5f, y = 0.5f, columns = 2)
        vm.setArranging(false)
        assertEquals(listOf(b, c, a), shown())
        vm.clear()
    }

    @Test
    fun startingToArrangeLeavesAnErrorInPlaceAndLeavingClearsIt() = runTest {
        // Regression test for MOB-146: arranging can start with a widget in hand, and an error
        // going away above the Hub moved the Hub away from the finger.
        val repo = DefaultPaymentHubRepository(MapSettings())
        val vm = WidgetHubViewModel(
            repo,
            PaymentHubController(repo, this),
            { "USD" },
            dispatcher = StandardTestDispatcher(testScheduler)
        )
        vm.addContact("", "alice@example.com")
        assertEquals(HubWidgetError.ContactNameRequired, vm.state.value.error)

        vm.setArranging(true)
        assertEquals(HubWidgetError.ContactNameRequired, vm.state.value.error)
        vm.setArranging(false)
        assertNull(vm.state.value.error)
        vm.clear()
    }

    @Test
    fun aWidgetDraggedPastOneOfAnotherSizeLandsWhereItIsHeldAndStaysThere() = runTest {
        // Regression test for MOB-139: with a wide widget in between, two orders each put the
        // other widget under the finger, and the drag swapped back and forth on every move.
        val repo = DefaultPaymentHubRepository(MapSettings())
        val vm = WidgetHubViewModel(
            repo,
            PaymentHubController(repo, this),
            { "USD" },
            dispatcher = StandardTestDispatcher(testScheduler)
        )
        repo.saveWidget(HubWidgetDraft("local.sats", HubWidgetKind.Sats, LocalHubWidgets.Single, currencyCode = "USD"))
        repo.saveWidget(HubWidgetDraft("local.rate", HubWidgetKind.Rate, LocalHubWidgets.Row, currencyCode = "USD"))
        repo.saveWidget(HubWidgetDraft("local.sats", HubWidgetKind.Sats, LocalHubWidgets.Single, currencyCode = "EUR"))
        advanceUntilIdle()
        // Packed in two columns: small, small / wide.
        val (small, wide, other) = repo.hub.value.widgets.map { it.id }
        fun shown() = vm.state.value.widgets.map { it.id }

        // Held where it already is, nothing moves.
        vm.previewMove(small, x = 0.6f, y = 0.4f, columns = 2)
        assertEquals(listOf(small, wide, other), shown())

        // Held over the other small one, the two trade places, and a finger that keeps moving a
        // little there changes nothing more.
        listOf(1.5f, 1.45f, 1.55f, 1.4f, 1.5f).forEach { x ->
            vm.previewMove(small, x = x, y = 0.5f, columns = 2)
            assertEquals(listOf(other, wide, small), shown())
        }
        vm.finishMove()
        advanceUntilIdle()
        assertEquals(listOf(other, wide, small), repo.hub.value.widgets.map { it.id })
        vm.clear()
    }

    @Test
    fun aBillIsReportedAsItsOwnDoorAndAddsNothingToTheHub() = runTest {
        val repo = DefaultPaymentHubRepository(MapSettings())
        val reported = mutableListOf<Pair<TelemetryFakeDoor, TelemetryFakeDoorAction>>()
        val vm = WidgetHubViewModel(
            repo,
            PaymentHubController(repo, this),
            { "USD" },
            dispatcher = StandardTestDispatcher(testScheduler),
            fakeDoor = { door, action -> reported += door to action }
        )
        vm.openGallery()

        vm.billSeen(HubBill.Delsur)
        vm.billTapped(HubBill.Delsur)
        advanceUntilIdle()

        assertEquals(
            listOf(
                TelemetryFakeDoor.BillDelsur to TelemetryFakeDoorAction.Shown,
                TelemetryFakeDoor.BillDelsur to TelemetryFakeDoorAction.Tapped
            ),
            reported
        )
        // A bill is not a widget: nothing is set up or placed, and the gallery stays open.
        assertEquals(HubWidgetScreen.Gallery, vm.state.value.screen)
        assertNull(vm.state.value.editor)
        assertEquals(emptyList(), repo.hub.value.widgets)
        // Two billers sharing a door would be counted as one.
        assertEquals(HubBill.entries.size, HubBill.entries.map { it.door }.toSet().size)
        vm.clear()
    }

    @Test
    fun anotherLayoutAppliesRightAwayWithoutOpeningTheWidgetSettings() = runTest {
        val repo = DefaultPaymentHubRepository(MapSettings())
        val vm = WidgetHubViewModel(
            repo,
            PaymentHubController(repo, this),
            { "USD" },
            dispatcher = StandardTestDispatcher(testScheduler)
        )
        repo.saveWidget(
            HubWidgetDraft("local.rate", HubWidgetKind.Rate, LocalHubWidgets.Single, currencyCode = "EUR")
        )
        advanceUntilIdle()
        val id = repo.hub.value.widgets.single().id

        vm.resizeWidget(id, LocalHubWidgets.Card.id)
        assertEquals(HubWidgetScreen.Hub, vm.state.value.screen)
        advanceUntilIdle()

        val widget = repo.hub.value.widgets.single()
        assertEquals(id, widget.id)
        assertEquals(LocalHubWidgets.Card, widget.variant)
        assertEquals("EUR", widget.currencyCode)
        assertEquals(HubWidgetScreen.Hub, vm.state.value.screen)
        assertNull(vm.state.value.editor)
        vm.clear()
    }

    @Test
    fun shortcutRequiresOneContactAndPositivePresetThenSaves() = runTest {
        val repo = DefaultPaymentHubRepository(MapSettings())
        val host = PaymentHubController(repo, this)
        val vm = WidgetHubViewModel(repo, host, { "USD" }, dispatcher = StandardTestDispatcher(testScheduler))
        val alice = repo.saveContact(assertNotNull(LightningAddress.parse("alice@example.com")), "Alice")
        val bob = repo.saveContact(assertNotNull(LightningAddress.parse("bob@example.com")), "Bob")
        advanceUntilIdle()
        vm.selectDefinition("local.shortcut")
        vm.configureSelected()
        assertEquals(HubWidgetError.SelectContacts, vm.state.value.editorValidationError())
        vm.toggleContact(alice.id)
        vm.toggleContact(bob.id)
        assertEquals(listOf(bob.id), vm.state.value.editor?.contactIds)
        assertEquals(HubWidgetError.InvalidAmount, vm.state.value.editorValidationError())
        vm.updateAmount("0")
        assertEquals(HubWidgetError.InvalidAmount, vm.state.value.editorValidationError())
        vm.updateAmount("12.50")
        assertNull(vm.state.value.editorValidationError())
        vm.saveWidget()
        advanceUntilIdle()
        assertEquals(HubWidgetScreen.Hub, vm.state.value.screen)
        assertEquals(HubWidgetKind.Shortcut, repo.hub.value.widgets.single().kind)
        assertEquals(1250L, vm.state.value.widgets.single().people.single().amount?.minor)
        vm.clear()
    }

    @Test
    fun aWidgetIsPlacedBeforeItIsSetUpAndKeepsItsPlaceUntilItIs() = runTest {
        val settings = MapSettings()
        val repo = DefaultPaymentHubRepository(settings)
        val host = PaymentHubController(repo, this)
        val vm = WidgetHubViewModel(repo, host, { "USD" }, dispatcher = StandardTestDispatcher(testScheduler))
        val alice = repo.saveContact(assertNotNull(LightningAddress.parse("alice@example.com")), "Alice")
        advanceUntilIdle()
        for (definition in listOf("local.contacts", "local.shortcut")) {
            vm.selectDefinition(definition)
            assertEquals(true, vm.state.value.addsWithoutSetup)
            vm.continueFromVariants()
            advanceUntilIdle()
            assertEquals(HubWidgetScreen.Hub, vm.state.value.screen)
        }
        assertEquals(
            listOf(HubWidgetKind.Contacts, HubWidgetKind.Shortcut),
            vm.state.value.widgets.map { it.kind }
        )
        assertEquals(true, vm.state.value.widgets.all { it.people.isEmpty() })
        // Nothing was invented for it: no payment target exists until the user chooses one.
        assertEquals(emptyList(), repo.hub.value.targets)
        assertEquals(2, DefaultPaymentHubRepository(settings).hub.value.widgets.size)

        // Its settings still insist on the choices it cannot work without.
        val shortcut = vm.state.value.widgets[1].id
        vm.editWidget(shortcut)
        assertEquals(false, vm.state.value.addsWithoutSetup)
        vm.saveWidget()
        assertEquals(HubWidgetError.SelectContacts, vm.state.value.error)
        vm.toggleContact(alice.id)
        vm.updateAmount("5")
        vm.saveWidget()
        advanceUntilIdle()
        assertEquals(shortcut, vm.state.value.widgets[1].id)
        assertEquals(500L, vm.state.value.widgets[1].people.single().amount?.minor)

        // A set-up widget still leaves with the last person it was about.
        repo.deleteContact(alice.id)
        advanceUntilIdle()
        assertEquals(listOf(HubWidgetKind.Contacts), vm.state.value.widgets.map { it.kind })
        vm.clear()
    }

    @Test
    fun namedShortcutStillSaysWhoIsPaid() = runTest {
        val repo = DefaultPaymentHubRepository(MapSettings())
        val host = PaymentHubController(repo, this)
        val vm = WidgetHubViewModel(repo, host, { "USD" }, dispatcher = StandardTestDispatcher(testScheduler))
        val alice = repo.saveContact(assertNotNull(LightningAddress.parse("alice@example.com")), "Alice")
        advanceUntilIdle()
        vm.selectDefinition("local.shortcut")
        vm.configureSelected()
        vm.toggleContact(alice.id)
        vm.updateTitle("Coffee")
        vm.updateAmount("3")
        vm.saveWidget()
        advanceUntilIdle()
        val person = vm.state.value.widgets.single().people.single()
        assertEquals("Coffee", person.title)
        assertEquals("Alice", person.contactTitle)
        vm.clear()
    }

    @Test
    fun removingTheLastWidgetEndsArranging() = runTest {
        // The emptied canvas has no Done button, so arranging would otherwise be stuck on.
        val repo = DefaultPaymentHubRepository(MapSettings())
        val host = PaymentHubController(repo, this)
        val vm = WidgetHubViewModel(repo, host, { "USD" }, dispatcher = StandardTestDispatcher(testScheduler))
        val alice = repo.saveContact(assertNotNull(LightningAddress.parse("alice@example.com")), "Alice")
        advanceUntilIdle()
        repeat(2) {
            vm.selectDefinition("local.contacts")
            vm.configureSelected()
            vm.toggleContact(alice.id)
            vm.saveWidget()
            advanceUntilIdle()
        }
        vm.setArranging(true)
        vm.removeWidget(vm.state.value.widgets.first().id)
        advanceUntilIdle()
        assertEquals(true, vm.state.value.arranging)
        vm.removeWidget(vm.state.value.widgets.single().id)
        advanceUntilIdle()
        assertEquals(emptyList(), vm.state.value.widgets)
        assertEquals(false, vm.state.value.arranging)
        vm.clear()
    }
}
