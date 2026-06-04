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

cd "${ROOT_DIR}"

./mvnw -q -DskipTests install

start_service() {
  local module="$1"
  local name="$2"
  local log="${LOG_DIR}/${name}.log"
  nohup ./mvnw -pl "${module}" spring-boot:run > "${log}" 2>&1 &
  echo "${name} started pid=$! log=${log}"
}

start_service "semirisk-services/semirisk-data-service" "semirisk-data-service"
start_service "semirisk-services/semirisk-risk-service" "semirisk-risk-service"
start_service "semirisk-services/semirisk-ai-service" "semirisk-ai-service"
start_service "semirisk-services/semirisk-alert-service" "semirisk-alert-service"
start_service "semirisk-services/semirisk-report-service" "semirisk-report-service"
start_service "semirisk-services/semirisk-gateway" "semirisk-gateway"

echo "Backend services:"
echo "  gateway:      http://localhost:8080"
echo "  data-service: http://localhost:8081"
echo "  risk-service: http://localhost:8082"
echo "  ai-service:   http://localhost:8083"
echo "  alert-service:http://localhost:8084"
echo "  report-service:http://localhost:8085"
echo "  ai-model:     ${SEMIRISK_AI_MODEL:-deepseekv4-pro}"
