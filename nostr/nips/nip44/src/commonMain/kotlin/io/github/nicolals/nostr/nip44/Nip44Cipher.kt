package io.github.nicolals.nostr.nip44

import io.github.nicolals.nostr.core.crypto.CryptoPrimitives
import io.github.nicolals.nostr.core.crypto.CryptoResult
import io.github.nicolals.nostr.core.crypto.EventVerifier
import io.github.nicolals.nostr.core.event.Event
import io.github.nicolals.nostr.core.primitives.PublicKey
import io.github.nicolals.nostr.core.primitives.SecretKey
import kotlin.concurrent.Volatile
import okio.ByteString

/**
 * NIP-44 encryption for an account whose private key need not be available to the caller.
 * Implementations may suspend for remote signing services or user approval; cancellation propagates.
 * Validate the outer event before decrypting received content, or use [decryptEvent].
 */
interface Nip44Cipher {
    suspend fun encrypt(plaintext: String, recipientPublicKey: PublicKey): CryptoResult<String>
    suspend fun decrypt(content: String, senderPublicKey: PublicKey): CryptoResult<String>
}

/**
 * Local implementation; private key material is retained without being exposed by the capability.
 * Plaintext is limited to 65,535 UTF-8 bytes to bound encryption and decryption working memory.
 */
class LocalNip44Cipher(private val secretKey: SecretKey, private val crypto: CryptoPrimitives) :
    Nip44Cipher {
    // Conversation keys are deterministic per peer; cache the last peer to avoid redoing secp256k1
    // ECDH + HKDF-Extract on every encrypt/decrypt (e.g. NWC talks to one fixed wallet pubkey).
    @Volatile
    private var cachedPeer: Pair<PublicKey, ByteString>? = null

    override suspend fun encrypt(
        plaintext: String,
        recipientPublicKey: PublicKey
    ): CryptoResult<String> = conversationKey(recipientPublicKey).flatMap { key ->
        crypto.nip44RandomNonce().flatMap { nonce ->
            crypto.encryptNip44Content(plaintext, key, nonce)
        }
    }

    override suspend fun decrypt(
        content: String,
        senderPublicKey: PublicKey
    ): CryptoResult<String> = conversationKey(senderPublicKey).flatMap { key ->
        crypto.decryptNip44Content(content, key)
    }

    private fun conversationKey(peerPublicKey: PublicKey): CryptoResult<ByteString> {
        cachedPeer?.let { (cachedKey, conversationKey) ->
            if (cachedKey == peerPublicKey) return CryptoResult.Ok(conversationKey)
        }
        return crypto.nip44ConversationKey(secretKey.bytes, peerPublicKey).also { result ->
            if (result is CryptoResult.Ok) {
                cachedPeer = peerPublicKey to result.value
            }
        }
    }

    override fun toString(): String = "LocalNip44Cipher([redacted])"
}

/** Authenticates an incoming event before passing its exact content and author to the cipher. */
suspend fun Nip44Cipher.decryptEvent(event: Event, verifier: EventVerifier): CryptoResult<String> =
    verifier.verify(event).flatMap { decrypt(event.content, event.pubkey) }
