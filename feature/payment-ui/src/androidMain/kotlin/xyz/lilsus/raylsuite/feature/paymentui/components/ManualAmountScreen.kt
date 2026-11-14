package xyz.lilsus.raylsuite.feature.paymentui.components

import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import xyz.lilsus.raylsuite.core.model.DisplayAmount
import xyz.lilsus.raylsuite.core.model.DisplayCurrency
import xyz.lilsus.raylsuite.core.ui.components.BackIconButton
import xyz.lilsus.raylsuite.core.ui.format.AmountFormatter
import xyz.lilsus.raylsuite.core.ui.format.rememberAmountFormatter
import xyz.lilsus.raylsuite.core.ui.platform.enableTestTagsAsResourceId
import xyz.lilsus.raylsuite.core.ui.theme.RaylSuiteTheme
import xyz.lilsus.raylsuite.feature.paymentui.LnurlPayDisplay
import xyz.lilsus.raylsuite.feature.paymentui.PaymentTestTags
import xyz.lilsus.raylsuite.feature.paymentui.R
import xyz.lilsus.raylsuite.feature.paymentui.amount.ManualAmountKey
import xyz.lilsus.raylsuite.feature.paymentui.amount.ManualAmountUiState
import xyz.lilsus.raylsuite.feature.paymentui.amount.RangeStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualAmountScreen(
    state: ManualAmountUiState,
    lnurlPayDisplay: LnurlPayDisplay? = null,
    onKeyPress: (ManualAmountKey) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit
) {
    var reviewing by rememberSaveable(state.amount, state.rangeStatus) { mutableStateOf(false) }
    val canContinue = state.amount?.minor?.let { it > 0 } == true &&
        state.rangeStatus == RangeStatus.InRange
    Dialog(
        onDismissRequest = { if (reviewing) reviewing = false else onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnClickOutside = false
        )
    ) {
        val view = LocalView.current
        val darkStatusBarIcons = MaterialTheme.colorScheme.surface.luminance() > 0.5f
        val darkNavigationBarIcons = MaterialTheme.colorScheme.background.luminance() > 0.5f
        SideEffect {
            // The dialog has its own window; Activity system-bar styling does not reach it.
            val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                @Suppress("DEPRECATION")
                window.statusBarColor = android.graphics.Color.TRANSPARENT
                @Suppress("DEPRECATION")
                window.navigationBarColor =
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O && darkNavigationBarIcons) {
                        // Android 7 cannot draw dark navigation icons over a light surface.
                        android.graphics.Color.argb(0x80, 0x1B, 0x1B, 0x1B)
                    } else {
                        android.graphics.Color.TRANSPARENT
                    }
            }
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = darkStatusBarIcons
                isAppearanceLightNavigationBars = darkNavigationBarIcons
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
        }
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .enableTestTagsAsResourceId()
                .testTag(PaymentTestTags.MANUAL_AMOUNT_SCREEN),
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                TopAppBar(
                    // The entry step needs no title: a keypad under a close button explains itself.
                    title = {
                        if (reviewing) {
                            Text(stringResource(R.string.confirm_payment_title))
                        }
                    },
                    navigationIcon = {
                        if (reviewing) {
                            BackIconButton(
                                onClick = { reviewing = false },
                                testTag = PaymentTestTags.MANUAL_AMOUNT_BACK_BUTTON
                            )
                        } else {
                            IconButton(
                                onClick = onDismiss,
                                modifier = Modifier.testTag(
                                    PaymentTestTags.MANUAL_AMOUNT_CANCEL_BUTTON
                                )
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = stringResource(
                                        R.string.manual_amount_cancel_button
                                    )
                                )
                            }
                        }
                    },
                    actions = {
                        if (reviewing) {
                            TextButton(
                                onClick = onDismiss,
                                modifier = Modifier.testTag(
                                    PaymentTestTags.MANUAL_AMOUNT_CANCEL_BUTTON
                                )
                            ) {
                                Text(stringResource(R.string.manual_amount_cancel_button))
                            }
                        }
                    }
                )
            }
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(
                    modifier = Modifier.widthIn(max = 480.dp).fillMaxSize()
                        .padding(padding).consumeWindowInsets(padding),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Crossfade(
                        targetState = reviewing,
                        animationSpec = tween(durationMillis = 180),
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    ) { showReview ->
                        val stepModifier = Modifier
                            .fillMaxSize()
                            .then(
                                if (showReview == reviewing) {
                                    Modifier
                                } else {
                                    Modifier.clearAndSetSemantics {}
                                }
                            )
                        if (showReview) {
                            ManualAmountReviewContent(
                                state = state,
                                lnurlPayDisplay = lnurlPayDisplay,
                                modifier = stepModifier
                            )
                        } else {
                            ManualAmountEntryContent(
                                state = state,
                                onKeyPress = { if (!reviewing) onKeyPress(it) },
                                modifier = stepModifier
                            )
                        }
                    }

                    Button(
                        onClick = {
                            if (reviewing) onSubmit() else reviewing = true
                        },
                        enabled = canContinue,
                        modifier = Modifier
                            .padding(horizontal = screenPadding)
                            .padding(top = 12.dp, bottom = 16.dp)
                            .fillMaxWidth()
                            .height(56.dp)
                            .testTag(
                                if (reviewing) {
                                    PaymentTestTags.MANUAL_AMOUNT_PAY_BUTTON
                                } else {
                                    PaymentTestTags.MANUAL_AMOUNT_CONTINUE_BUTTON
                                }
                            )
                    ) {
                        Text(
                            text = stringResource(
                                if (reviewing) R.string.pay_button else R.string.continue_button
                            ),
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }
        }
    }
}

