package cn.liukebin.GostX.service

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import cn.liukebin.GostX.data.ConfigRepository
import cn.liukebin.GostX.data.GlobalVpnState
import cn.liukebin.GostX.data.LogRepository
import cn.liukebin.GostX.data.VpnStatus
import cn.liukebin.GostX.notification.NOTIFICATION_ID
import cn.liukebin.GostX.notification.NotificationHelper
import java.lang.NoSuchMethodException
import java.lang.reflect.InvocationTargetException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal fun shouldRestartVpnOnNetworkAvailable(
    status: VpnStatus,
    isVpnNetwork: Boolean,
    restartInProgress: Boolean
): Boolean = status == VpnStatus.CONNECTED && !isVpnNetwork && !restartInProgress

class GostVpnService : VpnService() {

    companion object {
        const val ACTION_START = "cn.liukebin.GostX.START_VPN"
        const val ACTION_STOP = "cn.liukebin.GostX.STOP_VPN"

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

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        log("Uncaught coroutine exception: ${throwable.message}")
        GlobalVpnState.setError("意外错误: ${throwable.message}")
        stopSelf()
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
    private var tunFd: ParcelFileDescriptor? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var vpnLogJob: Job? = null
    private lateinit var configRepo: ConfigRepository
    @Volatile private var reconnectInProgress = false
    // Timestamp (ms) of the last successful VPN connect. Suppresses onAvailable
    // restarts for RECONNECT_COOLDOWN_MS after each connect because Android fires
    // onAvailable for all existing non-VPN networks whenever VPN routing changes,
    // which would otherwise trigger an infinite restart loop.
    @Volatile private var lastVpnConnectTime = 0L
    private val RECONNECT_COOLDOWN_MS = 30_000L

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannel(this)
        configRepo = ConfigRepository(getSharedPreferences("gostx_prefs", Context.MODE_PRIVATE))
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) {
            // Promote to foreground synchronously here — before any coroutine —
            // to satisfy the 5-second requirement and supply the mandatory
            // foreground service type on API 34 (UPSIDE_DOWN_CAKE)+.
            promoteToForeground("")
        }
        when (intent?.action) {
            ACTION_START -> scope.launch { startVpn() }
            ACTION_STOP -> scope.launch { stopVpn() }
        }
        return START_STICKY
    }

    // API-safe startForeground: supplies FOREGROUND_SERVICE_TYPE_SPECIAL_USE on API 34+
    // to avoid MissingForegroundServiceTypeException when foregroundServiceType is declared
    // in the manifest.
    private fun promoteToForeground(addr: String) {
        val notification = NotificationHelper.buildRunningNotification(this, addr)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startVpn() {
        GlobalVpnState.setConnecting()
        // startForeground already called synchronously in onStartCommand

        val yaml = configRepo.getActiveConfig()

        val vpnDnsAddr: String
        try {
            GostLibBridge.startVPNMode(yaml)
            vpnDnsAddr = GostLibBridge.getVpnDnsAddr()
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
            .addDnsServer(if (vpnDnsAddr.isNotEmpty()) vpnDnsAddr else "8.8.8.8")
            .setSession("GostX")
            .setBlocking(false)
            // Exclude our own app from VPN routing so gost's outbound connections
            // bypass the TUN interface, preventing the tun2socks→gost→tun2socks
            // routing loop that causes OOM crashes.
            .addDisallowedApplication(packageName)

        tunFd = builder.establish() ?: run {
            log("Failed to establish VPN interface")
            GlobalVpnState.setError("VPN 接口建立失败，请先授予 VPN 权限")
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

        try {
            val status = GostLibBridge.getStatus()
            val addr = parseFirstAddress(status)
            GlobalVpnState.setConnected(addr)
            lastVpnConnectTime = System.currentTimeMillis()
            promoteToForeground(addr)  // update notification with actual listen address
            log("VPN started, gost status: $status")
            registerNetworkCallback()
            saveLastRunState(true)
            startVpnLogPolling()
        } catch (e: Exception) {
            log("VPN post-start error: ${e.message}")
            GlobalVpnState.setError("VPN 启动后错误: ${e.message}")
            closeTun()
            GostLibBridge.stopVPN()
            GostLibBridge.stop()
            stopSelf()
        }
    }

    private fun stopVpn(updatePersistentState: Boolean = true) {
        unregisterNetworkCallback()
        vpnLogJob?.cancel()
        vpnLogJob = null
        // stopVPN closes tun2socks' dup'd fd (ref count 2→1).
        // closeTun closes the original fd (ref count 1→0), which ends the Android VPN
        // session immediately so the status-bar VPN icon disappears right away.
        // stop() waits for gost connections to drain; we do it last so the icon is
        // already gone before the (potentially slow) gost shutdown completes.
        GostLibBridge.stopVPN()
        closeTun()
        GostLibBridge.stop()
        if (updatePersistentState) {
            GlobalVpnState.setStopped()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
            saveLastRunState(false)
            log("VPN stopped")
        } else {
            // Reconnect path: keep the foreground service running so Android does
            // not consider the session ended. Status goes back to CONNECTING so
            // the UI shows a brief reconnecting indicator without a full STOPPED flash.
            GlobalVpnState.setConnecting()
            log("VPN restarting after network change")
        }
    }

    private fun closeTun() {
        tunFd?.close()
        tunFd = null
    }

    private fun log(msg: String) = LogRepository.append(msg)

    /** Polls the Go VPN log buffer every second and forwards entries to LogRepository. */
    private fun startVpnLogPolling() {
        vpnLogJob = scope.launch {
            while (isActive) {
                delay(1000)
                val msgs = GostLibBridge.getVPNLog()
                if (msgs.isNotEmpty()) {
                    msgs.trimEnd('\n').split('\n').forEach { line ->
                        if (line.isNotEmpty()) LogRepository.append(line)
                    }
                }
            }
        }
    }

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
                val isVpnNetwork = cm.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                // Ignore network-available callbacks fired within the cooldown window
                // after VPN connects. Android re-notifies all non-VPN networks whenever
                // VPN routing changes; without this guard each notification would trigger
                // a restart, creating an infinite loop.
                val cooldownExpired =
                    (System.currentTimeMillis() - lastVpnConnectTime) > RECONNECT_COOLDOWN_MS
                if (cooldownExpired && shouldRestartVpnOnNetworkAvailable(
                        status = GlobalVpnState.state.value.status,
                        isVpnNetwork = isVpnNetwork,
                        restartInProgress = reconnectInProgress
                    )) {
                    reconnectInProgress = true
                    scope.launch {
                        try {
                            stopVpn(updatePersistentState = false)
                            startVpn()
                        } finally {
                            reconnectInProgress = false
                        }
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

internal object GostLibBridge {
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
        } catch (e: NoSuchMethodException) {
            throw IllegalStateException("gostlib.Gostlib#$name not found; verify the generated AAR matches the expected API", e)
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

    fun getVPNLog(): String = invoke("getVPNLog") as? String ?: ""

    fun getVpnDnsAddr(): String = invoke("getVPNDNSAddr") as? String ?: ""

    fun validateConfig(yaml: String): String = invoke("validateConfig", yaml) as? String ?: ""
}
