package main

import (
	"fmt"
	"io"
	"log"
)

// LogLogger uses the standard log package as the logger
type Logger struct {
	logger *log.Logger
}

func NewLogger(w io.Writer) *Logger {
	return &Logger{
		logger: log.New(w, "", log.LstdFlags|log.Lshortfile),
	}
}

// Log uses the standard log library log.Output
func (l *Logger) Log(v ...interface{}) {
	l.logger.Output(3, fmt.Sprintln(v...))
}

// Logf uses the standard log library log.Output
func (l *Logger) Logf(format string, v ...interface{}) {
	l.logger.Output(3, fmt.Sprintf(format, v...))
}
