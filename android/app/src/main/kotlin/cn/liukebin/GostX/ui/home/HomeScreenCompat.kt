package cn.liukebin.GostX.ui.home

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import cn.liukebin.GostX.data.ConfigRepository

@Composable
fun HomeScreen(
    onRequestVpnPermission: () -> Unit = {},
    onNavigateToLogs: () -> Unit = {},
    onNavigateToConfig: () -> Unit = {}
) {
    val context = LocalContext.current
    val repo = remember(context) {
        ConfigRepository(context.getSharedPreferences("gostx_prefs", Context.MODE_PRIVATE))
    }

    HomeScreen(
        repo = repo,
        onRequestVpnPermission = onRequestVpnPermission,
        onNavigateToLogs = onNavigateToLogs,
        onNavigateToConfigEdit = { profileId ->
            repo.setActiveProfile(profileId)
            onNavigateToConfig()
        }
    )
}
