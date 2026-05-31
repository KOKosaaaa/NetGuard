// purge.go — full self-destruct: wipe every service the agent deployed
// (xray, sing-box, Telemost) AND the agent itself, leaving the box clean.
//
// This can't run synchronously inside the request handler: stopping
// netguard-agent.service and deleting our own binary would kill this
// process mid-response. So we launch the cleanup in its OWN cgroup
// (systemd-run) / detached session (setsid fallback) so it outlives the
// agent's termination, then return immediately. The caller's HTTP 200
// flushes first; the app then polls /v1/health and, once it stops
// answering, knows the server is gone.
//
// IMPORTANT: the script is passed INLINE via `sh -c`, never staged as a
// file in /tmp. netguard-agent.service runs with PrivateTmp=true, so a
// file the agent writes to /tmp lives in a private mount namespace that a
// fresh systemd-run unit cannot see ("cannot open /tmp/...: No such
// file") — which silently broke the first cut of this feature.

package deploy

import (
	"fmt"
	"log"
	"os/exec"
)

// purgeScript builds the self-destruct shell program. Paths come from the
// same constants the install paths use so this never drifts.
func purgeScript() string {
	return fmt.Sprintf(`
# NetGuard agent self-destruct. Removes every deployed VPN service and
# the agent itself. Launched detached so it survives the agent dying.
sleep 2

# --- Telemost (wlb-telemost@N instances + binary + unit + data + user) ---
systemctl list-units --type=service '%[1]s*' --no-legend --plain | awk '{print $1}' | xargs -r systemctl disable --now
rm -f %[2]s %[3]s
rm -rf %[4]s /var/log/whitelist-bypass /run/whitelist-bypass
userdel wlb 2>/dev/null || true

# --- xray ---
systemctl disable --now xray 2>/dev/null || true
rm -f %[5]s %[6]s
rm -rf /etc/xray %[7]s %[8]s

# --- sing-box (best-effort; only if the agent ever set it up) ---
systemctl disable --now sing-box 2>/dev/null || true

# --- stripe-server (Telemost striping exit mux) ---
systemctl disable --now %[11]s 2>/dev/null || true
rm -f %[12]s %[13]s

# --- the agent itself ---
systemctl disable --now netguard-agent 2>/dev/null || true
rm -f /etc/systemd/system/netguard-agent.service
rm -rf %[9]s
rm -f %[10]s %[10]s.bak %[10]s.new
systemctl daemon-reload 2>/dev/null || true
`,
		telemostUnitPrefix,        // 1 wlb-telemost@
		telemostUnitPath,          // 2 /etc/systemd/system/wlb-telemost@.service
		telemostInstallPath,       // 3 /usr/local/bin/headless-telemost-creator
		telemostConfDir,           // 4 /etc/whitelist-bypass
		XrayInstallPath,           // 5 /usr/local/bin/xray
		XrayUnitPath,              // 6 /etc/systemd/system/xray.service
		XrayDataDir,               // 7 /var/lib/xray
		XrayAssetDir,              // 8 /usr/local/share/xray
		"/var/lib/netguard-agent", // 9 agent state dir
		AgentBinaryPath,           // 10 /usr/local/bin/netguard-agent
		stripeUnitName,            // 11 netguard-stripe-server.service
		stripeUnitPath,            // 12 /etc/systemd/system/netguard-stripe-server.service
		stripeInstallPath,         // 13 /usr/local/bin/netguard-stripe-server
	)
}

// PurgeAll launches the self-destruct script detached, then returns. After
// this the agent has a few seconds left before the script stops
// netguard-agent.service.
func PurgeAll() error {
	script := purgeScript()
	// Preferred: a transient systemd scope. It gets its own cgroup, so
	// `systemctl disable --now netguard-agent` inside the script doesn't
	// take the script down with the agent. --collect cleans up the unit
	// (incl. a prior failed one of the same name) after it exits.
	if _, err := exec.LookPath("systemd-run"); err == nil {
		cmd := exec.Command("systemd-run", "--collect",
			"--unit=netguard-purge", "/bin/sh", "-c", script)
		if err := cmd.Run(); err == nil {
			log.Print("purge: launched via systemd-run")
			return nil
		}
		log.Print("purge: systemd-run failed, falling back to setsid")
	}
	// Fallback: detach with setsid (util-linux) so the script lands in a
	// new session, outside the agent's process group.
	if _, err := exec.LookPath("setsid"); err == nil {
		cmd := exec.Command("setsid", "/bin/sh", "-c", script)
		if err := cmd.Start(); err == nil {
			log.Print("purge: launched via setsid")
			return nil
		}
	}
	// Last resort: plain background sh. If the agent's cgroup kill catches
	// it, the VPN services above were already removed before the agent's
	// own removal line runs.
	cmd := exec.Command("/bin/sh", "-c", script)
	if err := cmd.Start(); err != nil {
		return fmt.Errorf("launch purge: %w", err)
	}
	log.Print("purge: launched via background sh")
	return nil
}
