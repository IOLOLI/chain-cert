#!/bin/bash
# 阿里云 ECS 首次初始化：装依赖、建目录、建库、生成密钥文件、装 systemd
# 用法（在 ECS 上、用具备 sudo 权限的用户执行）：
#   bash install.sh
# 幂等：重复执行只补缺的部分，不会覆盖已有的 /etc/chainsip/backend.env
set -euo pipefail

PKG_DIR="$(cd "$(dirname "$0")" && pwd)"
MYSQL_ADMIN_USER=${MYSQL_ADMIN_USER:-}
APP_ROOT=/opt/chainsip
CHAIN_ROOT=/opt/fisco
ENV_FILE=/etc/chainsip/backend.env
LOG_DIR=/var/log/chainsip

log()  { printf '\033[32m[deploy]\033[0m %s\n' "$*"; }
warn() { printf '\033[33m[warn]\033[0m %s\n' "$*"; }
die()  { printf '\033[31m[error]\033[0m %s\n' "$*"; exit 1; }

[ "$(id -u)" -eq 0 ] || die "请用 root 或 sudo 执行"
[ -n "$MYSQL_ADMIN_USER" ] || die "请设置 MYSQL_ADMIN_USER（MySQL 管理员用户名，例如你的 DBA 账号）"

# ---------------------------------------------------------------- 1. 依赖
if command -v dnf >/dev/null; then
  PM=dnf
elif command -v yum >/dev/null; then
  PM=yum
elif command -v apt-get >/dev/null; then
  PM=apt
else
  die "不认识的包管理器（既没有 dnf/yum 也没有 apt）"
fi
log "包管理器：$PM"

install_java() {
  if [ -x /usr/bin/java ] && /usr/bin/java -version 2>&1 | grep -q '"1\.8'; then
    log "已装 JDK 8：$(/usr/bin/java -version 2>&1 | head -1)"; return
  fi
  case "$PM" in
    dnf|yum) $PM install -y java-1.8.0-openjdk-headless ;;
    apt)     apt-get update -y && apt-get install -y openjdk-8-jdk-headless \
               || warn "apt 源里没有 openjdk-8，请改装 Dragonwell/Kona 8 并把 java 链到 /usr/bin/java" ;;
  esac
}

install_mysql() {
  if command -v mysqld >/dev/null || systemctl list-unit-files | grep -q '^mysqld\?\.service'; then
    log "已装 MySQL"; return
  fi
  case "$PM" in
    dnf|yum) $PM install -y mysql-server ;;
    apt)     DEBIAN_FRONTEND=noninteractive apt-get install -y mysql-server ;;
  esac
  systemctl enable --now mysqld 2>/dev/null || systemctl enable --now mysql
}

install_nginx() {
  command -v nginx >/dev/null && { log "已装 nginx"; return; }
  case "$PM" in
    dnf|yum) $PM install -y nginx ;;
    apt)     DEBIAN_FRONTEND=noninteractive apt-get install -y nginx ;;
  esac
  systemctl enable nginx
}

command -v curl >/dev/null || { case "$PM" in dnf|yum) $PM install -y curl ;; apt) apt-get install -y curl ;; esac; }
install_java
install_mysql
install_nginx

# ---------------------------------------------------------------- 2. 目录与用户
log "创建运行用户与目录"
id chainsip >/dev/null 2>&1 || useradd -r -m -d /home/chainsip -s /sbin/nologin chainsip
id fisco   >/dev/null 2>&1 || useradd -r -m -d /home/fisco   -s /sbin/nologin fisco
mkdir -p "$APP_ROOT"/{backend,frontend,deploy,docs} "$CHAIN_ROOT" "$LOG_DIR" /etc/chainsip
touch "$LOG_DIR/backend.log"
chown -R chainsip:chainsip "$APP_ROOT" "$LOG_DIR"
chown -R fisco:fisco "$CHAIN_ROOT"

# ---------------------------------------------------------------- 3. 数据库
gen() { tr -dc 'A-Za-z0-9' </dev/urandom | head -c "$1"; }

if [ -f "$ENV_FILE" ]; then
  log "$ENV_FILE 已存在，沿用其中的密码（不覆盖）"
  # shellcheck disable=SC1090
  . "$ENV_FILE"
  DB_PASS="${CHAINSIP_DB_PASSWORD:-}"
  [ -n "$DB_PASS" ] || die "$ENV_FILE 里没有 CHAINSIP_DB_PASSWORD，请手工补上"
else
  DB_PASS="$(gen 24)"
  JWT_SECRET="$(gen 48)"
  MASTER_KEY="$(gen 32)"
  log "生成 $ENV_FILE（数据库密码、JWT 密钥、机构私钥主密钥都是随机值）"
  cat > "$ENV_FILE" <<EOF
# ChainSIP 后端环境变量（systemd 通过 EnvironmentFile 读取）
# 改完执行：systemctl restart chainsip-backend
SERVER_PORT=8080
CHAINSIP_DB_HOST=127.0.0.1
CHAINSIP_DB_PORT=3306
CHAINSIP_DB_NAME=chainsip
CHAINSIP_DB_USER=chainsip
CHAINSIP_DB_PASSWORD=${DB_PASS}
CHAINSIP_JWT_SECRET=${JWT_SECRET}
CHAINSIP_CRYPTO_MASTER_KEY=${MASTER_KEY}
CHAINSIP_LOG_LEVEL=INFO
# SDK 连的节点 channel 地址（服务端自建链就是本机；用云数据库/远程链时改成那个地址）
CHAINSIP_CHAIN_ENDPOINT=127.0.0.1:20200
# 合约地址：迁移已有链时沿用；重建链时换成新部署的地址
# CHAINSIP_CORE_ADDRESS=<CORE_CONTRACT_ADDRESS>
# CHAINSIP_TRANSFER_ADDRESS=<TRANSFER_CONTRACT_ADDRESS>
EOF
  chmod 600 "$ENV_FILE"
