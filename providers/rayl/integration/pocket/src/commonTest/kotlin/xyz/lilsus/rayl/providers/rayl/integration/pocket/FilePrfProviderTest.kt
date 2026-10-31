package xyz.lilsus.rayl.providers.rayl.integration.pocket

import breez_sdk_spark.DeriveSeedsRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlinx.coroutines.test.runTest

class FilePrfProviderTest {
    @Test
    fun derivesLikeTheBreezCliFileProvider() = runTest {
        val provider = FilePrfProvider(ByteArray(32) { it.toByte() })

        val output = provider.deriveSeeds(
            DeriveSeedsRequest(salts = listOf("Rayl"))
        )

        // HMAC-SHA256(key = 0x00..0x1f, "Rayl")
        assertEquals(
            "4037e0fc15c18c665445887fde540192c11a80c9d58e6d93667d03245f02858c",
            output.seeds.single().toHexString()
        )
    }

    @Test
    fun keepsAnExistingSecret() {
        val existing = ByteArray(32) { 1 }
        var written = false

        val secret = filePrfSecret(read = { existing }, write = { written = true })

        assertSame(existing, secret)
        assertEquals(false, written)
    }

    @Test
    fun createsAndWritesASecretOnce() {
        var written: ByteArray? = null

        val secret = filePrfSecret(read = { null }, write = { written = it })

        assertEquals(32, secret.size)
        assertSame(secret, written)
    }

    @Test
    fun rejectsAMalformedSecret() {
        assertFailsWith<IllegalArgumentException> { FilePrfProvider(ByteArray(16)) }
    }
}
