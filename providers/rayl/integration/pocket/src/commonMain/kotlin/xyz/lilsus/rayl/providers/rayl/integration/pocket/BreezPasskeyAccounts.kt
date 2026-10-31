package xyz.lilsus.rayl.providers.rayl.integration.pocket

import breez_sdk_spark.ConnectWithPasskeyRequest
import breez_sdk_spark.CreatePasskeyOutput
import breez_sdk_spark.DeriveSeedsOutput
import breez_sdk_spark.DeriveSeedsRequest
import breez_sdk_spark.DomainAssociation
import breez_sdk_spark.PasskeyAvailability
import breez_sdk_spark.PasskeyClient
import breez_sdk_spark.PasskeyConfig
import breez_sdk_spark.PasskeyCredential
import breez_sdk_spark.PasskeyException
import breez_sdk_spark.PrfProvider
import breez_sdk_spark.PrfProviderException
import breez_sdk_spark.Seed
import breez_sdk_spark.SignInRequest
import breez_sdk_spark.Wallet
import fr.acinq.bitcoin.crypto.Digest
import fr.acinq.bitcoin.crypto.hmac

internal class BreezPasskeyAccounts(prfProvider: PrfProvider, breezApiKey: String?) :
    PasskeyAccounts {
    private val client = PasskeyClient(prfProvider, breezApiKey, PasskeyConfig())

    override suspend fun isAvailable(): Boolean = translated {
        when (client.checkAvailability()) {
            PasskeyAvailability.Available, is PasskeyAvailability.Skipped -> true
            PasskeyAvailability.PrfUnsupported, is PasskeyAvailability.NotAssociated -> false
        }
    }

    override suspend fun connect(credentialId: ByteArray?): PasskeyConnection = translated {
        val response = client.connectWithPasskey(
            ConnectWithPasskeyRequest(
                label = null,
                allowCredentials = credentialId?.let(::listOf),
                excludeCredentials = null
            )
        )
        PasskeyConnection(
            label = response.wallet.label,
            phrase = response.wallet.phrase(),
            labels = response.labels,
            // Only registration reports the user handle.
            created = response.credential?.userId != null,
            credentialId = response.credential?.credentialId
        )
    }

    override suspend fun open(label: String, credentialId: ByteArray?): String = translated {
        client.signIn(
            SignInRequest(
                label = label,
                allowCredentials = credentialId?.let(::listOf),
                preferImmediatelyAvailableCredentials = null
            )
        ).wallet.phrase()
    }

    override suspend fun list(): List<String> = translated { client.labels().list() }

    override suspend fun publish(label: String) = translated { client.labels().store(label) }

    override fun close() = client.close()

    private fun Wallet.phrase(): String = when (val seed = seed) {
        is Seed.Mnemonic -> seed.mnemonic
        else -> throw PasskeyAccountsException.Other("Unexpected seed")
    }

    private inline fun <T> translated(block: () -> T): T = try {
        block()
    } catch (error: PasskeyException) {
        PocketTimings.mark("passkey error: ${error::class.simpleName} ${error.message}")
        throw when (error) {
            is PasskeyException.Prf -> when (error.v1) {
                is PrfProviderException.UserCancelled,
                is PrfProviderException.UserTimedOut -> PasskeyAccountsException.Cancelled

                is PrfProviderException.PrfNotSupported -> PasskeyAccountsException.Unsupported

                else -> PasskeyAccountsException.Other(error.message)
            }

            is PasskeyException.CreatedButNotDerived ->
                PasskeyAccountsException.CreatedButNotDerived(error.credentialId, error.message)

            else -> PasskeyAccountsException.Other(error.message)
        }
    }
}

/**
 * A stand-in passkey for emulators and automated builds, which cannot complete passkey ceremonies:
 * HMAC-SHA256 with a random secret stored on this device, like the Breez CLI's file-backed
 * provider. It never prompts and is never missing, so it always "signs in". Unlike a real passkey
 * it is not synced: uninstalling loses it.
 *
 */
internal class FilePrfProvider(private val secret: ByteArray) : PrfProvider {
    init {
        require(secret.size == SECRET_BYTES) { "Invalid secret" }
    }

    override suspend fun deriveSeeds(request: DeriveSeedsRequest): DeriveSeedsOutput =
        DeriveSeedsOutput(seeds = request.salts.map(::derive), credentialId = null)

    override suspend fun isSupported(): Boolean = true

    override suspend fun createPasskey(
        excludeCredentials: List<ByteArray>,
        salts: List<String>
    ): CreatePasskeyOutput = CreatePasskeyOutput(
        credential = PasskeyCredential(
            credentialId = CREDENTIAL_ID,
            userId = null,
            aaguid = null,
            backupEligible = false
        ),
        seeds = salts.map(::derive)
    )

    override suspend fun checkDomainAssociation(): DomainAssociation =
        DomainAssociation.Skipped("File-backed passkey")

    private fun derive(salt: String): ByteArray =
        Digest.sha256().hmac(secret, salt.encodeToByteArray(), HMAC_SHA256_BLOCK_BYTES)

    companion object {
        const val SECRET_BYTES = 32
        private const val HMAC_SHA256_BLOCK_BYTES = 64
        private val CREDENTIAL_ID = "rayl-file-passkey".encodeToByteArray()
    }
}

/** Reads the [FilePrfProvider] secret, creating it with [write] when [read] finds none. */
internal fun filePrfSecret(read: () -> ByteArray?, write: (ByteArray) -> Unit): ByteArray =
    read() ?: secureRandomBytes(FilePrfProvider.SECRET_BYTES).also(write)
