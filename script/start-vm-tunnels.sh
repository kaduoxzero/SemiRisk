#!/usr/bin/env bash
set -euo pipefail

JUMP_HOST="${SEMIRISK_SSH_JUMP_HOST:-172.16.0.151}"
JUMP_USER="${SEMIRISK_SSH_JUMP_USER:-kaduox}"
VM_HOST="${SEMIRISK_VM_HOST:-192.168.101.130}"
VM_USER="${SEMIRISK_VM_USER:-kaduox}"
PID_FILE="${SEMIRISK_TUNNEL_PID_FILE:-logs/semirisk-vm-tunnel.pid}"
LOG_FILE="${SEMIRISK_TUNNEL_LOG_FILE:-logs/semirisk-vm-tunnel.log}"
SCREEN_SESSION="${SEMIRISK_TUNNEL_SCREEN_SESSION:-semirisk-vm-tunnel}"

mkdir -p "$(dirname "${PID_FILE}")"

screen_running() {
  screen -ls | grep -q "[.]${SCREEN_SESSION}[[:space:]]"
}

all_tunnel_ports_listening() {
  lsof -nP -iTCP:3306 -sTCP:LISTEN >/dev/null 2>&1 \
    && lsof -nP -iTCP:6379 -sTCP:LISTEN >/dev/null 2>&1 \
    && lsof -nP -iTCP:9200 -sTCP:LISTEN >/dev/null 2>&1 \
    && lsof -nP -iTCP:9000 -sTCP:LISTEN >/dev/null 2>&1 \
    && lsof -nP -iTCP:9001 -sTCP:LISTEN >/dev/null 2>&1 \
    && lsof -nP -iTCP:5672 -sTCP:LISTEN >/dev/null 2>&1 \
    && lsof -nP -iTCP:15672 -sTCP:LISTEN >/dev/null 2>&1 \
    && lsof -nP -iTCP:8848 -sTCP:LISTEN >/dev/null 2>&1 \
    && lsof -nP -iTCP:9848 -sTCP:LISTEN >/dev/null 2>&1 \
    && lsof -nP -iTCP:9411 -sTCP:LISTEN >/dev/null 2>&1
}

if [[ -f "${PID_FILE}" ]]; then
  state="$(cat "${PID_FILE}")"
  if [[ "${state}" == screen:* ]] && screen -ls | grep -q "[.]${state#screen:}[[:space:]]"; then
    echo "SemiRisk VM tunnel already running session=${state#screen:}"
    exit 0
  elif [[ "${state}" == *.sock ]] && [[ -S "${state}" ]] \
    && ssh -S "${state}" -O check -J "${JUMP_USER}@${JUMP_HOST}" "${VM_USER}@${VM_HOST}" >/dev/null 2>&1; then
    echo "SemiRisk VM tunnel already running control=${state}"
    exit 0
  elif [[ "${state}" != *.sock ]] && kill -0 "${state}" 2>/dev/null; then
    echo "SemiRisk VM tunnel already running pid=${state}"
    exit 0
  fi
elif screen_running; then
  echo "screen:${SCREEN_SESSION}" > "${PID_FILE}"
  echo "SemiRisk VM tunnel already running session=${SCREEN_SESSION}"
  exit 0
elif all_tunnel_ports_listening; then
  echo "SemiRisk VM tunnel already running on local middleware ports."
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
  -L 9848:127.0.0.1:9848
  -L 9411:127.0.0.1:9411
  "${VM_USER}@${VM_HOST}"
)

if [[ -n "${SEMIRISK_SSH_JUMP_PASSWORD:-}" && -n "${SEMIRISK_VM_PASSWORD:-}" ]]; then
  if ! command -v screen >/dev/null 2>&1; then
    echo "screen is required for non-interactive tunnel startup on macOS. Install screen or run without password env vars for interactive mode."
    exit 1
  fi

  export SEMIRISK_SSH_JUMP_HOST="${JUMP_HOST}"
  export SEMIRISK_SSH_JUMP_USER="${JUMP_USER}"
  export SEMIRISK_VM_HOST="${VM_HOST}"
  export SEMIRISK_VM_USER="${VM_USER}"
  export SEMIRISK_TUNNEL_KNOWN_HOSTS="${SEMIRISK_TUNNEL_KNOWN_HOSTS:-logs/semirisk-known-hosts}"
  export SEMIRISK_TUNNEL_LOG_FILE="${LOG_FILE}"
  screen -S "${SCREEN_SESSION}" -X quit >/dev/null 2>&1 || true
  rm -f "${PID_FILE}" "${LOG_FILE}"

  screen -dmS "${SCREEN_SESSION}" expect -c '
    set jump_password $env(SEMIRISK_SSH_JUMP_PASSWORD)
    set vm_password $env(SEMIRISK_VM_PASSWORD)
    unset env(SEMIRISK_SSH_JUMP_PASSWORD)
    unset env(SEMIRISK_VM_PASSWORD)

    log_file -noappend $env(SEMIRISK_TUNNEL_LOG_FILE)

    set timeout 30
    set password_count 0
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
      -L 9848:127.0.0.1:9848 \
      -L 9411:127.0.0.1:9411 \
      $env(SEMIRISK_VM_USER)@$env(SEMIRISK_VM_HOST)
    expect {
      -re "(?i)password:" {
        incr password_count
        if {$password_count == 1} {
          send "$jump_password\r"
        } elseif {$password_count == 2} {
          send "$vm_password\r"
          set timeout -1
        } else {
          exit 10
        }
        exp_continue
      }
      -re "Address already in use|Could not request local forwarding|channel_setup_fwd_listener" {
        exit 11
      }
      -re "Local forwarding listening|Authenticated to .*via proxy|Entering interactive session" {
        set timeout -1
        exp_continue
      }
      timeout {
        exit 12
      }
      eof {
        exit 13
      }
    }
  '

  echo "screen:${SCREEN_SESSION}" > "${PID_FILE}"
  for _ in {1..60}; do
    if all_tunnel_ports_listening; then
      tunnel_pid="$(lsof -nP -tiTCP:3306 -sTCP:LISTEN | head -n 1 || true)"
      if [[ -n "${tunnel_pid}" ]]; then
        echo "${tunnel_pid}" > "${PID_FILE}"
      fi
      echo "SemiRisk VM tunnel started session=${SCREEN_SESSION}"
      echo "Use SEMIRISK_MIDDLEWARE_HOST=127.0.0.1 when starting local services."
      exit 0
    fi

    if ! screen_running; then
      sleep 1
      continue
    fi
    sleep 1
  done

  if ! screen_running; then
    echo "SemiRisk VM tunnel failed. Recent log:"
    tail -n 40 "${LOG_FILE}" 2>/dev/null || true
    rm -f "${PID_FILE}"
    exit 1
  fi

  echo "SemiRisk VM tunnel did not expose all local ports in time. Recent log:"
  tail -n 40 "${LOG_FILE}" 2>/dev/null || true
  screen -S "${SCREEN_SESSION}" -X quit >/dev/null 2>&1 || true
  rm -f "${PID_FILE}"
  exit 1
else
  echo "Starting interactive tunnel. Keep this terminal open, or set SEMIRISK_SSH_JUMP_PASSWORD and SEMIRISK_VM_PASSWORD for background mode."
  echo "Use SEMIRISK_MIDDLEWARE_HOST=127.0.0.1 in another terminal when starting local services."
  exec ssh "${SSH_ARGS[@]}"
fi
