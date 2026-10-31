package xyz.lilsus.rayl

import com.russhwolf.settings.Settings
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.lilsus.raylsuite.core.model.WalletStartMode

/**
 * Owns which wallet Rayl pays with: its built-in wallet unless an external wallet is connected or
 * being set up. At most one external wallet may own a connection. See SPEC-wallet-lifecycle.
 */
internal class RaylSelection(private val settings: Settings) {
    private val mutableExternal = MutableStateFlow(configuredExternalWallet(settings))

    /** The external wallet in use or in setup; `null` means Rayl's built-in wallet. */
    val external = mutableExternal.asStateFlow()
    var startMode = WalletStartMode.Restore
        private set

    // Retained through Android rotation, but never restored from an abandoned process/session.
    private val mutableSession = MutableStateFlow(Random.nextLong())

    /** Changes whenever a wallet must mount as a new session. */
    val session = mutableSession.asStateFlow()
    val sessionId: Long get() = mutableSession.value
    private val preparationMutex = Mutex()
    private val mutablePreparation = MutableStateFlow(WalletPreparation.Cleaning)
    val preparation = mutablePreparation.asStateFlow()

    /** Delete unowned external connections before any wallet is mounted. */
    suspend fun prepare(clearSavedWallet: suspend (ExternalWallet) -> Boolean) {
        preparationMutex.withLock {
            if (mutablePreparation.value == WalletPreparation.Ready) return
            mutablePreparation.value = WalletPreparation.Cleaning
            try {
                for (provider in ExternalWallet.entries) {
                    if (provider != mutableExternal.value && !clearSavedWallet(provider)) {
                        mutablePreparation.value = WalletPreparation.Failed
                        return
                    }
                }
                mutablePreparation.value = WalletPreparation.Ready
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutablePreparation.value = WalletPreparation.Failed
            }
        }
    }

    /** Only successful connection setup becomes eligible for restoration on another launch. */
    fun connected(wallet: ExternalWallet) {
        if (mutableExternal.value == wallet &&
            mutablePreparation.value == WalletPreparation.Ready
        ) {
            settings.putString(EXTERNAL_WALLET_KEY, wallet.name)
        }
    }
    private val mutableWelcomeCompleted =
        MutableStateFlow(settings.getBoolean(WELCOME_KEY, false))
    val welcomeCompleted = mutableWelcomeCompleted.asStateFlow()

    fun completeWelcome() {
        settings.putBoolean(WELCOME_KEY, true)
        mutableWelcomeCompleted.value = true
    }

    /** Starts fresh setup of [wallet] once Rayl's built-in wallet has closed its session. */
    fun choose(wallet: ExternalWallet) {
        check(mutableExternal.value == null) {
            "Go back to Rayl's wallet before choosing another wallet"
        }
        check(mutablePreparation.value == WalletPreparation.Ready) {
            "Finish connection cleanup before choosing a wallet"
        }
        startMode = WalletStartMode.Fresh
        mutableSession.value = Random.nextLong()
        mutableExternal.value = wallet
    }

    /** Back to Rayl's wallet, after removing the external connection or cancelling its setup. */
    fun clear() {
        mutablePreparation.value = WalletPreparation.Cleaning
        settings.remove(EXTERNAL_WALLET_KEY)
        mutableSession.value = Random.nextLong()
        mutableExternal.value = null
    }

    /** Mounts a new session of Rayl's wallet, e.g. after a debug build erased it. */
    fun restartRaylWallet() {
        if (mutableExternal.value == null) mutableSession.value = Random.nextLong()
    }
}

/** Wallets the user can connect instead of Rayl's built-in wallet. */
internal enum class ExternalWallet {
    Blink,
    Nwc,
    Spark;

    /** Identifies the wallet in platform UI and in the Rayl experience's wallet options. */
    val id: String get() = name.lowercase()

    companion object {
        fun fromId(id: String): ExternalWallet? = entries.firstOrNull { it.id == id }
    }
}

private const val EXTERNAL_WALLET_KEY = "rayl.wallet"
private const val WELCOME_KEY = "rayl.welcome"

/** The external wallet that completed setup on an earlier launch, if any. */
internal fun configuredExternalWallet(settings: Settings): ExternalWallet? =
    ExternalWallet.entries.firstOrNull { it.name == settings.getStringOrNull(EXTERNAL_WALLET_KEY) }

/** Rayl's built-in wallet is in use once the welcome is done and no external wallet is. */
internal fun usesRaylWallet(settings: Settings): Boolean =
    settings.getBoolean(WELCOME_KEY, false) && configuredExternalWallet(settings) == null

internal enum class WalletPreparation { Cleaning, Failed, Ready }
