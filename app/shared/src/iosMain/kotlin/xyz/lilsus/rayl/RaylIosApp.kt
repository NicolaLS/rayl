package xyz.lilsus.rayl

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.lilsus.rayl.providers.blink.BlinkDeepLinks
import xyz.lilsus.rayl.providers.blink.BlinkIosExperience
import xyz.lilsus.rayl.providers.nwc.NwcDeepLinks
import xyz.lilsus.rayl.providers.nwc.NwcIosExperience
import xyz.lilsus.rayl.providers.rayl.RaylIosExperience
import xyz.lilsus.rayl.providers.rayl.RaylPaymentLinks
import xyz.lilsus.rayl.providers.rayl.RaylWalletOption
import xyz.lilsus.rayl.providers.rayl.RaylWalletRequests
import xyz.lilsus.rayl.providers.rayl.startRaylPocketEarly
import xyz.lilsus.rayl.providers.spark.SparkAppHost
import xyz.lilsus.rayl.providers.spark.SparkDeepLinks
import xyz.lilsus.rayl.providers.spark.SparkEnvironment
import xyz.lilsus.rayl.providers.spark.SparkIosExperience
import xyz.lilsus.rayl.providers.spark.createIOSAppHost
import xyz.lilsus.raylsuite.core.model.ThemePreference
import xyz.lilsus.raylsuite.core.model.WalletStartMode
import xyz.lilsus.raylsuite.core.settings.createAppSettings
import xyz.lilsus.raylsuite.core.ui.resources.NativeStringResource
import xyz.lilsus.raylsuite.core.ui.resources.nativeString
import xyz.lilsus.raylsuite.feature.languagesettings.createLanguageRepository
import xyz.lilsus.raylsuite.feature.themesettings.DefaultThemePreferences

/** What the iOS root view renders; `wallet` is `rayl` for Rayl's own wallet. */
class RaylSnapshot(
    val wallet: String?,
    val raylExperience: RaylIosExperience?,
    val blinkExperience: BlinkIosExperience?,
    val nwcExperience: NwcIosExperience?,
    val sparkExperience: SparkIosExperience?,
    val preparingWallets: Boolean,
    val walletCleanupFailed: Boolean,
    val welcomeCompleted: Boolean,
    val connected: Boolean,
    val canCancelSetup: Boolean,
    val closingSpark: Boolean,
    val sparkCloseFailed: Boolean,
    val colorScheme: String,
    val text: Map<String, String>,
    val message: String?
)

private data class RaylExperienceState(
    val connected: Boolean = false,
    val canCancelSetup: Boolean = false,
    val closingSpark: Boolean = false,
    val sparkCloseFailed: Boolean = false,
    val colorScheme: String = "system"
)

object RaylIosApp {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val appSettings = createAppSettings()
    private val selection = RaylSelection(appSettings)
    private val language = createLanguageRepository()
    private val experienceState = MutableStateFlow(RaylExperienceState())
    private val messageKey = MutableStateFlow<String?>(null)
    private var rayl: RaylIosExperience? = null
    private var blink: BlinkIosExperience? = null
    private var nwc: NwcIosExperience? = null
    private var spark: SparkIosExperience? = null
    private var sparkHostFactory: ((WalletStartMode) -> SparkAppHost)? = null
    private var raylFactory: (() -> RaylIosExperience)? = null
    private var cancellations = emptyList<() -> Unit>()

    // A connection link that waits for Rayl's wallet to close before its setup starts.
    private var pendingNwcLink: String? = null

