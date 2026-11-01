package xyz.lilsus.rayl.providers.blink.feature.blinkcontacts

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import xyz.lilsus.rayl.providers.blink.integration.blink.BlinkContact
import xyz.lilsus.raylsuite.core.model.LightningAddress
import xyz.lilsus.raylsuite.feature.paymenthub.DefaultPaymentHubRepository
import xyz.lilsus.raylsuite.feature.paymenthub.HubWidgetDraft
import xyz.lilsus.raylsuite.feature.paymenthub.HubWidgetKind
import xyz.lilsus.raylsuite.feature.paymenthub.LocalHubWidgets

class BlinkContactStorageTest {
    @Test
    fun automaticImportAddsEveryValidDistinctNewContact() = runTest {
        val hub = DefaultPaymentHubRepository(MapSettings())
        hub.saveContact(assertNotNull(LightningAddress.parse("existing@example.com")), "Existing")
        val importer =
            BlinkContactsImporter(
                fetchContacts = {
                    listOf(
                        BlinkContact("existing", "Existing replacement", 3, "example.com"),
                        BlinkContact("alice", " Alice ", 2, "example.com"),
                        BlinkContact("alice@example.com", "Duplicate", 1, "example.com"),
                        BlinkContact("not an address", "Invalid", 0, "example.com"),
                        BlinkContact("bob@example.org", null, 0, "ignored.example")
                    )
                },
                hubRepository = hub
            )

        importer.importAll()

        assertEquals(
            listOf(
                "Existing" to "existing@example.com",
                "Alice" to "alice@example.com",
                "bob" to "bob@example.org"
            ),
            hub.hub.value.contacts.map { it.title to it.address.full }
        )
    }
}
