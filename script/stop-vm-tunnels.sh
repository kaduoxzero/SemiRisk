#!/usr/bin/env bash
set -euo pipefail

PID_FILE="${SEMIRISK_TUNNEL_PID_FILE:-logs/semirisk-vm-tunnel.pid}"
JUMP_HOST="${SEMIRISK_SSH_JUMP_HOST:-172.16.0.151}"
JUMP_USER="${SEMIRISK_SSH_JUMP_USER:-kaduox}"
VM_HOST="${SEMIRISK_VM_HOST:-192.168.101.130}"
VM_USER="${SEMIRISK_VM_USER:-kaduox}"

if [[ ! -f "${PID_FILE}" ]]; then
  echo "No SemiRisk VM tunnel pid file found."
  exit 0
fi

state="$(cat "${PID_FILE}")"
if [[ "${state}" == *.sock ]]; then
  ssh -S "${state}" -O exit -J "${JUMP_USER}@${JUMP_HOST}" "${VM_USER}@${VM_HOST}" 2>/dev/null || true
  echo "SemiRisk VM tunnel stopped control=${state}"
elif kill -0 "${state}" 2>/dev/null; then
  kill "${state}"
  echo "SemiRisk VM tunnel stopped pid=${state}"
else
  echo "SemiRisk VM tunnel state=${state} is not running."
fi

rm -f "${PID_FILE}"