    init {
        retryWalletCleanup()
        scope.launch {
            RaylWalletRequests.rejections.collect {
                pendingNwcLink = null
                messageKey.value = "wallet_switch_busy"
            }
        }
        scope.launch {
            RaylDeepLinks.events.collect { uri ->
                selection.preparation.first { it == WalletPreparation.Ready }
                val selected = selection.external.value
                val hasConnection = when (selected) {
                    ExternalWallet.Blink -> blinkExperience()?.isConnected() == true

                    ExternalWallet.Nwc -> nwcExperience()?.isConnected() == true

                    // FIXME: Spark cold-start isConnected() is false while async restore is still
                    // Loading, which can reject a cold-start payment deep link.
                    ExternalWallet.Spark -> sparkExperience()?.isConnected() == true

                    // Rayl's wallet queues links while it connects, or until the welcome is done.
                    null -> true
                }
                if (uri.substringBefore(':').equals("nostr+walletconnect", true)) {
                    if (selected == null) {
                        selection.completeWelcome()
                        pendingNwcLink = uri
                        RaylWalletRequests.request(ExternalWallet.Nwc.id)
                    } else if (selected == ExternalWallet.Nwc && !hasConnection) {
                        NwcDeepLinks.emit(uri)
                    } else {
                        messageKey.value = "connection_exists"
                    }
                } else if (!hasConnection) {
                    messageKey.value = "connect_to_pay"
                } else {
                    when (selected) {
                        null -> RaylPaymentLinks.emit(uri)
                        ExternalWallet.Blink -> BlinkDeepLinks.emit(uri)
                        ExternalWallet.Nwc -> NwcDeepLinks.emit(uri)
                        ExternalWallet.Spark -> SparkDeepLinks.emit(uri)
                    }
                }
            }
        }
    }

    fun configureSpark(environment: SparkEnvironment, breezApiKey: String?) {
        check(spark == null && rayl == null) { "Configure Spark before mounting its experience" }
        sparkHostFactory = { mode -> createIOSAppHost(environment, mode, breezApiKey) }
        raylFactory = {
            RaylIosExperience(
                RAYL_POCKET,
                environment.toRaylEnvironment(),
                breezApiKey,
                ::externalWalletOptions
            )
        }
        if (usesRaylWallet(appSettings)) {
            startRaylPocketEarly(environment.toRaylEnvironment(), breezApiKey)
        }
    }

    fun observe(onChange: (RaylSnapshot) -> Unit): () -> Unit {
        val job = scope.launch {
            combine(
                combine(
                    selection.external,
                    selection.session,
                    selection.preparation
                ) { external, _, preparation -> external to preparation },
                selection.welcomeCompleted,
                experienceState,
                language.preference,
                messageKey
            ) {
                    selectionState,
                    welcome,
                    experience,
                    _,
                    message
                ->
                val (external, preparation) = selectionState
                val ready = preparation == WalletPreparation.Ready
                val keys =
                    listOf(
                        "welcome_title",
                        "welcome_body",
                        "get_started",
                        "close",
                        "wallet_cleanup_failed",
                        "wallet_cleanup_retry"
                    )
                val text =
                    keys.associateWith { nativeString(NativeStringResource("RaylApp", it)) } +
                        listOf("session_close_failed", "session_close_retry").associateWith {
                            nativeString(NativeStringResource("SparkExperience", "spark_$it"))
                        } +
                        (
                            "cancel_setup" to nativeString(
                                NativeStringResource(
                                    "WalletManagement",
                                    "settings_manage_wallet_cancel"
                                )
                            )
                            )
                // SwiftUI may render this snapshot after removal has cleared the live selection.
                // Keep its experience with it; rendering must never resolve a different session.
                val raylExperience = if (ready &&
                    external == null &&
                    welcome
                ) {
                    raylExperience()
                } else {
                    null
                }
                val blinkExperience = if (ready &&
                    external == ExternalWallet.Blink
                ) {
                    blinkExperience()
                } else {
                    null
                }
                val nwcExperience = if (ready && external == ExternalWallet.Nwc) {
                    nwcExperience()
                } else {
                    null
                }
                val sparkExperience = if (ready &&
                    external == ExternalWallet.Spark
                ) {
                    sparkExperience()
                } else {
                    null
                }
                val mountedWallet = when {
                    raylExperience != null -> "rayl"
                    blinkExperience != null -> "blink"
                    nwcExperience != null -> "nwc"
                    sparkExperience != null -> "spark"
                    else -> null
                }
                RaylSnapshot(
                    mountedWallet,
                    raylExperience,
                    blinkExperience,
                    nwcExperience,
                    sparkExperience,
                    !ready,
                    preparation == WalletPreparation.Failed,
                    welcome,
                    mountedWallet != null && experience.connected,
                    mountedWallet != null && experience.canCancelSetup &&
                        !experience.closingSpark && !experience.sparkCloseFailed,
                    experience.closingSpark,
                    experience.sparkCloseFailed,
                    if (mountedWallet == null) {
                        // The wallet owns preference writes; reload after it unmounts.
                        when (DefaultThemePreferences(appSettings).current()) {
                            ThemePreference.Light -> "light"
                            ThemePreference.Dark -> "dark"
                            ThemePreference.System -> "system"
                        }
                    } else {
                        experience.colorScheme
                    },
                    text,
                    message?.let {
                        nativeString(NativeStringResource("RaylApp", it))
                    }
                )
            }.collect(onChange)
        }
        return { job.cancel() }
    }

