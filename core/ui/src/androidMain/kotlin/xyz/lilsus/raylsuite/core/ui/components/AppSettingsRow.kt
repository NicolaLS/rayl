package xyz.lilsus.raylsuite.core.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * One line of a settings list: a tinted glyph, a title over its current value, and whatever control
 * or affordance closes the row. Compact on purpose — a settings screen is scanned, not read.
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
    trailingContent: (@Composable RowScope.() -> Unit)? = null
) {
    AppListRow(
        modifier = modifier,
        onClick = onClick,
        testTag = testTag,
        minHeight = AppSettingsRowDefaults.MinHeight,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = contentColor,
        contentPadding = AppSettingsRowDefaults.RowPadding
    ) {
        icon?.let {
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
}
