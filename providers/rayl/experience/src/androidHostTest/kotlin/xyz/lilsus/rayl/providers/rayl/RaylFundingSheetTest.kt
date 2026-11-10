package xyz.lilsus.rayl.providers.rayl

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Android's add funds sheet: the invoice can always be scanned or copied, so a wallet app on the
 * phone is never the only way to pay it, and opening one always asks which app to use.
 */
@RunWith(RobolectricTestRunner::class)
class RaylFundingSheetTest {
    @get:Rule
    val compose = createComposeRule()

    private val application: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun withoutAWalletAppTheInvoiceIsOnlyShownAndCopied() {
        showInvoice()

        compose.onNodeWithTag("rayl_funding_qr").assertExists()
        compose.onNodeWithText("Copy invoice").assertExists()
        compose.onNodeWithText("Open in wallet").assertDoesNotExist()
    }

    @Test
    fun aWalletAppIsOfferedNextToTheInvoiceAndOpenedThroughTheChooser() {
        installWalletApp()
        showInvoice()

        compose.onNodeWithTag("rayl_funding_qr").assertExists()
        compose.onNodeWithText("Copy invoice").assertExists()
        compose.onNodeWithText("Open in wallet").performClick()

        val started = shadowOf(application).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, started.action)
        val target = started.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        assertEquals("lightning:lnbcrt1", target?.dataString)
    }

    private fun showInvoice() {
        val funding = RaylFunding(
            createInvoice = { "lnbcrt1" },
            receipts = emptyFlow(),
            description = "Rayl",
            scope = CoroutineScope(Dispatchers.Unconfined)
        )
        funding.request()
        compose.setContent { RaylFundingSheet(funding) }
    }

    private fun installWalletApp() {
        val wallet = ComponentName("com.example.wallet", "com.example.wallet.PayActivity")
        val packages = shadowOf(application.packageManager)
        packages.addActivityIfNotPresent(wallet)
        packages.addIntentFilterForActivity(
            wallet,
            IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addDataScheme("lightning")
            }
        )
    }
}
