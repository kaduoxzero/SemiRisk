#!/usr/bin/env bash
set -euo pipefail

# ==============================================================================
# frp 客户端部署脚本
# 在 VM 192.168.101.130 上执行
# ==============================================================================
# 用途：安装 frpc（frp client），连接到云服务器 123.57.239.56
#
# 用法：
#   1. 将本项目同步到 VM：
#      rsync -avz --exclude='.git' --exclude='target/' \
#        L:\ProjectSource\Java\SemiRisk/ kaduox@192.168.101.130:/opt/semirisk/
#
#   2. 登录 VM 并执行：
#      ssh kaduox@192.168.101.130
#      cd /opt/semirisk
#      sudo bash script/install-frpc-on-vm.sh
# ==============================================================================

FRP_VERSION="0.62.1"
FRP_DIR="/usr/local/frp"
FRPC_CONF="${FRP_DIR}/frpc.toml"
FRPC_BIN="${FRP_DIR}/frpc"
SYSTEMD_UNIT="/etc/systemd/system/frpc.service"

# 云服务器地址（可从环境变量覆盖）
SERVER_ADDR="${FRP_SERVER_ADDR:-123.57.239.56}"
SERVER_PORT="${FRP_SERVER_PORT:-7000}"
TOKEN="${FRP_TOKEN:-semirisk-frp-token-2025}"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

info()  { echo -e "${GREEN}[INFO]${NC}  $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
error() { echo -e "${RED}[ERROR]${NC} $*"; }
step()  { echo -e "${BLUE}[STEP]${NC}  $*"; }

# ---------- 检查是否 root ----------
if [ "$(id -u)" -ne 0 ]; then
  error "请使用 root 权限运行此脚本"
  exit 1
fi

# ---------- 检查操作系统 ----------
if [ -f /etc/os-release ]; then
  . /etc/os-release
  OS="$NAME"
else
  error "无法检测操作系统类型"
  exit 1
fi

info "检测到操作系统: ${OS}"
info "frp 服务端地址: ${SERVER_ADDR}:${SERVER_PORT}"

# ---------- 安装依赖 ----------
step "安装依赖..."
case "$OS" in
  Ubuntu|Debian)
    apt-get update -qq
    apt-get install -y -qq curl wget ca-certificates
    ;;
  CentOS|Red\ Hat|AlmaLinux)
    yum install -y curl wget ca-certificates
    ;;
  *)
    warn "未知操作系统，跳过依赖安装"
    ;;
esac
info "依赖安装完成"

# ---------- 创建目录 ----------
step "创建安装目录..."
mkdir -p "${FRP_DIR}" /var/log
info "目录创建完成"

# ---------- 下载 frp ----------
step "下载 frp ${FRP_VERSION}..."
ARCH=$(uname -m)
case "$ARCH" in
  x86_64)   FRP_ARCH="amd64" ;;
  aarch64)  FRP_ARCH="arm64" ;;
  armv7l)   FRP_ARCH="arm" ;;
  *)        FRP_ARCH="amd64" ;;
esac

FRP_FILENAME="frp_${FRP_VERSION}_linux_${FRP_ARCH}.tar.gz"
FRP_URL="https://github.com/fatedier/frp/releases/download/v${FRP_VERSION}/${FRP_FILENAME}"

info "下载: ${FRP_URL}"
curl -sSL -o "/tmp/${FRP_FILENAME}" "${FRP_URL}"

if [ ! -f "/tmp/${FRP_FILENAME}" ]; then
  error "下载失败，请检查网络连接或 frp 版本号"
  exit 1
fi

# ---------- 解压 ----------
info "解压..."
tar -xzf "/tmp/${FRP_FILENAME}" -C /tmp/
mv "/tmp/frp_${FRP_VERSION}_linux_${FRP_ARCH}/frpc" "${FRPC_BIN}"
chmod +x "${FRPC_BIN}"
rm -rf "/tmp/frp_${FRP_VERSION}_linux_${FRP_ARCH}" "/tmp/${FRP_FILENAME}"
info "frp 下载解压完成"

