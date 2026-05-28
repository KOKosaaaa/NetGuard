#!/usr/bin/env bash
# netguard-agent bootstrap — invoked by the NetGuard Android client over
# SSH on a fresh VPS. Idempotent: re-running on an already-bootstrapped
# box reuses the existing install and just rotates the pair token.
#
# Inputs (env):
#   NG_BIN_B64        — base64-encoded netguard-agent binary
#                       (required on first install; on re-bootstrap the
#                        existing /usr/local/bin/netguard-agent is kept
#                        unless this env is set)
#   NG_BIN_SHA256     — sha256 of the decoded binary (always required
#                       when NG_BIN_B64 is set)
#   NG_LISTEN         — agent listen addr (default :9443)
#
# Stdout on success: a single line with the agent's pair token.
# Exit codes: 0 OK; non-zero with E_* in stderr otherwise.

set -euo pipefail

fail() {
    local code="$1"; shift
    echo "$code: $*" 1>&2
    exit 1
}

# --- 1. preflight -----------------------------------------------------------
[[ "$(id -u)" -eq 0 ]] || fail E_NOT_ROOT "must run as root (got uid $(id -u))"

if [[ ! -r /etc/os-release ]]; then
    fail E_OS_UNKNOWN "/etc/os-release missing"
fi
# shellcheck disable=SC1091
. /etc/os-release
case "${ID:-}" in
    debian|ubuntu) : ;;
    *) fail E_OS_UNSUPPORTED "supported: Debian, Ubuntu (got ID=${ID:-})" ;;
esac

# Pacify apt-get prompts for the rest of this script.
export DEBIAN_FRONTEND=noninteractive

# --- 2. prerequisites -------------------------------------------------------
need_apt=()
command -v base64 >/dev/null  || need_apt+=(coreutils)
command -v sha256sum >/dev/null || need_apt+=(coreutils)
command -v systemctl >/dev/null || fail E_NO_SYSTEMD "systemd is required"
if [[ ${#need_apt[@]} -gt 0 ]]; then
    apt-get update -qq >/dev/null 2>&1 || true
    apt-get install -y -qq "${need_apt[@]}" \
        || fail E_APT_PREREQ "apt-get install ${need_apt[*]} failed"
fi

# --- 3. install binary ------------------------------------------------------
INSTALL_BIN=/usr/local/bin/netguard-agent
NG_LISTEN="${NG_LISTEN:-:9443}"

if [[ -n "${NG_BIN_B64:-}" ]]; then
    [[ -n "${NG_BIN_SHA256:-}" ]] \
        || fail E_NO_SHA "NG_BIN_B64 supplied without NG_BIN_SHA256"
    tmp=$(mktemp)
    trap 'rm -f "$tmp"' EXIT
    echo "$NG_BIN_B64" | base64 -d > "$tmp" \
        || fail E_BASE64 "failed to decode NG_BIN_B64"
    got=$(sha256sum "$tmp" | cut -d' ' -f1)
    if [[ "$got" != "$NG_BIN_SHA256" ]]; then
        fail E_CHECKSUM "binary sha256 mismatch (want $NG_BIN_SHA256 got $got)"
    fi
    install -m 0755 "$tmp" "$INSTALL_BIN"
elif [[ ! -x "$INSTALL_BIN" ]]; then
    fail E_NO_BINARY "no NG_BIN_B64 supplied and $INSTALL_BIN missing"
fi

# --- 4. systemd unit --------------------------------------------------------
UNIT=/etc/systemd/system/netguard-agent.service
cat > "$UNIT" <<UNITEOF
[Unit]
Description=NetGuard control-plane agent
After=network-online.target
Wants=network-online.target
StartLimitBurst=10
StartLimitIntervalSec=120

[Service]
Type=simple
ExecStart=$INSTALL_BIN -listen $NG_LISTEN -state /var/lib/netguard-agent
User=root
Group=root
StateDirectory=netguard-agent
StateDirectoryMode=0700
Restart=on-failure
RestartSec=5
# ProtectSystem=strict breaks apt-get during xray/sing-box install
# (Read-only /var/cache/apt). Agent legitimately mutates system files.
ProtectHome=true
PrivateTmp=true
NoNewPrivileges=true
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
UNITEOF
chmod 0644 "$UNIT"

systemctl daemon-reload
# enable --now: starts AND sets it to come up on boot. Idempotent —
# re-runs are no-ops if it's already enabled+running.
if ! systemctl enable --now netguard-agent.service > /tmp/ngsvc.log 2>&1; then
    cat /tmp/ngsvc.log 1>&2
    fail E_SYSTEMD_START "systemctl enable --now failed (see logs above)"
fi

# --- 5. wait for pair-token file --------------------------------------------
# Agent creates this on first run. On a re-bootstrap (existing install,
# valid pair-token still in DB) the agent re-writes the file on boot.
# 30s should easily cover SQLite migrate + cert gen on slow VPS.
tok=""
for _ in $(seq 1 30); do
    if [[ -s /var/lib/netguard-agent/pair-token.txt ]]; then
        tok=$(< /var/lib/netguard-agent/pair-token.txt)
        break
    fi
    sleep 1
done
[[ -n "$tok" ]] || fail E_NO_PAIR_TOKEN \
    "agent started but pair-token.txt was not produced; check journalctl -u netguard-agent"

# --- 6. emit token on stdout ------------------------------------------------
# The Android client captures this single line, then exchanges it via
# POST /v1/auth/pair and deletes the file (best-effort: agent also
# deletes on first successful pair).
echo "$tok"
