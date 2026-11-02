package xyz.lilsus.rayl.providers.spark

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Education is app-scoped; wallet erasure must not erase this preference. */
internal class SparkOnboardingState(private val settings: Settings) {
    private val mutableCompleted = MutableStateFlow(settings.getBoolean(KEY_COMPLETED, false))
    val completed = mutableCompleted.asStateFlow()

    fun complete() {
        settings.putBoolean(KEY_COMPLETED, true)
        mutableCompleted.value = true
    }

    private companion object {
        const val KEY_COMPLETED = "spark.onboarding.completed"
    }
}