/**
 * Entry step: the amount owns the free space above a keypad that stays anchored over the action.
 */
@Composable
private fun ManualAmountEntryContent(
    state: ManualAmountUiState,
    onKeyPress: (ManualAmountKey) -> Unit,
    modifier: Modifier = Modifier
) {
    val formatter = rememberAmountFormatter()
    val reserve = amountBlockReserve(state.alternativeCurrency != null)
    BoxWithConstraints(modifier = modifier) {
        val keySize = keypadKeySize(
            availableWidth = maxWidth - screenPadding * 2,
            availableHeight = maxHeight,
            amountReserve = reserve
        )
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = screenPadding, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    AmountInputDisplay(state, formatter)
                    AmountSecondaryLine(state, formatter)
                    state.alternativeCurrency?.let { alternative ->
                        Spacer(modifier = Modifier.height(16.dp))
                        SwitchCurrencyButton(
                            label = currencyLabel(alternative).uppercase(),
                            onClick = { onKeyPress(ManualAmountKey.SwitchCurrency) }
                        )
                    }
                }
            }
            AmountKeypad(
                onKeyPress = onKeyPress,
                enableDecimal = state.allowDecimal,
                keySize = keySize,
                modifier = Modifier.padding(horizontal = screenPadding)
            )
        }
    }
}

/**
 * Height the figure, its secondary line and the currency switch need before anything has to
 * scroll. The secondary line is always counted, because a range warning can appear in it later.
 */
@Composable
private fun amountBlockReserve(hasAlternativeCurrency: Boolean): Dp {
    val base = amountFigureReserve + secondaryLineReserve +
        if (hasAlternativeCurrency) switchButtonReserve else 0.dp
    return base * maxOf(1f, LocalDensity.current.fontScale)
}

/**
 * The pad takes what the amount above it does not need. A short screen shrinks the keys rather
 * than pushing the currency switch off the bottom.
 */
