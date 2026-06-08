#!/usr/bin/env bash
set -euo pipefail

PID_FILE="${SEMIRISK_TUNNEL_PID_FILE:-logs/semirisk-vm-tunnel.pid}"
JUMP_HOST="${SEMIRISK_SSH_JUMP_HOST:-172.16.0.151}"
JUMP_USER="${SEMIRISK_SSH_JUMP_USER:-kaduox}"
VM_HOST="${SEMIRISK_VM_HOST:-192.168.101.130}"
VM_USER="${SEMIRISK_VM_USER:-kaduox}"
SCREEN_SESSION="${SEMIRISK_TUNNEL_SCREEN_SESSION:-semirisk-vm-tunnel}"

stop_port_tunnel_if_present() {
  local pids
  pids="$(for port in 3306 6379 9200 9000 9001 5672 15672 8848; do
    lsof -tiTCP:"${port}" -sTCP:LISTEN 2>/dev/null || true
  done | sort -u)"

  if [[ -z "${pids}" ]]; then
    return 1
  fi

  while read -r pid; do
    if ps -p "${pid}" -o command= 2>/dev/null | grep -q "ssh -N .*${VM_HOST}"; then
      kill "${pid}" 2>/dev/null || true
      echo "SemiRisk VM tunnel stopped pid=${pid}"
      return 0
    fi
  done <<< "${pids}"

  return 1
}

if [[ ! -f "${PID_FILE}" ]]; then
  if screen -ls | grep -q "[.]${SCREEN_SESSION}[[:space:]]"; then
    screen -S "${SCREEN_SESSION}" -X quit >/dev/null 2>&1 || true
    echo "SemiRisk VM tunnel stopped session=${SCREEN_SESSION}"
    exit 0
  elif stop_port_tunnel_if_present; then
    exit 0
  fi
  echo "No SemiRisk VM tunnel pid file found."
  exit 0
fi

state="$(cat "${PID_FILE}")"
if [[ "${state}" == screen:* ]]; then
  screen -S "${state#screen:}" -X quit >/dev/null 2>&1 || true
  echo "SemiRisk VM tunnel stopped session=${state#screen:}"
elif [[ "${state}" == *.sock ]]; then
  ssh -S "${state}" -O exit -J "${JUMP_USER}@${JUMP_HOST}" "${VM_USER}@${VM_HOST}" 2>/dev/null || true
  echo "SemiRisk VM tunnel stopped control=${state}"
elif kill -0 "${state}" 2>/dev/null; then
  kill "${state}"
  echo "SemiRisk VM tunnel stopped pid=${state}"
else
  echo "SemiRisk VM tunnel state=${state} is not running."
fi

rm -f "${PID_FILE}"
