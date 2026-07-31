package libgost

import (
	"context"
	"fmt"
	"io"
	"os"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	corelogger "github.com/go-gost/core/logger"
	xlogger "github.com/go-gost/x/logger"
)

// logCh buffers log messages produced by the shared logger (see log()).
// Capacity 512 means we can hold ~512 messages before dropping.
var logCh = make(chan string, 512)

// logMaxBytes is the maximum log file size before in-place rotation.
// Default 2 MiB; override with SetLogMaxSize before SetLogFile.
var logMaxBytes atomic.Int64

func init() {
	logMaxBytes.Store(2 * 1024 * 1024)
}

// loggingEnabled gates all enqueueLog output. False by default; call SetLoggingEnabled(true)
// before starting VPN to activate logging.
var loggingEnabled atomic.Bool

// logLevelStr stores the current log level string ("off", "error", "warn", "info", "debug", "trace").
var logLevelStr atomic.Value

func init() {
	logLevelStr.Store("off")
}

func getLogLevel() string {
	if v := logLevelStr.Load(); v != nil {
		return v.(string)
	}
	return "off"
}

// ── shared logger ────────────────────────────────────────────────────────────

// chanWriter adapts logCh to io.Writer. The logger is backed by log/slog,
// which emits one complete record per Write, so each line is forwarded to the
// app log channel as a separate message.
type chanWriter struct{}

func (chanWriter) Write(p []byte) (int, error) {
	for _, line := range strings.Split(strings.TrimRight(string(p), "\n"), "\n") {
		if line != "" {
			enqueueLog("%s", line)
		}
	}
	return len(p), nil
}

// coreLevels maps libgost log level names to go-gost/core log levels.
// "off" is deliberately absent — see installLogger.
var coreLevels = map[string]corelogger.LogLevel{
	"error": corelogger.ErrorLevel,
	"warn":  corelogger.WarnLevel,
	"info":  corelogger.InfoLevel,
	"debug": corelogger.DebugLevel,
	"trace": corelogger.TraceLevel,
}

// sharedLogger holds the single logger used by libgost, sing-tun and
// go-gost/x. Stored atomically because installLogger may replace it while
// service goroutines are logging.
var sharedLogger atomic.Value

func init() { installLogger() }

// log returns the shared logger. Never nil.
func log() corelogger.Logger {
	if l, ok := sharedLogger.Load().(corelogger.Logger); ok && l != nil {
		return l
	}
	return xlogger.Nop()
}

// installLogger (re)builds the shared logger for the current log level and
// installs it as go-gost/x's default.
//
// It must be called after every loader.Load(), which replaces the default
// logger, and after every SetLogLevel, because a go-gost/x logger's level is
// fixed at construction time.
func installLogger() {
	lvl, on := coreLevels[getLogLevel()]
	var out io.Writer = io.Discard
	if on {
		out = io.MultiWriter(os.Stderr, chanWriter{})
	} else {
		// "off" (or unknown): silence every sink.
		lvl = corelogger.FatalLevel
	}

	l := xlogger.NewLogger(
		xlogger.OutputOption(out),
		xlogger.FormatOption(corelogger.TextFormat),
		xlogger.LevelOption(lvl),
	)
	sharedLogger.Store(l)
	corelogger.SetDefault(l)
}

// ── channel enqueue ──────────────────────────────────────────────────────────

// enqueueLog formats and enqueues a log message into logCh. Called only by
// chanWriter and tests. Do NOT call directly for operational logs — use log()
// so log-level filtering works. Records carry their own slog timestamp, so no
// prefix is added here.
func enqueueLog(format string, args ...any) {
	if !loggingEnabled.Load() {
		return
	}
	msg := fmt.Sprintf(format, args...)
	select {
	case logCh <- msg:
	default:
		// buffer full – drop to avoid blocking the caller
	}
}

// ── file drain ───────────────────────────────────────────────────────────────

var logDrainOnce sync.Once
var logDrainErr error
var logDrainRunning atomic.Bool      // true while drainLogFile is running
var logDrainCancel context.CancelFunc // non-nil while drain goroutine is running; for test cleanup

// SetLogFile writes log messages to the given file path.
// Opens with O_APPEND; call once on app startup.
func SetLogFile(path string) error {
	logDrainOnce.Do(func() {
		f, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0644)
		if err != nil {
			logDrainErr = err
			return
		}
		ctx, cancel := context.WithCancel(context.Background())
		logDrainCancel = cancel
		logDrainRunning.Store(true)
		go drainLogFile(ctx, f)
	})
	return logDrainErr
}

// drainLogFile reads from logCh and writes to f until ctx is cancelled.
// Batches messages that arrived concurrently to minimise write syscalls.
// After each batch it calls rotateLargeFile to keep the file within logMaxBytes.
func drainLogFile(ctx context.Context, f *os.File) {
	defer logDrainRunning.Store(false)
	for {
		select {
		case <-ctx.Done():
			f.Close() //nolint:errcheck
			return
		case msg := <-logCh:
			var b strings.Builder
			b.WriteString(msg)
			b.WriteByte('\n')
		drain:
			for {
				select {
				case msg = <-logCh:
					b.WriteString(msg)
					b.WriteByte('\n')
				default:
					break drain
				}
			}
			f.WriteString(b.String()) //nolint:errcheck
			rotateLargeFile(f, logMaxBytes.Load())
		}
	}
}

