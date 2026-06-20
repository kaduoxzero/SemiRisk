#!/usr/bin/env bash
set -euo pipefail

# Run this script on the SemiRisk middleware VM.
# It installs Docker if missing and starts the middleware required by SemiRisk.

PROJECT_DIR="${PROJECT_DIR:-/opt/semirisk-middleware}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
MYSQL_ROOT_PASSWORD="${MYSQL_ROOT_PASSWORD:-root}"
MYSQL_DATABASE="${MYSQL_DATABASE:-semirisk}"
MYSQL_USER="${MYSQL_USER:-semirisk}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-semirisk}"
MINIO_ROOT_USER="${MINIO_ROOT_USER:-semirisk}"
MINIO_ROOT_PASSWORD="${MINIO_ROOT_PASSWORD:-semirisk123}"
RABBITMQ_MODE="${RABBITMQ_MODE:-container}"
RABBITMQ_USER="${RABBITMQ_USER:-semirisk}"
RABBITMQ_PASSWORD="${RABBITMQ_PASSWORD:-semirisk}"
DOCKER_REGISTRY_MIRRORS="${DOCKER_REGISTRY_MIRRORS:-https://docker.m.daocloud.io,https://docker.1ms.run}"

configure_docker_mirrors() {
  if [[ -z "${DOCKER_REGISTRY_MIRRORS}" ]]; then
    return
  fi

  mkdir -p /etc/docker
  local mirror_json=""
  IFS=',' read -ra mirrors <<< "${DOCKER_REGISTRY_MIRRORS}"
  for mirror in "${mirrors[@]}"; do
    mirror="$(echo "${mirror}" | xargs)"
    if [[ -z "${mirror}" ]]; then
      continue
    fi
    if [[ -n "${mirror_json}" ]]; then
      mirror_json+=", "
    fi
    mirror_json+="\"${mirror}\""
  done

  if [[ -n "${mirror_json}" ]]; then
    cat > /etc/docker/daemon.json <<EOF
{
  "registry-mirrors": [${mirror_json}]
}
EOF
    systemctl daemon-reload || true
    systemctl restart docker
  fi
}

install_host_rabbitmq() {
  if ! command -v rabbitmqctl >/dev/null 2>&1; then
    apt-get update
    DEBIAN_FRONTEND=noninteractive apt-get install -y rabbitmq-server
  fi

  systemctl enable --now rabbitmq-server
  rabbitmq-plugins enable rabbitmq_management

  if rabbitmqctl list_users | awk '{print $1}' | grep -qx "${RABBITMQ_USER}"; then
    rabbitmqctl change_password "${RABBITMQ_USER}" "${RABBITMQ_PASSWORD}"
  else
    rabbitmqctl add_user "${RABBITMQ_USER}" "${RABBITMQ_PASSWORD}"
  fi
  rabbitmqctl set_user_tags "${RABBITMQ_USER}" administrator
  rabbitmqctl set_permissions -p / "${RABBITMQ_USER}" ".*" ".*" ".*"
  systemctl restart rabbitmq-server
}

if ! command -v docker >/dev/null 2>&1; then
  curl -fsSL https://get.docker.com | sh
  systemctl enable docker
  systemctl start docker
fi

configure_docker_mirrors

if [[ "${RABBITMQ_MODE}" == "host" ]]; then
  install_host_rabbitmq
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

  redis-master:
    image: redis:7.4
    container_name: semirisk-redis-master
    restart: unless-stopped
    command: redis-server --port 6379 --cluster-enabled yes --cluster-config-file nodes.conf --cluster-node-timeout 5000 --appendonly yes
    ports:
      - "6379:6379"
    volumes:
      - redis-master-data:/data

  redis-replica-1:
    image: redis:7.4
    container_name: semirisk-redis-replica-1
    restart: unless-stopped
    command: redis-server --port 6380 --cluster-enabled yes --cluster-config-file nodes.conf --cluster-node-timeout 5000 --appendonly yes --slaveof semirisk-redis-master 6379
    ports:
      - "6380:6380"
    volumes:
      - redis-replica-1-data:/data

  redis-replica-2:
    image: redis:7.4
    container_name: semirisk-redis-replica-2
    restart: unless-stopped
    command: redis-server --port 6381 --cluster-enabled yes --cluster-config-file nodes.conf --cluster-node-timeout 5000 --appendonly yes --slaveof semirisk-redis-master 6379
    ports:
      - "6381:6381"
    volumes:
      - redis-replica-2-data:/data

  redis-replica-3:
    image: redis:7.4
    container_name: semirisk-redis-replica-3
    restart: unless-stopped
    command: redis-server --port 6382 --cluster-enabled yes --cluster-config-file nodes.conf --cluster-node-timeout 5000 --appendonly yes --slaveof semirisk-redis-master 6379
    ports:
      - "6382:6382"
    volumes:
      - redis-replica-3-data:/data

  redis-cluster-init:
    image: redis:7.4
    container_name: semirisk-redis-cluster-init
    restart: "no"
    command: >
      bash -c "
        sleep 10 &&
        redis-cli -h semirisk-redis-master -p 6379 --cluster create \
          semirisk-redis-master:6379 \
          semirisk-redis-replica-1:6380 \
          semirisk-redis-replica-2:6381 \
          semirisk-redis-replica-3:6382 \
          --cluster-replicas 1 --cluster-yes
      "
    depends_on:
      - redis-master
      - redis-replica-1
      - redis-replica-2
      - redis-replica-3

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

EOF

if [[ "${RABBITMQ_MODE}" == "container" ]]; then
  cat >> docker-compose.yml <<EOF
  rabbitmq:
    image: rabbitmq:3.13-management
    container_name: semirisk-rabbitmq
    restart: unless-stopped
    environment:
      RABBITMQ_DEFAULT_USER: ${RABBITMQ_USER}
      RABBITMQ_DEFAULT_PASS: ${RABBITMQ_PASSWORD}
    ports:
      - "5672:5672"
      - "15672:15672"

EOF
fi

cat >> docker-compose.yml <<EOF
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
  redis-master-data:
  redis-replica-1-data:
  redis-replica-2-data:
  redis-replica-3-data:
EOF

docker compose up -d
docker compose ps

echo "Waiting for MySQL to accept connections..."
for i in {1..30}; do
  if docker exec semirisk-mysql mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" -e "SELECT 1" >/dev/null 2>&1; then
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
