#!/usr/bin/env bash
set -euo pipefail

# ==============================================================================
# frp 服务端部署脚本
# 在云服务器 123.57.239.56 上执行
# ==============================================================================
# 用途：安装 frps（frp server），开放 7000 端口，配置 systemd 守护
#
# 用法：
#   sudo bash -c 'curl -sSL https://... | bash'
#   或直接下载后执行：chmod +x install-frps.sh && ./install-frps.sh
# ==============================================================================

FRP_VERSION="0.62.1"
FRP_DIR="/usr/local/frp"
FRPS_CONF="${FRP_DIR}/frps.toml"
FRPS_BIN="${FRP_DIR}/frps"
SYSTEMD_UNIT="/etc/systemd/system/frps.service"

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

# ---------- 安装依赖 ----------
step "安装依赖..."
case "$OS" in
  Ubuntu|Debian)
    apt-get update -qq
    apt-get install -y -qq curl wget ca-certificates iptables
    ;;
  CentOS|Red\ Hat|AlmaLinux)
    yum install -y curl wget ca-certificates iptables
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
mv "/tmp/frp_${FRP_VERSION}_linux_${FRP_ARCH}/frps" "${FRPS_BIN}"
chmod +x "${FRPS_BIN}"
rm -rf "/tmp/frp_${FRP_VERSION}_linux_${FRP_ARCH}" "/tmp/${FRP_FILENAME}"
info "frp 下载解压完成"

# ---------- 写入配置 ----------
step "写入 frps 配置..."
cat > "${FRPS_CONF}" << 'EOF'
# frp 服务端配置
bind_addr = 0.0.0.0
bind_port = 7000
bind_port_dashboard = 7500
dashboard_user = admin
dashboard_pwd = admin
authentication_method = token
token = semirisk-frp-token-2025
log_file = /var/log/frps.log
log_level = info
log_max_days = 7
max_pool_count = 50
EOF

info "frps 配置已写入: ${FRPS_CONF}"

# ---------- 创建 systemd 服务 ----------
step "创建 systemd 服务..."
cat > "${SYSTEMD_UNIT}" << EOF
[Unit]
Description=Frp Server (frps)
After=network.target

[Service]
Type=simple
ExecStart=${FRPS_BIN} -c ${FRPS_CONF}
Restart=on-failure
RestartSec=5
LimitNOFILE=1048576

[Install]
WantedBy=multi-user.target
EOF

# ---------- 开放防火墙端口 ----------
step "配置防火墙..."

# 尝试使用 firewall-cmd (CentOS/RHEL)
if command -v firewall-cmd &>/dev/null; then
  firewall-cmd --permanent --add-port=7000/tcp 2>/dev/null || true
  firewall-cmd --permanent --add-port=7500/tcp 2>/dev/null || true
  firewall-cmd --reload 2>/dev/null || true
  info "firewalld: 已开放 7000, 7500 端口"

# 尝试使用 ufw (Ubuntu/Debian)
elif command -v ufw &>/dev/null; then
  ufw allow 7000/tcp 2>/dev/null || true
  ufw allow 7500/tcp 2>/dev/null || true
  info "ufw: 已开放 7000, 7500 端口"

# 尝试使用 iptables
elif command -v iptables &>/dev/null; then
  iptables -A INPUT -p tcp --dport 7000 -j ACCEPT 2>/dev/null || true
  iptables -A INPUT -p tcp --dport 7500 -j ACCEPT 2>/dev/null || true
  info "iptables: 已开放 7000, 7500 端口"
else
  warn "未检测到防火墙管理工具，请手动开放端口 7000 和 7500"
fi

# ---------- 启动服务 ----------
step "启动 frps 服务..."
systemctl daemon-reload
systemctl enable frps
systemctl start frps

sleep 2

# ---------- 验证 ----------
if systemctl is-active --quiet frps; then
  info "frps 启动成功！"
else
  error "frps 启动失败，请检查日志: journalctl -u frps -n 20"
  exit 1
fi

# ---------- 输出总结 ----------
echo ""
echo "============================================================"
echo "  frp 服务端安装完成！"
echo "============================================================"
echo ""
echo "  frps 地址:   123.57.239.56:7000"
echo "  管理面板:    http://123.57.239.56:7500"
echo "  用户名:      admin"
echo "  密码:      admin"
echo "  配置文件:    ${FRPS_CONF}"
echo "  日志文件:    /var/log/frps.log"
echo ""
echo "  下一步：在 VM 192.168.101.130 上安装 frp 客户端"
echo "  运行: ./script/install-frpc-on-vm.sh"
echo ""
echo "  常用命令："
echo "    systemctl status frps    # 查看状态"
echo "    journalctl -u frps -f    # 查看日志"
echo "    systemctl restart frps   # 重启"
echo "============================================================"
echo ""