    fun completeWelcome() = selection.completeWelcome()
    fun dismissMessage() {
        messageKey.value = null
    }

    /** Starts setup of an external wallet once Rayl's wallet has closed its session for it. */
    private fun useWallet(id: String) {
        val wallet = ExternalWallet.fromId(id)
        val link = pendingNwcLink
        pendingNwcLink = null
        if (wallet == null ||
            selection.external.value != null ||
            selection.preparation.value != WalletPreparation.Ready
        ) {
            return
        }
        unmount()
        RaylPaymentLinks.clear()
        selection.choose(wallet)
        if (wallet == ExternalWallet.Nwc) link?.let(NwcDeepLinks::emit)
    }

    private fun externalWalletOptions(): List<RaylWalletOption> =
        ExternalWallet.entries.map { wallet ->
            RaylWalletOption(
                id = wallet.id,
                title = nativeString(NativeStringResource("RaylApp", "${wallet.id}_title")),
                subtitle = nativeString(NativeStringResource("RaylApp", "${wallet.id}_body"))
            )
        }

    private fun raylExperience(): RaylIosExperience? {
        if (selection.external.value != null || !selection.welcomeCompleted.value) return null
        return rayl ?: checkNotNull(raylFactory) {
            "Configure the environment before observing Rayl"
        }().also { experience ->
            rayl = experience
            cancellations =
                listOf(
                    experience.observeConnected { connected ->
                        if (rayl === experience) {
                            experienceState.update { it.copy(connected = connected) }
                        }
                    },
                    experience.observeTheme { colorScheme ->
                        if (rayl === experience) {
                            experienceState.update { it.copy(colorScheme = colorScheme) }
                        }
                    },
                    // The pocket closes but is not erased; see SPEC-wallet-lifecycle.
                    experience.observeUseWallet { id ->
                        scope.launch { if (rayl === experience) useWallet(id) }
                    },
                    experience.observeErased {
                        scope.launch {
                            if (rayl === experience) {
                                unmount()
                                selection.restartRaylWallet()
                            }
                        }
                    }
                )
        }
    }

    private fun blinkExperience(): BlinkIosExperience? {
        // combine can still deliver a prior selection while the latest state propagates.
        if (selection.external.value != ExternalWallet.Blink) return null
        return blink ?: BlinkIosExperience(RAYL_BLINK, selection.startMode).also { experience ->
            blink = experience
            cancellations =
                listOf(
                    experience.observeConnected { connected ->
                        if (blink === experience) {
                            if (connected) selection.connected(ExternalWallet.Blink)
                            experienceState.update { it.copy(connected = connected) }
                        }
                    },
                    experience.observeCanCancelSetup { canCancel ->
                        if (blink === experience) {
                            experienceState.update { it.copy(canCancelSetup = canCancel) }
                        }
                    },
                    experience.observeTheme { colorScheme ->
                        if (blink === experience) {
                            experienceState.update { it.copy(colorScheme = colorScheme) }
                        }
                    },
                    experience.observeRemoved {
                        if (it) scope.launch { if (blink === experience) backToRayl() }
                    }
                )
        }
    }

