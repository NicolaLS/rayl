package xyz.lilsus.rayl

import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.runBlocking
import xyz.lilsus.raylsuite.core.model.LightningAddress
import xyz.lilsus.raylsuite.core.model.StoredAmount
import xyz.lilsus.raylsuite.core.settings.createAppSettings
import xyz.lilsus.raylsuite.feature.paymenthub.DefaultPaymentHubRepository
import xyz.lilsus.raylsuite.feature.paymenthub.HubWidgetDraft
import xyz.lilsus.raylsuite.feature.paymenthub.HubWidgetKind
import xyz.lilsus.raylsuite.feature.paymenthub.LocalHubWidgets

/**
 * Benchmark-only state seeder so Macrobenchmark can measure both fresh-install onboarding and
 * returning-user journeys against the real [MainActivity].
 */
class BenchmarkSeedActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent.getStringExtra(EXTRA_MODE)) {
            MODE_RETURNING -> seedReturningUser()
            else -> clearToFreshInstall()
        }
        setContentView(View(this))
        window.decorView.post { finish() }
    }

    private fun clearToFreshInstall() {
        getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    private fun seedReturningUser() {
        val prefs = getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .clear()
            .putBoolean(KEY_APP_ONBOARDED, true)
            .putBoolean(KEY_POCKET_ONBOARDING_COMPLETE, true)
            .commit()

        val hubRepository = DefaultPaymentHubRepository(createAppSettings(this, PREFERENCES_NAME))
        runBlocking {
            val contacts = listOf(
                "alice@example.com" to "Alice",
                "bob@example.com" to "Bob",
                "cafe@example.com" to "Cafe Luna",
                "maya@example.com" to "Maya",
                "sam@example.com" to "Sam",
                "studio@example.com" to "Studio"
            ).mapNotNull { (raw, title) ->
                LightningAddress.parse(raw)?.let { hubRepository.saveContact(it, title) }
            }

            hubRepository.saveWidget(
                HubWidgetDraft(
                    definitionId = "local.rate",
                    kind = HubWidgetKind.Rate,
                    variant = LocalHubWidgets.Card,
                    currencyCode = "USD"
                )
            )
            hubRepository.saveWidget(
                HubWidgetDraft(
                    definitionId = "local.contacts",
                    kind = HubWidgetKind.Contacts,
                    variant = LocalHubWidgets.Row,
                    title = "Frequent",
                    contactIds = contacts.take(4).map { it.id }
                )
            )
            hubRepository.saveWidget(
                HubWidgetDraft(
                    definitionId = "local.sats",
                    kind = HubWidgetKind.Sats,
                    variant = LocalHubWidgets.Single,
                    currencyCode = "USD"
                )
            )
            contacts.firstOrNull()?.let { alice ->
                hubRepository.saveWidget(
                    HubWidgetDraft(
                        definitionId = "local.shortcut",
                        kind = HubWidgetKind.Shortcut,
                        variant = LocalHubWidgets.Single,
                        title = "Coffee",
                        contactIds = listOf(alice.id),
                        amount = StoredAmount(500, "SAT")
                    )
                )
            }
            hubRepository.saveWidget(
                HubWidgetDraft(
                    definitionId = "local.rate",
                    kind = HubWidgetKind.Rate,
                    variant = LocalHubWidgets.Row,
                    currencyCode = "EUR"
                )
            )
            hubRepository.saveWidget(
                HubWidgetDraft(
                    definitionId = "local.contacts",
                    kind = HubWidgetKind.Contacts,
                    variant = LocalHubWidgets.Card,
                    title = "Contacts",
                    contactIds = contacts.map { it.id }
                )
            )
        }
    }

    private companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_RETURNING = "returning"
        const val PREFERENCES_NAME = "rayl_preferences"
        const val KEY_APP_ONBOARDED = "rayl.onboarded"
        const val KEY_POCKET_ONBOARDING_COMPLETE = "rayl.pocket.onboarding.complete"
    }
}