fi

log "建库建账号（幂等）"
SQL_FILE=$(mktemp)
cat > "$SQL_FILE" <<SQL
CREATE DATABASE IF NOT EXISTS chainsip DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
CREATE USER IF NOT EXISTS 'chainsip'@'127.0.0.1' IDENTIFIED BY '${DB_PASS}';
ALTER USER 'chainsip'@'127.0.0.1' IDENTIFIED BY '${DB_PASS}';
GRANT ALL PRIVILEGES ON chainsip.* TO 'chainsip'@'127.0.0.1';
FLUSH PRIVILEGES;
SQL

# 新装的 MySQL 8：ALinux 的管理员走 socket 免密，Debian 系走 auth_socket，都可能要密码
if mysql -u"$MYSQL_ADMIN_USER" < "$SQL_FILE" 2>/dev/null; then
  log "数据库就绪：chainsip / 用户 chainsip@127.0.0.1（管理员免密）"
elif mysql -u"$MYSQL_ADMIN_USER" -p"${MYSQL_ROOT_PASSWORD:-$DB_PASS}" < "$SQL_FILE" 2>/dev/null; then
  log "数据库就绪：chainsip / 用户 chainsip@127.0.0.1（管理员密码登录）"
else
  rm -f "$SQL_FILE"
  die "无法自动建库。请手工执行：
  mysql -u"$MYSQL_ADMIN_USER" -p
  CREATE DATABASE chainsip DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
  CREATE USER 'chainsip'@'127.0.0.1' IDENTIFIED BY '${DB_PASS}';
  GRANT ALL ON chainsip.* TO 'chainsip'@'127.0.0.1';"
fi
rm -f "$SQL_FILE"

# ---------------------------------------------------------------- 4. nginx 与 systemd
if [ -f "$PKG_DIR/nginx/chainsip.conf" ]; then
  log "安装 nginx 站点配置"
  install -m 644 "$PKG_DIR/nginx/chainsip.conf" /etc/nginx/conf.d/chainsip.conf
  # 让开默认站点，否则 "duplicate default server" 直接起不来
  if [ -f /etc/nginx/conf.d/default.conf ]; then
    mv -f /etc/nginx/conf.d/default.conf /etc/nginx/conf.d/default.conf.disabled
  fi
  rm -f /etc/nginx/sites-enabled/default 2>/dev/null || true
  # RHEL/Alibaba Cloud Linux 的默认 server 块写在 nginx.conf 里
  if grep -q '^[[:space:]]*listen 80 default_server' /etc/nginx/nginx.conf 2>/dev/null; then
    cp -n /etc/nginx/nginx.conf /etc/nginx/nginx.conf.bak
    sed -i 's/^\([[:space:]]*\)listen 80 default_server/\1# listen 80 default_server/; s/^\([[:space:]]*\)listen \[::\]:80 default_server/\1# listen [::]:80 default_server/' /etc/nginx/nginx.conf
    log "已注释 nginx.conf 里自带的 default_server"
  fi
fi

if [ -f "$PKG_DIR/systemd/chainsip-backend.service" ]; then
  log "安装 systemd 服务"
  install -m 644 "$PKG_DIR/systemd/chainsip-backend.service" /etc/systemd/system/
  install -m 644 "$PKG_DIR/systemd/fisco-chain.service"     /etc/systemd/system/
  install -m 755 "$PKG_DIR/chain-control.sh"                "$APP_ROOT/deploy/chain-control.sh"
  chown chainsip:chainsip "$APP_ROOT/deploy/chain-control.sh"
  systemctl daemon-reload
  systemctl enable chainsip-backend fisco-chain >/dev/null 2>&1 || true
fi

# ---------------------------------------------------------------- 5. 体检
log "SELinux / 防火墙提示："
[ "$(getenforce 2>/dev/null || echo Disabled)" = "Enforcing" ] && \
  warn "SELinux 是 Enforcing，若 nginx 502 请执行：setsebool -P httpd_can_network_connect 1"
command -v firewall-cmd >/dev/null && firewall-cmd --state >/dev/null 2>&1 && \
  warn "firewalld 在运行：本机只需放行 80/443（systemctl stop firewalld 或 firewall-cmd --add-service=http --permanent）"

cat <<EOF

================ 初始化完成，接下来 ================
1) 把链目录解包到 $CHAIN_ROOT     → tar xzf chainsip-chain.tar.gz -C $CHAIN_ROOT
2) 导入数据库（有备份时）          → mysql chainsip < sql/chainsip.sql
3) 后端 jar + sdk/ → $APP_ROOT/backend（jar 与 sdk 证书目录同级）
4) 前端 dist/*     → $APP_ROOT/frontend
5) 启动：systemctl start fisco-chain && systemctl start chainsip-backend && nginx -t && systemctl reload nginx
6) 验收：curl -s http://127.0.0.1:8080/api/public/health
详见 docs/DEPLOY-ECS.md
===================================================
EOF
