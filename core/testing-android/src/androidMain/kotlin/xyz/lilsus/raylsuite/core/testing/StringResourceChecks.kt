package xyz.lilsus.raylsuite.core.testing

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
import xyz.lilsus.raylsuite.core.ui.resources.LocalizedTextKey

/**
 * Checks an Android mapping from shared text keys to string resources, for every key:
 * - it maps to the resource with the key's own name. iOS looks texts up by that name, so a
 *   different resource means Android shows another text than iOS (a swapped mapping).
 * - the text exists in every app language with the same placeholders, so no translation drops
 *   or invents the detail a message carries.
 */
fun assertStringResourcesFollowKeys(
    keys: Collection<LocalizedTextKey>,
    resourceOf: (LocalizedTextKey) -> Int
) {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val languages = APP_LANGUAGES.associateWith { context.inLanguage(it) }
    val problems = keys.flatMap { key ->
        val id = resourceOf(key)
        val name = context.resources.getResourceEntryName(id)
        buildList {
            if (name != key.key) add("$key maps to R.string.$name, not ${key.key}")
            val placeholders = languages.mapValues { (_, localized) ->
                PLACEHOLDER.findAll(localized.getString(id)).map { it.value }.sorted().toList()
            }
            if (placeholders.values.distinct().size > 1) {
                add("R.string.$name has different placeholders per language: $placeholders")
            }
            languages.forEach { (language, localized) ->
                if (localized.getString(id).isBlank()) add("R.string.$name is blank in $language")
            }
        }
    }
    if (problems.isNotEmpty()) throw AssertionError(problems.joinToString("\n"))
}

private fun Context.inLanguage(tag: String): Context = createConfigurationContext(
    Configuration(resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
)

/** The languages Rayl ships. */
private val APP_LANGUAGES = listOf("en", "de", "es")
private val PLACEHOLDER = Regex("""%(\d+\$)?[sdf]""")
