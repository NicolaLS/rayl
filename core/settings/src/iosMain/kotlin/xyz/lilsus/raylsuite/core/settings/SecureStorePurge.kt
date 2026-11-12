@file:OptIn(
    kotlinx.cinterop.BetaInteropApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class
)

package xyz.lilsus.raylsuite.core.settings

import kotlinx.cinterop.CPointerVarOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.kCFBooleanTrue
import platform.Foundation.CFBridgingRelease
import platform.Foundation.NSString
import platform.Foundation.create
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitAll
import platform.Security.kSecReturnAttributes

/**
 * Deletes every [createSecureSettings] store whose name is one of [prefixes], or one of them
 * followed by `.` and a suffix, unless it is in [keep]. Keychain items survive an uninstall, so
 * stores named after things the app no longer knows would otherwise stay on the device.
 */
fun purgeSecureStores(prefixes: List<String>, keep: Set<String>) {
    val stale = secureStoreNames().filter { name ->
        name !in keep && prefixes.any { name == it || name.startsWith("$it.") }
    }
    stale.forEach { service ->
        val status = withKeychainDictionary(
            mapOf(
                kSecClass to kSecClassGenericPassword,
                kSecAttrService to NSString.create(string = service)
            )
        ) { SecItemDelete(it) }
        check(status == errSecSuccess || status == errSecItemNotFound) {
            "Unable to purge secure values: Keychain status $status"
        }
    }
}

/** The services of this app's generic-password Keychain items. */
private fun secureStoreNames(): Set<String> = withKeychainDictionary(
    mapOf(
        kSecClass to kSecClassGenericPassword,
        kSecMatchLimit to kSecMatchLimitAll,
        kSecReturnAttributes to kCFBooleanTrue
    )
) { query ->
    memScoped {
        val result = alloc<CPointerVarOf<CFTypeRef>>()
        when (val status = SecItemCopyMatching(query, result.ptr)) {
            errSecSuccess -> {
                val items = CFBridgingRelease(result.value) as? List<*> ?: emptyList<Any?>()
                // `kSecAttrService` is the constant string "svce".
                items.mapNotNull { (it as? Map<*, *>)?.get(SERVICE_ATTRIBUTE) as? String }.toSet()
            }

            errSecItemNotFound -> emptySet()

            else -> error("Unable to list secure values: Keychain status $status")
        }
    }
}

private const val SERVICE_ATTRIBUTE = "svce"
