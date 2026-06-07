#!/usr/bin/env bash
set -euo pipefail

BUILD_PROFILE="${BUILD_PROFILE:-prod}"
COMPOSE_FILE="${COMPOSE_FILE:-script/docker/docker-compose.yml}"
NACOS_URL="${NACOS_URL:-http://127.0.0.1:8848}"
MYSQL_ROOT_PASSWORD="${MYSQL_ROOT_PASSWORD:-ruoyi123}"
NACOS_USERNAME="${NACOS_USERNAME:-nacos}"
NACOS_PASSWORD="${NACOS_PASSWORD:-nacos}"
SKIP_BUILD="${SKIP_BUILD:-false}"

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

step() {
  printf '\n==> %s\n' "$1"
}

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
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
    sleep 3
  done
}

wait_mysql() {
  local deadline=$((SECONDS + 120))
  until docker exec mysql mysqladmin ping -uroot "-p${MYSQL_ROOT_PASSWORD}" --silent >/dev/null 2>&1; do
    if (( SECONDS >= deadline )); then
      echo "Timeout waiting for MySQL" >&2
      exit 1
    fi
    sleep 3
  done
}

import_sql() {
  local path="$1"
  local database="${2:-}"
  if [[ ! -f "$path" ]]; then
    echo "SQL file not found: ${path}" >&2
    exit 1
  fi
  echo "Import SQL: ${path}"
  if [[ -n "${database}" ]]; then
    docker exec -i mysql mysql --force --default-character-set=utf8mb4 -uroot "-p${MYSQL_ROOT_PASSWORD}" "${database}" < "$path"
  else
    docker exec -i mysql mysql --force --default-character-set=utf8mb4 -uroot "-p${MYSQL_ROOT_PASSWORD}" < "$path"
  fi
}

nacos_token() {
  curl -fsS -X POST "${NACOS_URL}/nacos/v1/auth/login" \
    -d "username=${NACOS_USERNAME}" \
    -d "password=${NACOS_PASSWORD}" 2>/dev/null \
    | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p' || true
}

publish_nacos_config() {
  local data_id="$1"
  local file_name="$2"
  local type="$3"
  local token="$4"
  local path="script/config/nacos/${file_name}"

  if [[ ! -f "$path" ]]; then
    echo "Nacos config file not found: ${path}" >&2
    exit 1
  fi

  local args=(
    -fsS
    -X POST "${NACOS_URL}/nacos/v1/cs/configs"
    --data-urlencode "dataId=${data_id}"
    --data-urlencode "group=DEFAULT_GROUP"
    --data-urlencode "tenant=${BUILD_PROFILE}"
    --data-urlencode "content@${path}"
    --data-urlencode "type=${type}"
  )

  if [[ -n "$token" ]]; then
    args+=(--data-urlencode "accessToken=${token}")
  fi

  curl "${args[@]}" >/dev/null
  echo "Published Nacos config: ${data_id} [${BUILD_PROFILE}]"
}

require_cmd docker
require_cmd curl

if [[ "${SKIP_BUILD}" != "true" ]]; then
  require_cmd mvn
  require_cmd npm

  step "Build Java services"
  mvn "-P${BUILD_PROFILE}" -DskipTests package \
    -pl ruoyi-gateway,ruoyi-auth,ruoyi-modules/ruoyi-system,ruoyi-modules/ruoyi-resource -am

  step "Build frontend"
  (
    cd ruoyi-ui
    npm install
    npm run build:prod
  )
else
  step "Use prebuilt artifacts"
  test -f ruoyi-gateway/target/ruoyi-gateway.jar
  test -f ruoyi-auth/target/ruoyi-auth.jar
  test -f ruoyi-modules/ruoyi-system/target/ruoyi-system.jar
  test -f ruoyi-modules/ruoyi-resource/target/ruoyi-resource.jar
  test -f ruoyi-ui/dist/index.html
fi

step "Build Docker images"
docker compose -f "${COMPOSE_FILE}" build risk-ai ruoyi-gateway ruoyi-system ruoyi-auth ruoyi-resource nginx

step "Start middleware"
mkdir -p script/docker/mysql/data script/docker/mysql/conf script/docker/redis/data script/docker/redis/conf script/docker/nacos/logs script/docker/logs
if ! chmod -R 777 script/docker/redis/data script/docker/nacos/logs script/docker/logs 2>/dev/null; then
  if [[ -n "${SUDO_PASSWORD:-}" ]] && command -v sudo >/dev/null 2>&1; then
    printf '%s\n' "${SUDO_PASSWORD}" | sudo -S chmod -R 777 script/docker/redis/data script/docker/nacos/logs script/docker/logs
  else
    echo "Unable to update runtime directory permissions. Re-run with SUDO_PASSWORD set or fix permissions manually." >&2
    exit 1
  fi
fi
docker compose -f "${COMPOSE_FILE}" up -d mysql redis
wait_mysql
import_sql "script/sql/semi-cloud.sql"
import_sql "script/sql/risk-module.sql" "semi_cloud"
import_sql "script/sql/semi-config.sql"
docker compose -f "${COMPOSE_FILE}" up -d nacos risk-ai
wait_http "${NACOS_URL}/nacos" 120
wait_http "http://127.0.0.1:18088/health" 60

step "Publish Nacos configs"
TOKEN="$(nacos_token)"
publish_nacos_config "application-common.yml" "application-common.yml" "yaml" "${TOKEN}"
publish_nacos_config "datasource.yml" "datasource.yml" "yaml" "${TOKEN}"
publish_nacos_config "ruoyi-gateway.yml" "ruoyi-gateway.yml" "yaml" "${TOKEN}"
publish_nacos_config "ruoyi-auth.yml" "ruoyi-auth.yml" "yaml" "${TOKEN}"
publish_nacos_config "ruoyi-system.yml" "ruoyi-system.yml" "yaml" "${TOKEN}"
publish_nacos_config "ruoyi-resource.yml" "ruoyi-resource.yml" "yaml" "${TOKEN}"

step "Start application"
docker compose -f "${COMPOSE_FILE}" up -d ruoyi-system ruoyi-resource ruoyi-auth ruoyi-gateway nginx

step "Health summary"
docker compose -f "${COMPOSE_FILE}" ps
printf '\nFrontend: http://127.0.0.1\n'
printf 'Gateway:  http://127.0.0.1:8080\n'
printf 'Nacos:    %s/nacos\n' "${NACOS_URL}"
printf 'AI:       http://127.0.0.1:18088/health\n'
