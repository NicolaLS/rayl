package xyz.lilsus.raylsuite.core.ui.platform

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScreenCaptureProtectionTest {
    @Test
    fun setsAndClearsFlagForSingleScreen() {
        val tracker = WindowSecureFlagTracker<Any>()
        val window = Any()
        val handle = FakeWindowSecureHandle(secure = false)

        tracker.acquire(window, handle)
        assertTrue(handle.secure)

        tracker.release(window, handle)
        assertFalse(handle.secure)
    }

    @Test
    fun keepsFlagUntilAllNestedProtectionsRelease() {
        val tracker = WindowSecureFlagTracker<Any>()
        val window = Any()
        val handle = FakeWindowSecureHandle(secure = false)

        tracker.acquire(window, handle)
        tracker.acquire(window, handle)
        assertTrue(handle.secure)

        tracker.release(window, handle)
        assertTrue(handle.secure)

        tracker.release(window, handle)
        assertFalse(handle.secure)
    }

    @Test
    fun preservesExternallySetSecureFlag() {
        val tracker = WindowSecureFlagTracker<Any>()
        val window = Any()
        val handle = FakeWindowSecureHandle(secure = true)

        tracker.acquire(window, handle)
        assertTrue(handle.secure)

        tracker.release(window, handle)
        assertTrue(handle.secure)
    }

    @Test
    fun tracksWindowsIndependently() {
        val tracker = WindowSecureFlagTracker<Any>()
        val first = Any()
        val second = Any()
        val firstHandle = FakeWindowSecureHandle(secure = false)
        val secondHandle = FakeWindowSecureHandle(secure = false)

        tracker.acquire(first, firstHandle)
        tracker.acquire(second, secondHandle)

        tracker.release(first, firstHandle)
        assertFalse(firstHandle.secure)
        assertTrue(secondHandle.secure)

        tracker.release(second, secondHandle)
        assertFalse(secondHandle.secure)
    }

    private class FakeWindowSecureHandle(var secure: Boolean) : WindowSecureHandle {
        override fun isSecure(): Boolean = secure
        override fun setSecure() {
            secure = true
        }

        override fun clearSecure() {
            secure = false
        }
    }
}
