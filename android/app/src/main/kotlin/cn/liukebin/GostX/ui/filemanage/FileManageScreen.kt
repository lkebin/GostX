package cn.liukebin.gostx.ui.filemanage

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.liukebin.gostx.R
import cn.liukebin.gostx.data.FileInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "%.1f MB".format(bytes.toDouble() / (1024 * 1024))
}

private fun formatTime(millis: Long): String {
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return fmt.format(Date(millis))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileManageScreen(
    onBack: () -> Unit = {},
    vm: FileManageViewModel = viewModel(
        factory = remember {
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(
                    modelClass: Class<T>,
                    extras: CreationExtras
                ): T {
                    val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
                    return FileManageViewModel(app) as T
                }
            }
        }
    )
) {
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) vm.importFile(uri)
    }

    val files by vm.files.collectAsState()
    val pendingOverwrite by vm.pendingOverwrite.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        vm.toastEvent.collect { message ->
            snackbarHostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Short
            )
        }
    }

    // Overwrite confirmation dialog
    if (pendingOverwrite != null) {
        AlertDialog(
            title = { Text(stringResource(R.string.file_overwrite_title)) },
            text = {
                Text(stringResource(R.string.file_overwrite_message, pendingOverwrite!!.second))
            },
            confirmButton = {
                TextButton(onClick = { vm.confirmOverwrite() }) {
                    Text(stringResource(R.string.file_overwrite_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.cancelOverwrite() }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            onDismissRequest = { vm.cancelOverwrite() }
        )
    }

    var renameTarget by remember { mutableStateOf<String?>(null) }

    // Rename dialog
    if (renameTarget != null) {
        var renameInput by remember(renameTarget) { mutableStateOf(renameTarget!!) }
        AlertDialog(
            title = { Text(stringResource(R.string.file_rename_title)) },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    label = { Text(stringResource(R.string.file_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.renameFile(renameTarget!!, renameInput)
                        renameTarget = null
                    },
                    enabled = renameInput.isNotBlank()
                ) {
                    Text(stringResource(R.string.action_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            onDismissRequest = { renameTarget = null }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.file_manage_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.nav_back)
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                modifier = Modifier.size(56.dp),
                shape = RoundedCornerShape(16.dp),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.file_import))
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        if (files.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.file_manage_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(paddingValues)
            ) {
                items(files, key = { it.name }) { file ->
                    FileListItem(
                        file = file,
                        onRename = { renameTarget = file.name },
                        onDelete = { vm.deleteFile(file.name) },
                        onCopyPath = { vm.copyPath(file.name) }
                    )
                }
            }
        }
    }
}

@Composable
private fun FileListItem(
    file: FileInfo,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onCopyPath: () -> Unit
) {
    var deletePending by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }

    // Delete confirmation dialog
    if (deletePending) {
        AlertDialog(
            title = { Text(stringResource(R.string.file_delete_confirm_title)) },
            text = {
                Text(stringResource(R.string.file_delete_confirm_message, file.name))
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete()
                    deletePending = false
                }) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deletePending = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            onDismissRequest = { deletePending = false }
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onCopyPath)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Description,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.name,
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "${formatSize(file.sizeBytes)}  ${formatTime(file.lastModified)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.file_rename)) },
                    onClick = {
                        menuExpanded = false
                        onRename()
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.file_copy_path)) },
                    onClick = {
                        menuExpanded = false
                        onCopyPath()
                    }
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.file_delete),
                            color = MaterialTheme.colorScheme.error
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        deletePending = true
                    }
                )
            }
        }
    }
}
