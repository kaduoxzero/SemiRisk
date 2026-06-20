#!/usr/bin/env bash
set -euo pipefail

# SemiRisk Single-Image Docker Deployment Script
# Builds a single image containing all 6 backend services + Nginx frontend,
# then deploys via docker-compose with 7 middleware containers.
#
# Prerequisites on the target machine:
#   - Docker 24+ installed and running
#   - Java 21 JDK + Maven (for backend build)
#   - Node.js 22 LTS (for frontend build)
#   - At least 16 GB RAM recommended
#
# Usage:
#   ./script/deploy-docker.sh                    # local build + deploy
#   ./script/deploy-docker.sh --remote           # deploy to remote VM via SSH
#
# Environment overrides (set before running):
#   SEMIRISK_AI_API_KEY       - DeepSeek API key (required for AI features)
#   SEMIRISK_VM_HOST          - Remote VM IP (default: 192.168.101.130)
#   SEMIRISK_VM_USER          - Remote SSH user (default: kaduox)
#   SEMIRISK_VM_DIR           - Remote deploy directory (default: /opt/semirisk)
#   MYSQL_ROOT_PASSWORD       - MySQL root password (default: root)
#   MYSQL_PASSWORD            - MySQL app user password (default: semirisk)

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# ---------- Color helpers ----------
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

info()  { echo -e "${GREEN}[INFO]${NC}  $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
error() { echo -e "${RED}[ERROR]${NC} $*"; }
step()  { echo -e "${BLUE}[STEP]${NC}  $*"; }

# ---------- Check prerequisites ----------
check_prereqs() {
  local missing=0
  for cmd in docker java mvn; do
    if ! command -v "$cmd" &>/dev/null; then
      warn "Command not found: $cmd"
      missing=1
    fi
  done
  if [ "$missing" -eq 1 ]; then
    error "Some prerequisites are missing. Install them and retry."
    exit 1
  fi
  info "Prerequisites OK"
}

# ---------- Build backend JARs ----------
build_backend() {
  step "Building backend services with Maven..."
  cd "${ROOT_DIR}"
  if [ -f "./mvnw" ]; then
    chmod +x ./mvnw
    ./mvnw -q -DskipTests clean install
  else
    mvn -q -DskipTests clean install
  fi
  info "Backend build complete"
}

# ---------- Build frontend ----------
build_frontend() {
  step "Building frontend (semirisk-ui)..."
  cd "${ROOT_DIR}/semirisk-ui"
  if [ ! -d "node_modules" ]; then
    info "Installing npm dependencies..."
    npm ci
  fi
  npm run build
  info "Frontend build complete"
}

# ---------- Build single Docker image ----------
build_image() {
  step "Building single Docker image (semirisk/app:latest)..."
  cd "${ROOT_DIR}"
  docker build -t semirisk/app:latest -f deploy/Dockerfile.single .
  info "Docker image built successfully"
}

# ---------- Local build + deploy ----------
deploy_local() {
  cd "${ROOT_DIR}"

  # Build backend
  build_backend

  # Build frontend
  build_frontend

  # Build Docker image
  build_image

  # Start all services via compose
  step "Starting all services via docker compose..."
  docker compose up -d

  # Wait for middleware + app to become healthy
  info "Waiting for services to become healthy (this may take 2 minutes)..."
  sleep 90

  # Verify
  step "Verifying deployment..."
  if docker compose ps | grep -q "Unhealthy"; then
    warn "Some services may still be starting. Check with: docker compose ps"
  fi

  docker compose ps

  info "============================================"
  info "  SemiRisk deployed successfully!"
  info "============================================"
  info ""
  info "  Frontend:    http://localhost:80"
  info "  Gateway:     http://localhost:8080"
  info "  Swagger:     http://localhost:8080/swagger-ui.html"
  info "  Nacos:       http://localhost:8848/nacos"
  info "  Zipkin:      http://localhost:9411"
  info "  MinIO:       http://localhost:9001"
  info "  RabbitMQ:    http://localhost:15672"
  info "  MySQL:       localhost:3306"
  info "  Redis Cluster:  127.0.0.1:6379(master), 127.0.0.1:6380-6382(replicas)"
  info "  Elasticsearch: localhost:9200"
  info ""
  info "  Nacos login: nacos / nacos"
  info "============================================"
}

# ---------- Deploy to remote VM ----------
deploy_remote() {
  local vm_host="${SEMIRISK_VM_HOST:-192.168.101.130}"
  local vm_user="${SEMIRISK_VM_USER:-kaduox}"
  local vm_dir="${SEMIRISK_VM_DIR:-/opt/semirisk}"

  step "Deploying to ${vm_user}@${vm_host}:${vm_dir}"

  # Step 1: Sync project to VM
  info "Syncing project to remote VM..."
  rsync -avz --delete \
    --exclude='.git' \
    --exclude='.git/' \
    --exclude='target/' \
    --exclude='**/target/' \
    --exclude='node_modules/' \
    --exclude='dist/' \
    --exclude='.idea/' \
    --exclude='logs/' \
    --exclude='*.log' \
    --exclude='.env.local' \
    --exclude='.env.*.local' \
    --exclude='test-results/' \
    --exclude='semirisk-ui/test-results/' \
    "${ROOT_DIR}/" "${vm_user}@${vm_host}:${vm_dir}/"

  info "Project synced. Building and deploying on remote VM..."

  # Step 2: SSH into VM and deploy
  ssh -t "${vm_user}@${vm_host}" <<'REMOTE_SCRIPT'
set -euo pipefail

VM_DIR="/opt/semirisk"
cd "${VM_DIR}"

# Load env if present
if [ -f .env.local ]; then
  set -a
  source .env.local
  set +a
fi

echo ""
echo "=== Building backend ==="
if [ -f "./mvnw" ]; then
  chmod +x ./mvnw
  ./mvnw -q -DskipTests clean install
else
  mvn -q -DskipTests clean install
fi

echo ""
echo "=== Building frontend ==="
cd semirisk-ui
if [ ! -d node_modules ]; then
  echo "Installing npm dependencies..."
  npm ci
fi
npm run build
cd ..

echo ""
echo "=== Stopping existing stack ==="
docker compose down --remove-orphans 2>/dev/null || true

echo ""
echo "=== Building single Docker image ==="
docker build -t semirisk/app:latest -f deploy/Dockerfile.single .

echo ""
echo "=== Starting all services ==="
docker compose up -d

echo ""
echo "=== Waiting for services to become healthy ==="
sleep 90

echo ""
echo "=== Stack status ==="
docker compose ps

echo ""
echo "============================================"
echo "  SemiRisk deployed on this VM!"
echo "============================================"
echo ""
echo "  Frontend:    http://localhost:80"
echo "  Gateway:     http://localhost:8080"
echo "  Swagger:     http://localhost:8080/swagger-ui.html"
echo "  Nacos:       http://localhost:8848/nacos"
echo "  Zipkin:      http://localhost:9411"
echo "  MinIO:       http://localhost:9001"
echo "  RabbitMQ:    http://localhost:15672"
echo ""
echo "  Nacos login: nacos / nacos"
echo "============================================"
REMOTE_SCRIPT

  info "Remote deployment complete!"
}

# ---------- Main ----------
main() {
  check_prereqs

  local mode="${1:-local}"

  case "${mode}" in
    --remote|-r)
      deploy_remote
      ;;
    --local|-l|"")
      deploy_local
      ;;
    *)
      error "Unknown mode: $mode"
      echo "Usage: $0 [--local|--remote]"
      exit 1
      ;;
  esac
}

main "$@"
