package cn.liukebin.GostX.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.annotation.RequiresApi
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
    @Volatile private var vpnLogJob: Job? = null
    private lateinit var configRepo: ConfigRepository
    @Volatile private var reconnectInProgress = false
    // Timestamp (ms) of the last successful VPN connect. Suppresses onAvailable
    // restarts for RECONNECT_COOLDOWN_MS after each connect because Android fires
    // onAvailable for all existing non-VPN networks whenever VPN routing changes,
    // which would otherwise trigger an infinite restart loop.
    @Volatile private var lastVpnConnectTime = 0L
    private val RECONNECT_COOLDOWN_MS = 30_000L
    private var serviceReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannel(this)
        LogRepository.init(this)
        configRepo = ConfigRepository(getSharedPreferences("gostx_prefs", Context.MODE_PRIVATE))
        val workDir = getExternalFilesDir(null)?.absolutePath
            ?: filesDir.absolutePath.also { log("External storage unavailable, falling back to internal: $it") }
        GostLibBridge.setWorkDir(workDir)
            .onFailure { log("WARNING: setWorkDir($workDir) failed: ${it.message}") }
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
            ACTION_START -> scope.launch {
                LogRepository.deleteLog()
                startVpn()
            }
            ACTION_STOP -> scope.launch {
                stopVpn()
            }
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
            GostLibBridge.setMemoryLimit(true)
            registerServiceReceiver()
            startVpnLogPolling()
        } catch (e: Exception) {
            log("VPN post-start error: ${e.message}")
            GlobalVpnState.setError("VPN 启动后错误: ${e.message}")
            GostLibBridge.setMemoryLimit(false)
            closeTun()
            GostLibBridge.stopVPN()
            GostLibBridge.stop()
            stopSelf()
        }
    }

    private fun stopVpn(updatePersistentState: Boolean = true) {
        if (updatePersistentState) GlobalVpnState.setStopping()
        unregisterNetworkCallback()
        vpnLogJob?.cancel()
        vpnLogJob = null
        // Close the ParcelFileDescriptor first: this immediately notifies Android's
        // ConnectivityService that the VPN session is ending, so the status-bar icon
        // disappears right away. The Go side still holds its dup'd fd and can finish
        // cleanly afterwards.
        closeTun()
        GostLibBridge.stopVPN()
        GostLibBridge.stop()
        GostLibBridge.setMemoryLimit(false)
        unregisterServiceReceiver()
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
            // Reconnect path: startVpn() will re-register the receiver on success
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
        vpnLogJob?.cancel()
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

    private fun registerServiceReceiver() {
        unregisterServiceReceiver() // prevent double-registration on reconnect
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        vpnLogJob?.cancel()
                        vpnLogJob = null
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        if (GlobalVpnState.state.value.status == VpnStatus.CONNECTED &&
                            vpnLogJob?.isActive != true
                        ) {
                            startVpnLogPolling()
                        }
                    }
                    PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            handleIdleModeChanged()
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            }
        }
        registerReceiver(receiver, filter)
        serviceReceiver = receiver
    }

    private fun unregisterServiceReceiver() {
        serviceReceiver?.let { unregisterReceiver(it) }
        serviceReceiver = null
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun handleIdleModeChanged() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isDeviceIdleMode) {
            // Entering Doze: stop log polling and network callback to avoid
            // spurious reconnect attempts while the device is deeply sleeping.
            vpnLogJob?.cancel()
            vpnLogJob = null
            unregisterNetworkCallback()
        } else {
            // Exiting Doze: restore network monitoring and resume log polling
            // only if the screen is also on (isInteractive).
            registerNetworkCallback()
            if (pm.isInteractive &&
                GlobalVpnState.state.value.status == VpnStatus.CONNECTED &&
                vpnLogJob?.isActive != true
            ) {
                startVpnLogPolling()
            }
        }
    }

    private fun registerNetworkCallback() {
        unregisterNetworkCallback()
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
        unregisterServiceReceiver()
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

    fun setMemoryLimit(enabled: Boolean) {
        runCatching { invoke("setMemoryLimit", enabled) }
    }

    fun setWorkDir(path: String): Result<Unit> =
        runCatching { invoke("setWorkDir", path); Unit }
}
