package cn.liukebin.gostx.ui.config

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.liukebin.gostx.R
import cn.liukebin.gostx.data.ConfigRepository

@Composable
private fun RenameProfileDialog(
    currentName: String,
    otherNames: Set<String>,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember(currentName) { mutableStateOf(currentName) }
    val trimmed = name.trim()
    val isDuplicate = trimmed != currentName && trimmed in otherNames
    val hasComma = ',' in trimmed
    val isError = isDuplicate || hasComma

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profile_rename_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.profile_name_label)) },
                isError = isError,
                supportingText = when {
                    isDuplicate -> { { Text(stringResource(R.string.profile_name_duplicate)) } }
                    hasComma -> { { Text(stringResource(R.string.profile_name_invalid_char)) } }
                    else -> null
                },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(trimmed) },
                enabled = !isError && trimmed.isNotEmpty() && trimmed != currentName
            ) {
                Text(stringResource(R.string.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen(
    repo: ConfigRepository,
    profileId: String,
    onBack: () -> Unit,
    vm: ConfigViewModel = viewModel(
        key = profileId,
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ConfigViewModel(repo, profileId) as T
        }
    )
) {
    val state by vm.uiState.collectAsState()
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showSaved by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.navBack.collect { onBack() }
    }

    LaunchedEffect(state.canDelete) {
        if (!state.canDelete) showDeleteConfirm = false
    }

    LaunchedEffect(state.isSaved) {
        if (state.isSaved) {
            showSaved = true
            kotlinx.coroutines.delay(2000)
            showSaved = false
        }
    }

    if (state.validationError != null) {
        AlertDialog(
            onDismissRequest = { vm.clearValidationError() },
            title = { Text(stringResource(R.string.config_error_title)) },
            text = { Text(state.validationError!!) },
            confirmButton = {
                TextButton(onClick = { vm.clearValidationError() }) {
                    Text(stringResource(R.string.action_ok))
                }
            }
        )
    }

    if (showRenameDialog) {
        RenameProfileDialog(
            currentName = state.profileName,
            otherNames = state.otherProfileNames,
            onConfirm = { vm.renameProfile(it); showRenameDialog = false },
            onDismiss = { showRenameDialog = false }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.profile_delete_confirm_title)) },
            text = { Text(stringResource(R.string.profile_delete_confirm_message, state.profileName)) },
            confirmButton = {
                TextButton(onClick = { showDeleteConfirm = false; vm.deleteProfile() }) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.profileName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.nav_back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { vm.save() }) {
                        Icon(Icons.Filled.Save, contentDescription = stringResource(R.string.action_save))
                    }
                    IconButton(onClick = { showRenameDialog = true }) {
                        Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.profile_rename))
                    }
                    IconButton(
                        onClick = { showDeleteConfirm = true },
                        enabled = state.canDelete
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.action_delete)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            OutlinedTextField(
                value = state.yaml,
                onValueChange = { vm.onYamlChange(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                minLines = 10,
            )

            AnimatedVisibility(
                visible = showSaved,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Column {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.config_saved),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
