#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
LOG_DIR="${ROOT_DIR}/logs"
mkdir -p "${LOG_DIR}"

if [[ -f "${ROOT_DIR}/.env.local" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "${ROOT_DIR}/.env.local"
  set +a
fi

: "${SEMIRISK_MIDDLEWARE_HOST:=127.0.0.1}"
export SEMIRISK_MIDDLEWARE_HOST

if [[ -z "${SEMIRISK_ES_URL:-}" ]]; then
  export SEMIRISK_ES_URL="http://${SEMIRISK_MIDDLEWARE_HOST}:9200"
fi

cd "${ROOT_DIR}"

./mvnw -q -DskipTests install

if ! command -v screen >/dev/null 2>&1; then
  echo "screen is required to keep backend services running in the background."
  exit 1
fi

start_service() {
  local module="$1"
  local name="$2"
  local port="$3"
  local log="${LOG_DIR}/${name}.log"
  local jar="${ROOT_DIR}/${module}/target/${name}-0.0.1-SNAPSHOT.jar"
  local session="semirisk-${name}"
  if lsof -nP -iTCP:"${port}" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "${name} already running port=${port}"
    return
  fi
  if [[ ! -f "${jar}" ]]; then
    echo "${name} jar not found: ${jar}"
    exit 1
  fi
  screen -S "${session}" -X quit >/dev/null 2>&1 || true
  : > "${log}"
  screen -dmS "${session}" bash -lc "cd '${ROOT_DIR}/${module}' && exec java -jar '${jar}' > '${log}' 2>&1"
  for _ in {1..45}; do
    if lsof -nP -iTCP:"${port}" -sTCP:LISTEN >/dev/null 2>&1; then
      echo "${name} started session=${session} port=${port} log=${log}"
      return
    fi
    sleep 1
  done
  echo "${name} did not listen on port=${port} in time log=${log}"
  tail -n 80 "${log}" 2>/dev/null || true
  exit 1
}

start_service "semirisk-services/semirisk-data-service" "semirisk-data-service" "8081"
start_service "semirisk-services/semirisk-risk-service" "semirisk-risk-service" "8082"
start_service "semirisk-services/semirisk-ai-service" "semirisk-ai-service" "8083"
start_service "semirisk-services/semirisk-alert-service" "semirisk-alert-service" "8084"
start_service "semirisk-services/semirisk-report-service" "semirisk-report-service" "8085"
start_service "semirisk-services/semirisk-gateway" "semirisk-gateway" "8080"

echo "Backend services:"
echo "  gateway:      http://localhost:8080"
echo "  data-service: http://localhost:8081"
echo "  risk-service: http://localhost:8082"
echo "  ai-service:   http://localhost:8083"
echo "  alert-service:http://localhost:8084"
echo "  report-service:http://localhost:8085"
echo "  ai-model:     ${SEMIRISK_AI_MODEL:-deepseekv4-pro}"
