package com.gostx

import android.app.Activity
import android.content.Context
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.gostx.data.ConfigRepository
import com.gostx.data.GlobalVpnState
import com.gostx.ui.Screen
import com.gostx.ui.config.ConfigScreen
import com.gostx.ui.home.HomeScreen
import com.gostx.ui.log.LogScreen
import com.gostx.service.GostVpnService

class MainActivity : ComponentActivity() {
    private lateinit var configRepository: ConfigRepository

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            GostVpnService.start(this)
        } else {
            GlobalVpnState.setError("VPN 权限被拒绝")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("gostx_prefs", Context.MODE_PRIVATE)
        configRepository = ConfigRepository(prefs)
        setContent { GostXApp(configRepository, onRequestVpnPermission = ::requestVpnPermission) }
    }

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent == null) {
            GostVpnService.start(this)
        } else {
            vpnPermissionLauncher.launch(intent)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GostXApp(
    configRepository: ConfigRepository,
    onRequestVpnPermission: () -> Unit = {}
) {
    val navController = rememberNavController()
    MaterialTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("GostX") },
                    actions = {
                        IconButton(onClick = { navController.navigate(Screen.Logs.route) }) {
                            Icon(Icons.AutoMirrored.Filled.Article, contentDescription = "日志")
                        }
                        IconButton(onClick = { navController.navigate(Screen.Config.route) }) {
                            Icon(Icons.Filled.Settings, contentDescription = "配置")
                        }
                    }
                )
            }
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = Screen.Home.route,
                modifier = Modifier.padding(padding)
            ) {
                composable(Screen.Home.route) {
                    HomeScreen(onRequestVpnPermission = onRequestVpnPermission)
                }
                composable(Screen.Logs.route) { LogScreen(onBack = { navController.popBackStack() }) }
                composable(Screen.Config.route) {
                    ConfigScreen(repo = configRepository, onBack = { navController.popBackStack() })
                }
            }
        }
    }
}
