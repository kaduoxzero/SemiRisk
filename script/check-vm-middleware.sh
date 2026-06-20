#!/usr/bin/env bash
set -euo pipefail

VM_HOST="${VM_HOST:-192.168.101.130}"

check_port() {
  local name="$1"
  local port="$2"
  if nc -z -w 3 "${VM_HOST}" "${port}"; then
    echo "[OK] ${name} ${VM_HOST}:${port}"
  else
    echo "[FAIL] ${name} ${VM_HOST}:${port}"
  fi
}

check_port "MySQL" 3306
check_port "Redis master" 6379
check_port "Redis replica-1" 6380
check_port "Redis replica-2" 6381
check_port "Redis replica-3" 6382
check_port "Elasticsearch" 9200
check_port "MinIO API" 9000
check_port "MinIO Console" 9001
check_port "RabbitMQ" 5672
check_port "RabbitMQ Console" 15672
check_port "Nacos" 8848
