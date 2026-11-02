package xyz.lilsus.rayl.providers.nwc.integration.nwc

import kotlin.test.Test
import kotlin.test.assertFalse

class NwcSecretRedactionTest {
    @Test
    fun theConnectionSecretIsNeverPrinted() {
        // The connection URI carries the secret that spends from the wallet (MOB-95).
        val uri = "nostr+walletconnect://abc?relay=wss://relay.example&secret=$SECRET"
        val metadata = NwcWalletMetadata(
            methods = setOf("pay_invoice"),
            encryptionSchemes = emptySet(),
            negotiatedEncryption = null,
            encryptionDefaultedToNip04 = false,
            notifications = emptySet(),
            network = null,
            color = null
        )
        val printed = listOf(
            NwcCredentials(connectionUri = uri, alias = "wallet", metadata = metadata).toString(),
            NwcWalletDiscovery(
                connectionUri = uri,
                walletPublicKey = "abc",
                relayUrls = listOf("wss://relay.example"),
                lightningAddress = null,
                aliasSuggestion = null,
                metadata = metadata
            ).toString()
        )

        printed.forEach { assertFalse(SECRET in it, it) }
    }

    private companion object {
        const val SECRET = "71a8c14c1407c113601079c4302dab36460f0ccd0ad506f1f2dc73b5100e4f3c"
    }
}
