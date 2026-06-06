package cn.liukebin.gostx.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FabPosition
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalLifecycleOwner
import cn.liukebin.gostx.R
import cn.liukebin.gostx.data.ConfigProfile
import cn.liukebin.gostx.data.ConfigRepository
import cn.liukebin.gostx.data.VpnStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    repo: ConfigRepository,
    onRequestVpnPermission: () -> Unit = {},
    onNavigateToLogs: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
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
    val batteryOptimizationNeeded by vm.batteryOptimizationNeeded.collectAsState()
    val loggingEnabled by vm.loggingEnabled.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showAddDialog by remember { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                vm.checkBatteryOptimization()
                vm.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val existingNames = remember(homeState.profiles) { homeState.profiles.map { it.name }.toSet() }
    val nextDefaultName = remember(homeState.profiles) { repo.getNextDefaultName() }

    LaunchedEffect(vpnState) {
        if (vpnState.status == VpnStatus.ERROR && vpnState.error != null) {
            snackbarHostState.showSnackbar(
                message = vpnState.error!!,
                withDismissAction = true,
                duration = SnackbarDuration.Indefinite
            )
        }
    }

    if (showAddDialog) {
        AddProfileDialog(
            existingNames = existingNames,
            initialName = nextDefaultName,
            onConfirm = { name ->
                val newId = vm.addProfile(name)
                if (newId != null) {
                    showAddDialog = false
                    onNavigateToConfigEdit(newId)
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
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.profile_add))
                    }
                    if (loggingEnabled) {
                        IconButton(onClick = onNavigateToLogs) {
                            Icon(
                                Icons.AutoMirrored.Filled.Article,
                                contentDescription = stringResource(R.string.nav_log)
                            )
                        }
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.nav_settings)
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    if (!isTransitioning) vm.toggleVpn(onRequestVpnPermission)
                },
                modifier = Modifier.size(72.dp),
                shape = CircleShape,
                containerColor = when (vpnState.status) {
                    VpnStatus.CONNECTED -> MaterialTheme.colorScheme.primary
                    VpnStatus.ERROR -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.primaryContainer
                },
                contentColor = when (vpnState.status) {
                    VpnStatus.CONNECTED -> MaterialTheme.colorScheme.onPrimary
                    VpnStatus.ERROR -> MaterialTheme.colorScheme.onError
                    else -> MaterialTheme.colorScheme.onPrimaryContainer
                }
            ) {
                if (isTransitioning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        trackColor = Color.Transparent
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
        floatingActionButtonPosition = FabPosition.Center,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            if (batteryOptimizationNeeded) {
                BatteryOptimizationBanner(
                    onOpenSettings = { vm.openBatteryOptimizationSettings() },
                    onDismiss = { vm.dismissBatteryOptimizationPrompt() }
                )
            }
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(homeState.profiles, key = { it.id }) { profile ->
                    Card(
                        shape = MaterialTheme.shapes.medium,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        ProfileListItem(
                            profile = profile,
                            isActive = profile.id == homeState.activeProfileId,
                            radioEnabled = canSetActiveProfile(vpnState.status),
                            onActivate = { vm.setActiveProfile(profile.id) },
                            onEdit = { onNavigateToConfigEdit(profile.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BatteryOptimizationBanner(
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = stringResource(R.string.battery_opt_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.battery_opt_dismiss))
                }
                Spacer(modifier = Modifier.size(4.dp))
                TextButton(onClick = onOpenSettings) {
                    Text(stringResource(R.string.battery_opt_open_settings))
                }
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
    ListItem(
        headlineContent = { Text(profile.name) },
        leadingContent = {
            RadioButton(
                selected = isActive,
                onClick = onActivate,
                enabled = radioEnabled
            )
        },
        trailingContent = {
            IconButton(onClick = onEdit) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = stringResource(R.string.nav_config)
                )
            }
        }
    )
}
