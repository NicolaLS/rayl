package xyz.lilsus.rayl.providers.spark.application.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import xyz.lilsus.rayl.providers.spark.application.AppBootstrapConfig
import xyz.lilsus.rayl.providers.spark.application.AppEnvironment
import xyz.lilsus.rayl.providers.spark.application.payment.SparkPayments
import xyz.lilsus.raylsuite.core.model.WalletStartMode

@OptIn(ExperimentalCoroutinesApi::class)
class WalletRemovalTest {
    @Test
    fun failedCleanupRetainsItsMarkerAcrossRetryAndRestart() = runTest {
        val directories = TestDirectories().apply { canDeleteData = false }
        val vault = TestVault()
        val access = newAccess(backgroundScope, vault, directories)

        assertEquals(RemoveWalletResult.RESET_REQUIRED, access.removeWallet())
        assertFalse(access.removed.value)
        assertTrue(directories.pending)
        assertEquals(0, directories.finishAttempts)

        access.retryConnection()
        assertEquals(WalletAccessState.ResetRequired, access.state.value)
        assertFalse(access.removed.value)
        assertTrue(directories.pending)
        assertEquals(2, directories.deleteAttempts)
        assertEquals(0, directories.finishAttempts)

        directories.canDeleteData = true
        val restarted = newAccess(backgroundScope, vault, directories)
        restarted.start()
        runCurrent()

        assertEquals(WalletAccessState.NoWallet, restarted.state.value)
        assertTrue(restarted.removed.value)
        assertFalse(directories.pending)
        assertEquals(3, directories.deleteAttempts)
        assertEquals(3, vault.deleteAttempts)
        assertEquals(1, directories.finishAttempts)
    }

    @Test
    fun markerFailureLeavesWalletDataIntactAndRetryCanBeginCleanup() = runTest {
        val directories = TestDirectories().apply { canBegin = false }
        val vault = TestVault()
        val access = newAccess(backgroundScope, vault, directories)

        assertEquals(RemoveWalletResult.RESET_REQUIRED, access.removeWallet())
        access.retryConnection()
        assertEquals(WalletAccessState.ResetRequired, access.state.value)
        assertFalse(access.removed.value)
        assertFalse(directories.pending)
        assertEquals(0, vault.deleteAttempts)
        assertEquals(0, directories.deleteAttempts)
        assertEquals(0, directories.finishAttempts)

        directories.canBegin = true
        access.retryConnection()

        assertEquals(WalletAccessState.NoWallet, access.state.value)
        assertTrue(access.removed.value)
        assertFalse(directories.pending)
        assertEquals(1, vault.deleteAttempts)
        assertEquals(1, directories.deleteAttempts)
        assertEquals(1, directories.finishAttempts)
    }

    private fun newAccess(scope: CoroutineScope, vault: CredentialVault, directories: WalletDirectories): DefaultWalletAccess {
        val payments = SparkPayments()
        return DefaultWalletAccess(
            bootstrapConfig = AppBootstrapConfig(AppEnvironment.DEBUG),
            vault = vault,
            directories = directories,
            connector = SparkSdkConnector { _, _, _, _ -> error("Cleanup must not reconnect the SDK") },
            payments = payments,
            paymentLifecycle = payments,
            applicationScope = scope,
            startMode = WalletStartMode.Restore
        )
    }
}

private class TestVault : CredentialVault {
    var deleteAttempts = 0

    override suspend fun load(): CredentialLoadResult = error("Cleanup must not load credentials")

    override suspend fun store(credential: WalletCredential): CredentialStoreResult = error("Cleanup must not store credentials")

    override suspend fun delete(): CredentialDeleteResult {
        deleteAttempts += 1
        return CredentialDeleteResult.DELETED
    }
}

private class TestDirectories : WalletDirectories {
    override val sdkDirectory = "unused-test-sdk-directory"

    override fun sdkStorageIsEmpty(): Boolean = false
    var pending = false
    var canBegin = true
    var canDeleteData = true
    var deleteAttempts = 0
    var finishAttempts = 0

    override fun beginReset(): Boolean {
        if (canBegin) pending = true
        return canBegin
    }

    override fun resetPending(): Boolean = pending

    override fun deleteWalletData(): Boolean {
        deleteAttempts += 1
        return canDeleteData
    }

    override fun finishReset(): Boolean {
        finishAttempts += 1
        pending = false
        return true
    }
}
