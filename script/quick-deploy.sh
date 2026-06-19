#!/usr/bin/env bash
set -euo pipefail

# ==============================================================================
# SemiRisk -- Quick Deploy on Linux VM
# ==============================================================================
# Run this on the target VM to build the Docker image and deploy everything.
#
# Prerequisites:
#   - Docker 24+, Java 21 JDK, Maven 3.9+, Node.js 22
#   - At least 16 GB RAM
#   - Project code at /opt/semirisk
#
# Usage:
#   chmod +x /opt/semirisk/script/quick-deploy.sh
#   /opt/semirisk/script/quick-deploy.sh
# ==============================================================================

ROOT_DIR="/opt/semirisk"

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
for cmd in docker java mvn node npm; do
  if ! command -v "$cmd" &>/dev/null; then
    error "Required command not found: $cmd"
    exit 1
  fi
done
info "All prerequisites found"

# ---------- Step 1: Build backend ----------
step "1/5 Building backend services (Maven)..."
cd "${ROOT_DIR}"
if [ -f "./mvnw" ]; then
  chmod +x ./mvnw
  ./mvnw -q -DskipTests clean install
else
  mvn -q -DskipTests clean install
fi
info "Backend JARs built"

# ---------- Step 2: Build frontend ----------
step "2/5 Building frontend (npm)..."
cd "${ROOT_DIR}/semirisk-ui"
if [ ! -d "node_modules" ]; then
  npm ci
fi
npm run build
cd "${ROOT_DIR}"
info "Frontend built"

# ---------- Step 3: Build Docker image ----------
step "3/5 Building Docker image (semirisk/app:latest)..."
docker build -t semirisk/app:latest -f deploy/Dockerfile.single .
info "Docker image built ($(docker images --format '{{.Size}}' semirisk/app | head -1))"

# ---------- Step 4: Stop old stack ----------
step "4/5 Stopping existing services..."
docker compose down --remove-orphans 2>/dev/null || true

# ---------- Step 5: Start everything ----------
step "5/5 Starting all services..."
docker compose up -d

# ---------- Wait for health ----------
info "Waiting for services to become healthy (this takes ~90 seconds)..."
sleep 90

# ---------- Verify ----------
step "Verification"
echo ""
docker compose ps
echo ""

UNHEALTHY=$(docker compose ps | grep -c "Unhealthy" || true)
if [ "$UNHEALTHY" -gt 0 ]; then
  warn "${UNHEALTHY} container(s) still unhealthy. Check logs with: docker compose logs -f"
else
  info "All containers healthy!"
fi

echo ""
echo "============================================================"
echo "  SemiRisk is running!"
echo "============================================================"
echo ""
echo "  Frontend:    http://$(hostname -I | awk '{print $1}'):80"
echo "  Gateway:     http://$(hostname -I | awk '{print $1}'):8080"
echo "  Swagger:     http://$(hostname -I | awk '{print $1}'):8080/swagger-ui.html"
echo "  Nacos:       http://$(hostname -I | awk '{print $1}'):8848/nacos"
echo "  Zipkin:      http://$(hostname -I | awk '{print $1}'):9411"
echo "  MinIO:       http://$(hostname -I | awk '{print $1}'):9001"
echo "  RabbitMQ:    http://$(hostname -I | awk '{print $1}'):15672"
echo ""
echo "  Nacos login: nacos / nacos"
echo "============================================================"
echo ""
echo "  Useful commands:"
echo "    docker compose ps          # 查看状态"
echo "    docker compose logs -f     # 查看日志"
echo "    docker compose down        # 停止全部"
echo "    docker exec -it semirisk-app supervisorctl status  # 查看进程"
echo "============================================================"
