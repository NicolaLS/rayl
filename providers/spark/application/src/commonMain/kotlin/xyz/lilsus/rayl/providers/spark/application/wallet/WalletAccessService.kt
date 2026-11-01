package xyz.lilsus.rayl.providers.spark.application.wallet

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import xyz.lilsus.rayl.providers.spark.application.AppBootstrapConfig
import xyz.lilsus.rayl.providers.spark.application.payment.PaymentSessionLifecycle
import xyz.lilsus.rayl.providers.spark.application.payment.SparkPaymentClient
import xyz.lilsus.rayl.providers.spark.application.payment.SparkPayments
import xyz.lilsus.raylsuite.core.model.WalletStartMode

interface SparkSdkSession {
    val paymentClient: SparkPaymentClient
    suspend fun disconnect()
}

fun interface SparkSdkConnector {
    /**
     * [freshStorage] means the SDK starts without local data (a new import or reinstall), so its
     * balance is unknown until the first sync.
     */
    suspend fun connect(
        credential: WalletCredential,
        storageDirectory: String,
        freshStorage: Boolean,
        bootstrapConfig: AppBootstrapConfig
    ): SparkSdkSession
}

interface WalletDirectories {
    val sdkDirectory: String

    /** Whether the SDK has no local data yet; checked before [sdkDirectory] creates it. */
    fun sdkStorageIsEmpty(): Boolean

    fun beginReset(): Boolean
    fun resetPending(): Boolean
    fun deleteWalletData(): Boolean
    fun finishReset(): Boolean
}

