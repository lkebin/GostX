package gostlib

import (
	"context"
	"fmt"
	"os"
	"runtime"
	"strings"
	"sync"
	"sync/atomic"
)

// vpnLogCh buffers log messages from the gVisor transport handler.
// Capacity 512 means we can hold ~512 messages before dropping.
var vpnLogCh = make(chan string, 512)

// VPN connection counters; reset by resetVPNStats.
var (
	vpnTCPConns    int64 // total TCP sessions dispatched
	vpnUDPConns    int64 // total UDP sessions dispatched
	vpnFailedConns int64 // sessions where router.Dial failed
)

var logDrainOnce    sync.Once
var logDrainErr     error
var logDrainRunning atomic.Bool // true once drainLogToFile goroutine is running
var logDrainCancel  context.CancelFunc // non-nil when goroutine is running; for test cleanup only

func logVPN(format string, args ...any) {
	msg := fmt.Sprintf(format, args...)
	select {
	case vpnLogCh <- msg:
	default:
		// buffer full – drop to avoid blocking the packet-dispatch goroutine
	}
}

// SetLogFile tells gostlib to write VPN log messages directly to the given
// file path. The file is opened with O_APPEND so it can be safely shared with
// the Kotlin logger. Call once on app startup; subsequent calls are no-ops.
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
		go drainLogToFile(ctx, f)
	})
	return logDrainErr
}

// drainLogToFile runs until ctx is cancelled. It blocks on vpnLogCh
// then batches any additional messages that arrived concurrently before writing,
// minimising the number of write syscalls.
func drainLogToFile(ctx context.Context, f *os.File) {
	defer logDrainRunning.Store(false)
	for {
		select {
		case <-ctx.Done():
			f.Close() //nolint:errcheck
			return
		case msg := <-vpnLogCh:
			var b strings.Builder
			b.WriteString(msg)
			b.WriteByte('\n')
		drain:
			for {
				select {
				case msg = <-vpnLogCh:
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

// GetVPNLog drains all pending VPN log messages and returns them
// newline-separated. Retained for external tooling; not called by the app
// when SetLogFile has been configured.
func GetVPNLog() string {
	if logDrainRunning.Load() {
		// drain goroutine owns the channel; direct reads would steal messages
		return ""
	}
	var sb strings.Builder
	for {
		select {
		case msg := <-vpnLogCh:
			sb.WriteString(msg)
			sb.WriteByte('\n')
		default:
			return sb.String()
		}
	}
}

func resetVPNStats() {
	atomic.StoreInt64(&vpnTCPConns, 0)
	atomic.StoreInt64(&vpnUDPConns, 0)
	atomic.StoreInt64(&vpnFailedConns, 0)
	// Only drain stale log messages when no file drain goroutine owns the channel.
	if !logDrainRunning.Load() {
		for {
			select {
			case <-vpnLogCh:
			default:
				return
			}
		}
	}
}

// resetLogDrainForTest resets the log drain state for testing.
// Must only be called from tests. Cancels any running drain goroutine and
// waits for it to exit before resetting state, making the test repeatable.
func resetLogDrainForTest() {
	if logDrainCancel != nil {
		logDrainCancel()
		// Wait for goroutine to exit (logDrainRunning goes false on exit)
		for logDrainRunning.Load() {
			runtime.Gosched()
		}
		logDrainCancel = nil
	}
	// Drain any messages the goroutine did not consume
drain:
	for {
		select {
		case <-vpnLogCh:
		default:
			break drain
		}
	}
	logDrainOnce = sync.Once{}
	logDrainErr = nil
}
