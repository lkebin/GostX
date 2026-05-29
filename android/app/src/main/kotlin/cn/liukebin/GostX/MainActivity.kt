package cn.liukebin.GostX

import android.app.Activity
import android.content.Context
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import cn.liukebin.GostX.data.ConfigRepository
import cn.liukebin.GostX.data.GlobalVpnState
import cn.liukebin.GostX.ui.Screen
import cn.liukebin.GostX.ui.config.ConfigScreen
import cn.liukebin.GostX.ui.home.HomeScreen
import cn.liukebin.GostX.ui.log.LogScreen
import cn.liukebin.GostX.service.GostVpnService

class MainActivity : ComponentActivity() {
    private lateinit var configRepository: ConfigRepository

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            GostVpnService.start(this)
        } else {
            GlobalVpnState.setError(getString(R.string.vpn_permission_denied))
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

@Composable
fun GostXApp(
    configRepository: ConfigRepository,
    onRequestVpnPermission: () -> Unit = {}
) {
    val navController = rememberNavController()
    MaterialTheme {
        Scaffold { padding ->
            NavHost(
                navController = navController,
                startDestination = Screen.Home.route,
                modifier = Modifier.padding(padding),
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { ExitTransition.None }
            ) {
                composable(Screen.Home.route) {
                    HomeScreen(
                        onRequestVpnPermission = onRequestVpnPermission,
                        onNavigateToLogs = { navController.navigate(Screen.Logs.route) },
                        onNavigateToConfig = { navController.navigate(Screen.Config.route) }
                    )
                }
                composable(Screen.Logs.route) { LogScreen(onBack = { navController.popBackStack() }) }
                composable(Screen.Config.route) {
                    ConfigScreen(repo = configRepository, onBack = { navController.popBackStack() })
                }
            }
        }
    }
}
