package cn.liukebin.GostX.ui

import android.net.Uri

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Logs : Screen("logs")
    object Settings : Screen("settings")
    object ConfigEdit : Screen("config/{profileId}") {
        fun createRoute(profileId: String): String = "config/${Uri.encode(profileId)}"
    }
}
