#!/bin/bash
# 在开发机（Windows Git Bash / macOS / Linux）上打一个可以直接丢给 ECS 的部署包
#
#   bash deploy/pack.sh                 # jar + dist + MySQL 备份 + 部署资产
#   bash deploy/pack.sh --with-chain    # 额外把虚拟机上的链目录打进去（迁移链）
#   bash deploy/pack.sh --chain-tar F   # 用已有的链快照（已在链停机状态下打好的包）
#   bash deploy/pack.sh --no-db         # 不导出数据库（在 ECS 上重建空库）
#
# 产物：dist-pkg/chainsip-deploy-YYYYmmdd-HHMM.tar.gz
set -euo pipefail
cd "$(dirname "$0")/.."

ROOT=$(pwd)
STAMP=$(date +%Y%m%d-%H%M)
OUT="$ROOT/dist-pkg"
PKG="$OUT/chainsip-deploy-$STAMP"
WITH_CHAIN=0
WITH_DB=1
CHAIN_TAR=${CHAIN_TAR:-}
CHAIN_HOST=${CHAIN_HOST:-127.0.0.1}
CHAIN_USER=${CHAIN_USER:-}
CHAIN_PASS=${CHAIN_PASS:-}
DB_USER=${DB_USER:-chainsip}
DB_PASS=${DB_PASS:-}
DB_NAME=${DB_NAME:-chainsip}
# 到了服务器上，SDK 连的是本机节点
TARGET_ENDPOINT=${TARGET_ENDPOINT:-127.0.0.1:20200}

while [ $# -gt 0 ]; do
  case "$1" in
    --with-chain) WITH_CHAIN=1 ;;
    --chain-tar)  CHAIN_TAR="$2"; shift ;;
    --no-db)      WITH_DB=0 ;;
    *) echo "未知参数 $1"; exit 2 ;;
  esac
  shift
done

say() { printf '\033[32m[pack]\033[0m %s\n' "$*"; }

# 本机可能没把 maven / npm 加到 PATH，按常见安装位置兜底
find_bin() {
  local name="$1"; shift
  if command -v "$name" >/dev/null 2>&1; then command -v "$name"; return 0; fi
  for c in "$@"; do [ -x "$c" ] && { echo "$c"; return 0; }; done
  return 1
}
MAVEN=$(find_bin mvn "${MAVEN_HOME:-}/bin/mvn" /usr/share/maven/bin/mvn) \
  || { echo "找不到 mvn，请设置 MAVEN 环境变量指向 maven 可执行文件"; exit 1; }
NPM=$(find_bin npm "${NODE_HOME:-}/npm.cmd" /usr/local/bin/npm /usr/bin/npm) \
  || { echo "找不到 npm，请设置 NPM 环境变量"; exit 1; }
say "maven = $MAVEN"
say "npm   = $NPM"

rm -rf "$PKG"; mkdir -p "$PKG"/{backend,frontend,deploy,sql,docs}

# ------------------------------------------------------------------ 1. 后端
say "构建后端 jar（跳过测试）"

( cd backend && "$MAVEN" -s settings-2x.xml -Dmaven.repo.local="${M2:-$HOME/.m2/repository}" \
    -q package -DskipTests )
cp backend/target/chainsip-backend.jar "$PKG/backend/"

say "打包 SDK 配置与证书，并把 peers 改成本机节点"
cp -r backend/sdk "$PKG/backend/"
# 2.x 的 config.toml 要求所有值带引号，这里只换 peers 那一行
sed -i "s|^peers = \[.*\]|peers = [\"${TARGET_ENDPOINT}\"]|" "$PKG/backend/sdk/config.toml"
grep -n '^peers' "$PKG/backend/sdk/config.toml"

# ------------------------------------------------------------------ 2. 前端
say "构建前端"
( cd frontend && "$NPM" run build >/dev/null )
cp -r frontend/dist/. "$PKG/frontend/"

# ------------------------------------------------------------------ 3. 数据库
if [ "$WITH_DB" -eq 1 ]; then
  say "导出数据库 $DB_NAME"
  mysqldump -u"$DB_USER" -p"$DB_PASS" --single-transaction --routines --triggers \
    --default-character-set=utf8mb4 --no-tablespaces "$DB_NAME" > "$PKG/sql/chainsip.sql" 2>/dev/null \
    || { echo "导出失败：检查 mysqldump 是否在 PATH、密码是否正确（DB_PASS=... 可覆盖）"; exit 1; }
  ls -lh "$PKG/sql/chainsip.sql" | awk '{print "  " $5, $9}'
else
  say "跳过数据库导出（--no-db）"
fi

# ------------------------------------------------------------------ 4. 部署资产
say "复制部署资产"
cp -r deploy/. "$PKG/deploy/"
# Windows 上 checkout 出来的脚本可能是 CRLF，进了 Linux 会报 "\r: command not found"
find "$PKG/deploy" -name '*.sh' -exec sed -i 's/\r$//' {} +
cp docs/DEPLOY-ECS.md docs/CHAIN-TOPOLOGY.md "$PKG/docs/" 2>/dev/null || true
cp README.md "$PKG/" 2>/dev/null || true

