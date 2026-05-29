// go/cmd/macos/logger.go
package main

import (
	"fmt"
	"io"
	"time"
)

type Logger struct {
	w io.Writer
}

func NewLogger(w io.Writer) *Logger {
	return &Logger{w: w}
}

func (l *Logger) Log(v ...interface{}) {
	fmt.Fprintf(l.w, "[%s] %s\n", time.Now().Format("15:04:05"), fmt.Sprint(v...))
}

func (l *Logger) Logf(format string, v ...interface{}) {
	fmt.Fprintf(l.w, "[%s] %s\n", time.Now().Format("15:04:05"), fmt.Sprintf(format, v...))
}
