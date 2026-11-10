package xyz.lilsus.rayl.providers.rayl.integration.pocket

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.lilsus.raylsuite.core.telemetry.RaylTrace

sealed interface PocketSetupResult {
    /** The key is stored and the pocket is connecting. */
    data object Done : PocketSetupResult

    /**
     * The passkey already holds [accounts] (Breez passkey labels), maybe from other apps. The user
     * picks one with [PocketSetup.choose], or Rayl's own with [PocketSetup.createAccount] when
     * [canCreate].
     */
    data class ChooseAccount(val accounts: List<String>, val canCreate: Boolean) : PocketSetupResult

    /** The user dismissed the passkey prompt; only an explicit retry prompts again. */
    data object Cancelled : PocketSetupResult

    /**
     * The chosen passkey's password manager does not support the PRF extension, so it cannot
     * derive a key; another password manager can. Such a passkey never held a wallet.
     */
    data object PasskeyWithoutPrf : PocketSetupResult

    data class Failed(val detail: String?) : PocketSetupResult
}

/**
 * Sets up the pocket key from a passkey with Breez passkey login: the passkey derives the key, so
 * it is also the only backup. Every app sharing the passkey and an account (label) opens the same
 * wallet. A passkey with no accounts yet uses Breez's default account.
 */
interface PocketSetup {
    /** Whether this device can use passkeys; false means Rayl's wallet is not offered. */
    suspend fun isAvailable(): Boolean

    /** Whether the pocket already has a key, so no setup is needed. */
    suspend fun hasKey(): Boolean

    /** Opens or creates the passkey and finishes setup when there is no account to choose. */
    suspend fun start(): PocketSetupResult

    /** Uses one of the accounts offered by [PocketSetupResult.ChooseAccount]. */
    suspend fun choose(account: String): PocketSetupResult

    /** Uses Rayl's own account on the passkey, creating it. */
    suspend fun createAccount(): PocketSetupResult

    fun close()
}

/** Rayl's own label, for a wallet not shared with other apps. */
internal const val RAYL_ACCOUNT = "Rayl"

