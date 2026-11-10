package xyz.lilsus.raylsuite.core.telemetry

import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.tracing.Trace
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

// Host tests run this code on a JVM, where the Android framework is a stub that throws.
private val onDevice: Boolean = try {
    SystemClock.elapsedRealtime()
    true
} catch (_: RuntimeException) {
    false
}

internal actual fun processAgeMs(): Long? =
    if (onDevice) SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime() else 0L

internal actual fun platformTraceEvent(name: String) {
    if (!onDevice || !Trace.isEnabled()) return
    Trace.beginSection(name)
    Trace.endSection()
}

private val nextCookie = AtomicInteger()

internal actual fun platformTraceBegin(name: String): TraceInterval? {
    if (!onDevice || !Trace.isEnabled()) return null
    val cookie = nextCookie.incrementAndGet()
    Trace.beginAsyncSection(name, cookie)
    return AndroidTraceInterval(name, cookie)
}

private class AndroidTraceInterval(private val name: String, private val cookie: Int) :
    TraceInterval {
    private val ended = AtomicBoolean(false)

    override fun end() {
        if (ended.compareAndSet(false, true)) Trace.endAsyncSection(name, cookie)
    }
}

internal actual fun platformLog(tag: String, message: String) {
    if (onDevice) Log.i(tag, message)
}
