package cn.liukebin.gostx

import android.app.Activity
import android.content.Context
import android.net.VpnService
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import cn.liukebin.gostx.data.ConfigRepository
import cn.liukebin.gostx.data.GlobalVpnState
import cn.liukebin.gostx.ui.Screen
import cn.liukebin.gostx.ui.config.ConfigScreen
import cn.liukebin.gostx.ui.home.HomeScreen
import cn.liukebin.gostx.ui.log.LogScreen
import cn.liukebin.gostx.ui.settings.AppFilterScreen
import cn.liukebin.gostx.ui.filemanage.FileManageScreen
import cn.liukebin.gostx.ui.settings.SettingsScreen
import cn.liukebin.gostx.service.GostVpnService
import androidx.compose.foundation.isSystemInDarkTheme

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
        enableEdgeToEdge()
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
    val darkTheme = isSystemInDarkTheme()
    val colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (darkTheme) darkColorScheme() else lightColorScheme()
    }
    MaterialTheme(colorScheme = colorScheme) {
        Surface(modifier = Modifier.fillMaxSize()) {
            NavHost(
                navController = navController,
                startDestination = Screen.Home.route,
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { ExitTransition.None }
            ) {
                composable(Screen.Home.route) {
                    HomeScreen(
                        repo = configRepository,
                        onRequestVpnPermission = onRequestVpnPermission,
                        onNavigateToLogs = { navController.navigate(Screen.Logs.route) },
                        onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                        onNavigateToConfigEdit = { profileId ->
                            navController.navigate(Screen.ConfigEdit.createRoute(profileId))
                        }
                    )
                }
                composable(Screen.Logs.route) { LogScreen(onBack = { navController.popBackStack() }) }
                composable(Screen.Settings.route) {
                    SettingsScreen(
                        repo = configRepository,
                        onNavigateToAppFilter = { navController.navigate(Screen.AppFilter.route) },
                        onNavigateToFileManage = { navController.navigate(Screen.FileManage.route) },
                        onBack = { navController.popBackStack() }
                    )
                }
                composable(Screen.AppFilter.route) {
                    AppFilterScreen(
                        repo = configRepository,
                        onBack = { navController.popBackStack() }
                    )
                }
                composable(Screen.FileManage.route) {
                    FileManageScreen(
                        onBack = { navController.popBackStack() }
                    )
                }
                composable(
                    route = Screen.ConfigEdit.route,
                    arguments = listOf(navArgument("profileId") { type = NavType.StringType })
                ) { backStackEntry ->
                    val profileId = backStackEntry.arguments?.getString("profileId")
                        ?.let(Uri::decode)
                        ?: configRepository.getActiveProfileId()
                    ConfigScreen(
                        repo = configRepository,
                        profileId = profileId,
                        onBack = { navController.popBackStack() }
                    )
                }
            }
        }
    }
}
