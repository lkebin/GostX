package com.gostx.service

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.gostx.data.ConfigRepository
import com.gostx.data.GlobalVpnState
import com.gostx.data.LogRepository
import com.gostx.data.VpnStatus
import com.gostx.notification.NOTIFICATION_ID
import com.gostx.notification.NotificationHelper
import java.lang.reflect.InvocationTargetException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class GostVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.gostx.START_VPN"
        const val ACTION_STOP = "com.gostx.STOP_VPN"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, GostVpnService::class.java).apply {
                action = ACTION_START
            })
        }

        fun stop(context: Context) {
            context.startService(Intent(context, GostVpnService::class.java).apply {
                action = ACTION_STOP
            })
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tunFd: ParcelFileDescriptor? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private lateinit var configRepo: ConfigRepository

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannel(this)
        configRepo = ConfigRepository(getSharedPreferences("gostx_prefs", Context.MODE_PRIVATE))
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> scope.launch { startVpn() }
            ACTION_STOP -> scope.launch { stopVpn() }
        }
        return START_STICKY
    }

    private fun startVpn() {
        GlobalVpnState.setConnecting()
        startForeground(NOTIFICATION_ID, NotificationHelper.buildRunningNotification(this, ""))

        val yaml = configRepo.getActiveConfig()

        try {
            GostLibBridge.startVPNMode(yaml)
        } catch (e: Exception) {
            log("gost start failed: ${e.message}")
            GlobalVpnState.setError("gost 启动失败: ${e.message}")
            stopSelf()
            return
        }

        val builder = Builder()
            .setMtu(1500)
            .addAddress("10.0.0.2", 24)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("8.8.8.8")
            .setSession("GostX")
            .setBlocking(false)

        tunFd = builder.establish() ?: run {
            log("Failed to establish VPN interface")
            GlobalVpnState.setError("VPN 接口建立失败")
            GostLibBridge.stop()
            stopSelf()
            return
        }

        try {
            GostLibBridge.startVPN(tunFd!!.fd.toLong(), 1500L)
        } catch (e: Exception) {
            log("tun2socks start failed: ${e.message}")
            GlobalVpnState.setError("tun2socks 启动失败: ${e.message}")
            closeTun()
            GostLibBridge.stop()
            stopSelf()
            return
        }

        val status = GostLibBridge.getStatus()
        val addr = parseFirstAddress(status)
        GlobalVpnState.setConnected(addr)
        startForeground(NOTIFICATION_ID, NotificationHelper.buildRunningNotification(this, addr))
        log("VPN started, gost status: $status")

        registerNetworkCallback()
        saveLastRunState(true)
    }

    private fun stopVpn() {
        unregisterNetworkCallback()
        GostLibBridge.stopVPN()
        GostLibBridge.stop()
        closeTun()
        GlobalVpnState.setStopped()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        saveLastRunState(false)
        log("VPN stopped")
    }

    private fun closeTun() {
        tunFd?.close()
        tunFd = null
    }

    private fun log(msg: String) = LogRepository.append(msg)

    private fun parseFirstAddress(statusJson: String): String {
        val match = Regex(""""addresses":\["([^"]+)"""").find(statusJson)
        return match?.groupValues?.get(1) ?: ""
    }

    private fun saveLastRunState(running: Boolean) {
        getSharedPreferences("gostx_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("last_vpn_running", running).apply()
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (GlobalVpnState.state.value.status == VpnStatus.CONNECTED) {
                    scope.launch {
                        stopVpn()
                        startVpn()
                    }
                }
            }
        }
        cm.registerNetworkCallback(
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build(),
            cb
        )
        networkCallback = cb
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let {
            (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                .unregisterNetworkCallback(it)
        }
        networkCallback = null
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

private object GostLibBridge {
    private const val CLASS_NAME = "gostlib.Gostlib"

    private fun clazz(): Class<*> = try {
        Class.forName(CLASS_NAME)
    } catch (e: ClassNotFoundException) {
        throw IllegalStateException("gostlib.aar not available; see android/app/libs/README.txt", e)
    }

    private fun invoke(name: String, vararg args: Any): Any? {
        val types = args.map {
            when (it) {
                is Long -> java.lang.Long.TYPE
                is Int -> java.lang.Integer.TYPE
                is Boolean -> java.lang.Boolean.TYPE
                else -> it.javaClass
            }
        }.toTypedArray()
        return try {
            clazz().getMethod(name, *types).invoke(null, *args)
        } catch (e: InvocationTargetException) {
            val cause = e.targetException
            if (cause is Exception) throw cause
            throw RuntimeException(cause)
        }
    }

    fun startVPNMode(yaml: String) {
        invoke("startVPNMode", yaml)
    }

    fun startVPN(fd: Long, mtu: Long) {
        invoke("startVPN", fd, mtu)
    }

    fun stopVPN() {
        runCatching { invoke("stopVPN") }
    }

    fun stop() {
        runCatching { invoke("stop") }
    }

    fun getStatus(): String = invoke("getStatus") as? String ?: ""
}
