package cn.liukebin.gostx.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.liukebin.gostx.service.GostVpnService

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