class DefaultWalletAccess(
    private val bootstrapConfig: AppBootstrapConfig,
    private val vault: CredentialVault,
    private val directories: WalletDirectories,
    private val connector: SparkSdkConnector,
    override val payments: SparkPayments,
    private val paymentLifecycle: PaymentSessionLifecycle,
    applicationScope: CoroutineScope,
    startMode: WalletStartMode
) : WalletAccess {
    private val lifecycleJob = SupervisorJob(applicationScope.coroutineContext[Job])
    private val lifecycleScope = CoroutineScope(applicationScope.coroutineContext + lifecycleJob)

    // The SDK keeps running after a cancelled connect, so attempts run outside the lifecycle and
    // are never cancelled: whoever stops waiting still owns the late session and disconnects it.
    private val connectionScope = CoroutineScope(
        applicationScope.coroutineContext + SupervisorJob(applicationScope.coroutineContext[Job])
    )
    private val operationMutex = Mutex()
    private val mutableState = MutableStateFlow<WalletAccessState>(WalletAccessState.Loading)
    private val mutableRemoved = MutableStateFlow(false)
    private var sdk: SparkSdkSession? = null
    private var connecting: Deferred<SparkSdkSession>? = null
    private var hasCredential = startMode == WalletStartMode.Restore
    private var started = false
    private var closeCompleted = false

    override val state: StateFlow<WalletAccessState> = mutableState.asStateFlow()
    override val removed: StateFlow<Boolean> = mutableRemoved.asStateFlow()

    override fun start() {
        lifecycleScope.launch {
            operationMutex.withLock {
                if (started) return@withLock
                started = true
                restoreOrResumeReset()
            }
        }
    }

    override suspend fun importWallet(mnemonic: String, passphrase: String): ImportWalletResult =
        runOperation {
            operationMutex.withLock {
                mutableRemoved.value = false
                if (!started) {
                    started = true
                    restoreOrResumeReset()
                }
                if (hasCredential || directories.resetPending() ||
                    mutableState.value == WalletAccessState.ResetRequired
                ) {
                    return@withLock ImportWalletResult.ALREADY_CONFIGURED
                }

                val credential = try {
                    WalletCredential.fromMnemonic(mnemonic, passphrase)
                } catch (_: IllegalArgumentException) {
                    mutableState.value = WalletAccessState.NoWallet
                    return@withLock ImportWalletResult.INVALID_MNEMONIC
                }

                mutableState.value = WalletAccessState.Connecting
                if (!connectApplicationSession(credential)) {
                    discardUnstoredSession()
                    return@withLock ImportWalletResult.CONNECTION_FAILED
                }

                when (vault.store(credential)) {
                    CredentialStoreResult.STORED -> {
                        hasCredential = true
                        mutableState.value = WalletAccessState.Connected
                        return@withLock ImportWalletResult.IMPORTED
                    }

                    CredentialStoreResult.ALREADY_EXISTS,
                    CredentialStoreResult.UNAVAILABLE,
                    CredentialStoreResult.INVALIDATED,
                    CredentialStoreResult.FAILED -> {
                        discardUnstoredSession()
                        return@withLock ImportWalletResult.CREDENTIAL_STORE_FAILED
                    }
                }
            }
        }

    override suspend fun retryConnection(): Unit = runOperation {
        operationMutex.withLock {
            if (mutableState.value == WalletAccessState.ResetRequired) {
                removeLocalWallet()
            } else {
                disconnectCurrent()
                restoreOrResumeReset()
            }
            Unit
        }
    }

    override suspend fun removeWallet(): RemoveWalletResult = runOperation {
        operationMutex.withLock { removeLocalWallet() }
    }

    override suspend fun close() {
        lifecycleJob.cancel()
        withContext(NonCancellable) {
            lifecycleJob.join()
            operationMutex.withLock {
                if (closeCompleted) return@withLock
                paymentLifecycle.detach()
                sdk?.disconnect()
                sdk = null
                check(discardConnectingSession()) { "The wallet is still connecting" }
                closeCompleted = true
            }
        }
    }

    private suspend fun <T> runOperation(block: suspend () -> T): T {
        val operation = lifecycleScope.async {
            currentCoroutineContext().ensureActive()
            block()
        }
        return try {
            operation.await()
        } catch (cancellation: CancellationException) {
            operation.cancel()
            withContext(NonCancellable) { operation.join() }
            throw cancellation
        }
    }

    private suspend fun removeLocalWallet(notifyRemoval: Boolean = true): RemoveWalletResult {
        mutableRemoved.value = false
        mutableState.value = WalletAccessState.Removing
        // Record removal before touching credentials so a restart can resume partial cleanup.
        if (!directories.beginReset()) {
            mutableState.value = WalletAccessState.ResetRequired
            return RemoveWalletResult.RESET_REQUIRED
        }
        return resumeReset(notifyRemoval)
    }

    private suspend fun restoreOrResumeReset() {
        mutableState.value = WalletAccessState.Loading
        if (directories.resetPending()) {
            resumeReset()
            return
        }

        if (!hasCredential) {
            mutableState.value = WalletAccessState.NoWallet
            return
        }
        when (val result = vault.load()) {
            is CredentialLoadResult.Available -> {
                mutableState.value = WalletAccessState.Connecting
                mutableState.value = if (connectApplicationSession(result.credential)) {
                    WalletAccessState.Connected
                } else {
                    WalletAccessState.ReconnectRequired
                }
            }

            else -> applyCredentialLoadState(result)
        }
    }

    private suspend fun resumeReset(notifyRemoval: Boolean = true): RemoveWalletResult {
        if (!disconnectCurrent() || !discardConnectingSession()) {
            mutableState.value = WalletAccessState.ResetRequired
            return RemoveWalletResult.RESET_REQUIRED
        }
        val credentialDeleted = vault.delete() != CredentialDeleteResult.FAILED
        val dataDeleted = directories.deleteWalletData()
        // Startup must keep seeing the marker until every cleanup step has succeeded.
        val finished = credentialDeleted && dataDeleted && directories.finishReset()
        mutableState.value =
            if (finished) WalletAccessState.NoWallet else WalletAccessState.ResetRequired
        if (finished) hasCredential = false
        mutableRemoved.value = finished && notifyRemoval
        return if (finished) RemoveWalletResult.REMOVED else RemoveWalletResult.RESET_REQUIRED
    }

    private suspend fun connectApplicationSession(credential: WalletCredential): Boolean {
        currentCoroutineContext().ensureActive()
        if (sdk != null) return true
        if (!discardConnectingSession()) return false
        currentCoroutineContext().ensureActive()
        return try {
            val pending = connectionScope.async {
                val freshStorage = directories.sdkStorageIsEmpty()
                connector.connect(
                    credential,
                    directories.sdkDirectory,
                    freshStorage,
                    bootstrapConfig
                )
            }
            connecting = pending
            val connected =
                withTimeoutOrNull(CONNECTION_TIMEOUT_MILLIS) { pending.await() } ?: return false
            currentCoroutineContext().ensureActive()
            paymentLifecycle.attach(connected.paymentClient)
            sdk = connected
            connecting = null
            true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            false
        } finally {
            // close owns cleanup after lifetime cancellation, including a session whose attachment
            // was interrupted. Until then, retain failed cleanup, or an attempt still running after
            // the timeout, for a later retry or close.
            if (lifecycleJob.isActive) discardConnectingSession(waitForAttempt = false)
        }
    }

    /**
     * Releases the session of an attempt that did not become the wallet's session. A running
     * attempt is awaited (or, without [waitForAttempt], kept) so no second SDK starts on the same
     * storage; false means it still runs or its disconnect failed.
     */
    private suspend fun discardConnectingSession(waitForAttempt: Boolean = true): Boolean =
        withContext(NonCancellable) {
            val pending = connecting ?: return@withContext true
            if (!waitForAttempt && !pending.isCompleted) return@withContext false
            val session = try {
                withTimeoutOrNull(CONNECTION_TIMEOUT_MILLIS) { pending.await() }
                    ?: return@withContext false
            } catch (_: Throwable) {
                // The attempt failed, so it owns no session.
                connecting = null
                return@withContext true
            }
            try {
                session.disconnect()
                connecting = null
                true
            } catch (_: Throwable) {
                false
            }
        }

    private suspend fun discardUnstoredSession() {
        // A failed import still owns its SDK until teardown completes. Persist cleanup before
        // reporting NoWallet so another import cannot reuse a session for a different phrase.
        removeLocalWallet(notifyRemoval = false)
    }

    private suspend fun disconnectCurrent(): Boolean {
        val current = sdk ?: return true
        return try {
            paymentLifecycle.detach()
            current.disconnect()
            sdk = null
            true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            if (lifecycleJob.isActive) {
                try {
                    paymentLifecycle.attach(current.paymentClient)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {}
            }
            false
        }
    }

    private fun applyCredentialLoadState(result: CredentialLoadResult) {
        mutableState.value = when (result) {
            CredentialLoadResult.Absent -> {
                hasCredential = false
                WalletAccessState.NoWallet
            }

            CredentialLoadResult.Unavailable -> WalletAccessState.CredentialProblem(
                CredentialProblemKind.UNAVAILABLE
            )

            CredentialLoadResult.Invalidated -> WalletAccessState.CredentialProblem(
                CredentialProblemKind.INVALIDATED
            )

            CredentialLoadResult.Corrupt -> WalletAccessState.CredentialProblem(
                CredentialProblemKind.CORRUPT
            )

            is CredentialLoadResult.Available -> error("Available credentials must be connected")
        }
    }

    private companion object {
        const val CONNECTION_TIMEOUT_MILLIS = 30_000L
    }
}

/** Erases an unmounted wallet without decrypting its credential or connecting the SDK. */
suspend fun clearWalletStorage(vault: CredentialVault, directories: WalletDirectories): Boolean {
    if (!directories.beginReset()) return false
    val credentialDeleted = vault.delete() != CredentialDeleteResult.FAILED
    val dataDeleted = directories.deleteWalletData()
    return credentialDeleted && dataDeleted && directories.finishReset()
}
