package com.gostx

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
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
import com.gostx.ui.Screen
import com.gostx.ui.config.ConfigScreen
import com.gostx.ui.home.HomeScreen
import com.gostx.ui.log.LogScreen

class MainActivity : ComponentActivity() {
    private lateinit var configRepository: ConfigRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("gostx_prefs", Context.MODE_PRIVATE)
        configRepository = ConfigRepository(prefs)
        setContent { GostXApp(configRepository) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GostXApp(configRepository: ConfigRepository) {
    val navController = rememberNavController()
    MaterialTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("GostX") },
                    actions = {
                        IconButton(onClick = { navController.navigate(Screen.Logs.route) }) {
                            Icon(Icons.Filled.Article, contentDescription = "日志")
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
                composable(Screen.Home.route) { HomeScreen() }
                composable(Screen.Logs.route) { LogScreen(onBack = { navController.popBackStack() }) }
                composable(Screen.Config.route) {
                    ConfigScreen(repo = configRepository, onBack = { navController.popBackStack() })
                }
            }
        }
    }
}
