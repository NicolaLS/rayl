package xyz.lilsus.raylsuite.feature.walletmanagement

import android.content.ClipData
import android.content.ClipboardManager
import android.os.PersistableBundle
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CurrencyBitcoin
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationEventHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import xyz.lilsus.raylsuite.core.ui.components.AppSettingsRow
import xyz.lilsus.raylsuite.core.ui.components.AppSettingsRowDefaults
import xyz.lilsus.raylsuite.core.ui.components.BackIconButton
import xyz.lilsus.raylsuite.core.ui.components.RaylMark

/** The host's marks for each provider (`rayl` for the pocket), by [WalletCard.provider]. */
val LocalWalletCardIcons = staticCompositionLocalOf<Map<String, Int>> { emptyMap() }

/**
 * A wallet's Settings tab with the shared wallet screens. [settings] renders the wallet's own
 * Settings with the wallet block it receives at the top, the same for every wallet: the active
 * wallet, which opens the list to pick another or add one; its balance with Add funds ([balance];
 * wallets without one keep [WalletBalanceRow]'s placeholder); and Details, its page, which holds
 * [payingContent], the settings only the active wallet has. Choosing another wallet calls [onChange], which must close this wallet's
 * session before it applies the change to [host]. [onRemovePaying] removes the active card through
 * its provider. While [busy], e.g. a payment is submitted, the active wallet cannot change.
 */
@Composable
fun WalletCardsSettings(
    host: WalletCardsHost,
    busy: Boolean,
    onChange: (WalletCardChange) -> Unit,
    onRemovePaying: () -> Unit,
    balance: (@Composable () -> Unit)? = null,
    payingContent: @Composable () -> Unit = {},
    settings: @Composable (walletBlock: @Composable () -> Unit) -> Unit
) {
    val cards by host.cards.collectAsStateWithLifecycle()
    var destination by rememberSaveable {
        // Back from adding a card lands on the providers it was chosen from.
        mutableStateOf(if (host.reopensAddCard()) ADD.also { host.reopenedAddCard() } else null)
    }
    NavigationEventHandler(
        state = rememberNavigationEventState(currentInfo = CardsNavigationInfo(destination)),
        isForwardEnabled = false,
        isBackEnabled = destination != null,
        onBackCompleted = {
            destination = if (destination == LIST || destination == null) null else LIST
        }
    )
    val toList = { destination = LIST }
    when (val current = destination) {
        null -> settings {
            WalletBlock(
                cards = cards,
                balance = balance ?: { WalletBalanceRow(balance = null, onAddFunds = null) },
                onWallets = toList,
                onDetails = { destination = DETAILS }
            )
        }

        LIST -> WalletCardsList(
            cards = cards,
            busy = busy,
            onBack = { destination = null },
            onActivate = { onChange(WalletCardChange.PayWith(it)) },
            onAdd = { destination = ADD }
        )

        ADD -> WalletCardProviders(
            providers = cards.addable,
            busy = busy,
            onBack = toList,
            onAdd = { onChange(WalletCardChange.Add(it)) }
        )

        else -> WalletCardPage(
            host = host,
            card = cards.paying,
            busy = busy,
            onBack = { destination = null },
            onRemovePaying = onRemovePaying,
            payingContent = payingContent
        )
    }
}

/**
 * The balance row of the wallet block, the same for every wallet. Without [onAddFunds] it is a
 * placeholder: the wallet has no balance or funding to show here yet.
 */
@Composable
fun WalletBalanceRow(
    balance: String?,
    onAddFunds: (() -> Unit)?,
    modifier: Modifier = Modifier,
    /** Replaces Add funds, e.g. Retry while the wallet is not connected. */
    action: String? = null,
    balanceMaxLines: Int = 1
) {
    val enabled = onAddFunds != null
    AppSettingsRow(
        title = stringResource(R.string.wallet_cards_balance),
        subtitle = balance ?: PLACEHOLDER,
        subtitleMaxLines = balanceMaxLines,
        icon = Icons.Filled.CurrencyBitcoin,
        testTag = "settings_balance",
        onClick = onAddFunds,
        modifier = modifier
    ) {
        Text(
            text = action ?: stringResource(R.string.wallet_cards_add_funds),
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA)
            }
        )
    }
}