    private fun nwcExperience(): NwcIosExperience? {
        if (selection.external.value != ExternalWallet.Nwc) return null
        return nwc ?: NwcIosExperience(RAYL_NWC, selection.startMode).also { experience ->
            nwc = experience
            cancellations =
                listOf(
                    experience.observeConnected { connected ->
                        if (nwc === experience) {
                            if (connected) selection.connected(ExternalWallet.Nwc)
                            experienceState.update { it.copy(connected = connected) }
                        }
                    },
                    experience.observeCanCancelSetup { canCancel ->
                        if (nwc === experience) {
                            experienceState.update { it.copy(canCancelSetup = canCancel) }
                        }
                    },
                    experience.observeTheme { colorScheme ->
                        if (nwc === experience) {
                            experienceState.update { it.copy(colorScheme = colorScheme) }
                        }
                    },
                    experience.observeRemoved {
                        if (it) scope.launch { if (nwc === experience) backToRayl() }
                    }
                )
        }
    }

    private fun sparkExperience(): SparkIosExperience? {
        if (selection.external.value != ExternalWallet.Spark) return null
        return spark ?: SparkIosExperience(
            host = checkNotNull(sparkHostFactory) {
                "Configure the environment before observing Rayl"
            }(selection.startMode),
            configuration = RAYL_SPARK
        ).also { experience ->
            spark = experience
            cancellations =
                listOf(
                    experience.observeConnected { connected ->
                        if (spark === experience) {
                            if (connected) selection.connected(ExternalWallet.Spark)
                            experienceState.update { it.copy(connected = connected) }
                        }
                    },
                    experience.observeCanCancelSetup { canCancel ->
                        if (spark === experience) {
                            experienceState.update { it.copy(canCancelSetup = canCancel) }
                        }
                    },
                    experience.observeTheme { colorScheme ->
                        if (spark === experience) {
                            experienceState.update { it.copy(colorScheme = colorScheme) }
                        }
                    },
                    experience.observeRemoved {
                        if (it) scope.launch { if (spark === experience) backToRayl() }
                    }
                )
        }
    }

    /** Cancels external wallet setup and goes back to Rayl's wallet. */
    fun cancelSetup() {
        val canCancel = when (selection.external.value) {
            ExternalWallet.Blink -> blink?.canCancelSetup() == true

            ExternalWallet.Nwc -> nwc?.canCancelSetup() == true

            ExternalWallet.Spark -> spark?.canCancelSetup() == true

            // Rayl's wallet has no setup to cancel.
            null -> false
        }
        if (!canCancel) return
        val sparkExperience = spark
        if (sparkExperience != null) {
            closeSparkSetup(sparkExperience)
        } else {
            blink?.cancelSetup()
            nwc?.cancelSetup()
        }
    }

    fun retryWalletCleanup() {
        scope.launch { selection.prepare(::clearSavedWallet) }
    }

    fun retrySparkClose() {
        if (experienceState.value.sparkCloseFailed) spark?.let(::closeSparkSetup)
    }

    private fun closeSparkSetup(experience: SparkIosExperience) {
        if (experienceState.value.closingSpark) return
        experienceState.update {
            it.copy(canCancelSetup = false, closingSpark = true, sparkCloseFailed = false)
        }
        scope.launch {
            try {
                experience.close()
                if (spark === experience) backToRayl()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (spark === experience) {
                    experienceState.update {
                        it.copy(closingSpark = false, sparkCloseFailed = true)
                    }
                }
            }
        }
    }

    /** Releases the mounted wallet's session objects before another one mounts. */
    private fun unmount() {
        cancellations.forEach { it() }
        cancellations = emptyList()
        rayl?.clear()
        blink?.clear()
        nwc?.clear()
        spark?.clear()
        rayl = null
        blink = null
        nwc = null
        spark = null
        experienceState.update {
            it.copy(
                connected = false,
                canCancelSetup = false,
                closingSpark = false,
                sparkCloseFailed = false
            )
        }
        messageKey.value = null
    }

    /** After the external wallet was removed or its setup cancelled. */
    private fun backToRayl() {
        unmount()
        RaylDeepLinks.clear()
        BlinkDeepLinks.clear()
        NwcDeepLinks.clear()
        RaylPaymentLinks.clear()
        SparkDeepLinks.clear()
        selection.clear()
        retryWalletCleanup()
    }
}
