package xyz.lilsus.rayl.providers.nwc

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class NwcOnboardingState(private val settings: Settings) {
    private val mutableCompleted =
        MutableStateFlow(settings.getBoolean(KEY_COMPLETED, false))

    val completed: StateFlow<Boolean> = mutableCompleted.asStateFlow()

    fun complete() {
        if (mutableCompleted.value) return

        settings.putBoolean(KEY_COMPLETED, true)
        mutableCompleted.value = true
    }

    fun nwcDeepLinkTarget(): NwcNwcDeepLinkTarget = if (completed.value) {
        NwcNwcDeepLinkTarget.Settings
    } else {
        NwcNwcDeepLinkTarget.Onboarding
    }

    fun canHandlePaymentDeepLink(walletConnected: Boolean): Boolean =
        completed.value && walletConnected

    private companion object {
        const val KEY_COMPLETED = "onboarding.completed"
    }
}

internal enum class NwcNwcDeepLinkTarget {
    Onboarding,
    Settings
}
