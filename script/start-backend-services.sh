#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
LOG_DIR="${ROOT_DIR}/logs"
mkdir -p "${LOG_DIR}"

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
start_service "semirisk-services/semirisk-gateway" "semirisk-gateway"

echo "Backend services:"
echo "  gateway:      http://localhost:8080"
echo "  data-service: http://localhost:8081"
echo "  risk-service: http://localhost:8082"
echo "  ai-service:   http://localhost:8083"
