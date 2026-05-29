package cn.liukebin.GostX.ui

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Logs : Screen("logs")
    object Config : Screen("config")
}
