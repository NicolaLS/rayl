package xyz.lilsus.raylsuite.feature.paymenthub.host

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import xyz.lilsus.raylsuite.core.model.LightningAddress
import xyz.lilsus.raylsuite.feature.paymenthub.DefaultPaymentHubRepository
import xyz.lilsus.raylsuite.feature.paymenthub.DirectTargetAmountRule

@OptIn(ExperimentalCoroutinesApi::class)
class PaymentHubControllerTest {
    @Test
    fun acceptingSuggestionSavesContactAndWidgetOnce() = runTest {
        val settings = MapSettings()
        val repository = DefaultPaymentHubRepository(settings)
        val controller = PaymentHubController(repository, this)
        val alice = assertNotNull(LightningAddress.parse("alice@example.com"))
        controller.offerSave(alice)
        controller.dispatch(PaymentHubIntent.SavePromptTitleChanged("Alice"))
        controller.dispatch(PaymentHubIntent.SavePromptSave)
        controller.dispatch(PaymentHubIntent.SavePromptSave)
        advanceUntilIdle()

        val saved = DefaultPaymentHubRepository(settings).hub.value
        val contact = saved.contacts.single()
        val target = saved.targets.single()
        assertEquals("Alice", contact.title)
        assertEquals(contact.id, target.contactId)
        assertEquals(DirectTargetAmountRule.AskEveryTime, target.amountRule)
        assertNull(controller.state.value.savePrompt)
        controller.offerSave(alice)
        assertNull(controller.state.value.savePrompt)

        repository.deleteWidget(saved.widgets.single().id)
        controller.offerSave(alice)
        assertNull(controller.state.value.savePrompt)
        assertEquals(listOf(contact), repository.hub.value.contacts)
    }
}
