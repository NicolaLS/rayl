package xyz.lilsus.rayl.providers.rayl

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal enum class RaylExit { None, Working, Failed, Done }

internal sealed interface RaylExitTarget {
    /** The user chose another wallet: [id] is an encoded `WalletCardChange`. */
    data class Wallet(val id: String) : RaylExitTarget

    /** The wallet's key copy was erased; the host mounts a new one. */
    data object Erased : RaylExitTarget
}

/**
 * How Rayl's wallet is left (SPEC-wallet-lifecycle): closed for another wallet, keeping the key
 * copy and pocket data, or erased by an explicit action. Separate from [RaylRuntime] so these
 * rules can be tested without the runtime's platform clients.
 */
internal class RaylWalletExit(
    private val scope: CoroutineScope,
    /** A debug build: offers erasing the pocket's key copy, whichever network it uses. */
    val canErase: Boolean,
    private val keyUnreadable: () -> Boolean,
    /** The key was generated on this device, so no passkey could set the same wallet up again. */
    private val withoutPasskey: () -> Boolean,
    /** True while leaving must wait, e.g. a payment is being submitted. */
    private val isBusy: () -> Boolean,
    private val stopSession: () -> Unit,
    private val closePocket: suspend () -> Unit,
    /** Erases the key copy and pocket data; false if that failed. */
    private val erasePocket: suspend () -> Boolean
) {
    /** Where the host goes once [state] is [RaylExit.Done]. */
    var target: RaylExitTarget? = null
        private set

    private val mutableState = MutableStateFlow(RaylExit.None)
    val state: StateFlow<RaylExit> = mutableState.asStateFlow()

    /**
     * Closes the pocket so the host can mount the wallet [walletId] asks for. Its key and funds
     * stay on this device, and paying with the pocket again restores it.
     * Returns false while a payment is being submitted.
     */
    fun useWallet(walletId: String): Boolean = leave(RaylExitTarget.Wallet(walletId))

    /** Debug builds only: deletes this device's wallet key and data, then sets the wallet up again. */
    fun erase() {
        if (canErase) leave(RaylExitTarget.Erased)
    }

    /**
     * When this device can no longer read its key copy, deletes it and sets the wallet up again:
     * the same passkey and account derive the same wallet. Never for a key generated without a
     * passkey, whose only copy that would delete.
     */
    fun setUpAgain() {
        if (keyUnreadable() && !withoutPasskey()) leave(RaylExitTarget.Erased)
    }

    /**
     * When this device can no longer read a key generated without a passkey, deletes it so a new
     * pocket is set up. The old one opens again only with its recovery phrase.
     */
    fun startNewPocket() {
        if (keyUnreadable() && withoutPasskey()) leave(RaylExitTarget.Erased)
    }

    fun retry() {
        val target = target
        if (mutableState.value == RaylExit.Failed && target != null) {
            mutableState.value = RaylExit.None
            leave(target)
        }
    }

    private fun leave(target: RaylExitTarget): Boolean {
        if (isBusy()) return false
        if (mutableState.value == RaylExit.Working || mutableState.value == RaylExit.Done) {
            return false
        }
        this.target = target
        mutableState.value = RaylExit.Working
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            mutableState.value = try {
                stopSession()
                val finished = if (target == RaylExitTarget.Erased) {
                    erasePocket()
                } else {
                    closePocket()
                    true
                }
                if (finished) RaylExit.Done else RaylExit.Failed
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                RaylExit.Failed
            }
        }
        return true
    }
}
