package xyz.lilsus.rayl.providers.nwc.feature.onboarding

/** The NWC URI entered, pasted or scanned, held until the confirmation step loads it. */
class NwcConnectionDraft {
    var uri: String? = null
        private set

    fun set(uri: String) {
        this.uri = uri
    }

    fun clear() {
        uri = null
    }
}
