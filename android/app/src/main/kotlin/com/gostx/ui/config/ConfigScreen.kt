package com.gostx.ui.config

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gostx.data.ConfigRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen(
    repo: ConfigRepository,
    onBack: () -> Unit,
    vm: ConfigViewModel = viewModel(factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ConfigViewModel(repo) as T
    })
) {
    val state by vm.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("配置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = { TextButton(onClick = { vm.save() }) { Text("保存") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            if (state.profiles.size > 1) {
                Row {
                    state.profiles.forEach { id ->
                        FilterChip(
                            selected = id == state.activeProfileId,
                            onClick = { vm.switchProfile(id) },
                            label = { Text(id) },
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            OutlinedTextField(
                value = state.yaml,
                onValueChange = { vm.onYamlChange(it) },
                modifier = Modifier.fillMaxWidth().weight(1f),
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                isError = state.validationError != null
            )

            state.validationError?.let { err ->
                Spacer(Modifier.height(4.dp))
                Text(
                    err,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (state.isSaved) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "已保存",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(Modifier.height(8.dp))
            Row {
                OutlinedButton(onClick = { vm.validate() }) { Text("验证") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { vm.resetToDefault() }) { Text("默认模板") }
            }
        }
    }
}
