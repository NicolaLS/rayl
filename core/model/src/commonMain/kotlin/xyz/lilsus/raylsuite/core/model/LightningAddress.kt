package xyz.lilsus.raylsuite.core.model

data class LightningAddress(val username: String, val domain: String, val tag: String? = null) {
    val full: String
        get() =
            buildString {
                append(username)
                tag?.let { append('+').append(it) }
                append('@')
                append(domain)
            }

    fun isSameAddressAs(other: LightningAddress): Boolean =
        full.equals(other.full, ignoreCase = true)

    companion object {
        fun parse(raw: String): LightningAddress? {
            val candidate =
                raw
                    .trim()
                    .removePrefixIgnoringCase("lightning:")
                    .unwrapAddressUrl()
                    ?: return null
            if (candidate.any { it == '/' || it == '?' || it == '#' }) return null
            if (candidate.count { it == '@' } != 1) return null

            // LUD-16 user names are lowercase; phone keyboards capitalise the first letter.
            val userPart = candidate.substringBefore('@').lowercase()
            val domain = candidate.substringAfter('@').lowercase()
            if (!userPart.isValidUserPart() || !domain.isValidDomain()) return null

            val tagIndex = userPart.indexOf('+')
            val username = userPart.substring(0, tagIndex.takeIf { it >= 0 } ?: userPart.length)
            val tag =
                tagIndex
                    .takeIf { it >= 0 }
                    ?.let { userPart.substring(it + 1).ifEmpty { null } }
            if (username.isEmpty()) return null

            return LightningAddress(
                username = username,
                domain = domain,
                tag = tag
            )
        }
    }
}

private fun String.removePrefixIgnoringCase(prefix: String): String =
    if (startsWith(prefix, ignoreCase = true)) {
        substring(prefix.length)
    } else {
        this
    }

private fun String.unwrapAddressUrl(): String? {
    if (!startsWith("http://", ignoreCase = true) &&
        !startsWith("https://", ignoreCase = true)
    ) {
        return this
    }
    val authorityAndPath = substringAfter("://", missingDelimiterValue = "")
    if (authorityAndPath.isEmpty() ||
        authorityAndPath.contains('?') ||
        authorityAndPath.contains('#')
    ) {
        return null
    }
    val authority = authorityAndPath.substringBefore('/')
    val path = authorityAndPath.substringAfter('/', missingDelimiterValue = "")
    return authority.takeIf { path.isEmpty() }
}

private fun String.isValidUserPart(): Boolean = isNotEmpty() &&
    all { character ->
        character in 'a'..'z' ||
            character in '0'..'9' ||
            character == '-' ||
            character == '_' ||
            character == '.' ||
            character == '+'
    }

private fun String.isValidDomain(): Boolean {
    if (length > MAX_DOMAIN_LENGTH) return false
    val labels = split('.')
    if (labels.size < 2) return false
    if (labels.last().none { it in 'a'..'z' }) return false
    return labels.all { label ->
        label.isNotEmpty() &&
            label.length <= MAX_DOMAIN_LABEL_LENGTH &&
            !label.startsWith('-') &&
            !label.endsWith('-') &&
            label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }
    }
}

private const val MAX_DOMAIN_LENGTH = 253
private const val MAX_DOMAIN_LABEL_LENGTH = 63
