package cn.liukebin.GostX.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.res.stringResource
import cn.liukebin.GostX.R
import cn.liukebin.GostX.data.VpnStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onRequestVpnPermission: () -> Unit = {},
    onNavigateToLogs: () -> Unit = {},
    onNavigateToConfig: () -> Unit = {},
    vm: HomeViewModel = viewModel()
) {
    val state by vm.vpnState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("GostX") },
                actions = {
                    IconButton(onClick = onNavigateToLogs) {
                        Icon(Icons.AutoMirrored.Filled.Article, contentDescription = stringResource(R.string.nav_log))
                    }
                    IconButton(onClick = onNavigateToConfig) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.nav_config))
                    }
                }
            )
        }
    ) { scaffoldPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(scaffoldPadding).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val dotColor = when (state.status) {
                VpnStatus.CONNECTED -> Color(0xFF4CAF50)
                VpnStatus.CONNECTING -> Color(0xFFFFC107)
                VpnStatus.ERROR -> Color(0xFFF44336)
                VpnStatus.STOPPED -> Color(0xFF9E9E9E)
            }
            Surface(
                modifier = Modifier.size(12.dp),
                shape = MaterialTheme.shapes.extraSmall,
                color = dotColor
            ) {}
            Spacer(Modifier.width(8.dp))
            Text(
                text = when (state.status) {
                    VpnStatus.CONNECTED -> stringResource(R.string.status_running)
                    VpnStatus.CONNECTING -> stringResource(R.string.status_connecting)
                    VpnStatus.ERROR -> stringResource(R.string.status_error)
                    VpnStatus.STOPPED -> stringResource(R.string.status_stopped)
                },
                style = MaterialTheme.typography.titleMedium
            )
        }

        if (state.listenAddr.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.listen_addr, state.listenAddr),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        state.error?.let { err ->
            Spacer(Modifier.height(8.dp))
            Text(
                err,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(Modifier.height(48.dp))

        Button(
            onClick = { vm.toggleVpn(onRequestVpnPermission) },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            enabled = state.status != VpnStatus.CONNECTING
        ) {
            Text(
                text = if (state.status == VpnStatus.CONNECTED ||
                    state.status == VpnStatus.CONNECTING
                ) stringResource(R.string.vpn_stop) else stringResource(R.string.vpn_start),
                style = MaterialTheme.typography.titleMedium
            )
        }

        }  // end Column
    }  // end Scaffold
}
