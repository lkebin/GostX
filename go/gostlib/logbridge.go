package gostlib

import (
	"context"
	"fmt"
	"os"
	"reflect"
	"runtime"
	"strings"
	"sync"
	"sync/atomic"
	"time"
	"unsafe"

	corelogger "github.com/go-gost/core/logger"
	"github.com/sirupsen/logrus"
)

// logCh buffers log messages from the logrus hook.
// Capacity 512 means we can hold ~512 messages before dropping.
var logCh = make(chan string, 512)

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

// ── logrus hook ─────────────────────────────────────────────────────────────

// channelHook is a logrus hook that forwards log entries to logCh.
// Attached to both the go-gost/x logger (via installLogrusHook) and the
// standard logrus logger (once, via installStdHookOnce) so that gostlib's own
// logrus calls and go-gost/x internal logs reach the same channel.
type channelHook struct{}

func (h *channelHook) Levels() []logrus.Level { return logrus.AllLevels }

func (h *channelHook) Fire(entry *logrus.Entry) error {
	var sb strings.Builder
	sb.WriteByte('[')
	level := entry.Level.String()
	if len(level) >= 4 {
		sb.WriteString(strings.ToUpper(level[:4]))
	} else {
		sb.WriteString(strings.ToUpper(level))
	}
	sb.WriteString("] ")
	sb.WriteString(entry.Message)
	for k, v := range entry.Data {
		sb.WriteString(fmt.Sprintf(" %s=%v", k, v))
	}
	enqueueLog("%s", sb.String())
	return nil
}

// ── channel enqueue ──────────────────────────────────────────────────────────

// enqueueLog formats, timestamps, and enqueues a log message into logCh.
// Called only by channelHook.Fire() and tests. Do NOT call directly for
// operational logs — use logrus so log-level filtering works.
func enqueueLog(format string, args ...any) {
	if !loggingEnabled.Load() {
		return
	}
	msg := fmt.Sprintf(format, args...)
	ts := time.Now().Format("15:04:05.000")
	select {
	case logCh <- ts + " " + msg:
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
		}
	}
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

var levelToLogrus = map[string]logrus.Level{
	"error": logrus.ErrorLevel,
	"warn":  logrus.WarnLevel,
	"info":  logrus.InfoLevel,
	"debug": logrus.DebugLevel,
	"trace": logrus.TraceLevel,
}

// SetLogLevel sets the minimum log level. Valid: "off", "error", "warn",
// "info", "debug", "trace". Call before starting the VPN.
func SetLogLevel(level string) {
	if !validLogLevels[level] {
		return
	}
	logLevelStr.Store(level)
	if level == "off" {
		loggingEnabled.Store(false)
	} else {
		loggingEnabled.Store(true)
	}
	applyLogrusLevel(logrus.StandardLogger(), level)
}

func applyLogrusLevel(l *logrus.Logger, level string) {
	if lvl, ok := levelToLogrus[level]; ok {
		l.SetLevel(lvl)
	} else {
		l.SetLevel(logrus.FatalLevel + 1) // "off": block everything
	}
}

// SetLoggingEnabled enables or disables log output.
func SetLoggingEnabled(v bool) { loggingEnabled.Store(v) }

// ── logrus hook installation ─────────────────────────────────────────────────

var installStdHookOnce sync.Once

// installLogrusHook attaches channelHook to the go-gost/x logger (fresh on
// every loader.Load()) and to the standard logrus logger (once). Sets the
// configured log level on both.
func installLogrusHook() {
	// go-gost/x logger — recreated each loader.Load(), so re-hook always.
	if l := corelogger.Default(); l != nil {
		if v := reflect.ValueOf(l); v.Kind() == reflect.Ptr && !v.IsNil() {
			if f := v.Elem().FieldByName("logger"); f.IsValid() && f.Kind() == reflect.Ptr {
				entry := *(**logrus.Entry)(unsafe.Pointer(f.UnsafeAddr()))
				if entry != nil && entry.Logger != nil {
					entry.Logger.AddHook(&channelHook{})
					applyLogrusLevel(entry.Logger, getLogLevel())
				}
			}
		}
	}

	// Standard logger — hook once, level on every Start.
	installStdHookOnce.Do(func() {
		logrus.StandardLogger().AddHook(&channelHook{})
	})
	applyLogrusLevel(logrus.StandardLogger(), getLogLevel())
}

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
