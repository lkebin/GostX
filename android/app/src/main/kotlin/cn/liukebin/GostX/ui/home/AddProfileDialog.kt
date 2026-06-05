package cn.liukebin.gostx.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import cn.liukebin.gostx.R

/**
 * Dialog for creating a new configuration profile.
 *
 * @param existingNames set of already-used profile names (for duplicate detection)
 * @param initialName pre-filled name, typically from [cn.liukebin.gostx.data.ConfigRepository.getNextDefaultName]
 * @param onConfirm called with the trimmed name when the user taps "Create"
 * @param onDismiss called when the user taps "Cancel" or dismisses the dialog
 */
@Composable
fun AddProfileDialog(
    existingNames: Set<String>,
    initialName: String,
    onConfirm: (name: String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    val trimmed = name.trim()
    val isDuplicate = trimmed in existingNames
    val hasComma = ',' in trimmed
    val isInvalid = trimmed.isEmpty() || isDuplicate || hasComma

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profile_new_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.profile_name_label)) },
                    singleLine = true,
                    isError = isDuplicate || hasComma,
                    supportingText = when {
                        isDuplicate -> { { Text(stringResource(R.string.profile_name_duplicate), color = MaterialTheme.colorScheme.error) } }
                        hasComma -> { { Text(stringResource(R.string.profile_name_invalid_char), color = MaterialTheme.colorScheme.error) } }
                        else -> null
                    }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(trimmed) },
                enabled = !isInvalid
            ) {
                Text(stringResource(R.string.action_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
