package xyz.lilsus.raylsuite.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import xyz.lilsus.raylsuite.core.ui.R

@Composable
fun AppListScaffold(
    isEmpty: Boolean,
    emptyMessage: String?,
    modifier: Modifier = Modifier,
    showSearchBar: Boolean = false,
    searchQuery: String = "",
    onSearchQueryChange: (String) -> Unit = {},
    searchLabel: String? = null,
    searchPlaceholder: String? = null,
    fadeContainerColor: Color = MaterialTheme.colorScheme.background,
    listSpacing: Dp = AppListDefaults.ItemSpacing,
    fixedContent: @Composable ColumnScope.() -> Unit = {},
    content: LazyListScope.() -> Unit
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(AppListDefaults.SectionSpacing)
    ) {
        if (showSearchBar) {
            AppListSearchField(
                query = searchQuery,
                onQueryChange = onSearchQueryChange,
                label = searchLabel,
                placeholder = searchPlaceholder
            )
        }
        fixedContent()
        if (isEmpty) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                emptyMessage?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            }
        } else {
            AppFadingLazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                containerColor = fadeContainerColor,
                verticalArrangement = Arrangement.spacedBy(listSpacing),
                content = content
            )
        }
    }
}

@Composable
fun AppFadingLazyColumn(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    containerColor: Color = MaterialTheme.colorScheme.background,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(AppListDefaults.ItemSpacing),
    contentPadding: PaddingValues = PaddingValues(),
    content: LazyListScope.() -> Unit
) {
    Box(modifier = modifier) {
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            verticalArrangement = verticalArrangement,
            content = content
        )
        if (state.canScrollForward) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(AppListDefaults.FadeHeight)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                containerColor.copy(alpha = 0f),
                                containerColor
                            )
                        )
                    )
            )
        }
    }
}

@Composable
fun AppListRow(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    selected: Boolean = false,
    showSelectedState: Boolean = selected,
    testTag: String? = null,
    minHeight: Dp = AppListDefaults.RowMinHeight,
    tonalElevation: Dp = if (selected) 3.dp else 1.dp,
    color: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    contentPadding: PaddingValues = AppListDefaults.RowPadding,
    role: Role = Role.Button,
    shape: Shape = MaterialTheme.shapes.medium,
    content: @Composable RowScope.() -> Unit
) {
    val surfaceModifier = if (testTag == null) {
        modifier.fillMaxWidth()
    } else {
        modifier
            .fillMaxWidth()
            .testTag(testTag)
    }
    val rowModifier = Modifier
        .heightIn(min = minHeight)
        .fillMaxWidth()
        .then(
            if (onClick != null) {
                Modifier.clickable(
                    enabled = enabled,
                    role = role,
                    onClick = onClick
                )
            } else {
                Modifier
            }
        )
        .then(
            if (showSelectedState) {
                Modifier.semantics { this.selected = selected }
            } else {
                Modifier
            }
        )
        .padding(contentPadding)

    Surface(
        modifier = surfaceModifier,
        tonalElevation = tonalElevation,
        shape = shape,
        color = color,
        contentColor = contentColor
    ) {
        Row(
            modifier = rowModifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

@Composable
fun AppSelectableListRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    testTag: String? = null,
    showSelectedIndicator: Boolean = true,
    leadingContent: (@Composable RowScope.() -> Unit)? = null,
    trailingContent: (@Composable RowScope.() -> Unit)? = null
) {
    AppListRow(
        modifier = modifier,
        onClick = onClick,
        enabled = enabled,
        selected = selected,
        showSelectedState = true,
        testTag = testTag,
        minHeight = 48.dp,
        tonalElevation = if (selected) 6.dp else 2.dp
    ) {
        leadingContent?.invoke(this)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = if (selected) {
                        FontWeight.SemiBold
                    } else {
                        FontWeight.Normal
                    }
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (showSelectedIndicator && selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        }
        trailingContent?.invoke(this)
    }
}

@Composable
fun AppListSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null
) {
    val labelContent: (@Composable () -> Unit)? = label?.let { labelText ->
        { Text(labelText) }
    }
    val placeholderContent: (@Composable () -> Unit)? = placeholder?.let { placeholderText ->
        { Text(placeholderText) }
    }
    val clearSearchLabel = stringResource(R.string.action_clear_search)
    val trailingIconContent: (@Composable () -> Unit)? = if (query.isNotEmpty()) {
        {
            IconButton(onClick = { onQueryChange("") }) {
                Icon(
                    imageVector = Icons.Filled.Clear,
                    contentDescription = clearSearchLabel
                )
            }
        }
    } else {
        null
    }
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        label = labelContent,
        placeholder = placeholderContent,
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = trailingIconContent,
        singleLine = true
    )
}

object AppListDefaults {
    val ItemSpacing = 12.dp
    val SectionSpacing = 14.dp
    val FadeHeight = 56.dp
    val RowMinHeight = 64.dp
    val RowPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
    val ScreenPadding = PaddingValues(horizontal = 16.dp, vertical = 24.dp)
}
