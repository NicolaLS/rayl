@file:OptIn(kotlin.experimental.ExperimentalObjCName::class)

package xyz.lilsus.rayl.providers.rayl.integration.pocket

import breez_sdk_spark.CreatePasskeyOutput
import breez_sdk_spark.DeriveSeedsOutput
import breez_sdk_spark.DeriveSeedsRequest
import breez_sdk_spark.DomainAssociation
import breez_sdk_spark.PasskeyCredential
import breez_sdk_spark.PrfProvider
import breez_sdk_spark.PrfProviderException
import kotlin.coroutines.resume
import kotlin.native.ObjCName
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSData
import platform.Foundation.dataWithBytes
import platform.posix.memcpy

/**
 * The device's passkeys on iOS, implemented in Swift over Breez's Swift `PasskeyProvider`.
 *
 * The Kotlin SDK has no iOS passkey provider, and the Swift one implements the Swift binding's
 * `PrfProvider`, so Swift forwards each call here instead. Both bindings share one native library:
 * Swift must only call the provider's methods, never the Swift SDK's `PasskeyClient` or other FFI.
 * Each call completes exactly once, with a result or an error.
 */
@ObjCName("RaylPasskeyAuthenticator", exact = true)
interface RaylPasskeyAuthenticator {
    fun deriveSeeds(
        salts: List<String>,
        allowCredentials: List<NSData>,
        preferImmediatelyAvailable: Boolean?,
        completion: (RaylPasskeySeeds?, RaylPasskeyError?) -> Unit
    )

    fun createPasskey(
        excludeCredentials: List<NSData>,
        salts: List<String>,
        completion: (RaylPasskeyCreated?, RaylPasskeyError?) -> Unit
    )

    fun checkDomainAssociation(completion: (RaylPasskeyDomain) -> Unit)
}

@ObjCName("RaylPasskeySeeds", exact = true)
class RaylPasskeySeeds(val seeds: List<NSData>, val credentialId: NSData?)

@ObjCName("RaylPasskeyCreated", exact = true)
class RaylPasskeyCreated(
    val credentialId: NSData,
    val userId: NSData?,
    val aaguid: NSData?,
    val backupEligible: Boolean?,
    /** Present when the authenticator evaluated PRF while creating the passkey. */
    val seeds: List<NSData>?
)

/** Mirrors Breez's `PrfProviderError` cases. */
@ObjCName("RaylPasskeyErrorKind", exact = true)
enum class RaylPasskeyErrorKind {
    PrfNotSupported,
    UserCancelled,
    UserTimedOut,
    CredentialNotFound,
    AuthenticationFailed,
    PrfEvaluationFailed,
    Configuration,
    CredentialAlreadyExists,
    Generic
}

@ObjCName("RaylPasskeyError", exact = true)
class RaylPasskeyError(val kind: RaylPasskeyErrorKind, val message: String)

@ObjCName("RaylPasskeyDomainKind", exact = true)
enum class RaylPasskeyDomainKind { Associated, NotAssociated, Skipped }

@ObjCName("RaylPasskeyDomain", exact = true)
class RaylPasskeyDomain(val kind: RaylPasskeyDomainKind, val source: String, val reason: String)

/**
 * Adapts [RaylPasskeyAuthenticator] to the Kotlin SDK. Every failure becomes a
 * [PrfProviderException]: any other exception thrown inside an SDK callback aborts the process.
 */
internal class IosPrfProvider(private val authenticator: RaylPasskeyAuthenticator) : PrfProvider {
    override suspend fun deriveSeeds(request: DeriveSeedsRequest): DeriveSeedsOutput = guarded {
        val result = suspendCancellableCoroutine { continuation ->
            authenticator.deriveSeeds(
                salts = request.salts,
                allowCredentials = request.allowCredentials.map { it.toNSData() },
                preferImmediatelyAvailable = request.preferImmediatelyAvailableCredentials
            ) { seeds, error -> continuation.resume(seeds to error) }
        }
        val seeds = result.first ?: throw result.second.toException()
        DeriveSeedsOutput(
            seeds = seeds.seeds.map { it.toByteArray() },
            credentialId = seeds.credentialId?.toByteArray()
        )
    }

    override suspend fun isSupported(): Boolean =
        true // Breez's provider needs iOS 18, Rayl's minimum.

    override suspend fun createPasskey(
        excludeCredentials: List<ByteArray>,
        salts: List<String>
    ): CreatePasskeyOutput = guarded {
        val result = suspendCancellableCoroutine { continuation ->
            authenticator.createPasskey(
                excludeCredentials = excludeCredentials.map { it.toNSData() },
                salts = salts
            ) { created, error -> continuation.resume(created to error) }
        }
        val created = result.first ?: throw result.second.toException()
        CreatePasskeyOutput(
            credential = PasskeyCredential(
                credentialId = created.credentialId.toByteArray(),
                userId = created.userId?.toByteArray(),
                aaguid = created.aaguid?.toByteArray(),
                backupEligible = created.backupEligible
            ),
            seeds = created.seeds?.map { it.toByteArray() }
        )
    }

    override suspend fun checkDomainAssociation(): DomainAssociation = guarded {
        val domain = suspendCancellableCoroutine { continuation ->
            authenticator.checkDomainAssociation { continuation.resume(it) }
        }
        when (domain.kind) {
            RaylPasskeyDomainKind.Associated -> DomainAssociation.Associated

            RaylPasskeyDomainKind.NotAssociated ->
                DomainAssociation.NotAssociated(domain.source, domain.reason)

            RaylPasskeyDomainKind.Skipped -> DomainAssociation.Skipped(domain.reason)
        }
    }

    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: PrfProviderException) {
        throw error
    } catch (error: Throwable) {
        throw PrfProviderException.Generic(error.message ?: "Passkey bridge failed")
    }
}

private fun RaylPasskeyError?.toException(): PrfProviderException {
    val message = this?.message ?: "The passkey returned no result"
    return when (this?.kind) {
        RaylPasskeyErrorKind.PrfNotSupported -> PrfProviderException.PrfNotSupported()

        RaylPasskeyErrorKind.UserCancelled -> PrfProviderException.UserCancelled()

        RaylPasskeyErrorKind.UserTimedOut -> PrfProviderException.UserTimedOut()

        RaylPasskeyErrorKind.CredentialNotFound -> PrfProviderException.CredentialNotFound(message)

        RaylPasskeyErrorKind.AuthenticationFailed ->
            PrfProviderException.AuthenticationFailed(message)

        RaylPasskeyErrorKind.PrfEvaluationFailed ->
            PrfProviderException.PrfEvaluationFailed(message)

        RaylPasskeyErrorKind.Configuration -> PrfProviderException.Configuration(message)

        RaylPasskeyErrorKind.CredentialAlreadyExists ->
            PrfProviderException.CredentialAlreadyExists(message)

        RaylPasskeyErrorKind.Generic, null -> PrfProviderException.Generic(message)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData = usePinned {
    NSData.dataWithBytes(if (isEmpty()) null else it.addressOf(0), size.convert())
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val bytes = ByteArray(length.toInt())
    if (bytes.isNotEmpty()) bytes.usePinned { memcpy(it.addressOf(0), this.bytes, length) }
    return bytes
}
