package xyz.lilsus.raylsuite.feature.paymenthub

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import xyz.lilsus.raylsuite.core.model.StoredAmount

/**
 * Rayl 1.1 is the first public release: later versions must read the Hub it stored (test
 * project 2, item 6): the user's contacts, payment targets with their amounts and history, and
 * widgets. The fixture is the document 1.1 writes (it omits the default schemaVersion). If this
 * fails, the format changed: read the old one too, don't change the fixture.
 */
class StoredHubFrom11Test {
    @Test
    fun readsTheContactsTargetsAndWidgets11Stored() {
        val hub = DefaultPaymentHubRepository(
            MapSettings("paymentHub.widgets.document" to DOCUMENT)
        ).hub.value

        assertEquals(
            listOf("Alice" to "alice@example.com", "Bob" to "bob@example.com"),
            hub.contacts.map { it.title to it.address.full }
        )
        val preset = hub.targets.single { it.id.value == "preset:6a8d07763fedb335" }
        assertEquals(
            DirectTargetAmountRule.Preset(StoredAmount(500, "USD")),
            preset.amountRule
        )
        val bob = hub.targets.single { it.id.value == "contact-payment:contact:852970908a08527d" }
        assertEquals(DirectTargetAmountRule.AskEveryTime, bob.amountRule)
        assertEquals(HubItemStats(1, 1_793_000_000_000), bob.stats)
        assertEquals(3, hub.targets.size)
        assertEquals(
            listOf(
                Triple(HubWidgetKind.Contacts, 2, listOf("contact:c9fc2171e44f23d7", "contact:852970908a08527d")),
                Triple(HubWidgetKind.Shortcut, 1, listOf())
            ),
            hub.widgets.map { Triple(it.kind, it.variant.columns, it.contactIds) }
        )
        assertEquals("preset:6a8d07763fedb335", hub.widgets[1].targetId?.value)
    }

    private companion object {
        const val DOCUMENT =
            """{"contacts":[{"id":"contact:c9fc2171e44f23d7","title":"Alice","address":"alice@example.com"},""" +
                """{"id":"contact:852970908a08527d","title":"Bob","address":"bob@example.com"}],""" +
                """"actions":[{"id":"contact-payment:contact:c9fc2171e44f23d7",""" +
                """"title":"Alice","contactId":"contact:c9fc2171e44f23d7"},{"id":"contact-payment:contact:852970908a08527d",""" +
                """"title":"Bob","contactId":"contact:852970908a08527d","count":1,"lastPaid":1793000000000},""" +
                """{"id":"preset:6a8d07763fedb335","title":"Alice","contactId":"contact:c9fc2171e44f23d7",""" +
                """"minor":500,"currency":"USD"}],"widgets":[{"id":"widget:25edce972bd9d2e9","definitionId":"local.contacts",""" +
                """"kind":"Contacts","variantId":"row","columns":2,"rows":1,"capacity":4,"contactIds":["contact:c9fc2171e44f23d7",""" +
                """"contact:852970908a08527d"]},{"id":"widget:bdaf9e15e9324ee7","definitionId":"local.shortcut",""" +
                """"kind":"Shortcut","variantId":"single","columns":1,"rows":1,"capacity":1,"actionId":"preset:6a8d07763fedb335"}]}"""
    }
}
