package cn.liukebin.GostX.ui.home

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.liukebin.GostX.R
import cn.liukebin.GostX.data.ConfigProfile
import cn.liukebin.GostX.data.ConfigRepository
import cn.liukebin.GostX.data.VpnStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    repo: ConfigRepository,
    onRequestVpnPermission: () -> Unit = {},
    onNavigateToLogs: () -> Unit = {},
    onNavigateToConfigEdit: (profileId: String) -> Unit = {},
    vm: HomeViewModel = viewModel(
        factory = remember(repo) {
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(
                    modelClass: Class<T>,
                    extras: CreationExtras
                ): T {
                    val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
                    return HomeViewModel(app, repo) as T
                }
            }
        }
    )
) {
    val vpnState by vm.vpnState.collectAsState()
    val homeState by vm.homeState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showAddDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val existingNames = remember(homeState.profiles) { homeState.profiles.map { it.name }.toSet() }
    val nextDefaultName = remember(homeState.profiles) { repo.getNextDefaultName() }

    LaunchedEffect(vpnState) {
        when {
            vpnState.status == VpnStatus.CONNECTED && vpnState.listenAddr.isNotEmpty() ->
                snackbarHostState.showSnackbar(context.getString(R.string.listen_addr, vpnState.listenAddr))
            vpnState.status == VpnStatus.ERROR && vpnState.error != null ->
                snackbarHostState.showSnackbar(vpnState.error!!)
        }
    }

    if (showAddDialog) {
        AddProfileDialog(
            existingNames = existingNames,
            initialName = nextDefaultName,
            onConfirm = { profileId ->
                // ConfigProfile.id == name by design (see ConfigRepository); name is the profile ID
                if (vm.addProfile(profileId)) {
                    showAddDialog = false
                    onNavigateToConfigEdit(profileId)
                }
            },
            onDismiss = { showAddDialog = false }
        )
    }

    val isTransitioning = vpnState.status == VpnStatus.CONNECTING || vpnState.status == VpnStatus.STOPPING

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("GostX") },
                actions = {
                    IconButton(onClick = onNavigateToLogs) {
                        Icon(
                            Icons.AutoMirrored.Filled.Article,
                            contentDescription = stringResource(R.string.nav_log)
                        )
                    }
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = stringResource(R.string.profile_add)
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { if (!isTransitioning) vm.toggleVpn(onRequestVpnPermission) },
                modifier = Modifier.alpha(if (isTransitioning) 0.5f else 1f),
                containerColor = when (vpnState.status) {
                    VpnStatus.CONNECTED -> Color(0xFF4CAF50)
                    VpnStatus.ERROR -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.primaryContainer
                }
            ) {
                if (isTransitioning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        painter = painterResource(R.drawable.ic_tile_vpn),
                        contentDescription = if (vpnState.status == VpnStatus.CONNECTED)
                            stringResource(R.string.vpn_stop_label)
                        else
                            stringResource(R.string.vpn_start_label)
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            items(homeState.profiles, key = { it.id }) { profile ->
                ProfileListItem(
                    profile = profile,
                    isActive = profile.id == homeState.activeProfileId,
                    radioEnabled = canSetActiveProfile(vpnState.status),
                    onActivate = { vm.setActiveProfile(profile.id) },
                    onEdit = { onNavigateToConfigEdit(profile.id) }
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun ProfileListItem(
    profile: ConfigProfile,
    isActive: Boolean,
    radioEnabled: Boolean,
    onActivate: () -> Unit,
    onEdit: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = isActive,
            onClick = onActivate,
            enabled = radioEnabled,
            modifier = Modifier.padding(start = 8.dp)
        )
        Text(
            text = profile.name,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
            style = MaterialTheme.typography.bodyLarge
        )
        IconButton(onClick = onEdit) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(R.string.nav_config)
            )
        }
    }
}