// SetLogMaxSize sets the maximum log file size in bytes. When the file
// exceeds this size, the oldest half is discarded and the newest half is
// kept. Call before SetLogFile. Default: 2 MiB (2097152).
func SetLogMaxSize(maxBytes int) {
	if maxBytes > 0 {
		logMaxBytes.Store(int64(maxBytes))
	}
}

// rotateLargeFile truncates f in-place when its size exceeds maxBytes.
// It keeps the newest half of the file content so the most recent log
// entries are retained. The file must have been opened with O_APPEND;
// after truncation subsequent writes still go to the new EOF.
func rotateLargeFile(f *os.File, maxBytes int64) {
	info, err := f.Stat()
	if err != nil || info.Size() <= maxBytes {
		return
	}
	// Read the newest half.
	keepFrom := info.Size() / 2
	buf := make([]byte, info.Size()-keepFrom)
	n, err := f.ReadAt(buf, keepFrom)
	if err != nil && err != io.EOF {
		return
	}
	buf = buf[:n]

	// Prepend a marker so viewers can see where the rotation happened.
	header := []byte("--- [log rotated: oldest entries discarded] ---\n")
	payload := append(header, buf...)

	// Truncate and rewrite. With O_APPEND the write atomically seeks to
	// the new EOF (0 after Truncate), so the rewrite lands at offset 0.
	if err := f.Truncate(0); err != nil {
		return
	}
	f.Write(payload) //nolint:errcheck
}

// GetVPNLog drains all pending log messages and returns them newline-separated.
func GetVPNLog() string {
	if logDrainRunning.Load() {
		return "" // drain goroutine owns the channel
	}
	var sb strings.Builder
	for {
		select {
		case msg := <-logCh:
			sb.WriteString(msg)
			sb.WriteByte('\n')
		default:
			return sb.String()
		}
	}
}

// ── log level control ────────────────────────────────────────────────────────

var validLogLevels = map[string]bool{
	"off": true, "error": true, "warn": true, "info": true, "debug": true, "trace": true,
}

// SetLogLevel sets the minimum log level. Valid: "off", "error", "warn",
// "info", "debug", "trace". Call before starting the VPN.
func SetLogLevel(level string) {
	if !validLogLevels[level] {
		return
	}
	logLevelStr.Store(level)
	loggingEnabled.Store(level != "off")
	installLogger()
}

// SetLoggingEnabled enables or disables log output.
func SetLoggingEnabled(v bool) { loggingEnabled.Store(v) }

// ── timezone ───────────────────────────────────────────────────────────────

// SetTimezone sets the timezone used for log timestamps.
//
// Android does not set $TZ for app processes and has no /etc/localtime, so
// Go's time.Local falls back to UTC (see time/zoneinfo_android.go, which
// leaves "getprop persist.sys.timezone" as a TODO). Without this call, Go log
// lines are offset from the Kotlin-side ones by the local UTC offset.
//
// name is an IANA zone ID such as "Asia/Shanghai"; Go resolves it from
// Android's bundled tzdata. offsetSeconds is the current UTC offset and is
// used only if that lookup fails, so the timestamps stay correct even on
// devices where the zone database is unreadable.
//
// Safe to call on every VPN start: repeated calls with an unchanged timezone
// are a no-op. time.Local is a process-wide variable, so rewriting it while
// other goroutines format timestamps would be a data race.
func SetTimezone(name string, offsetSeconds int) {
	key := name + "|" + strconv.Itoa(offsetSeconds)
	if prev, _ := appliedTZ.Load().(string); prev == key {
		return
	}

	loc := time.FixedZone(name, offsetSeconds)
	if name != "" {
		if l, err := time.LoadLocation(name); err == nil {
			loc = l
		}
	}

	appliedTZ.Store(key)
	time.Local = loc
}

// appliedTZ records the last timezone applied by SetTimezone, so repeated
// calls do not rewrite the time.Local global.
var appliedTZ atomic.Value

// ── test helpers ─────────────────────────────────────────────────────────────

// resetLogDrainForTest cancels the drain goroutine and drains pending messages.
// Call from tests only — before and after tests that interact with the log drain.
func resetLogDrainForTest() {
	SetLoggingEnabled(true)
	if logDrainCancel != nil {
		logDrainCancel()
		for logDrainRunning.Load() {
			runtime.Gosched()
		}
		logDrainCancel = nil
	}
drain:
	for {
		select {
		case <-logCh:
		default:
			break drain
		}
	}
	logDrainOnce = sync.Once{}
	logDrainErr = nil
}

// drainStaleLogs drains any log messages left in logCh from a previous session
// when no drain goroutine is running. Called when stopping TUN.
func drainStaleLogs() {
	if logDrainRunning.Load() {
		return // drain goroutine owns the channel
	}
	for {
		select {
		case <-logCh:
		default:
			return
		}
	}
}
