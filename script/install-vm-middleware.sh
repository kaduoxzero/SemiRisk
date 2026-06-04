#!/usr/bin/env bash
set -euo pipefail

# Run this script on VM 192.168.101.128.
# It installs Docker if missing and starts the middleware required by SemiRisk.

PROJECT_DIR="${PROJECT_DIR:-/opt/semirisk-middleware}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
MYSQL_ROOT_PASSWORD="${MYSQL_ROOT_PASSWORD:-root}"
MYSQL_DATABASE="${MYSQL_DATABASE:-semirisk}"
MYSQL_USER="${MYSQL_USER:-semirisk}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-semirisk}"
MINIO_ROOT_USER="${MINIO_ROOT_USER:-semirisk}"
MINIO_ROOT_PASSWORD="${MINIO_ROOT_PASSWORD:-semirisk123}"

if ! command -v docker >/dev/null 2>&1; then
  curl -fsSL https://get.docker.com | sh
  systemctl enable docker
  systemctl start docker
fi

mkdir -p "${PROJECT_DIR}"
cd "${PROJECT_DIR}"

cat > docker-compose.yml <<EOF
services:
  mysql:
    image: mysql:8.4
    container_name: semirisk-mysql
    restart: unless-stopped
    environment:
      MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD}
      MYSQL_DATABASE: ${MYSQL_DATABASE}
      MYSQL_USER: ${MYSQL_USER}
      MYSQL_PASSWORD: ${MYSQL_PASSWORD}
    ports:
      - "3306:3306"
    volumes:
      - mysql-data:/var/lib/mysql

  redis:
    image: redis:7.4
    container_name: semirisk-redis
    restart: unless-stopped
    ports:
      - "6379:6379"

  elasticsearch:
    image: docker.elastic.co/elasticsearch/elasticsearch:8.15.3
    container_name: semirisk-es
    restart: unless-stopped
    environment:
      discovery.type: single-node
      xpack.security.enabled: "false"
      ES_JAVA_OPTS: -Xms512m -Xmx512m
    ports:
      - "9200:9200"
    volumes:
      - es-data:/usr/share/elasticsearch/data

  minio:
    image: minio/minio:RELEASE.2025-01-20T14-49-07Z
    container_name: semirisk-minio
    restart: unless-stopped
    command: server /data --console-address ":9001"
    environment:
      MINIO_ROOT_USER: ${MINIO_ROOT_USER}
      MINIO_ROOT_PASSWORD: ${MINIO_ROOT_PASSWORD}
    ports:
      - "9000:9000"
      - "9001:9001"
    volumes:
      - minio-data:/data

  rabbitmq:
    image: rabbitmq:3.13-management
    container_name: semirisk-rabbitmq
    restart: unless-stopped
    ports:
      - "5672:5672"
      - "15672:15672"

  nacos:
    image: nacos/nacos-server:v2.4.3
    container_name: semirisk-nacos
    restart: unless-stopped
    environment:
      MODE: standalone
    ports:
      - "8848:8848"
      - "9848:9848"

volumes:
  mysql-data:
  es-data:
  minio-data:
EOF

docker compose up -d
docker compose ps

echo "Waiting for MySQL to accept connections..."
for i in {1..30}; do
  if docker exec semirisk-mysql mysqladmin ping -uroot -p"${MYSQL_ROOT_PASSWORD}" --silent >/dev/null 2>&1; then
    break
  fi
  sleep 2
  if [[ "$i" == "30" ]]; then
    echo "MySQL is not ready after 60 seconds; skip schema initialization."
    exit 0
  fi
done

if [[ -f "${SCRIPT_DIR}/semirisk-schema.sql" ]]; then
  docker exec -i semirisk-mysql mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" < "${SCRIPT_DIR}/semirisk-schema.sql"
  echo "SemiRisk schema initialized."
else
  echo "Schema file not found: ${SCRIPT_DIR}/semirisk-schema.sql"
fi
