package xyz.lilsus.raylsuite.core.ui

import xyz.lilsus.raylsuite.core.ui.resources.NativeStringResource
import xyz.lilsus.raylsuite.core.ui.resources.nativeString

suspend fun nativeBackActionText(): String =
    nativeString(NativeStringResource(table = "CoreUI", key = "action_back"))

data class NativeCredentialText(
    val connect: String,
    val privacy: String,
    val terms: String,
    val show: String,
    val hide: String,
    val paste: String
)

suspend fun nativeCredentialText(): NativeCredentialText = NativeCredentialText(
    connect = nativeString(NativeStringResource("CoreUI", "credential_connect")),
    privacy = nativeString(NativeStringResource(table = "CoreUI", key = "credential_privacy")),
    terms = nativeString(NativeStringResource(table = "CoreUI", key = "credential_terms")),
    show = nativeString(NativeStringResource(table = "CoreUI", key = "credential_show")),
    hide = nativeString(NativeStringResource(table = "CoreUI", key = "credential_hide")),
    paste = nativeString(NativeStringResource(table = "CoreUI", key = "credential_paste"))
)
