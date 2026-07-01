package cn.liukebin.gostx.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
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
import android.system.Os
import android.system.OsConstants
import cn.liukebin.gostx.R
import cn.liukebin.gostx.data.AppFilterMode
import cn.liukebin.gostx.data.ConfigRepository
import cn.liukebin.gostx.data.GlobalVpnState
import cn.liukebin.gostx.data.LogRepository
import cn.liukebin.gostx.data.VpnStatus
import cn.liukebin.gostx.notification.NOTIFICATION_ID
import cn.liukebin.gostx.notification.NotificationHelper
import java.lang.NoSuchMethodException
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

internal fun shouldRestartVpnOnNetworkAvailable(
    status: VpnStatus,
    isVpnNetwork: Boolean,
    restartInProgress: Boolean
): Boolean = status == VpnStatus.CONNECTED && !isVpnNetwork && !restartInProgress

// Reject a new ACTION_START when the VPN is already connected or connecting.
// Prevents overlapping startVpn() coroutines from a double-tap on the start button.
internal fun shouldAcceptStartAction(status: VpnStatus): Boolean =
    status != VpnStatus.CONNECTED && status != VpnStatus.CONNECTING

// After stopVpn(updatePersistentState=false) the state is CONNECTING. If a
// concurrent manual stop changed it to anything else, the network restart
// must abort — otherwise it would resurrect the VPN after the user stopped it.
internal fun shouldProceedWithRestart(status: VpnStatus): Boolean =
    status == VpnStatus.CONNECTING

class GostVpnService : VpnService() {