/** The active wallet, its balance, and its details: always the top of Settings. */
@Composable
private fun WalletBlock(
    cards: WalletCards,
    balance: @Composable () -> Unit,
    onWallets: () -> Unit,
    onDetails: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSettingsRowDefaults.RowSpacing)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ProviderMark(cards.paying.provider, cards.paying.displayName(), size = 40)
            AppSettingsRow(
                title = cards.paying.displayName(),
                testTag = "settings_wallets",
                onClick = onWallets,
                modifier = Modifier.weight(1f)
            ) { Chevron() }
        }
        balance()
        AppSettingsRow(
            title = stringResource(R.string.wallet_cards_details),
            icon = Icons.Filled.Info,
            testTag = "settings_wallet_details",
            onClick = onDetails
        ) { Chevron() }
    }
}

@Composable
private fun Chevron() {
    Icon(
        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(AppSettingsRowDefaults.ChevronSize)
    )
}

/** Picks the wallet that pays: tapping another one activates it, the active one stays. */
@Composable
private fun WalletCardsList(
    cards: WalletCards,
    busy: Boolean,
    onBack: () -> Unit,
    onActivate: (String) -> Unit,
    onAdd: () -> Unit
) {
    CardsScaffold(title = stringResource(R.string.wallet_cards_wallets), onBack = onBack) {
        if (busy) {
            Text(
                text = stringResource(R.string.wallet_removal_payment_active),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
            )
        }
        // Rayl is simply the card every user has from the start.
        (listOf(cards.pocket) + cards.cards).forEach { card ->
            val active = card.id == cards.payingId
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ProviderMark(card.provider, card.displayName(), size = 40)
                AppSettingsRow(
                    title = card.displayName(),
                    testTag = "wallet_card_${card.id}",
                    onClick = { if (!active && !busy) onActivate(card.id) },
                    modifier = Modifier.weight(1f)
                ) {
                    if (active) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = stringResource(R.string.wallet_cards_active),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(AppSettingsRowDefaults.ChevronSize)
                        )
                    }
                }
            }
        }
        if (cards.addable.isNotEmpty()) {
            AppSettingsRow(
                title = stringResource(R.string.wallet_cards_add),
                icon = Icons.Filled.Add,
                testTag = "wallet_cards_add",
                onClick = onAdd
            )
        }
    }
}

