package xyz.lilsus.rayl.providers.spark

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import xyz.lilsus.raylsuite.core.model.WalletStartMode

/** Education is app-scoped; wallet erasure must not erase this preference. */
internal class SparkOnboardingState(private val settings: Settings, startMode: WalletStartMode) {
    private val mutableCompleted = MutableStateFlow(
        // Previously configured Spark wallets completed education before connecting.
        settings.getBoolean(KEY_COMPLETED, startMode == WalletStartMode.Restore)
    )
    val completed = mutableCompleted.asStateFlow()

    init {
        // Persist false before a new connection, so interrupted education resumes on restore.
        settings.putBoolean(KEY_COMPLETED, completed.value)
    }

    fun complete() {
        settings.putBoolean(KEY_COMPLETED, true)
        mutableCompleted.value = true
    }

    private companion object {
        const val KEY_COMPLETED = "spark.onboarding.completed"
    }
}
