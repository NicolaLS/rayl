package xyz.lilsus.raylsuite.core.ui.platform

import android.view.Window
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.window.SecureFlagPolicy
import java.util.WeakHashMap

/**
 * Whether screens showing or accepting secrets set `FLAG_SECURE` on their Android window.
 * Enabled in release builds so recovery phrases, API keys, and connection URIs stay out of
 * screenshots and Recents snapshots, while normal screens and debug/E2E builds stay capturable.
 */
val LocalScreenCaptureProtectionEnabled = staticCompositionLocalOf { true }

/**
 * Sets `FLAG_SECURE` on the host activity's window while this composable is active, if
 * [LocalScreenCaptureProtectionEnabled] and [enabled] are both true. Reference-counted per window
 * so nested protected composables do not clear the flag while another still needs it.
 */
@Composable
fun ScreenCaptureProtection(enabled: Boolean = true) {
    val active = enabled && LocalScreenCaptureProtectionEnabled.current
    val window = LocalActivity.current?.window
    if (!active || window == null) return
    DisposableEffect(window) {
        val handle = AndroidWindowSecureHandle(window)
        windowSecureFlagTracker.acquire(window, handle)
        onDispose {
            windowSecureFlagTracker.release(window, handle)
        }
    }
}

/** Secure flag policy for `Dialog` / `AlertDialog` windows displaying secrets. */
@Composable
fun secureDialogFlagPolicy(): SecureFlagPolicy = if (LocalScreenCaptureProtectionEnabled.current) {
    SecureFlagPolicy.SecureOn
} else {
    SecureFlagPolicy.Inherit
}

internal interface WindowSecureHandle {
    fun isSecure(): Boolean
    fun setSecure()
    fun clearSecure()
}

private class AndroidWindowSecureHandle(private val window: Window) : WindowSecureHandle {
    override fun isSecure(): Boolean =
        (window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0

    override fun setSecure() {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun clearSecure() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}

/** Reference-counts `FLAG_SECURE` acquisitions per window and preserves externally set flags. */
internal class WindowSecureFlagTracker<K : Any> {
    private class Entry(var count: Int, val alreadySecure: Boolean)

    private val entries = WeakHashMap<K, Entry>()

    fun acquire(key: K, handle: WindowSecureHandle) {
        val existing = entries[key]
        if (existing != null) {
            existing.count += 1
            return
        }
        val alreadySecure = handle.isSecure()
        entries[key] = Entry(count = 1, alreadySecure = alreadySecure)
        if (!alreadySecure) handle.setSecure()
    }

    fun release(key: K, handle: WindowSecureHandle) {
        val existing = entries[key] ?: return
        existing.count -= 1
        if (existing.count > 0) return
        entries.remove(key)
        if (!existing.alreadySecure) handle.clearSecure()
    }
}

private val windowSecureFlagTracker = WindowSecureFlagTracker<Window>()
