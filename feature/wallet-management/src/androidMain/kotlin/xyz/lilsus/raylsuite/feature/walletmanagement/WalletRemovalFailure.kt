package xyz.lilsus.raylsuite.feature.walletmanagement

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
fun WalletRemovalFailure(isWorking: Boolean, onRetry: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.safeDrawingPadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(
                text = stringResource(R.string.settings_manage_wallet_remove),
                style = MaterialTheme.typography.headlineMedium
            )
            Text(stringResource(R.string.wallet_removal_failed))
            if (isWorking) CircularProgressIndicator()
            Button(onClick = onRetry, enabled = !isWorking) {
                Text(stringResource(R.string.wallet_removal_retry))
            }
        }
    }
}
