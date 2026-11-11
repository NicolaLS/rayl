package xyz.lilsus.raylsuite.core.ui.hero

/**
 * The suite colour tokens the hero draws with, as plain ARGB so a native renderer can read them
 * without Compose. `Color.kt` builds its Compose colours from the same constants.
 */
object RaylHeroColorTokens {
    const val ON_SURFACE_VARIANT_LIGHT = 0xFF425A6BL
    const val ON_SURFACE_VARIANT_DARK = 0xFFB8C7D5L
    const val PRIMARY_LIGHT = 0xFF2B66CFL
    const val PRIMARY_DARK = 0xFF8DB6FFL
    const val TERTIARY_LIGHT = 0xFF006B52L
    const val TERTIARY_DARK = 0xFF4DE2B2L
    const val ERROR_LIGHT = 0xFFBA1A1AL
    const val ERROR_DARK = 0xFFFFB4ABL
}

/** The colour the hero draws for [phase], so every platform renderer agrees on the palette. */
fun raylHeroColorArgb(phase: RaylHeroPhase, darkTheme: Boolean): Long = when (phase) {
    RaylHeroPhase.Ready ->
        if (darkTheme) {
            RaylHeroColorTokens.ON_SURFACE_VARIANT_DARK
        } else {
            RaylHeroColorTokens.ON_SURFACE_VARIANT_LIGHT
        }

    RaylHeroPhase.Acknowledged, RaylHeroPhase.Processing ->
        if (darkTheme) RaylHeroColorTokens.PRIMARY_DARK else RaylHeroColorTokens.PRIMARY_LIGHT

    RaylHeroPhase.Succeeded ->
        if (darkTheme) RaylHeroColorTokens.TERTIARY_DARK else RaylHeroColorTokens.TERTIARY_LIGHT

    RaylHeroPhase.Failed ->
        if (darkTheme) RaylHeroColorTokens.ERROR_DARK else RaylHeroColorTokens.ERROR_LIGHT
}
