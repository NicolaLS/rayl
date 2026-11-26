package io.github.nicolals.nostr.nip04

import io.github.nicolals.nostr.core.crypto.CryptoError
import io.github.nicolals.nostr.core.crypto.CryptoPrimitives
import io.github.nicolals.nostr.core.crypto.CryptoResult
import io.github.nicolals.nostr.core.crypto.EventVerifier
import io.github.nicolals.nostr.core.event.Event
import io.github.nicolals.nostr.core.primitives.PublicKey
import io.github.nicolals.nostr.core.primitives.SecretKey
import io.github.nicolals.nostr.nip04.model.Nip04CipherText
import kotlin.concurrent.Volatile
import okio.ByteString

/**
 * NIP-04 encryption for an account whose private key need not be available to the caller.
 * Implementations may suspend for remote signing services or user approval; cancellation propagates.
 * Validate the outer event before decrypting received content, or use [decryptEvent].
 */
interface Nip04Cipher {
    suspend fun encrypt(plaintext: String, recipientPublicKey: PublicKey): CryptoResult<String>
    suspend fun decrypt(content: String, senderPublicKey: PublicKey): CryptoResult<String>
}

/** Local implementation; private key material is retained without being exposed by the capability. */
class LocalNip04Cipher(private val secretKey: SecretKey, private val crypto: CryptoPrimitives) :
    Nip04Cipher {
    @Volatile
    private var cachedPeer: Pair<PublicKey, ByteString>? = null

    override suspend fun encrypt(
        plaintext: String,
        recipientPublicKey: PublicKey
    ): CryptoResult<String> = sharedSecret(recipientPublicKey).flatMap { secret ->
        crypto.encryptNip04WithSharedSecret(plaintext, secret).map { it.toContent() }
    }

    override suspend fun decrypt(
        content: String,
        senderPublicKey: PublicKey
    ): CryptoResult<String> {
        val ciphertext = Nip04CipherText.parseOrNull(content)
            ?: return CryptoResult.Err(
                CryptoError.InvalidInput("content", "nip04 format", content.length)
            )
        return sharedSecret(senderPublicKey).flatMap { secret ->
            crypto.decryptNip04WithSharedSecret(ciphertext, secret)
        }
    }

    private fun sharedSecret(peerPublicKey: PublicKey): CryptoResult<ByteString> {
        cachedPeer?.let { (cachedKey, secret) ->
            if (cachedKey == peerPublicKey) return CryptoResult.Ok(secret)
        }
        return crypto.ecdhXOnly(secretKey.bytes, peerPublicKey.bytes).also { result ->
            if (result is CryptoResult.Ok) {
                cachedPeer = peerPublicKey to result.value
            }
        }
    }

    override fun toString(): String = "LocalNip04Cipher([redacted])"
}

/** Authenticates an incoming event before passing its exact content and author to the cipher. */
suspend fun Nip04Cipher.decryptEvent(event: Event, verifier: EventVerifier): CryptoResult<String> =
    verifier.verify(event).flatMap { decrypt(event.content, event.pubkey) }
