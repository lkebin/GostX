package cn.liukebin.GostX.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.liukebin.GostX.service.GostVpnService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val wasRunning = context
            .getSharedPreferences("gostx_prefs", Context.MODE_PRIVATE)
            .getBoolean("last_vpn_running", false)

        if (wasRunning) {
            GostVpnService.start(context)
        }
    }
}
