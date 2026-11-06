package xyz.lilsus.rayl.providers.nwc.integration.nwc

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Rayl 1.1 is the first public release: later versions must read the NWC connection it stored
 * (test project 2, item 6). The fixture is the JSON 1.1 writes. If this fails, the format
 * changed (for example a new field without a default): read the old format too, don't change
 * the fixture. Unreadable, the user's NWC connection would silently be gone.
 */
class StoredNwcCredentialsFrom11Test {
    @Test
    fun readsTheConnection11Stored() {
        val store = TestSecureStringStore()
        store.putString(
            "credentials",
            """{"connectionUri":"nostr+walletconnect://abc?relay=wss%3A%2F%2Frelay.example&secret=00",""" +
                """"alias":"My wallet","methods":["pay_invoice","get_balance"],""" +
                """"encryptionSchemes":["nip04","nip44_v2"],"negotiatedEncryption":"nip44_v2",""" +
                """"encryptionDefaultedToNip04":false,"notifications":["payment_received"],""" +
                """"network":"mainnet","color":"#ff9900"}"""
        )

        assertEquals(
            NwcCredentials(
                connectionUri = "nostr+walletconnect://abc?relay=wss%3A%2F%2Frelay.example&secret=00",
                alias = "My wallet",
                metadata = NwcWalletMetadata(
                    methods = setOf("pay_invoice", "get_balance"),
                    encryptionSchemes = setOf("nip04", "nip44_v2"),
                    negotiatedEncryption = "nip44_v2",
                    encryptionDefaultedToNip04 = false,
                    notifications = setOf("payment_received"),
                    network = "mainnet",
                    color = "#ff9900"
                )
            ),
            NwcCredentialStore(store).read()
        )
    }

    @Test
    fun readsAConnectionWithoutOptionalDetails() {
        val store = TestSecureStringStore()
        store.putString(
            "credentials",
            """{"connectionUri":"nostr+walletconnect://abc?relay=wss%3A%2F%2Fr&secret=00",""" +
                """"alias":null,"methods":["pay_invoice"],"encryptionSchemes":[],""" +
                """"negotiatedEncryption":null,"encryptionDefaultedToNip04":true,""" +
                """"notifications":[],"network":null,"color":null}"""
        )

        val read = NwcCredentialStore(store).read()

        assertEquals("nostr+walletconnect://abc?relay=wss%3A%2F%2Fr&secret=00", read?.connectionUri)
        assertEquals(true, read?.metadata?.encryptionDefaultedToNip04)
    }
}
