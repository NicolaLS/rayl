package xyz.lilsus.raylsuite.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import xyz.lilsus.raylsuite.core.ui.R

/**
 * Shared connection chrome; providers own credentials, validation and connection work. [onBack]
 * leaves the setup. [connectionNotice] is for what connecting does beyond storing the
 * credential; [onShowInstructions] is offered, before the form, only where getting the credential
 * needs a guide.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CredentialConnectionScreen(
    title: String,
    description: String,
    actionTitle: String,
    canSubmit: Boolean,
    isSubmitting: Boolean,
    onSubmit: () -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    actionModifier: Modifier = Modifier,
    instructionsTitle: String? = null,
    onShowInstructions: (() -> Unit)? = null,
    connectionNotice: String? = null,
    privacyPolicyUrl: String? = null,
    termsUrl: String? = null,
    termsTitle: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val focusManager = LocalFocusManager.current
    val uriHandler = LocalUriHandler.current
    Scaffold(
        modifier = modifier,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(title) },
                navigationIcon = { onBack?.let { BackIconButton(onClick = it) } }
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AppPrimaryButton(
                    text = actionTitle,
                    onClick = {
                        focusManager.clearFocus(force = true)
                        if (canSubmit) onSubmit()
                    },
                    enabled = canSubmit,
                    isWorking = isSubmitting,
                    modifier = actionModifier.widthIn(max = 608.dp)
                        .padding(start = 24.dp, end = 24.dp, top = 8.dp)
                )
                Row(
                    modifier = Modifier.heightIn(min = 24.dp).padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    privacyPolicyUrl?.let { url ->
                        LegalLink(stringResource(R.string.credential_privacy)) {
                            uriHandler.openUri(url)
                        }
                    }
                    termsUrl?.let { url ->
                        LegalLink(termsTitle ?: stringResource(R.string.credential_terms)) {
                            uriHandler.openUri(url)
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            Column(
                modifier = Modifier.align(Alignment.TopCenter).widthIn(max = 608.dp)
                    .fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp).padding(top = 8.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (instructionsTitle != null && onShowInstructions != null) {
                    TextButton(
                        onClick = onShowInstructions,
                        enabled = !isSubmitting,
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp).size(18.dp)
                        )
                        Text(instructionsTitle)
                    }
                }
                content()
                connectionNotice?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun LegalLink(title: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Fills a credential from the clipboard; pasting is how most credentials arrive. */
@Composable
fun CredentialPasteButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.ContentPaste,
            contentDescription = null,
            modifier = Modifier.padding(end = 8.dp).size(18.dp)
        )
        Text(stringResource(R.string.credential_paste))
    }
}

@Composable
fun CredentialInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    onPaste: () -> Unit,
    onSubmit: () -> Unit,
    enabled: Boolean = true,
    multiline: Boolean = false,
    fieldModifier: Modifier = Modifier,
    pasteModifier: Modifier = Modifier
) {
    var visible by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = fieldModifier.fillMaxWidth(),
            singleLine = !multiline,
            minLines = if (multiline) 4 else 1,
            label = { Text(label) },
            placeholder = { Text(placeholder) },
            enabled = enabled,
            visualTransformation = if (visible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                autoCorrectEnabled = false,
                capitalization = KeyboardCapitalization.None,
                imeAction = ImeAction.Go
            ),
            keyboardActions = KeyboardActions(
                onGo = {
                    focusManager.clearFocus(force = true)
                    onSubmit()
                }
            ),
            trailingIcon = {
                IconButton(onClick = { visible = !visible }, enabled = enabled) {
                    Icon(
                        imageVector = if (visible) {
                            Icons.Default.VisibilityOff
                        } else {
                            Icons.Default.Visibility
                        },
                        contentDescription = stringResource(
                            if (visible) R.string.credential_hide else R.string.credential_show
                        )
                    )
                }
            }
        )
        CredentialPasteButton(onClick = onPaste, modifier = pasteModifier, enabled = enabled)
    }
}
