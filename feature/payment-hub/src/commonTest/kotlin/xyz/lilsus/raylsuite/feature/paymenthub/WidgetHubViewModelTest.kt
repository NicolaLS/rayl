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