private fun keypadKeySize(availableWidth: Dp, availableHeight: Dp, amountReserve: Dp): Dp {
    val rowGaps = keypadSpacing * (KEYPAD_ROWS - 1)
    val byWidth = (availableWidth - keypadSpacing * (KEYPAD_COLUMNS - 1)) / KEYPAD_COLUMNS
    val byHeight = (availableHeight - amountReserve - rowGaps) / KEYPAD_ROWS
    // Keys stay at the minimum touch target unless even that would not fit, which beats clipping
    // the bottom row in a split-screen window.
    val floor = minOf(
        keypadMinKeySize,
        ((availableHeight * KEYPAD_MAX_HEIGHT_FRACTION - rowGaps) / KEYPAD_ROWS)
            .coerceAtLeast(0.dp)
    )
    return minOf(byWidth, byHeight, keypadMaxKeySize).coerceAtLeast(floor)
}

/** Review step: recipient and amount sit together in the middle of the screen. */
@Composable
private fun ManualAmountReviewContent(
    state: ManualAmountUiState,
    lnurlPayDisplay: LnurlPayDisplay?,
    modifier: Modifier = Modifier
) {
    val formatter = rememberAmountFormatter()
    Box(
        modifier = modifier.verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = screenPadding, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            lnurlPayDisplay?.let {
                LnurlPayReviewSection(it, centered = true)
                Spacer(modifier = Modifier.height(28.dp))
            }
            state.amount?.let { amount ->
                AutoSizeAmountText(
                    text = AnnotatedString(formatter.format(amount)),
                    style = amountTextStyle(MaterialTheme.colorScheme.onSurface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(PaymentTestTags.MANUAL_AMOUNT_REVIEW)
                )
            }
            // The last look before paying shows the other currency too. A fiat amount is
            // converted at a rate from the backend, and the sats are what leaves the wallet.
            state.equivalentAmount?.let { equivalent ->
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "≈ ${formatter.format(equivalent)}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag(PaymentTestTags.MANUAL_AMOUNT_REVIEW_EQUIVALENT)
                )
            }
            RangeFeedback(state.rangeStatus)
        }
    }
}

@Composable
private fun AmountInputDisplay(state: ManualAmountUiState, formatter: AmountFormatter) {
    val colors = MaterialTheme.colorScheme
    val isActive = state.amount != null ||
        state.hasDecimal ||
        state.rawFraction.isNotEmpty() ||
        state.rawWhole != "0"
    val committedColor = if (isActive) {
        colors.onSurface
    } else {
        colors.onSurfaceVariant.copy(alpha = 0.4f)
    }
    val ghostColor = colors.onSurfaceVariant.copy(alpha = 0.4f)
    // The locale decides which side its mark sits on, and it is set like the figure it belongs to.
    val affix = formatter.affix(state.currency)
    val amountText = buildAnnotatedString {
        append(affix.prefix)
        append(committedNumber(state))
        if (state.allowDecimal && state.hasDecimal && state.rawFraction.isEmpty()) {
            withStyle(SpanStyle(color = ghostColor, fontWeight = FontWeight.Normal)) {
                append("0")
            }
        }
        append(affix.suffix)
    }

    AutoSizeAmountText(
        text = amountText,
        style = amountTextStyle(committedColor),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(PaymentTestTags.MANUAL_AMOUNT_DISPLAY)
    )
}

/**
 * One line under the figure. A range warning takes the equivalent's place instead of adding a
 * line, so nothing below it moves when the entered amount goes out of bounds.
 */
@Composable
private fun AmountSecondaryLine(state: ManualAmountUiState, formatter: AmountFormatter) {
    val warning = rangeMessage(state.rangeStatus)
    val equivalent = state.alternativeCurrency?.let {
        state.equivalentAmount?.let { amount -> "≈ ${formatter.format(amount)}" } ?: "—"
    }
    val text = warning ?: equivalent ?: return

    Spacer(modifier = Modifier.height(10.dp))
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = if (warning != null) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (warning == null) {
                    Modifier.testTag(PaymentTestTags.MANUAL_AMOUNT_EQUIVALENT)
                } else {
                    Modifier
                }
            )
    )
}