internal class PasskeyPocketSetup(
    private val pocket: RaylPocket,
    private val accounts: PasskeyAccounts
) : PocketSetup {
    private val lock = Mutex()
    private var connection: PasskeyConnection? = null

    /** The passkey of this setup, so later prompts cannot switch to another passkey. */
    private var credentialId: ByteArray? = null

    /**
     * Passkeys created in a password manager without PRF. The next start creates a passkey
     * elsewhere instead of signing in, since signing in would offer the same useless passkey.
     */
    private val withoutPrf = mutableListOf<ByteArray>()

    override suspend fun isAvailable(): Boolean = try {
        accounts.isAvailable()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        false
    }

    override suspend fun hasKey(): Boolean = pocket.hasKey()

    override suspend fun start(): PocketSetupResult = attempt {
        RaylTrace.debug("passkey connect")
        val opened = if (withoutPrf.isEmpty()) {
            accounts.connect(credentialId)
        } else {
            accounts.register(excludeCredentials = withoutPrf.toList())
        }
        RaylTrace.debug("passkey connected, ${opened.labels.size} accounts")
        connection = opened
        opened.credentialId?.let { credentialId = it }
        // A passkey created just now has no other accounts, and its default label is still being
        // published in the background.
        if (opened.created) return@attempt install(opened.phrase)
        // Listing again surfaces a relay failure that connecting hides as "no accounts".
        val labels = opened.labels.ifEmpty { accounts.list() }.distinct()
        RaylTrace.debug("passkey listed ${labels.size} accounts")
        if (labels.isEmpty()) {
            install(opened.phrase)
        } else {
            PocketSetupResult.ChooseAccount(labels, canCreate = RAYL_ACCOUNT !in labels)
        }
    }

    override suspend fun choose(account: String): PocketSetupResult = attempt {
        val opened = connection ?: return@attempt PocketSetupResult.Failed("Not connected")
        val phrase = if (account == opened.label) {
            opened.phrase
        } else {
            accounts.open(account, credentialId)
        }
        install(phrase)
    }

    override suspend fun createAccount(): PocketSetupResult = attempt {
        val phrase = accounts.open(RAYL_ACCOUNT, credentialId)
        try {
            // Publishing lets Rayl offer the account again after a reinstall. Without it, creating
            // the account again derives the same wallet.
            accounts.publish(RAYL_ACCOUNT)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
        }
        install(phrase)
    }

    override fun close() = accounts.close()

    private suspend fun install(phrase: String): PocketSetupResult =
        if (pocket.install(PocketKey(phrase))) {
            withoutPrf.clear()
            PocketSetupResult.Done
        } else {
            PocketSetupResult.Failed("The key could not be stored")
        }

    private suspend fun attempt(block: suspend () -> PocketSetupResult): PocketSetupResult =
        lock.withLock { attemptLocked(block).also { RaylTrace.debug("passkey setup: $it") } }

    private suspend fun attemptLocked(block: suspend () -> PocketSetupResult): PocketSetupResult =
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: PasskeyAccountsException) {
            when (error) {
                PasskeyAccountsException.Cancelled -> PocketSetupResult.Cancelled

                PasskeyAccountsException.WithoutPrf -> {
                    // The user picked a useless passkey; let them pick another one next time.
                    credentialId = null
                    connection = null
                    PocketSetupResult.PasskeyWithoutPrf
                }

                is PasskeyAccountsException.CreatedWithoutPrf -> {
                    withoutPrf += error.credentialId
                    credentialId = null
                    connection = null
                    PocketSetupResult.PasskeyWithoutPrf
                }

                is PasskeyAccountsException.CreatedButNotDerived -> {
                    // Registering again would orphan this passkey; sign in with it instead.
                    credentialId = error.credentialId
                    PocketSetupResult.Failed(error.message)
                }

                is PasskeyAccountsException.Other -> PocketSetupResult.Failed(error.message)
            }
        } catch (error: Exception) {
            PocketSetupResult.Failed(error.message)
        }
}

/** A passkey's default wallet, opened by [PasskeyAccounts.connect]. */
internal class PasskeyConnection(
    val label: String,
    val phrase: String,
    /** Accounts published for the passkey; empty when none or when the relays failed. */
    val labels: List<String>,
    /** Whether the passkey was registered by this call rather than signed in to. */
    val created: Boolean,
    val credentialId: ByteArray?
)

/** Breez passkey login, behind an interface so the setup rules are testable. */
internal interface PasskeyAccounts {
    suspend fun isAvailable(): Boolean

    /** Signs in to the passkey, or registers one when the device has none for Rayl's domain. */
    suspend fun connect(credentialId: ByteArray?): PasskeyConnection

    /** Registers a new passkey, never one of [excludeCredentials]. */
    suspend fun register(excludeCredentials: List<ByteArray>): PasskeyConnection

    /** The phrase of [label] on the passkey [credentialId], or the one the user picks if null. */
    suspend fun open(label: String, credentialId: ByteArray?): String

    suspend fun list(): List<String>

    suspend fun publish(label: String)

    fun close()
}

internal sealed class PasskeyAccountsException(message: String?) : Exception(message) {
    data object Cancelled : PasskeyAccountsException("Cancelled")

    /** The passkey's password manager does not support PRF. */
    data object WithoutPrf : PasskeyAccountsException("The passkey does not support PRF")

    /** A passkey was created in a password manager without PRF. */
    class CreatedWithoutPrf(val credentialId: ByteArray) :
        PasskeyAccountsException("The new passkey does not support PRF")

    class CreatedButNotDerived(val credentialId: ByteArray, message: String?) :
        PasskeyAccountsException(message)

    class Other(message: String?) : PasskeyAccountsException(message)
}