# ---------- 写入配置 ----------
step "写入 frpc 配置..."
cat > "${FRPC_CONF}" << EOF
# frp 客户端配置
server_addr = ${SERVER_ADDR}
server_port = ${SERVER_PORT}
authentication_method = token
token = ${TOKEN}

log_file = /var/log/frpc.log
log_level = info
log_max_days = 7

# 前端 HTTP（VM 80 → 云服务器 8081）
[semirisk-http]
type = tcp
local_ip = 127.0.0.1
local_port = 80
remote_port = 8081

# API Gateway（VM 8080 → 云服务器 8080）
[semirisk-api]
type = tcp
local_ip = 127.0.0.1
local_port = 8080
remote_port = 8080

# Nacos 控制台（VM 8848 → 云服务器 8848）
[semirisk-nacos]
type = tcp
local_ip = 127.0.0.1
local_port = 8848
remote_port = 8848

# MinIO 控制台（VM 9001 → 云服务器 9001）
[semirisk-minio]
type = tcp
local_ip = 127.0.0.1
local_port = 9001
remote_port = 9001

# Zipkin（VM 9411 → 云服务器 9411）
[semirisk-zipkin]
type = tcp
local_ip = 127.0.0.1
local_port = 9411
remote_port = 9411

# RabbitMQ 管理（VM 15672 → 云服务器 15672）
[semirisk-rabbitmq]
type = tcp
local_ip = 127.0.0.1
local_port = 15672
remote_port = 15672
EOF

info "frpc 配置已写入: ${FRPC_CONF}"

# ---------- 创建 systemd 服务 ----------
step "创建 systemd 服务..."
cat > "${SYSTEMD_UNIT}" << EOF
[Unit]
Description=Frp Client (frpc)
After=network.target

[Service]
Type=simple
ExecStart=${FRPC_BIN} -c ${FRPC_CONF}
Restart=on-failure
RestartSec=5
LimitNOFILE=1048576

[Install]
WantedBy=multi-user.target
EOF

# ---------- 启动服务 ----------
step "启动 frpc 服务..."
systemctl daemon-reload
systemctl enable frpc
systemctl start frpc

sleep 3

# ---------- 验证 ----------
if systemctl is-active --quiet frpc; then
  info "frpc 启动成功！"
else
  error "frpc 启动失败，请检查日志: journalctl -u frpc -n 20"
  error "请确认云服务器 123.57.239.56 的 frps 正在运行"
  error "请确认云服务器 123.57.239.56 的防火墙已开放 7000 端口"
  exit 1
fi

# ---------- 输出总结 ----------
echo ""
echo "============================================================"
echo "  frp 客户端安装完成！"
echo "============================================================"
echo ""
echo "  frps 服务端:  ${SERVER_ADDR}:${SERVER_PORT}"
echo "  客户端状态:   $(systemctl is-active frpc)"
echo "  配置文件:     ${FRPC_CONF}"
echo "  日志文件:     /var/log/frpc.log"
echo ""
echo "  端口映射:"
echo "    前端 HTTP:    http://${SERVER_ADDR}:8081"
echo "    API Gateway:  http://${SERVER_ADDR}:8080"
echo "    Nacos:        http://${SERVER_ADDR}:8848/nacos"
echo "    MinIO:        http://${SERVER_ADDR}:9001"
echo "    Zipkin:       http://${SERVER_ADDR}:9411"
echo "    RabbitMQ:     http://${SERVER_ADDR}:15672"
echo ""
echo "  常用命令："
echo "    systemctl status frpc    # 查看状态"
echo "    journalctl -u frpc -f    # 查看日志"
echo "    systemctl restart frpc   # 重启"
echo "    systemctl stop frpc      # 停止"
echo "============================================================"
echo ""
