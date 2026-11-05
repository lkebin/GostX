// Package cpu is a minimal stand-in for github.com/shirou/gopsutil/v3/cpu.
package cpu

// TimesStat mirrors the fields go-gost/x/metrics reads from gopsutil.
type TimesStat struct {
	CPU     string  `json:"cpu"`
	User    float64 `json:"user"`
	System  float64 `json:"system"`
	Idle    float64 `json:"idle"`
	Nice    float64 `json:"nice"`
	Iowait  float64 `json:"iowait"`
	Irq     float64 `json:"irq"`
	Softirq float64 `json:"softirq"`
	Steal   float64 `json:"steal"`
	Guest   float64 `json:"guest"`
}