# ------------------------------------------------------------------ 5. 链（可选，从虚拟机拉）
if [ -n "$CHAIN_TAR" ]; then
  say "使用已有链快照 $CHAIN_TAR"
  cp "$CHAIN_TAR" "$PKG/chainsip-chain.tar.gz"
  ls -lh "$PKG/chainsip-chain.tar.gz" | awk '{print "  " $5, $9}'
elif [ "$WITH_CHAIN" -eq 1 ]; then
  say "从 $CHAIN_HOST 打包链目录（4 共识 + 1 观察节点）"
  python - "$CHAIN_HOST" "$CHAIN_USER" "$CHAIN_PASS" "$PKG" <<'PY'
import sys, os, paramiko
host, user, pwd, pkg = sys.argv[1:5]
c = paramiko.SSHClient(); c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect(host, 22, user, pwd, timeout=25, allow_agent=False, look_for_keys=False)
# 从 ~/fisco 打包，成员是 nodes/...，这样在服务器上解到 /opt/fisco 就得到 /opt/fisco/nodes/...
# 数据目录不打包日志，体积能小一半
cmd = ("cd ~/fisco && tar czf /tmp/chainsip-chain.tar.gz --exclude='nodes/*/log/*' "
       "--exclude='nodes/*/nohup.out' nodes && ls -lh /tmp/chainsip-chain.tar.gz")
_, out, err = c.exec_command(cmd, timeout=600)
print(out.read().decode()); print(err.read().decode())
sftp = c.open_sftp()
sftp.get('/tmp/chainsip-chain.tar.gz', os.path.join(pkg, 'chainsip-chain.tar.gz'))
sftp.close(); c.close()
PY
fi

# ------------------------------------------------------------------ 6. 说明与打包
cat > "$PKG/DEPLOY-QUICKSTART.txt" <<EOF
ChainSIP 部署包  $STAMP

0) 先看 deploy/DEPLOY.md —— 里面有包内容清单、区块链部署细节、验收与排错。
   本文件只是九行速查。

1) 上传（并核对 sha256）：
   scp chainsip-deploy-$STAMP.tar.gz <DEPLOY_USER>@<ECS_PUBLIC_IP>:~/

2) 服务器上：
   cd ~ && tar xzf chainsip-deploy-$STAMP.tar.gz && cd chainsip-deploy-$STAMP
   bash deploy/install.sh                       # 装 JDK8/MySQL/nginx、建库建用户、生成密钥、装 systemd
$( [ -n "$CHAIN_TAR" ] || [ "$WITH_CHAIN" -eq 1 ] && echo "   tar xzf chainsip-chain.tar.gz -C /opt/fisco      # 恢复链（含合约与历史数据）" )
$( [ -n "$CHAIN_TAR" ] || [ "$WITH_CHAIN" -eq 1 ] && echo "   chmod +x /opt/fisco/nodes/127.0.0.1/fisco-bcos && chown -R fisco:fisco /opt/fisco" )
$( [ "$WITH_DB" -eq 1 ] && echo "   mysql chainsip < sql/chainsip.sql            # 恢复业务数据" )
   cp backend/chainsip-backend.jar /opt/chainsip/backend/
   cp -r backend/sdk               /opt/chainsip/backend/
   cp -r frontend/.                /opt/chainsip/frontend/
   chown -R chainsip:chainsip /opt/chainsip

   # 导入的是已有数据库时，主密钥必须改回旧值，否则一发交易就报私钥解密失败：
   #   sed -i 's/^CHAINSIP_CRYPTO_MASTER_KEY=.*/CHAINSIP_CRYPTO_MASTER_KEY=chainsip-dev-master-key-change-me-before-production/' /etc/chainsip/backend.env

   systemctl start fisco-chain
   systemctl start chainsip-backend
   nginx -t && systemctl reload nginx
   curl -s http://127.0.0.1:8080/api/public/health      # status=UP 即通

3) 验收：浏览器打开 http://<公网IP>/ ，admin / ChangeMe_123456，
   首页第四格应显示 "group 1 · 4 共识 + 1 观察"。

细节见 deploy/DEPLOY.md（包内容与区块链部署）、docs/DEPLOY-ECS.md（选型与排错）
EOF

say "生成压缩包"
( cd "$OUT" && tar czf "chainsip-deploy-$STAMP.tar.gz" "chainsip-deploy-$STAMP" )
rm -rf "$PKG"
ls -lh "$OUT/chainsip-deploy-$STAMP.tar.gz" | awk '{print "  → " $5, $9}'
say "完成。上传命令："
echo "  scp $OUT/chainsip-deploy-$STAMP.tar.gz <DEPLOY_USER>@<ECS_PUBLIC_IP>:~/"
