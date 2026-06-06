package gostlib

import (
	"fmt"
	"reflect"
	"strings"
	"unsafe"

	corelogger "github.com/go-gost/core/logger"
	"github.com/sirupsen/logrus"
)

// vpnLogHook is a logrus hook that forwards gost internal log entries to
// vpnLogCh so they appear in the app's log screen.
//
// The hook is attached to the logrus.Logger that loader.Load() creates via
// installLogrusHook(). This avoids using corelogger.SetDefault() with a
// custom concrete type, which would panic because atomic.Value enforces type
// consistency across all Store() calls.
type vpnLogHook struct{}

func (h *vpnLogHook) Levels() []logrus.Level {
	return []logrus.Level{
		logrus.DebugLevel,
		logrus.InfoLevel,
		logrus.WarnLevel,
		logrus.ErrorLevel,
		logrus.FatalLevel,
	}
}

func (h *vpnLogHook) Fire(entry *logrus.Entry) error {
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
	logVPN("%s", sb.String())
	return nil
}

// installLogrusHook attaches vpnLogHook to the logrus.Logger that backs
// the gost default logger. Must be called after loader.Load() which creates
// and registers the logger.
//
// The gost default logger is a *logrusLogger (unexported type in go-gost/x).
// We reach its *logrus.Entry field via reflect + unsafe to call AddHook
// without needing to export the type.
//
// loader.Load() creates a fresh logrus.Logger on every call so there is no
// risk of accumulating duplicate hooks across Stop/Start cycles.
func installLogrusHook() {
	l := corelogger.Default()
	if l == nil {
		return
	}
	v := reflect.ValueOf(l)
	if v.Kind() != reflect.Ptr || v.IsNil() {
		return
	}
	elem := v.Elem()
	// "logger" is the *logrus.Entry field in go-gost/x's logrusLogger struct.
	f := elem.FieldByName("logger")
	if !f.IsValid() || f.Kind() != reflect.Ptr {
		return
	}
	// UnsafeAddr gives the address of the unexported field in memory so we
	// can read the *logrus.Entry without going through Interface() (which
	// panics for unexported fields).
	entry := *(**logrus.Entry)(unsafe.Pointer(f.UnsafeAddr()))
	if entry == nil || entry.Logger == nil {
		return
	}
	entry.Logger.AddHook(&vpnLogHook{})
	// Keep logrus writing to stderr (Android logcat) for external debugging;
	// the hook additionally forwards log lines to the in-app log UI.
}