    companion object {
        const val ACTION_START = "cn.liukebin.gostx.START_VPN"
        const val ACTION_STOP = "cn.liukebin.gostx.STOP_VPN"

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
        GlobalVpnState.setError(getString(R.string.vpn_error_unexpected, throwable.message))
        stopSelf()
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
    private var tunFd: ParcelFileDescriptor? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val dozeReceiver = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                if (pm.isDeviceIdleMode) {
                    log("[doze] device entering idle mode — pausing TUN")
                    LibgostBridge.pauseTun()
                } else {
                    log("[doze] device exiting idle mode — waking TUN")
                    LibgostBridge.wakeTun()
                }
            }
        }
    } else {
        null
    }
    private lateinit var configRepo: ConfigRepository
    private val reconnectInProgress = AtomicBoolean(false)
    private val startInProgress = AtomicBoolean(false)
    // Timestamp (ms) of the last successful VPN connect. Suppresses onAvailable
    // restarts for RECONNECT_COOLDOWN_MS after each connect because Android fires
    // onAvailable for all existing non-VPN networks whenever VPN routing changes,
    // which would otherwise trigger an infinite restart loop.
    @Volatile private var lastVpnConnectTime = 0L
    private val RECONNECT_COOLDOWN_MS = 30_000L

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannel(this)
        LogRepository.init(this)
        configRepo = ConfigRepository(getSharedPreferences("gostx_prefs", Context.MODE_PRIVATE))
        val workDir = getExternalFilesDir(null)?.absolutePath
            ?: filesDir.absolutePath.also { log("External storage unavailable, falling back to internal: $it") }
        LibgostBridge.setWorkDir(workDir)
            .onFailure { log("WARNING: setWorkDir($workDir) failed: ${it.message}") }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val filter = IntentFilter(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(dozeReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(dozeReceiver, filter)
            }
        }
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
            ACTION_START -> {
                if (!shouldAcceptStartAction(GlobalVpnState.state.value.status)) {
                    log("Ignoring duplicate START: status=${GlobalVpnState.state.value.status}")
                    return START_NOT_STICKY
                }
                scope.launch {
                    LogRepository.deleteLog()
                    startVpn()
                }
            }
            ACTION_STOP -> scope.launch {
                stopVpn()
            }
        }
        return START_NOT_STICKY
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
        if (!startInProgress.compareAndSet(false, true)) {
            log("startVpn already in progress, ignoring")
            return
        }
        try {
            GlobalVpnState.setConnecting()
            // startForeground already called synchronously in onStartCommand

            val yaml = configRepo.getActiveConfig()

            val logLevel = configRepo.logLevel
            val logMaxBytes = configRepo.logMaxSizeKb.toLong() * 1024
            LibgostBridge.setLogLevel(logLevel)
            LibgostBridge.setLogMaxSize(logMaxBytes.toInt())
            LogRepository.maxLogBytes = logMaxBytes
            if (logLevel != "off") {
                LibgostBridge.setLogFile(LogRepository.getLogFile().absolutePath)
                    .onFailure { log("WARNING: setLogFile failed: ${it.message}") }
            }

            // Determine the DNS address from YAML before establishing VPN.
            // We parse the config here (not via Go) because startGost must run
            // after establish() so that registerSocketProtector() works correctly
            // (protect() has no effect before VPN is up).
            val vpnDnsAddr = extractVpnDnsAddr(yaml)

            val builder = Builder()
                .setMtu(1500)
                .addAddress("10.0.0.2", 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer(if (vpnDnsAddr.isNotEmpty()) vpnDnsAddr else "8.8.8.8")
                .setSession("gostx")
                .setBlocking(false)

            // Android VPN API forbids mixing addDisallowedApplication and
            // addAllowedApplication on the same builder. Handle three cases:
            //
            // 1. App filter OFF      → no exclusions (GostX participates in VPN so
            //                          sing-tun's TCP listener can route SYN-ACKs
            //                          back through tun0; upstream proxy connections
            //                          bypass VPN via socket protector)
            // 2. BLACKLIST           → disallow user-selected packages (not self)
            // 3. WHITELIST           → allow self + user-selected packages
            val stalePackages = mutableSetOf<String>()
            when {
                !configRepo.appFilterEnabled -> {
                    // No exclusions: GostX goes through VPN. Upstream proxy sockets
                    // bypass VPN via socket protector.
                }
                configRepo.appFilterMode == AppFilterMode.BLACKLIST -> {
                    // Exclude user-selected apps but NOT self.
                    configRepo.appFilterList.forEach { pkg ->
                        if (pkg == packageName) return@forEach
                        try { builder.addDisallowedApplication(pkg) }
                            catch (_: PackageManager.NameNotFoundException) {
                                stalePackages += pkg
                            }
                    }
                }
                configRepo.appFilterMode == AppFilterMode.WHITELIST -> {
                    // Include self so sing-tun's TCP listener can route properly.
                    try { builder.addAllowedApplication(packageName) }
                        catch (_: PackageManager.NameNotFoundException) {}
                    configRepo.appFilterList.forEach { pkg ->
                        if (pkg == packageName) return@forEach
                        try { builder.addAllowedApplication(pkg) }
                            catch (_: PackageManager.NameNotFoundException) {
                                stalePackages += pkg
                            }
                    }
                }
            }
            if (stalePackages.isNotEmpty()) {
                configRepo.appFilterList = configRepo.appFilterList - stalePackages
            }

            log("[start] establishing VPN interface...")
            tunFd = builder.establish() ?: run {
                log("Failed to establish VPN interface")
                GlobalVpnState.setError(getString(R.string.vpn_error_establish))
                stopSelf()
                return
            }
            log("[start] VPN interface ready")

            // NOW the VPN is active — register protector so upstream connections
            // bypass the VPN (preventing routing loops).
            registerSocketProtector()

            try {
                val systemDNS = collectSystemDNS()
                log("[start] loading config and starting gost...")
                LibgostBridge.startGost(yaml, systemDNS)
                log("[start] gost ready")
            } catch (e: Exception) {
                log("gost start failed: ${e.message}")
                GlobalVpnState.setError(getString(R.string.vpn_error_gost_start, e.message))
                closeTun()
                stopSelf()
                return
            }

            try {
                log("[start] starting VPN...")
                LibgostBridge.startTun(tunFd!!.fd.toLong(), 1500L)
            } catch (e: Exception) {
                log("VPN start failed: ${e.message}")
                GlobalVpnState.setError(getString(R.string.vpn_error_tun_start, e.message))
                closeTun()
                LibgostBridge.stopGost()
                stopSelf()
                return
            }

            try {
                val status = LibgostBridge.getStatus()
                val addr = parseFirstAddress(status)
                GlobalVpnState.setConnected(addr)
                lastVpnConnectTime = System.currentTimeMillis()
                promoteToForeground(addr)  // update notification with actual listen address
                log("[start] VPN connected, addr: $addr")
                registerNetworkCallback()
                saveLastRunState(true)
                LibgostBridge.setMemoryLimit(true)
            } catch (e: Exception) {
                log("VPN post-start error: ${e.message}")
                GlobalVpnState.setError(getString(R.string.vpn_error_post_start, e.message))
                LibgostBridge.setMemoryLimit(false)
                closeTun()
                LibgostBridge.stopTun()
                LibgostBridge.stopGost()
                stopSelf()
            }
        } finally {
            startInProgress.set(false)
        }
    }

    private fun stopVpn(updatePersistentState: Boolean = true) {
        if (updatePersistentState) GlobalVpnState.setStopping()
        unregisterNetworkCallback()
        // Close the ParcelFileDescriptor first: this immediately notifies Android's
        // ConnectivityService that the VPN session is ending, so the status-bar icon
        // disappears right away. The Go side still holds its dup'd fd and can finish
        // cleanly afterwards.
        closeTun()

        // Go cleanup: Stop() has a 5-second timeout on serveWg.Wait(), so this
        // call is guaranteed to return within 5 seconds. STOPPING loading is shown
        // during this time, preventing the user from starting a new connection while
        // the previous Go shutdown is still in progress.
        LibgostBridge.setMemoryLimit(false)
        LibgostBridge.setSocketProtector(null)
        log("[stop] stopping VPN...")
        LibgostBridge.stopTun()
        log("[stop] stopping gost...")
        LibgostBridge.stopGost()

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
            log("[stop] VPN stopped")
        } else {
            GlobalVpnState.setConnecting()
            log("VPN restarting after network change")
        }
    }

    private fun closeTun() {
        tunFd?.close()
        tunFd = null
    }

    /**
     * Registers a SocketProtector with Go that calls VpnService.protect() on
     * every socket gost creates for upstream proxy connections — BEFORE connect()
     * is called. This is the primary VPN-bypass mechanism; it works on all Android
     * devices.
     *
     * MUST be called AFTER establish() so that protect() routes sockets outside
     * the active VPN (before VPN is established there is nothing to protect from).
     */
    private fun registerSocketProtector() {
        LibgostBridge.setSocketProtector(object : libgost.SocketProtector {
            override fun protect(fd: Long): Boolean {
                val result = this@GostVpnService.protect(fd.toInt())
                if (!result) log("[protect] WARNING: VpnService.protect() returned false for fd=$fd")
                return result
            }
        })
        log("[start] socket protector registered")
    }

    /**
     * Reads system DNS servers from the current network. Returns a
     * comma-separated string (e.g. "192.168.1.1") or "" if unavailable.
     */
    private fun collectSystemDNS(): String {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.getLinkProperties(cm.activeNetwork)?.dnsServers
            ?.mapNotNull { it.hostAddress }
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString(",")
            ?: ""
    }

    private fun log(msg: String) = LogRepository.append(msg)

    /**
     * Parses the YAML config to detect if it contains a gost DNS handler service.
     * Returns "10.0.0.3" (the vpnDNSVirtualAddr constant set by Go) if found,
     * or "" if not (caller falls back to 8.8.8.8).
     *
     * This duplicates Go's detectVPNDNSService() logic so we can set the VPN
     * builder's DNS server BEFORE startGost() is called (gost must start after
     * establish() so that registerSocketProtector() works correctly).
     */
    private fun extractVpnDnsAddr(yaml: String): String {
        // Look for a "handler:" block whose "type:" is "dns".
        // The vpnDNSVirtualAddr Go constant is always "10.0.0.3".
        var inHandler = false
        var handlerIndent = -1
        for (line in yaml.lines()) {
            if (line.isBlank()) continue
            val indent = line.length - line.trimStart().length
            val t = line.trimStart()
            when {
                t.startsWith("handler:") -> {
                    inHandler = true
                    handlerIndent = indent
                }
                inHandler -> {
                    if (indent <= handlerIndent && !t.startsWith("-")) {
                        // Stepped out of handler block
                        inHandler = false
                    } else if (t.startsWith("type:")) {
                        val v = t.removePrefix("type:").trim().trim('"', '\'').lowercase()
                        if (v == "dns") return "10.0.0.3"
                        inHandler = false
                    }
                }
            }
        }
        return ""
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
                        restartInProgress = reconnectInProgress.get()
                    ) && reconnectInProgress.compareAndSet(false, true)) {
                    scope.launch {
                        try {
                            stopVpn(updatePersistentState = false)
                            if (!shouldProceedWithRestart(GlobalVpnState.state.value.status)) {
                                log("Aborting restart: state changed to ${GlobalVpnState.state.value.status}")
                                return@launch
                            }
                            startVpn()
                        } finally {
                            reconnectInProgress.set(false)
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
        // Release all Go resources synchronously. When the service is destroyed
        // without going through stopVpn() (e.g. coroutine exception → stopSelf()),
        // the Go DNS listener and gVisor stack would otherwise continue holding
        // their ports, causing "address already in use" on the next Start().
        dozeReceiver?.let { unregisterReceiver(it) }
        closeTun()
        LibgostBridge.stopTun()
        LibgostBridge.stopGost()
        scope.cancel()
        super.onDestroy()
    }
}

internal object LibgostBridge {
    private const val CLASS_NAME = "libgost.Libgost"

    private fun clazz(): Class<*> = try {
        Class.forName(CLASS_NAME)
    } catch (e: ClassNotFoundException) {
        throw IllegalStateException("libgost.aar not available; see android/app/libs/README.txt", e)
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
            throw IllegalStateException("libgost.Libgost#$name not found; verify the generated AAR matches the expected API", e)
        } catch (e: InvocationTargetException) {
            val cause = e.targetException
            if (cause is Exception) throw cause
            throw RuntimeException(cause)
        }
    }

    fun startGost(yaml: String, systemDNS: String) {
        invoke("startGost", yaml, systemDNS)
    }

    fun startTun(fd: Long, mtu: Long) {
        invoke("startTun", fd, mtu)
    }

    fun stopTun() {
        runCatching { invoke("stopTun") }
    }

    fun stopGost() {
        runCatching { invoke("stopGost") }
    }

    fun getStatus(): String = invoke("getStatus") as? String ?: ""

    fun getVpnDnsAddr(): String = invoke("getVPNDNSAddr") as? String ?: ""

    fun validateConfig(yaml: String): String = invoke("validateConfig", yaml) as? String ?: ""

    fun setMemoryLimit(enabled: Boolean) {
        runCatching { invoke("setMemoryLimit", enabled) }
    }

    fun setLogMaxSize(maxBytes: Int) {
        runCatching { invoke("setLogMaxSize", maxBytes) }
    }

    /**
     * Sets the SocketProtector callback that Go will call for every upstream
     * socket before connect(). Pass null to clear. The protector object must
     * implement libgost.SocketProtector (generated by gomobile from the Go
     * interface of the same name).
     */
    fun setSocketProtector(protector: libgost.SocketProtector?) {
        runCatching {
            val method = clazz().getMethod("setSocketProtector", libgost.SocketProtector::class.java)
            method.invoke(null, protector)
        }
    }

    fun setWorkDir(path: String): Result<Unit> =
        runCatching { invoke("setWorkDir", path); Unit }

    fun setLogFile(path: String): Result<Unit> =
        runCatching { invoke("setLogFile", path); Unit }

    fun setLoggingEnabled(enabled: Boolean) {
        runCatching { invoke("setLoggingEnabled", enabled) }
    }

    fun setLogLevel(level: String) {
        runCatching { invoke("setLogLevel", level) }
    }

    fun pauseTun() = runCatching { invoke("pauseTun") }

    fun wakeTun() = runCatching { invoke("wakeTun") }

    fun resetTunConnections() = runCatching { invoke("resetTunConnections") }

}
