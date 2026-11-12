package xyz.lilsus.rayl

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.lilsus.rayl.providers.blink.BlinkIosExperience
import xyz.lilsus.rayl.providers.nwc.NwcIosExperience
import xyz.lilsus.rayl.providers.rayl.RaylEnvironment
import xyz.lilsus.rayl.providers.rayl.RaylIosExperience
import xyz.lilsus.rayl.providers.rayl.integration.pocket.RaylPasskeyAuthenticator
import xyz.lilsus.rayl.providers.rayl.raylRecoveryPhrase
import xyz.lilsus.rayl.providers.rayl.startRaylPocketEarly
import xyz.lilsus.rayl.providers.spark.SparkAppHost
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
import xyz.lilsus.raylsuite.feature.walletmanagement.WalletCardProvider

/** What the iOS root view renders; `wallet` is `rayl` for the pocket, else the card's provider. */
class RaylSnapshot(
    val wallet: String?,
    val raylExperience: RaylIosExperience?,
    val blinkExperience: BlinkIosExperience?,
    val nwcExperience: NwcIosExperience?,
    val sparkExperience: SparkIosExperience?,
    val preparingWallets: Boolean,
    val walletCleanupFailed: Boolean,
    /** Rayl's app onboarding is done; until then the root shows [RaylIosApp.onboardingController]. */
    val onboarded: Boolean,
    val connected: Boolean,
    val canCancelSetup: Boolean,
    val closingSpark: Boolean,
    val sparkCloseFailed: Boolean,
    val colorScheme: String,
    val text: Map<String, String>,
    /** Shown once and briefly, e.g. that an added card was already there; then dismiss it. */
    val notice: String?
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
    private val registry = RaylCards(appSettings)
    private val cards = RaylWalletCardsHost(
        registry = registry,
        scope = scope,
        provider = ::cardProvider,
        clearCard = ::clearSavedCard,
        pocketRecoveryPhrase = { pocketEnvironment?.let { raylRecoveryPhrase(it) } },
        beforeChange = ::unmount
    )

    /** The pocket's network, known once [configureSpark] ran. */
    private var pocketEnvironment: RaylEnvironment? = null
    private val appOnboarding = RaylAppOnboarding(appSettings)
    private val onboardingController by lazy {
        RaylAppOnboardingController(appOnboarding, appSettings, language.preference)
    }
    private val language = createLanguageRepository()
    private val experienceState = MutableStateFlow(RaylExperienceState())
    private var rayl: RaylIosExperience? = null
    private var blink: BlinkIosExperience? = null
    private var nwc: NwcIosExperience? = null
    private var spark: SparkIosExperience? = null

    /** The paying card (`null`: the pocket) and session the mounted experience belongs to. */
    private var mounted: Pair<RaylCard?, Long>? = null

    /** What to do once the Spark session closed: cancel its setup, or apply a card change. */
    private var afterSparkClose: (() -> Unit)? = null
    private var sparkHostFactory: ((WalletStartMode, String?) -> SparkAppHost)? = null
    private var raylFactory: (() -> RaylIosExperience)? = null
    private var cancellations = emptyList<() -> Unit>()

    init {
        // Removing or cancelling a card asks for cleanup before the next wallet mounts.
        scope.launch {
            registry.preparation.collect {
                if (it == WalletPreparation.Cleaning) prepare()
            }
        }
    }

    /**
     * [passkeys] is the Swift passkey bridge, or `null` for the stand-in passkey of automated
     * builds; [debugTools] marks a debug build.
     */
    fun configureSpark(
        environment: SparkEnvironment,
        breezApiKey: String?,
        passkeys: RaylPasskeyAuthenticator?,
        debugTools: Boolean
    ) {
        check(spark == null && rayl == null) { "Configure Spark before mounting its experience" }
        pocketEnvironment = environment.toRaylEnvironment()
        sparkHostFactory = { mode, storage ->
            createIOSAppHost(environment, mode, breezApiKey, storage)
        }
        raylFactory = {
            RaylIosExperience(
                RAYL_POCKET,
                environment.toRaylEnvironment(),
                breezApiKey,
                passkeys,
                debugTools,
                cards
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
                    registry.paying,
                    registry.session,
                    registry.preparation
                ) { paying, session, preparation -> Triple(paying, session, preparation) },
                appOnboarding.step.map { it == AppOnboardingStep.Complete },
                experienceState,
                combine(language.preference, registry.notice) { _, notice -> notice }
            ) {
                    registryState,
                    welcome,
                    experience,
                    notice
                ->
                val (paying, session, preparation) = registryState
                val ready = preparation == WalletPreparation.Ready
                val keys =
                    listOf(
                        "wallet_cleanup_failed",
                        "wallet_cleanup_retry"
                    )
                val text =
                    keys.associateWith { nativeString(NativeStringResource("RaylApp", it)) } +
                        listOf("session_close_failed", "session_close_retry").associateWith {
                            nativeString(NativeStringResource("SparkExperience", "spark_$it"))
                        }
                // Another card, or a new session of the same one, never reuses an experience.
                if (ready && mounted != (paying to session)) {
                    unmount()
                    mounted = paying to session
                }
                // SwiftUI may render this snapshot after removal has cleared the live selection.
                // Keep its experience with it; rendering must never resolve a different session.
                val raylExperience =
                    if (ready && paying == null && welcome) raylExperience() else null
                val blinkExperience =
                    if (ready &&
                        paying?.wallet == ExternalWallet.Blink
                    ) {
                        blinkExperience(paying)
                    } else {
                        null
                    }
                val nwcExperience =
                    if (ready &&
                        paying?.wallet == ExternalWallet.Nwc
                    ) {
                        nwcExperience(paying)
                    } else {
                        null
                    }
                val sparkExperience =
                    if (ready &&
                        paying?.wallet == ExternalWallet.Spark
                    ) {
                        sparkExperience(paying)
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
                    notice?.let(::noticeText)
                )
            }.collect(onChange)
        }
        return { job.cancel() }
    }

    fun onboardingController(): RaylAppOnboardingController = onboardingController

    fun dismissNotice() = registry.dismissNotice()

    private fun noticeText(notice: RaylCardNotice): String = when (notice) {
        is RaylCardNotice.AlreadyAdded -> {
            val existing = notice.cardId?.let { id ->
                registry.cards.value.firstOrNull { it.id == id }
            }
            if (existing == null) {
                nativeString(NativeStringResource("RaylApp", "card_already_pocket"))
            } else {
                nativeString(NativeStringResource("RaylApp", "card_already_added"), existing.name)
            }
        }
    }

    private fun cardProvider(wallet: ExternalWallet): WalletCardProvider = WalletCardProvider(
        id = wallet.id,
        title = nativeString(NativeStringResource("RaylApp", "${wallet.id}_title")),
        subtitle = nativeString(NativeStringResource("RaylApp", "${wallet.id}_body"))
    )

    private fun raylExperience(): RaylIosExperience = rayl ?: checkNotNull(raylFactory) {
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
                experience.observeUseWallet { change ->
                    scope.launch { if (rayl === experience) cards.apply(change) }
                },
                experience.observeErased {
                    scope.launch { if (rayl === experience) registry.restartPocket() }
                }
            )
    }

    private fun blinkExperience(card: RaylCard): BlinkIosExperience = blink
        ?: BlinkIosExperience(
            card.blinkConfiguration(),
            registry.startMode,
            cards
        ).also { experience ->
            blink = experience
            cancellations =
                listOf(
                    experience.observeConnected { connected ->
                        if (blink === experience) {
                            if (connected) registry.connected(card)
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
                        if (it) scope.launch { if (blink === experience) registry.removed(card) }
                    }
                )
        }

    private fun nwcExperience(card: RaylCard): NwcIosExperience = nwc
        ?: NwcIosExperience(card.nwcConfiguration(), registry.startMode, cards).also { experience ->
            nwc = experience
            cancellations =
                listOf(
                    experience.observeConnected { connected ->
                        if (nwc === experience) {
                            if (connected) registry.connected(card)
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
                        if (it) scope.launch { if (nwc === experience) registry.removed(card) }
                    }
                )
        }

    private fun sparkExperience(card: RaylCard): SparkIosExperience = spark ?: SparkIosExperience(
        host = checkNotNull(sparkHostFactory) {
            "Configure the environment before observing Rayl"
        }(registry.startMode, card.sparkConfiguration().storageName),
        configuration = card.sparkConfiguration(),
        cards = cards,
        // Spark releases its SDK session before another wallet mounts.
        onLeave = { change -> spark?.let { closeSpark(it) { cards.apply(change) } } },
        onCancelSetup = ::cancelSetup
    ).also { experience ->
        spark = experience
        cancellations =
            listOf(
                experience.observeConnected { connected ->
                    if (spark === experience) {
                        if (connected) registry.connected(card)
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
                    if (it) scope.launch { if (spark === experience) registry.removed(card) }
                }
            )
    }

    /** Cancels adding a card and goes back to the wallet that paid before. */
    fun cancelSetup() {
        val canCancel = when (registry.paying.value?.wallet) {
            ExternalWallet.Blink -> blink?.canCancelSetup() == true

            ExternalWallet.Nwc -> nwc?.canCancelSetup() == true

            ExternalWallet.Spark -> spark?.canCancelSetup() == true

            // The pocket has no setup to cancel.
            null -> false
        }
        if (!canCancel) return
        val sparkExperience = spark
        if (sparkExperience != null) {
            closeSpark(sparkExperience) { registry.cancelSetup() }
        } else {
            blink?.cancelSetup()
            nwc?.cancelSetup()
        }
    }

    fun retryWalletCleanup() {
        scope.launch { prepare() }
    }

    private suspend fun prepare() {
        registry.prepare(::clearSavedCard, ::purgeUnownedCredentials)
    }

    fun retrySparkClose() {
        val then = afterSparkClose ?: return
        if (experienceState.value.sparkCloseFailed) spark?.let { closeSpark(it, then) }
    }

    private fun closeSpark(experience: SparkIosExperience, then: () -> Unit) {
        if (experienceState.value.closingSpark) return
        afterSparkClose = then
        experienceState.update {
            it.copy(canCancelSetup = false, closingSpark = true, sparkCloseFailed = false)
        }
        scope.launch {
            try {
                experience.close()
                if (spark === experience) {
                    afterSparkClose = null
                    then()
                }
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
        mounted = null
        afterSparkClose = null
        experienceState.update {
            it.copy(
                connected = false,
                canCancelSetup = false,
                closingSpark = false,
                sparkCloseFailed = false
            )
        }
    }
}