@Composable
private fun amountTextStyle(color: Color): TextStyle = TextStyle(
    fontFamily = MaterialTheme.typography.displayLarge.fontFamily,
    fontWeight = FontWeight.Medium,
    color = color,
    textAlign = TextAlign.Center
)

/**
 * Picks the largest amount size that still fits on one line. Twelve whole digits plus a fraction
 * has to stay readable without wrapping or clipping.
 */
@Composable
private fun AutoSizeAmountText(text: AnnotatedString, style: TextStyle, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val available = constraints.maxWidth
        val fontSize = remember(text, style, available, density) {
            if (available <= 0 || available == Constraints.Infinity) {
                amountTextSizes.first()
            } else {
                amountTextSizes.firstOrNull { candidate ->
                    val measured = measurer.measure(
                        text = text,
                        style = style.copy(fontSize = candidate),
                        maxLines = 1,
                        softWrap = false
                    )
                    measured.size.width <= available
                } ?: amountTextSizes.last()
            }
        }
        Text(
            text = text,
            style = style.copy(fontSize = fontSize),
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun SwitchCurrencyButton(label: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    FilledTonalButton(
        onClick = onClick,
        shape = CircleShape,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = colors.surfaceContainerHighest,
            contentColor = colors.primary
        ),
        elevation = null,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
        modifier = Modifier.testTag(PaymentTestTags.MANUAL_AMOUNT_SWITCH_CURRENCY)
    ) {
        Icon(
            imageVector = Icons.Filled.SwapVert,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(stringResource(R.string.enter_amount_switch_currency, label))
    }
}

private fun committedNumber(state: ManualAmountUiState): String {
    val whole = formatWhole(state.rawWhole)
    val shouldShowDecimal = state.allowDecimal &&
        (state.hasDecimal || state.rawFraction.isNotEmpty())
    if (!shouldShowDecimal) return whole

    return buildString {
        append(whole)
        append(".")
        append(state.rawFraction)
    }
}

private fun formatWhole(raw: String): String {
    val normalized = raw.ifEmpty { "0" }
    if (normalized.length <= 3) return normalized
    val reversed = normalized.reversed().chunked(3).joinToString(",")
    return reversed.reversed()
}

private fun currencyLabel(currency: DisplayCurrency): String = when (currency) {
    DisplayCurrency.Satoshi -> "sat"
    is DisplayCurrency.Fiat -> currency.iso4217.uppercase()
}

@Composable
private fun rangeMessage(rangeStatus: RangeStatus): String? = when (rangeStatus) {
    RangeStatus.InRange, RangeStatus.Unknown -> null
    RangeStatus.BelowMin -> stringResource(R.string.enter_amount_too_low)
    RangeStatus.AboveMax -> stringResource(R.string.enter_amount_too_large)
}

@Composable
private fun RangeFeedback(rangeStatus: RangeStatus) {
    val feedback = rangeMessage(rangeStatus) ?: return

    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = feedback,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun AmountKeypad(
    onKeyPress: (ManualAmountKey) -> Unit,
    enableDecimal: Boolean,
    keySize: Dp,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(keypadSpacing)
    ) {
        keypadRows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(
                    keypadSpacing,
                    Alignment.CenterHorizontally
                )
            ) {
                row.forEach { key ->
                    val enabled = key != ManualAmountKey.Decimal || enableDecimal
                    AmountKeyButton(
                        key = key,
                        enabled = enabled,
                        onClick = { onKeyPress(key) },
                        size = keySize
                    )
                }
            }
        }
    }
}

/**
 * A quiet key. The accent belongs to the primary action, and a key the currency cannot use stays
 * in the grid as empty space instead of a dimmed circle.
 */
