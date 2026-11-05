// Package process is a minimal stand-in for github.com/shirou/gopsutil/v3/process.
//
// The real package compiles cgo code that includes <libproc.h> on darwin, which
// the iOS SDK does not ship, so the Apple builds (macOS + iOS share one
// Libgost.xcframework) cannot use it. go-gost/x/metrics is the only consumer,
// and none of the GostX clients expose a metrics service, so this stub keeps
// the symbol surface the metrics collector compiles against while reporting no
// process data.
package process

import (
	"errors"
	"os"

	"github.com/shirou/gopsutil/v3/cpu"
)

// Rlimit constants mirror the values used by go-gost/x/metrics.
const (
	RLIMIT_AS     = 5
	RLIMIT_NOFILE = 7
)

var errStub = errors.New("gopsutil stub: process metrics unavailable on this platform")

type MemoryInfoStat struct {
	RSS  uint64 `json:"rss"`
	VMS  uint64 `json:"vms"`
	Data uint64 `json:"data"`
}

type RlimitStat struct {
	Resource int32  `json:"resource"`
	Soft     int32  `json:"soft"`
	Hard     int32  `json:"hard"`
	Used     uint64 `json:"used"`
}

type Process struct {
	Pid int32 `json:"pid"`
}

func NewProcess(pid int32) (*Process, error) {
	return &Process{Pid: pid}, nil
}

func (p *Process) MemoryInfo() (*MemoryInfoStat, error) { return nil, errStub }

func (p *Process) Times() (*cpu.TimesStat, error) { return nil, errStub }

// NumFDs counts the process file descriptors through /dev/fd, which works on
// both macOS and iOS without cgo.
func (p *Process) NumFDs() (int32, error) {
	entries, err := os.ReadDir("/dev/fd")
	if err != nil {
		return 0, err
	}
	return int32(len(entries)), nil
}

func (p *Process) RlimitUsage(gatherUsed bool) ([]RlimitStat, error) { return nil, errStub }

func (p *Process) CreateTime() (int64, error) { return 0, errStub }
