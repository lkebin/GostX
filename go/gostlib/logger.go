package gostlib

import (
	"fmt"
	"strings"

	corelogger "github.com/go-gost/core/logger"
)

// chanLogger implements corelogger.Logger and writes log lines to vpnLogCh so
// they appear in the app's log screen alongside VPN transport events.
//
// Set as gost's default logger once at package init; all service, handler, and
// chain activity is captured automatically.
type chanLogger struct {
	level  corelogger.LogLevel
	fields map[string]any
}

// levelOrder maps level strings to comparable ints (lower = more verbose).
var levelOrder = map[corelogger.LogLevel]int{
	corelogger.TraceLevel: 0,
	corelogger.DebugLevel: 1,
	corelogger.InfoLevel:  2,
	corelogger.WarnLevel:  3,
	corelogger.ErrorLevel: 4,
	corelogger.FatalLevel: 5,
}

func init() {
	// Install our channel-backed logger as gost's default. All service,
	// handler, and chain log output will flow to GetVPNLog().
	corelogger.SetDefault(&chanLogger{level: corelogger.InfoLevel})
}

func (l *chanLogger) WithFields(m map[string]any) corelogger.Logger {
	merged := make(map[string]any, len(l.fields)+len(m))
	for k, v := range l.fields {
		merged[k] = v
	}
	for k, v := range m {
		merged[k] = v
	}
	return &chanLogger{level: l.level, fields: merged}
}

// formatPrefix builds a short context prefix from interesting fields so log
// lines look like "[INFO] svc/handler: message" rather than a raw dump of all
// fields.
func (l *chanLogger) formatPrefix() string {
	var sb strings.Builder
	for _, key := range []string{"service", "listener", "handler", "connector", "dialer", "hop", "node"} {
		if v, ok := l.fields[key]; ok {
			if sb.Len() > 0 {
				sb.WriteByte('/')
			}
			sb.WriteString(fmt.Sprintf("%v", v))
		}
	}
	return sb.String()
}

func (l *chanLogger) emit(level corelogger.LogLevel, msg string) {
	if !l.IsLevelEnabled(level) {
		return
	}
	prefix := l.formatPrefix()
	var sb strings.Builder
	sb.WriteByte('[')
	sb.WriteString(strings.ToUpper(string(level))[0:4])
	sb.WriteByte(']')
	if prefix != "" {
		sb.WriteByte(' ')
		sb.WriteString(prefix)
		sb.WriteByte(':')
	}
	sb.WriteByte(' ')
	sb.WriteString(msg)
	logVPN("%s", sb.String())
}

func (l *chanLogger) Trace(args ...any)            { l.emit(corelogger.TraceLevel, fmt.Sprint(args...)) }
func (l *chanLogger) Tracef(f string, a ...any)    { l.emit(corelogger.TraceLevel, fmt.Sprintf(f, a...)) }
func (l *chanLogger) Debug(args ...any)            { l.emit(corelogger.DebugLevel, fmt.Sprint(args...)) }
func (l *chanLogger) Debugf(f string, a ...any)    { l.emit(corelogger.DebugLevel, fmt.Sprintf(f, a...)) }
func (l *chanLogger) Info(args ...any)             { l.emit(corelogger.InfoLevel, fmt.Sprint(args...)) }
func (l *chanLogger) Infof(f string, a ...any)     { l.emit(corelogger.InfoLevel, fmt.Sprintf(f, a...)) }
func (l *chanLogger) Warn(args ...any)             { l.emit(corelogger.WarnLevel, fmt.Sprint(args...)) }
func (l *chanLogger) Warnf(f string, a ...any)     { l.emit(corelogger.WarnLevel, fmt.Sprintf(f, a...)) }
func (l *chanLogger) Error(args ...any)            { l.emit(corelogger.ErrorLevel, fmt.Sprint(args...)) }
func (l *chanLogger) Errorf(f string, a ...any)    { l.emit(corelogger.ErrorLevel, fmt.Sprintf(f, a...)) }
func (l *chanLogger) Fatal(args ...any)            { l.emit(corelogger.FatalLevel, fmt.Sprint(args...)) }
func (l *chanLogger) Fatalf(f string, a ...any)    { l.emit(corelogger.FatalLevel, fmt.Sprintf(f, a...)) }

func (l *chanLogger) GetLevel() corelogger.LogLevel { return l.level }

func (l *chanLogger) IsLevelEnabled(level corelogger.LogLevel) bool {
	return levelOrder[level] >= levelOrder[l.level]
}

// SetLogLevel changes the minimum log level captured from gost internals.
// Accepted values: "trace", "debug", "info", "warn", "error".
// Default is "info".
func SetLogLevel(level string) {
	lv := corelogger.LogLevel(strings.ToLower(level))
	if _, ok := levelOrder[lv]; !ok {
		lv = corelogger.InfoLevel
	}
	corelogger.SetDefault(&chanLogger{level: lv})
}
