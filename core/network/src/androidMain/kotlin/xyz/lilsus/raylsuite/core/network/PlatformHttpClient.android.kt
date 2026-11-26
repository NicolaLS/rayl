package xyz.lilsus.raylsuite.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.OkHttpClient

/**
 * Shared engine client so Ktor clients reuse one connection pool, TLS session cache, and
 * dispatcher instead of spinning up separate thread pools per feature. Ktor does not shut down a
 * `preconfigured` OkHttpClient when an individual [HttpClient] closes.
 */
private val sharedOkHttpClient: OkHttpClient by lazy { OkHttpClient() }

internal actual fun platformHttpClient(): HttpClient = HttpClient(OkHttp) {
    engine {
        preconfigured = sharedOkHttpClient
    }
}
