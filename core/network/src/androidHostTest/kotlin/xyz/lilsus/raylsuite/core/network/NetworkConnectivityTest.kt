package xyz.lilsus.raylsuite.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetworkCapabilities

/**
 * Wallets refuse to send while Android reports no usable internet (a definite "no network" error
 * instead of a payment whose outcome is unknown). Usable means: internet, validated by Android,
 * so a Wi-Fi behind a captive portal counts as offline.
 */
@RunWith(RobolectricTestRunner::class)
class NetworkConnectivityTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val connectivity = shadowOf(context.getSystemService(ConnectivityManager::class.java))

    @Before
    fun initialize() = initializeNetworkConnectivity(context)

    @Test
    fun aValidatedInternetConnectionIsAvailable() {
        activeNetworkWith(
            NetworkCapabilities.NET_CAPABILITY_INTERNET,
            NetworkCapabilities.NET_CAPABILITY_VALIDATED
        )

        assertTrue(createNetworkConnectivity().isNetworkAvailable())
    }

    @Test
    fun aNetworkAndroidHasNotValidatedIsNotAvailable() {
        // A captive portal or a network without a route to the internet.
        activeNetworkWith(NetworkCapabilities.NET_CAPABILITY_INTERNET)

        assertFalse(createNetworkConnectivity().isNetworkAvailable())
    }

    @Test
    fun noActiveNetworkIsNotAvailable() {
        connectivity.setDefaultNetworkActive(false)
        connectivity.setActiveNetworkInfo(null)

        assertFalse(createNetworkConnectivity().isNetworkAvailable())
    }

    private fun activeNetworkWith(vararg capabilities: Int) {
        val network = context.getSystemService(ConnectivityManager::class.java).activeNetwork
            ?: error("Robolectric has no default network")
        val networkCapabilities = ShadowNetworkCapabilities.newInstance()
        capabilities.forEach { shadowOf(networkCapabilities).addCapability(it) }
        connectivity.setNetworkCapabilities(network, networkCapabilities)
    }
}