/**
 * The active wallet's page: its mark and name with an edit button, the settings only it has
 * ([payingContent]), and Remove card at the bottom. Rayl is never renamed or removed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WalletCardPage(
    host: WalletCardsHost,
    card: WalletCard,
    busy: Boolean,
    onBack: () -> Unit,
    onRemovePaying: () -> Unit,
    payingContent: @Composable () -> Unit
) {
    var renaming by rememberSaveable(card.id) { mutableStateOf(false) }
    var showsPhrase by rememberSaveable(card.id) { mutableStateOf(false) }
    // Read only to know whether there is one; shown only when asked for.
    val recoveryPhrase by produceState<String?>(null, card.id) {
        value = host.recoveryPhrase(card.id)
    }
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {},
                navigationIcon = { BackIconButton(onClick = onBack) }
            )
        },
        bottomBar = {
            // Removing needs no confirmation: the funds stay in the wallet itself.
            if (!card.isPocket) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (busy) {
                        Text(
                            text = stringResource(R.string.wallet_removal_payment_active),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(
                        onClick = onRemovePaying,
                        enabled = !busy,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("wallet_card_remove")
                    ) { Text(stringResource(R.string.wallet_cards_remove)) }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(AppSettingsRowDefaults.RowSpacing)
        ) {
            CardHeader(card = card, onRename = { renaming = true })
            Spacer(Modifier.height(16.dp))
            payingContent()
            if (recoveryPhrase != null) {
                AppSettingsRow(
                    title = stringResource(R.string.wallet_cards_recovery_phrase),
                    icon = Icons.Filled.Key,
                    testTag = "wallet_card_recovery_phrase",
                    onClick = { showsPhrase = true }
                )
            }
        }
    }
    if (renaming) {
        RenameDialog(
            name = card.name,
            onRename = {
                host.rename(card.id, it)
                renaming = false
            },
            onDismiss = { renaming = false }
        )
    }
    val phrase = recoveryPhrase
    if (showsPhrase && phrase != null) {
        RecoveryPhraseDialog(phrase = phrase, onDismiss = { showsPhrase = false })
    }
}

/** The mark and the name in large type with its edit button. */
@Composable
private fun CardHeader(card: WalletCard, onRename: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ProviderMark(card.provider, card.displayName(), size = 64)
        Text(
            text = card.displayName(),
            style = MaterialTheme.typography.headlineMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        // Rayl keeps its name.
        if (!card.isPocket) {
            IconButton(onClick = onRename, modifier = Modifier.testTag("wallet_card_rename")) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = stringResource(R.string.wallet_cards_rename),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun RenameDialog(name: String, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    var value by rememberSaveable { mutableStateOf(name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.wallet_cards_rename)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("wallet_card_name_field")
            )
        },
        confirmButton = {
            TextButton(
                enabled = value.isNotBlank(),
                onClick = { onRename(value) }
            ) { Text(stringResource(R.string.wallet_cards_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_manage_wallet_cancel))
            }
        }
    )
}

/** The words of a wallet whose only backup they are. */
@Composable
private fun RecoveryPhraseDialog(phrase: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.wallet_cards_recovery_phrase)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.wallet_cards_recovery_phrase_body))
                Text(
                    text = phrase,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.testTag("rayl_recovery_phrase_words")
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.wallet_cards_done)) }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    val clip = ClipData.newPlainText("Recovery phrase", phrase).apply {
                        // Keeps the words out of the clipboard preview (Android 13+).
                        description.extras = PersistableBundle().apply {
                            putBoolean("android.content.extra.IS_SENSITIVE", true)
                        }
                    }
                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
                }
            ) { Text(stringResource(R.string.wallet_cards_copy)) }
        }
    )
}

@Composable
private fun WalletCardProviders(
    providers: List<WalletCardProvider>,
    busy: Boolean,
    onBack: () -> Unit,
    onAdd: (String) -> Unit
) {
    CardsScaffold(title = stringResource(R.string.wallet_cards_add), onBack = onBack) {
        Text(
            text = stringResource(R.string.wallet_cards_add_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 12.dp)
        )
        if (busy) {
            Text(
                text = stringResource(R.string.wallet_removal_payment_active),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
            )
        }
        providers.forEach { provider ->
            AppSettingsRow(
                title = provider.title,
                subtitle = provider.subtitle,
                subtitleMaxLines = 2,
                testTag = "wallet_cards_add_${provider.id}",
                onClick = { if (!busy) onAdd(provider.id) }
            ) {
                ProviderMark(provider.id, provider.title, size = 32)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CardsScaffold(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(title) },
                navigationIcon = { BackIconButton(onClick = onBack) }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(AppSettingsRowDefaults.RowSpacing)
        ) { content() }
    }
}

/** Rayl's own mark for the pocket, the host's mark for [provider], or the initial of [title]. */
@Composable
private fun ProviderMark(
    provider: String,
    title: String,
    size: Int,
    modifier: Modifier = Modifier
) {
    @DrawableRes val icon = LocalWalletCardIcons.current[provider]
    if (provider == POCKET_PROVIDER) {
        RaylMark(modifier = modifier, size = size.dp)
    } else if (icon != null) {
        Image(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = modifier.size(size.dp)
        )
    } else {
        Box(
            modifier = modifier.size(size.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = title.take(1),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

/** The pocket is named in the app's language; cards keep the name they were given. */
@Composable
private fun WalletCard.displayName(): String =
    if (isPocket) stringResource(R.string.wallet_cards_pocket) else name

/** [WalletCard.provider] of the pocket. */
private const val POCKET_PROVIDER = "rayl"

/** A balance this wallet cannot show here. */
private const val PLACEHOLDER = "—"

private const val DISABLED_ALPHA = 0.38f

private const val LIST = "list"
private const val ADD = "add"
private const val DETAILS = "details"

private data class CardsNavigationInfo(val destination: String?) : NavigationEventInfo()
