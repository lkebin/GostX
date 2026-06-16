package cn.liukebin.gostx.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.liukebin.gostx.R
import cn.liukebin.gostx.data.AppFilterMode
import cn.liukebin.gostx.data.ConfigRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    repo: ConfigRepository,
    onNavigateToAppFilter: () -> Unit = {},
    onNavigateToFileManage: () -> Unit = {},
    onBack: () -> Unit = {},
    vm: SettingsViewModel = viewModel(
        factory = remember(repo) {
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(
                    modelClass: Class<T>,
                    extras: CreationExtras
                ): T = SettingsViewModel(repo) as T
            }
        }
    )
) {
    val loggingEnabled by vm.loggingEnabled.collectAsState()
    val logLevel by vm.logLevel.collectAsState()
    val appFilterEnabled by vm.appFilterEnabled.collectAsState()
    val appFilterMode by vm.appFilterMode.collectAsState()
    val appFilterList by vm.appFilterList.collectAsState()

    var showLogLevelDialog by remember { mutableStateOf(false) }

    val logLevelOptions = remember {
        listOf("error", "warn", "info", "debug", "trace")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.nav_back)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .padding(vertical = 8.dp)
        ) {
            DualTargetSwitchItem(
                icon = Icons.AutoMirrored.Filled.Article,
                title = stringResource(R.string.settings_logging_label),
                description = stringResource(R.string.settings_logging_restart_hint),
                checked = loggingEnabled,
                onCheckedChange = { vm.setLoggingEnabled(it) },
                onTextClick = if (loggingEnabled) {
                    { showLogLevelDialog = true }
                } else null
            )

            if (loggingEnabled) {
                SettingItem(
                    icon = null,
                    title = stringResource(R.string.settings_log_level_label),
                    description = logLevelLabel(logLevel),
                    onClick = { showLogLevelDialog = true },
                    trailing = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )
            }

            HorizontalDivider(modifier = Modifier.padding(top = 16.dp))

            DualTargetSwitchItem(
                icon = Icons.Outlined.FilterAlt,
                title = stringResource(R.string.settings_app_filter_label),
                description = stringResource(R.string.settings_app_filter_hint),
                checked = appFilterEnabled,
                onCheckedChange = { vm.setAppFilterEnabled(it) },
                onTextClick = if (appFilterEnabled) onNavigateToAppFilter else null
            )

            if (appFilterEnabled) {
                SettingItem(
                    icon = null,
                    title = stringResource(R.string.settings_app_filter_manage),
                    description = stringResource(R.string.settings_app_filter_count, appFilterList.size),
                    onClick = onNavigateToAppFilter,
                    trailing = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = stringResource(R.string.nav_config),
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )

                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        onClick = { vm.setAppFilterMode(AppFilterMode.BLACKLIST) },
                        selected = appFilterMode == AppFilterMode.BLACKLIST,
                        label = { Text(stringResource(R.string.settings_app_filter_mode_blacklist)) }
                    )
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        onClick = { vm.setAppFilterMode(AppFilterMode.WHITELIST) },
                        selected = appFilterMode == AppFilterMode.WHITELIST,
                        label = { Text(stringResource(R.string.settings_app_filter_mode_whitelist)) }
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(top = 16.dp))

            SettingItem(
                icon = Icons.Outlined.FolderOpen,
                title = stringResource(R.string.file_manage_title),
                onClick = onNavigateToFileManage,
                trailing = {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = stringResource(R.string.nav_config),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            )
        }
    }

    // Log level radio dialog — Settings guidelines prefer radio dialogs over dropdowns
    if (showLogLevelDialog) {
        AlertDialog(
            onDismissRequest = { showLogLevelDialog = false },
            title = { Text(stringResource(R.string.settings_log_level_label)) },
            text = {
                Column {
                    logLevelOptions.forEach { level ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.setLogLevel(level)
                                    showLogLevelDialog = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = logLevel == level,
                                onClick = {
                                    vm.setLogLevel(level)
                                    showLogLevelDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = logLevelLabel(level),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLogLevelDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun logLevelLabel(level: String): String = when (level) {
    "error" -> stringResource(R.string.settings_log_level_error)
    "warn" -> stringResource(R.string.settings_log_level_warn)
    "info" -> stringResource(R.string.settings_log_level_info)
    "debug" -> stringResource(R.string.settings_log_level_debug)
    "trace" -> stringResource(R.string.settings_log_level_trace)
    else -> level
}

// Dual-target switch: left text area navigates (or shows dialog), right switch toggles.
// Follows Android Settings guidelines for switches with sub-options.
@Composable
private fun DualTargetSwitchItem(
    icon: ImageVector,
    title: String,
    description: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onTextClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(modifier = Modifier.width(16.dp))
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .then(
                    if (onTextClick != null) Modifier.clickable(onClick = onTextClick)
                    else Modifier
                )
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium
            )
            if (description != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
        Spacer(modifier = Modifier.width(8.dp))
    }
}

@Composable
private fun SettingItem(
    icon: ImageVector? = null,
    title: String,
    description: String? = null,
    onClick: () -> Unit,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(modifier = Modifier.width(if (icon != null) 16.dp else 56.dp))
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(16.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium
            )
            if (description != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
        if (trailing != null) {
            Spacer(modifier = Modifier.width(8.dp))
            trailing()
        }
        Spacer(modifier = Modifier.width(16.dp))
    }
}
