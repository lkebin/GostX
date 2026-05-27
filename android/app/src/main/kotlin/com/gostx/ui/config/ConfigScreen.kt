package com.gostx.ui.config

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.gostx.data.ConfigRepository

@Composable fun ConfigScreen(repo: ConfigRepository, onBack: () -> Unit) { Text("Config") }
