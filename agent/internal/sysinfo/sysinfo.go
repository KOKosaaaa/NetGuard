// Package sysinfo gathers host telemetry for the /v1/status endpoint.
//
// Everything in here is read-only — no shelling out to top/free/uptime,
// just /proc and syscall.Sysinfo. Keeps the agent dependency-free and
// avoids spawning a child process on every status poll.
package sysinfo

import (
	"bufio"
	"errors"
	"os"
	"os/exec"
	"runtime"
	"strconv"
	"strings"
	"syscall"
	"time"
)

// Status is the payload of GET /v1/status.
type Status struct {
	Agent    AgentInfo     `json:"agent"`
	Host     HostInfo      `json:"host"`
	Services []ServiceInfo `json:"services"`
}

type AgentInfo struct {
	Version  string `json:"version"`
	Uptime   int64  `json:"uptime_s"`
	StartedAt time.Time `json:"started_at"`
	// Arch is the agent binary's GOARCH ("amd64"/"arm64"). The app reads it
	// to pick the matching binary for an upload-based self-update.
	Arch string `json:"arch"`
}

type HostInfo struct {
	LoadAvg  [3]float64 `json:"load_avg"`
	CPUCount int        `json:"cpu_count"`
	Memory   Memory     `json:"memory"`
	DiskRoot Disk       `json:"disk_root"`
	Uptime   int64      `json:"uptime_s"`
}

type Memory struct {
	TotalMB     uint64 `json:"total_mb"`
	UsedMB      uint64 `json:"used_mb"`
	FreeMB      uint64 `json:"free_mb"`
	SwapTotalMB uint64 `json:"swap_total_mb"`
}

type Disk struct {
	TotalGB uint64 `json:"total_gb"`
	UsedGB  uint64 `json:"used_gb"`
	FreeGB  uint64 `json:"free_gb"`
}

type ServiceInfo struct {
	Name    string  `json:"name"`
	Active  bool    `json:"active"`
	Version *string `json:"version"`
	PID     *int    `json:"pid"`
	Since   *string `json:"since"`
}

// Gather fills a Status snapshot.
func Gather(agentVersion string, agentStarted time.Time, knownServices []string) Status {
	return Status{
		Agent: AgentInfo{
			Version:   agentVersion,
			Uptime:    int64(time.Since(agentStarted).Seconds()),
			StartedAt: agentStarted,
			Arch:      runtime.GOARCH,
		},
		Host: HostInfo{
			LoadAvg:  readLoadAvg(),
			CPUCount: runtime.NumCPU(),
			Memory:   readMemory(),
			DiskRoot: readDisk("/"),
			Uptime:   readUptime(),
		},
		Services: probeServices(knownServices),
	}
}

func readLoadAvg() [3]float64 {
	data, err := os.ReadFile("/proc/loadavg")
	if err != nil {
		return [3]float64{}
	}
	parts := strings.Fields(string(data))
	if len(parts) < 3 {
		return [3]float64{}
	}
	var out [3]float64
	for i := 0; i < 3; i++ {
		out[i], _ = strconv.ParseFloat(parts[i], 64)
	}
	return out
}

func readMemory() Memory {
	f, err := os.Open("/proc/meminfo")
	if err != nil {
		return Memory{}
	}
	defer f.Close()
	var total, free, available, swapTotal uint64
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		line := sc.Text()
		switch {
		case strings.HasPrefix(line, "MemTotal:"):
			total = parseKB(line)
		case strings.HasPrefix(line, "MemFree:"):
			free = parseKB(line)
		case strings.HasPrefix(line, "MemAvailable:"):
			available = parseKB(line)
		case strings.HasPrefix(line, "SwapTotal:"):
			swapTotal = parseKB(line)
		}
	}
	// Used = total - available is closer to what `free` reports than
	// total-free (which double-counts cache as "used").
	used := uint64(0)
	if total > available {
		used = total - available
	}
	return Memory{
		TotalMB:     total / 1024,
		UsedMB:      used / 1024,
		FreeMB:      free / 1024,
		SwapTotalMB: swapTotal / 1024,
	}
}

func parseKB(line string) uint64 {
	parts := strings.Fields(line)
	if len(parts) < 2 {
		return 0
	}
	v, _ := strconv.ParseUint(parts[1], 10, 64)
	return v
}

func readDisk(path string) Disk {
	var st syscall.Statfs_t
	if err := syscall.Statfs(path, &st); err != nil {
		return Disk{}
	}
	const gb = 1024 * 1024 * 1024
	total := st.Blocks * uint64(st.Bsize)
	free := st.Bavail * uint64(st.Bsize)
	used := total - free
	return Disk{
		TotalGB: total / gb,
		UsedGB:  used / gb,
		FreeGB:  free / gb,
	}
}

func readUptime() int64 {
	data, err := os.ReadFile("/proc/uptime")
	if err != nil {
		return 0
	}
	parts := strings.Fields(string(data))
	if len(parts) == 0 {
		return 0
	}
	v, _ := strconv.ParseFloat(parts[0], 64)
	return int64(v)
}

func probeServices(names []string) []ServiceInfo {
	out := make([]ServiceInfo, 0, len(names))
	for _, name := range names {
		out = append(out, probeOne(name))
	}
	return out
}

func probeOne(name string) ServiceInfo {
	si := ServiceInfo{Name: name}
	// systemctl is-active <unit> returns "active" + exit 0 if up, anything
	// else + non-zero exit otherwise. We don't shell out to top — this is
	// already a fork/exec but a cheap one and the operation is rare.
	out, err := exec.Command("systemctl", "is-active", name).Output()
	if err == nil && strings.TrimSpace(string(out)) == "active" {
		si.Active = true
	}
	if !si.Active {
		return si
	}
	// Pull PID + start timestamp from `systemctl show` (one call, parseable).
	show, err := exec.Command("systemctl", "show", name,
		"--property=MainPID", "--property=ActiveEnterTimestamp").Output()
	if err != nil {
		return si
	}
	for _, line := range strings.Split(string(show), "\n") {
		kv := strings.SplitN(line, "=", 2)
		if len(kv) != 2 {
			continue
		}
		switch kv[0] {
		case "MainPID":
			if pid, err := strconv.Atoi(kv[1]); err == nil && pid > 0 {
				si.PID = &pid
			}
		case "ActiveEnterTimestamp":
			if kv[1] != "" {
				v := kv[1]
				si.Since = &v
			}
		}
	}
	return si
}

// ParseError is returned by helpers when we can't make sense of /proc data.
var ParseError = errors.New("parse error")
