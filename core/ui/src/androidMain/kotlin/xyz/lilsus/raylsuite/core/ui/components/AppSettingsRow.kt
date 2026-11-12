package xyz.lilsus.raylsuite.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** The shape of a row inside an [AppSettingsSection]; null outside one. */
private val LocalAppSettingsRowShape = compositionLocalOf<Shape?> { null }

/**
 * Rows that belong together, as one block under an optional [title]: they sit close, with small
 * inner corners, and the block rounds its outer ones, the way Android's own settings group rows.
 */
@Composable
fun AppSettingsSection(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {
        title?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                    .semantics { heading() }
            )
        }
        CompositionLocalProvider(
            LocalAppSettingsRowShape provides RoundedCornerShape(AppSettingsRowDefaults.InnerCorner)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(AppSettingsRowDefaults.OuterCorner)),
                verticalArrangement = Arrangement.spacedBy(AppSettingsRowDefaults.SectionRowGap),
                content = content
            )
        }
    }
}

/** A row of a settings list with content of its own, e.g. a control under its title. */
@Composable
fun AppSettingsPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 1.dp,
        shape = LocalAppSettingsRowShape.current ?: MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier.padding(AppSettingsRowDefaults.RowPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

/**
 * One line of a settings list: a tinted glyph, a title over its current value, and whatever control
 * or affordance closes the row. Compact on purpose — a settings screen is scanned, not read.
 * [leadingContent] takes the glyph's place for a picture of its own, e.g. a wallet's mark.
 */
@Composable
fun AppSettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    subtitleMaxLines: Int = 1,
    testTag: String? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable RowScope.() -> Unit)? = null
) {
    AppListRow(
        modifier = modifier,
        onClick = onClick,
        testTag = testTag,
        minHeight = AppSettingsRowDefaults.MinHeight,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = contentColor,
        contentPadding = AppSettingsRowDefaults.RowPadding,
        shape = LocalAppSettingsRowShape.current ?: MaterialTheme.shapes.medium
    ) {
        leadingContent?.invoke()
        icon?.takeIf { leadingContent == null }?.let {
            Icon(
                imageVector = it,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(AppSettingsRowDefaults.IconSize)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = subtitleMaxLines,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        trailingContent?.invoke(this)
    }
}

object AppSettingsRowDefaults {
    val MinHeight = 44.dp
    val RowPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
    val IconSize = 22.dp
    val ChevronSize = 20.dp
    val RowSpacing = 8.dp
    val ScreenPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp)

    /** Between the sections of a settings screen. */
    val SectionSpacing = 24.dp

    /** Between the rows of one section. */
    val SectionRowGap = 2.dp
    val OuterCorner = 20.dp
    val InnerCorner = 4.dp
}
