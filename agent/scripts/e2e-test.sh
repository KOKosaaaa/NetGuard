#!/usr/bin/env bash
# End-to-end smoke test against a live agent.
#
# Usage: ./e2e-test.sh HOST [PORT]
# Example: ./e2e-test.sh 104.238.29.0
#
# Walks through pair → status → xray deploy → poll task → list inbounds.
# Reads the pair token directly from the server via SSH — assumes the
# caller can already `ssh root@HOST` (key set up via earlier provisioning).

set -euo pipefail

HOST="${1:?usage: e2e-test.sh HOST [PORT]}"
PORT="${2:-9443}"
BASE="https://$HOST:$PORT/v1"

say() { printf '\n=== %s ===\n' "$*"; }

say "0. health"
curl -k -sS "$BASE/health" | tee /dev/stderr | grep -q '"ok":true'

say "1. read pair token via SSH"
SSH_KEY="${SSH_KEY:-$HOME/.ssh/id_ed25519}"
PAIR=$(ssh -i "$SSH_KEY" -o StrictHostKeyChecking=no "root@$HOST" \
    'cat /var/lib/netguard-agent/pair-token.txt')
echo "pair token: ${PAIR:0:12}…"

say "2. exchange pair → bearer"
RESP=$(curl -k -sS -XPOST "$BASE/auth/pair" \
    -H 'Content-Type: application/json' \
    -d "{\"pair_token\":\"$PAIR\",\"device_name\":\"e2e\",\"app_version\":\"phase1\"}")
echo "$RESP"
BEARER=$(echo "$RESP" | python -c 'import sys,json; print(json.load(sys.stdin)["bearer"])')
echo "bearer: ${BEARER:0:12}…"

AUTH=(-H "Authorization: Bearer $BEARER")

say "3. status"
curl -k -sS "${AUTH[@]}" "$BASE/status" | python -m json.tool | head -25

say "4. POST /xray/deploy with one VLESS inbound"
DEPLOY=$(curl -k -sS -XPOST "${AUTH[@]}" "$BASE/xray/deploy" \
    -H 'Content-Type: application/json' \
    -d '{"first_inbound":{"protocol":"vless","port":11443,"label":"HEL-test"}}')
echo "$DEPLOY"
TASK_ID=$(echo "$DEPLOY" | python -c 'import sys,json; print(json.load(sys.stdin)["task_id"])')
echo "task: $TASK_ID"

say "5. poll until done/failed"
for i in $(seq 1 60); do
    sleep 2
    T=$(curl -k -sS "${AUTH[@]}" "$BASE/tasks/$TASK_ID")
    STATUS=$(echo "$T" | python -c 'import sys,json; print(json.load(sys.stdin)["status"])')
    STEP=$(echo "$T" | python -c 'import sys,json; print(json.load(sys.stdin).get("step",""))')
    PROG=$(echo "$T" | python -c 'import sys,json; print(json.load(sys.stdin).get("progress",0))')
    printf '  poll %02d: status=%s step=%s progress=%d\n' "$i" "$STATUS" "$STEP" "$PROG"
    case "$STATUS" in
        done|failed|rolled_back) echo "FINAL:"; echo "$T" | python -m json.tool; break ;;
    esac
done

say "6. /xray/inbounds"
curl -k -sS "${AUTH[@]}" "$BASE/xray/inbounds" | python -m json.tool

say "DONE"