@Composable
private fun AmountKeyButton(key: ManualAmountKey, enabled: Boolean, onClick: () -> Unit, size: Dp) {
    val colors = MaterialTheme.colorScheme
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = colors.surfaceContainerHighest,
            contentColor = colors.onSurface,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = Color.Transparent
        ),
        elevation = null,
        modifier = Modifier.size(size).testTag(key.testTag())
    ) {
        if (key == ManualAmountKey.Backspace) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Backspace,
                contentDescription = stringResource(R.string.enter_amount_backspace),
                modifier = Modifier.size((size * 0.34f).coerceIn(20.dp, 28.dp))
            )
        } else {
            Text(
                text = when (key) {
                    is ManualAmountKey.Digit -> key.value.toString()
                    else -> "."
                },
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontFamily = MaterialTheme.typography.bodyLarge.fontFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = (size.value * 0.32f).coerceIn(18f, 26f).sp
                )
            )
        }
    }
}

private fun ManualAmountKey.testTag(): String = when (this) {
    is ManualAmountKey.Digit -> PaymentTestTags.manualAmountDigitKey(value)
    ManualAmountKey.Decimal -> PaymentTestTags.MANUAL_AMOUNT_DECIMAL_KEY
    ManualAmountKey.Backspace -> PaymentTestTags.MANUAL_AMOUNT_BACKSPACE_KEY
    ManualAmountKey.SwitchCurrency -> PaymentTestTags.MANUAL_AMOUNT_SWITCH_CURRENCY
}

private val screenPadding = 24.dp
private val keypadSpacing = 12.dp
private val keypadMaxKeySize = 76.dp
private val keypadMinKeySize = 48.dp
private const val KEYPAD_COLUMNS = 3
private const val KEYPAD_ROWS = 4

/** The pad never takes the whole step, so the amount keeps a strip of its own on any window. */
private const val KEYPAD_MAX_HEIGHT_FRACTION = 0.8f

private val amountFigureReserve = 104.dp // column padding plus one line at the largest size
private val secondaryLineReserve = 34.dp // the equivalent, or a range warning in its place
private val switchButtonReserve = 56.dp

private val amountTextSizes: List<TextUnit> =
    listOf(52.sp, 44.sp, 38.sp, 32.sp, 27.sp, 23.sp, 20.sp)

private val keypadRows = listOf(
    listOf<ManualAmountKey>(
        ManualAmountKey.Digit(1),
        ManualAmountKey.Digit(2),
        ManualAmountKey.Digit(3)
    ),
    listOf(
        ManualAmountKey.Digit(4),
        ManualAmountKey.Digit(5),
        ManualAmountKey.Digit(6)
    ),
    listOf(
        ManualAmountKey.Digit(7),
        ManualAmountKey.Digit(8),
        ManualAmountKey.Digit(9)
    ),
    listOf(
        ManualAmountKey.Decimal,
        ManualAmountKey.Digit(0),
        ManualAmountKey.Backspace
    )
)

@Preview
@Composable
private fun ManualAmountScreenPreview() {
    RaylSuiteTheme {
        ManualAmountScreen(
            state = ManualAmountUiState(
                amount = DisplayAmount(12345, DisplayCurrency.Satoshi),
                currency = DisplayCurrency.Satoshi,
                rawWhole = "12345",
                allowDecimal = false,
                alternativeCurrency = DisplayCurrency.Fiat("usd"),
                equivalentAmount = DisplayAmount(812, DisplayCurrency.Fiat("usd"))
            ),
            onKeyPress = {},
            onSubmit = {},
            onDismiss = {}
        )
    }
}

@Preview
@Composable
private fun ManualAmountScreenPreviewWithRange() {
    RaylSuiteTheme {
        ManualAmountScreen(
            state = ManualAmountUiState(
                amount = null,
                currency = DisplayCurrency.Fiat("usd"),
                rangeStatus = RangeStatus.BelowMin
            ),
            onKeyPress = {},
            onSubmit = {},
            onDismiss = {}
        )
    }
}
