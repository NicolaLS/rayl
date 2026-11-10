@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package xyz.lilsus.raylsuite.core.telemetry

import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.useContents
import platform.Foundation.NSLog
import platform.Foundation.NSProcessInfo
import platform.darwin.OS_LOG_CATEGORY_POINTS_OF_INTEREST
import platform.darwin.OS_SIGNPOST_EVENT
import platform.darwin.OS_SIGNPOST_ID_EXCLUSIVE
import platform.darwin.OS_SIGNPOST_ID_INVALID
import platform.darwin.OS_SIGNPOST_ID_NULL
import platform.darwin.OS_SIGNPOST_INTERVAL_BEGIN
import platform.darwin.OS_SIGNPOST_INTERVAL_END
import platform.darwin.__dso_handle
import platform.darwin._os_signpost_emit_with_name_impl
import platform.darwin.os_log_create
import platform.darwin.os_signpost_enabled
import platform.darwin.os_signpost_id_generate
import platform.posix.uname
import platform.posix.utsname

/**
 * iOS may start the process long before the user opens the app (prewarming), and tells it so
 * through this environment variable. Time since process start means nothing then.
 */
internal actual fun processAgeMs(): Long? =
    if (NSProcessInfo.processInfo.environment["ActivePrewarm"] == "1") null else 0L

private val signpostLog = os_log_create(
    subsystem = "xyz.lilsus.raylsuite",
    category = OS_LOG_CATEGORY_POINTS_OF_INTEREST
)

internal actual fun platformTraceEvent(name: String) {
    emitSignpost(OS_SIGNPOST_EVENT, OS_SIGNPOST_ID_EXCLUSIVE, name)
}

internal actual fun platformTraceBegin(name: String): TraceInterval? {
    if (!os_signpost_enabled(signpostLog)) return null
    val identifier = os_signpost_id_generate(signpostLog)
    if (identifier == OS_SIGNPOST_ID_NULL || identifier == OS_SIGNPOST_ID_INVALID) return null
    emitSignpost(OS_SIGNPOST_INTERVAL_BEGIN, identifier, name)
    return IosTraceInterval(name, identifier)
}

private class IosTraceInterval(private val name: String, private val identifier: ULong) :
    TraceInterval {
    private var ended = false

    override fun end() {
        if (ended) return
        ended = true
        emitSignpost(OS_SIGNPOST_INTERVAL_END, identifier, name)
    }
}

private fun emitSignpost(type: UByte, identifier: ULong, name: String) {
    if (!os_signpost_enabled(signpostLog)) return
    memScoped {
        // os_signpost helpers are C macros and are not exposed to Kotlin/Native. Mirror the
        // name-only macro expansion with an empty format buffer and no dynamic payload.
        val formatBuffer = allocArray<UByteVar>(FORMAT_BUFFER_SIZE)
        formatBuffer[0] = 0u
        _os_signpost_emit_with_name_impl(
            dso = __dso_handle.ptr,
            log = signpostLog,
            type = type,
            spid = identifier,
            name = name,
            format = "",
            buf = formatBuffer,
            size = FORMAT_BUFFER_SIZE.toUInt()
        )
    }
}

private const val FORMAT_BUFFER_SIZE = 64

internal actual fun platformLog(tag: String, message: String) {
    NSLog("$tag ${message.replace("%", "%%")}")
}

/** The hardware model, such as `iPhone15,2`. */
internal fun iosDeviceModel(): String = memScoped {
    val info = alloc<utsname>()
    uname(info.ptr)
    info.machine.toKString()
}

internal fun iosMajorVersion(): Int =
    NSProcessInfo.processInfo.operatingSystemVersion.useContents { majorVersion.toInt() }
