package xyz.lilsus.rayl.providers.rayl

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Host requests to switch from Rayl's wallet to an external wallet without the settings screen,
 * such as a wallet connection link. Accepted requests end in the experience's exit callback.
 */
object RaylWalletRequests {
    private val eventsChannel = Channel<String>(1, BufferOverflow.DROP_OLDEST)
    internal val events: Flow<String> = eventsChannel.receiveAsFlow()
    private val mutableRejections = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** Wallet ids that could not be switched to, because a payment is being submitted. */
    val rejections: SharedFlow<String> = mutableRejections.asSharedFlow()

    fun request(walletId: String) {
        eventsChannel.trySend(walletId)
    }

    internal fun rejected(walletId: String) {
        mutableRejections.tryEmit(walletId)
    }

    fun clear() {
        while (eventsChannel.tryReceive().isSuccess) {
            // Discard requests meant for an earlier session.
        }
    }
}
