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
}
