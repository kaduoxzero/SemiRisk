#!/usr/bin/env bash
set -euo pipefail

JUMP_HOST="${SEMIRISK_SSH_JUMP_HOST:-172.16.0.151}"
JUMP_USER="${SEMIRISK_SSH_JUMP_USER:-kaduox}"
VM_HOST="${SEMIRISK_VM_HOST:-192.168.101.130}"
VM_USER="${SEMIRISK_VM_USER:-kaduox}"
PID_FILE="${SEMIRISK_TUNNEL_PID_FILE:-logs/semirisk-vm-tunnel.pid}"

mkdir -p "$(dirname "${PID_FILE}")"

if [[ -f "${PID_FILE}" ]] && kill -0 "$(cat "${PID_FILE}")" 2>/dev/null; then
  echo "SemiRisk VM tunnel already running pid=$(cat "${PID_FILE}")"
  exit 0
fi

SSH_ARGS=(
  -N
  -o ExitOnForwardFailure=yes
  -o StrictHostKeyChecking=no
  -o "UserKnownHostsFile=${SEMIRISK_TUNNEL_KNOWN_HOSTS:-logs/semirisk-known-hosts}"
  -o ServerAliveInterval=30
  -o ServerAliveCountMax=3
  -J "${JUMP_USER}@${JUMP_HOST}"
  -L 3306:127.0.0.1:3306
  -L 6379:127.0.0.1:6379
  -L 9200:127.0.0.1:9200
  -L 9000:127.0.0.1:9000
  -L 9001:127.0.0.1:9001
  -L 5672:127.0.0.1:5672
  -L 15672:127.0.0.1:15672
  -L 8848:127.0.0.1:8848
  "${VM_USER}@${VM_HOST}"
)

if [[ -n "${SEMIRISK_SSH_JUMP_PASSWORD:-}" && -n "${SEMIRISK_VM_PASSWORD:-}" ]]; then
  export SEMIRISK_SSH_JUMP_HOST="${JUMP_HOST}"
  export SEMIRISK_SSH_JUMP_USER="${JUMP_USER}"
  export SEMIRISK_VM_HOST="${VM_HOST}"
  export SEMIRISK_VM_USER="${VM_USER}"
  export SEMIRISK_TUNNEL_KNOWN_HOSTS="${SEMIRISK_TUNNEL_KNOWN_HOSTS:-logs/semirisk-known-hosts}"
  nohup expect -c '
    set timeout -1
    spawn ssh -N \
      -o ExitOnForwardFailure=yes \
      -o StrictHostKeyChecking=no \
      -o UserKnownHostsFile=$env(SEMIRISK_TUNNEL_KNOWN_HOSTS) \
      -o ServerAliveInterval=30 \
      -o ServerAliveCountMax=3 \
      -J $env(SEMIRISK_SSH_JUMP_USER)@$env(SEMIRISK_SSH_JUMP_HOST) \
      -L 3306:127.0.0.1:3306 \
      -L 6379:127.0.0.1:6379 \
      -L 9200:127.0.0.1:9200 \
      -L 9000:127.0.0.1:9000 \
      -L 9001:127.0.0.1:9001 \
      -L 5672:127.0.0.1:5672 \
      -L 15672:127.0.0.1:15672 \
      -L 8848:127.0.0.1:8848 \
      $env(SEMIRISK_VM_USER)@$env(SEMIRISK_VM_HOST)
    expect "password:"
    send "$env(SEMIRISK_SSH_JUMP_PASSWORD)\r"
    expect "password:"
    send "$env(SEMIRISK_VM_PASSWORD)\r"
    expect eof
  ' > logs/semirisk-vm-tunnel.log 2>&1 &
  echo "$!" > "${PID_FILE}"
  echo "SemiRisk VM tunnel started pid=$!"
  echo "Use SEMIRISK_MIDDLEWARE_HOST=127.0.0.1 when starting local services."
else
  echo "Starting interactive tunnel. Keep this terminal open, or set SEMIRISK_SSH_JUMP_PASSWORD and SEMIRISK_VM_PASSWORD for background mode."
  echo "Use SEMIRISK_MIDDLEWARE_HOST=127.0.0.1 in another terminal when starting local services."
  exec ssh "${SSH_ARGS[@]}"
fi
