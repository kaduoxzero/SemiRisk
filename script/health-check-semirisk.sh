#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="${COMPOSE_FILE:-${ROOT_DIR}/script/docker/docker-compose.yml}"
MYSQL_ROOT_PASSWORD="${MYSQL_ROOT_PASSWORD:-ruoyi123}"
REDIS_PASSWORD="${REDIS_PASSWORD:-ruoyi123}"
GATEWAY_URL="${GATEWAY_URL:-http://127.0.0.1:8080}"
FRONTEND_URL="${FRONTEND_URL:-http://127.0.0.1}"
AI_URL="${AI_URL:-http://127.0.0.1:18088}"
NACOS_URL="${NACOS_URL:-http://127.0.0.1:8848}"
NACOS_NAMESPACE="${NACOS_NAMESPACE:-prod}"
REQUIRED_AI_MODEL="${DEEPSEEK_MODEL:-deepseekv4-pro}"
REQUIRE_AI_PROVIDER="${REQUIRE_AI_PROVIDER:-true}"

step() {
  printf '\n==> %s\n' "$1"
}

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 1
  fi
}

curl_expect() {
  local url="$1"
  local expected="$2"
  local body
  body="$(curl -fsS --max-time 15 "$url")"
  if [[ "$body" != *"$expected"* ]]; then
    echo "Unexpected response from ${url}" >&2
    echo "$body" >&2
    exit 1
  fi
}

wait_http() {
  local url="$1"
  local seconds="${2:-90}"
  local deadline=$((SECONDS + seconds))
  until curl -fsS --max-time 5 "$url" >/dev/null 2>&1; do
    if (( SECONDS >= deadline )); then
      echo "Timeout waiting for ${url}" >&2
      exit 1
    fi
    sleep 2
  done
}

require_cmd docker
require_cmd curl

step "Container status"
docker compose -f "${COMPOSE_FILE}" ps

step "MySQL"
docker exec mysql mysqladmin ping -uroot "-p${MYSQL_ROOT_PASSWORD}" --silent >/dev/null

step "Redis"
docker exec redis redis-cli -a "${REDIS_PASSWORD}" ping 2>/dev/null | grep -q '^PONG$'

step "Nacos"
curl -fsS --max-time 15 "${NACOS_URL}/nacos" >/dev/null
for service in ruoyi-gateway ruoyi-auth ruoyi-system ruoyi-resource; do
  curl -fsS --max-time 15 \
    "${NACOS_URL}/nacos/v1/ns/instance/list?serviceName=${service}&groupName=DEFAULT_GROUP&namespaceId=${NACOS_NAMESPACE}" \
    | grep -q '"healthy":true'
done

step "Frontend"
curl -fsSI --max-time 15 "${FRONTEND_URL}/" | grep -q '200'

step "Gateway protected route"
curl_expect "${GATEWAY_URL}/actuator/health" '"code": 401'

step "AI health"
wait_http "${AI_URL}/health" 60
AI_HEALTH="$(curl -fsS --max-time 15 "${AI_URL}/health")"
printf '%s\n' "${AI_HEALTH}"
if [[ "${AI_HEALTH}" != *"\"status\":\"UP\""* && "${AI_HEALTH}" != *'"status": "UP"'* ]]; then
  echo "AI service is not UP" >&2
  exit 1
fi
if [[ "${AI_HEALTH}" != *"\"model\":\"${REQUIRED_AI_MODEL}\""* && "${AI_HEALTH}" != *"\"model\": \"${REQUIRED_AI_MODEL}\""* ]]; then
  echo "AI model is not ${REQUIRED_AI_MODEL}" >&2
  exit 1
fi
if [[ "${REQUIRE_AI_PROVIDER}" == "true" && "${AI_HEALTH}" != *'"providerConfigured":true'* && "${AI_HEALTH}" != *'"providerConfigured": true'* ]]; then
  echo "AI provider is not configured" >&2
  exit 1
fi

step "AI analyze API"
curl -fsS --max-time 120 \
  -H 'Content-Type: application/json' \
  -d '{"templateType":"供应链风险研判报告","dateRange":"交付健康检查","events":[{"eventTitle":"供应商交付延期","enterpriseName":"测试企业","category":"供应链","riskLevel":"HIGH","status":"OPEN","riskScore":88,"sourceName":"交付自检","description":"用于验证AI分析接口可用","occurredAt":"2026-06-07 00:00:00"}]}' \
  "${AI_URL}/analyze" | grep -q '"content"'

step "SemiRisk health check passed"
