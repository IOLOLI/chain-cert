# ChainSIP 手动部署教程（阿里云 ECS）

> 本文是**纯手工版**：每条命令都能直接复制粘贴，不依赖 `deploy/install.sh`。
> `docs/DEPLOY-ECS.md` 讲的是"为什么这样部署 + 一键脚本"，两篇配合用：
> 想理解原理看那篇，想现在就把站跑起来看这篇。
>
> 全程 8 步，动手约 30 分钟（下载依赖的时间另算）。下文默认以具备 sudo 权限的部署用户执行。

---

## TL;DR（熟练的话，这就是全部）

```bash
# 1. 传包（本机执行）
scp dist-pkg/chainsip-deploy-*.tar.gz <DEPLOY_USER>@<ECS_PUBLIC_IP>:~/

# 2. 登录后（服务器执行，ALinux 口径；Ubuntu 把 dnf 换 apt，mysqld 换 mysql）
dnf install -y java-1.8.0-openjdk-headless mysql-server nginx && systemctl enable --now mysqld nginx
useradd -r -s /sbin/nologin fisco; useradd -r -s /sbin/nologin chainsip
mkdir -p /opt/chainsip/{backend,frontend} /opt/fisco /var/log/chainsip /etc/chainsip
cd ~ && tar xzf chainsip-deploy-*.tar.gz && cd chainsip-deploy-*

# 3. 建库导数据
mysql -u<DB_ADMIN_USER> -p'<DB_ADMIN_PASSWORD>' -e "create database if not exists chainsip default charset utf8mb4;
  create user if not exists 'chainsip'@'127.0.0.1' identified by '<DB_PASSWORD>';
  grant all on chainsip.* to 'chainsip'@'127.0.0.1'; flush privileges;"
mysql -u<DB_ADMIN_USER> -p'<DB_ADMIN_PASSWORD>' chainsip < sql/chainsip.sql

# 4. 起链
tar xzf chainsip-chain.tar.gz -C /opt/fisco
chmod +x /opt/fisco/nodes/127.0.0.1/fisco-bcos /opt/fisco/nodes/127.0.0.1/*.sh
chown -R fisco:fisco /opt/fisco
cp -r deploy /opt/chainsip/ && chmod +x /opt/chainsip/deploy/*.sh
sudo -u fisco env CHAIN_HOME=/opt/fisco/nodes/127.0.0.1 /opt/chainsip/deploy/chain-control.sh start

# 5. 后端（/etc/chainsip/backend.env 要先按 §6.2 写）
cp backend/chainsip-backend.jar /opt/chainsip/backend/ && cp -r backend/sdk /opt/chainsip/backend/
chown -R chainsip:chainsip /opt/chainsip
cp /opt/chainsip/deploy/systemd/*.service /etc/systemd/system/ && systemctl daemon-reload
systemctl enable --now chainsip-backend

# 6. 前端
cp -r frontend/. /opt/chainsip/frontend/ && chmod -R a+r /opt/chainsip/frontend
cp /opt/chainsip/deploy/nginx/chainsip.conf /etc/nginx/conf.d/ && nginx -t && systemctl reload nginx
```

细节、原理和出错时的对照表在后面几节。**第 5 步的 `backend.env` 不能省**，尤其是主密钥那一项。

---

## 目录

| 步骤 | 内容 | 卡住会怎样 |
|---|---|---|
| 0 | 开工前：包、规格、安全组 | —— |
| 1 | 登录服务器，认出镜像 | 后面所有包管理命令选错分支 |
| 2 | 把安装包传上去 | 传不上去就只能重建链（附录 B） |
| 3 | 装 JDK8 / MySQL8 / nginx | —— |
| 4 | 建库、导数据 | 后端起不来（表不存在） |
| 5 | **先起链**（5 节点） | 后端起得来但 `chain.ready=false` |
| 6 | 起后端 | 页面能开、接口 502 |
| 7 | nginx 发前端 | 只有 IP 打不开 |
| 8 | 验收 | —— |

---

## 0. 开工前

### 0.1 安装包

在你本机仓库目录下，已经打好了：

```
dist-pkg/<DEPLOY_PACKAGE>.tar.gz     <PACKAGE_SIZE>
sha256 = <SHA256_OF_ARTIFACT>
```

包里有什么（不用解压，这就是全部依赖）：

| 路径 | 大小 | 是什么 |
|---|---|---|
| `backend/chainsip-backend.jar` | 59 MB | Spring Boot 可执行 jar（JDK 8） |
| `backend/sdk/` | 656 KB | FISCO SDK 配置 + 链的 TLS 证书（`ca.crt`/`sdk.crt`/`sdk.key`） |
| `frontend/` | 1.7 MB | 前端构建产物（`index.html` + `assets/`） |
| `sql/chainsip.sql` | 36 KB | 数据库全量备份（6 张表） |
| `chainsip-chain.tar.gz` | 13 MB | **整条链的快照**：5 个节点 + 账本数据 + 合约 |
| `deploy/` | 40 KB | nginx 站点、systemd 单元、链启停脚本 |

> 链快照只有 13 MB，是因为 FISCO 的 RocksDB 压缩率高，解压后约 35 MB。**合约地址、账号地址、历史流水全在里面**，搬过去不用重新部署合约。

### 0.2 服务器规格

| 项 | 要求 | 说明 |
|---|---|---|
| CPU / 内存 | 4 vCPU / 8 GiB | 5 个节点约 1.2 G，MySQL 0.4 G，JVM 1.5 G |
| 系统盘 | ≥ 40 GiB | 实际占用约 3 GiB |
| 镜像 | Alibaba Cloud Linux 3 / Ubuntu 22.04 | 全文给两套命令，按 `cat /etc/os-release` 选 |
| 带宽 | 5 Mbps 够演示 | 首屏 1.7 MB |

2C4G 也能跑，但要把 JVM 堆从 1536m 降到 1024m，否则容易 OOM。

### 0.3 安全组：只开三个口

控制台 → 实例 → 安全组 → 配置规则 → **入方向**：

| 端口 | 授权对象 | 用途 |
|---|---|---|
| 22 | 你的公网 IP/32 | SSH |
| 80 | 0.0.0.0/0 | HTTP |
| 443 | 0.0.0.0/0 | HTTPS（暂时没有证书可不开） |

**这几个绝对不能对公网开**，开了等于把链和数据库交给全网：

```
20200-20204   SDK channel（带证书，但仍是内网口）
30300-30304   节点 p2p
8545-8549     节点 JSON-RPC（上面有 sendRawTransaction，公网暴露=谁都能写链）
3306          MySQL
```

后端和链在同一台机器，走 `127.0.0.1`，不需要任何公网端口。

---

## 1. 登录服务器

### 方式 A：控制台 Workbench（22 端口不通时用这个）

ECS 控制台 → 实例 → **远程连接** → Workbench / VNC → 输入云服务器登录密码。
这条路走的是控制台通道，**不经过 22 端口**，安全组没放行 22 也能进。

### 方式 B：本地终端

```bash
ssh <DEPLOY_USER>@<ECS_PUBLIC_IP>
```

> 如果卡在 `connect timeout` 而不是提示输密码，说明包被安全组丢了——回头做 §0.3，或改用方式 A。

### 进机器后先认门

```bash
cat /etc/os-release | head -3
nproc; free -h | head -2; df -h / | tail -1
```

- 出现 `Alibaba Cloud Linux` / `CentOS` → 后文用 **dnf** 分支
- 出现 `Ubuntu` / `Debian` → 后文用 **apt** 分支

时间必须准（JWT 和区块时间都依赖它）：

```bash
timedatectl set-timezone Asia/Shanghai
timedatectl set-ntp true
date
```

---

## 2. 把安装包传上去

三选一，任选：

**A. Workbench 上传**（推荐，和 §1A 同一条通道）
Workbench 窗口右上角有「文件」/上传按钮，目标目录填 `/root`，选那个 67 MB 的 tar.gz。

**B. WinSCP / FileZilla**：协议 SFTP，主机 `<ECS_PUBLIC_IP>`，用户 `<DEPLOY_USER>`，传到 `~/`。

**C. 本机终端 scp**（Windows 10+ 自带 OpenSSH）：

```bash
cd <PROJECT_DIR>
scp dist-pkg/<DEPLOY_PACKAGE>.tar.gz <DEPLOY_USER>@<ECS_PUBLIC_IP>:~/
```

传完在服务器上校验（必须一致，不一致说明传输截断，重传）：

```bash
cd ~
ls -l <DEPLOY_PACKAGE>.tar.gz
sha256sum <DEPLOY_PACKAGE>.tar.gz
# 与构建机输出的 sha256 一致
```

---

## 3. 建目录、装依赖

### 3.1 建运行用户与目录

链和后端都不该用管理员账号直接运行：

```bash
useradd -r -s /sbin/nologin fisco   2>/dev/null || true
useradd -r -s /sbin/nologin chainsip 2>/dev/null || true

mkdir -p /opt/chainsip/{backend,frontend,deploy,docs} /opt/fisco
mkdir -p /var/log/chainsip /etc/chainsip
```

### 3.2 装 JDK 8 + MySQL 8 + nginx

**Alibaba Cloud Linux 3 / CentOS 8+：**

```bash
dnf install -y java-1.8.0-openjdk-headless mysql-server nginx tar curl
systemctl enable --now mysqld
systemctl enable --now nginx
```

**Ubuntu 22.04：**

```bash
export DEBIAN_FRONTEND=noninteractive
apt update
apt install -y openjdk-8-jre-headless mysql-server nginx tar curl
systemctl enable --now mysql
systemctl enable --now nginx
```

> Ubuntu 22.04 的 `openjdk-8-jre-headless` 在 universe 源里。如果 `apt` 说找不到包，装 Temurin 8：
> ```bash
> mkdir -p /usr/lib/jvm && cd /tmp
> curl -LO https://mirrors.tuna.tsinghua.edu.cn/Adoptium/8/jre/x64/linux/OpenJDK8U-jre_x64_linux_hotspot_8u422b05.tar.gz
> tar xzf OpenJDK8U-jre_x64_linux_hotspot_*.tar.gz -C /usr/lib/jvm
> ln -sfn /usr/lib/jvm/jdk8u422-b05-jre /usr/lib/jvm/java-8
> echo 'export JAVA_HOME=/usr/lib/jvm/java-8' > /etc/profile.d/java8.sh
> echo 'export PATH=$JAVA_HOME/bin:$PATH' >> /etc/profile.d/java8.sh
> source /etc/profile.d/java8.sh
> ```

### 3.3 校验

```bash
java -version     # 必须出现 1.8.0_xxx，出现 11/17 就是装错了
mysql --version   # 8.0.x
nginx -v
```

**`java -version` 是 17 怎么办？** ALinux 3 有时会带 JDK 17，用 alternatives 切回来：

```bash
alternatives --config java     # 选 1.8 那一项
```

### 3.4 SELinux（ALinux 有，Ubuntu 默认没有）

不处理会踩两个坑：nginx 起得来但**不许连 8080**（502），以及**读不了 `/opt` 下的静态文件**（403）。
`/opt` 的默认安全上下文是 `usr_t`，nginx 进程读不了，必须单独标一次：

```bash
getenforce                                        # 不是 Enforcing 就跳过本节

setsebool -P httpd_can_network_connect 1          # 允许 nginx 反代到本机 8080
chcon -R -t httpd_sys_content_t /opt/chainsip/frontend   # 允许 nginx 读静态文件
```

> `chcon` 是临时标记，磁盘重打标签会丢；想永久生效用
> `semanage fcontext -a -t httpd_sys_content_t '/opt/chainsip/frontend(/.*)?' && restorecon -Rv /opt/chainsip/frontend`
> （需要 `policycoreutils-python-utils`）。演示环境 `chcon` 够了。

### 3.5 开防火墙的 80

```bash
# firewalld（ALinux/CentOS）
systemctl is-active firewalld && firewall-cmd --permanent --add-service=http && firewall-cmd --reload

# ufw（Ubuntu，默认没开）
ufw status | head -1
```

---

## 4. 解包，然后配数据库

### 4.1 解包

```bash
cd ~
tar xzf chainsip-deploy-20261026-0928.tar.gz
cd chainsip-deploy-20261026-0928
ls          # backend  deploy  docs  frontend  sql  chainsip-chain.tar.gz  README.md
```

### 4.2 取 MySQL 管理员密码

**Alibaba Cloud Linux 3（dnf 装的 MySQL 8）**：安装时随机生成，记在错误日志里

```bash
grep 'temporary password' /var/log/mysqld.log | tail -1
# 形如：... <DEPLOY_USER>@localhost: xxxxxxxx
```

> 如果**没有任何输出**，说明这台是免密初始化，直接 `mysql -u<DB_ADMIN_USER>` 进去执行下面这条 ALTER 即可。

用这串临时密码进来，换成自己的（`#` 换成你记住的密码，下文都用 `<DB_ADMIN_PASSWORD>` 举例）：

```bash
mysql -u<DB_ADMIN_USER> -p'上面那串临时密码' --connect-expired-password \
  -e "ALTER USER '<DB_ADMIN_USER>'@'localhost' IDENTIFIED BY '<DB_ADMIN_PASSWORD>';"
```

**Ubuntu 22.04**：MySQL 管理员走 socket 免密认证，直接进

```bash
mysql -e "ALTER USER '<DB_ADMIN_USER>'@'localhost' IDENTIFIED WITH caching_sha2_password BY '<DB_ADMIN_PASSWORD>';"
```

以后统一用 `mysql -u<DB_ADMIN_USER> -p'<DB_ADMIN_PASSWORD>'`。

### 4.3 建库、建业务账号

注意账号是 `'chainsip'@'127.0.0.1'`——后端走 TCP 连本机，不是 socket：

```bash
mysql -u<DB_ADMIN_USER> -p'<DB_ADMIN_PASSWORD>' <<'SQL'
CREATE DATABASE IF NOT EXISTS chainsip
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
CREATE USER IF NOT EXISTS 'chainsip'@'127.0.0.1' IDENTIFIED BY '<DB_PASSWORD>';
GRANT ALL PRIVILEGES ON chainsip.* TO 'chainsip'@'127.0.0.1';
FLUSH PRIVILEGES;
SQL
```

> MySQL 8 默认密码策略要求「8 位以上 + 大小写 + 数字 + 符号」。`<DB_PASSWORD>` 满足。
> 想用简单密码就得先 `SET GLOBAL validate_password.policy=LOW;`（不推荐）。

### 4.4 导数据

```bash
mysql -u<DB_ADMIN_USER> -p'<DB_ADMIN_PASSWORD>' chainsip < sql/chainsip.sql
```

### 4.5 校验（数字必须对得上）

```bash
mysql -u<DB_ADMIN_USER> -p'<DB_ADMIN_PASSWORD>' chainsip -e "
  select 'account' t, count(*) n from cs_account
  union all select 'wallet', count(*) from cs_wallet
  union all select 'tx_log', count(*) from cs_chain_tx_log
  union all select 'inst_key', count(*) from cs_institution_key;"
```

期望（这份备份是 2026-10-26 09:28 导出的，数字对不上说明导入失败）：

```
account    9     账号（admin / bureau_demo / sponsor_demo / employee_demo / regulator_demo …）
wallet    10     账号 ↔ 链上地址绑定
tx_log    15     上链交易索引
inst_key   9     机构链账户私钥（AES-256-GCM 密文）
```

`cs_chain_event`（事件索引）和 `cs_login_log`（登录留痕）可以少，前者的 0 行是正常的——
事件由后端在下次启动/读链时补齐。

> 业务数据（职工、缴费明细）**不在数据库里**，在链上。库里只有账号、地址绑定、密钥密文和索引，所以表少是正常的。

---

## 5. 部署链（先起链，后起后端）

顺序很重要：后端启动时会初始化 SDK 并探测合约，链没起会一直 `chain.ready=false`。

### 5.1 解压链快照

```bash
tar xzf chainsip-chain.tar.gz -C /opt/fisco
ls /opt/fisco/nodes/127.0.0.1          # node0 node1 node2 node3 node4 cert sdk
```

### 5.2 权限

```bash
chmod +x /opt/fisco/nodes/127.0.0.1/fisco-bcos
chmod +x /opt/fisco/nodes/127.0.0.1/*.sh
chown -R fisco:fisco /opt/fisco
```

### 5.3 启动

```bash
cp -r deploy /opt/chainsip/            # 里面是 chain-control.sh 与 systemd 单元
chmod +x /opt/chainsip/deploy/*.sh

sudo -u fisco env CHAIN_HOME=/opt/fisco/nodes/127.0.0.1 \
  /opt/chainsip/deploy/chain-control.sh start
```

正常输出：

```
[chain] 启动 node0
[chain] 启动 node1
...
[chain] 启动 node4
[chain] 进程数 = 5
[chain] 出块正常，最新区块高度 = 22
```

### 5.4 验证三件事

```bash
# ① 5 个进程
pgrep -fc 'fisco-bcos -c config.ini'          # 5

# ② 区块高度在涨（等 5 秒再打一次，数字要变大）
curl -s -X POST http://127.0.0.1:8545 -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","method":"getBlockNumber","params":[1],"id":1}'; echo

# ③ 监管节点也在同步（node4 的 RPC 端口是 8549）
curl -s -X POST http://127.0.0.1:8549 -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","method":"getBlockNumber","params":[1],"id":1}'; echo
```

两条 RPC 返回的高度必须一致，说明 **4 个共识节点出块、第 5 个观察节点同步**。

### 5.5 为什么整份搬过来就能用

不需要改任何配置，原因有三个：

1. `config.ini` 里 `listen_ip` / `channel_listen_ip` 已经是 `0.0.0.0`，换 IP 照样监听；
2. p2p 的 `node.0..3=127.0.0.1:3030x` 是**同机端口**，迁移后仍同机，拓扑不变；
3. `data/group1` 是完整账本，合约代码与状态都在里面——`<CORE_CONTRACT_ADDRESS>`（ChainSIP）、`<TRANSFER_CONTRACT_ADDRESS>`（ChainSIPTransfer）继续有效。

节点证书由各自 `node.key` 决定，与主机 IP 无关，也不用重签。**SDK 证书必须与链成套**：包里的 `backend/sdk/conf/` 就是这条链的，别和别的链混用。

### 5.6 停止 / 重启

```bash
sudo -u fisco env CHAIN_HOME=/opt/fisco/nodes/127.0.0.1 /opt/chainsip/deploy/chain-control.sh stop
sudo -u fisco env CHAIN_HOME=/opt/fisco/nodes/127.0.0.1 /opt/chainsip/deploy/chain-control.sh status
```

---

## 6. 部署后端

### 6.1 放程序

```bash
cp backend/chainsip-backend.jar /opt/chainsip/backend/
cp -r backend/sdk /opt/chainsip/backend/          # 必须与 jar 同级
chown -R chainsip:chainsip /opt/chainsip/backend

grep -n '^peers' /opt/chainsip/backend/sdk/config.toml
# peers = ["127.0.0.1:20200"]      ← pack.sh 已改好，是本机节点
```

### 6.2 写环境变量文件

```bash
cat > /etc/chainsip/backend.env <<'EOF'
CHAINSIP_DB_HOST=127.0.0.1
CHAINSIP_DB_PORT=3306
CHAINSIP_DB_NAME=chainsip
CHAINSIP_DB_USER=chainsip
CHAINSIP_DB_PASSWORD="<DB_PASSWORD>"
CHAINSIP_JWT_SECRET="REPLACE_ME"
CHAINSIP_CRYPTO_MASTER_KEY="chainsip-dev-master-key-change-me-before-production"
CHAINSIP_LOG_LEVEL=INFO
CHAINSIP_CHAIN_ENDPOINT=127.0.0.1:20200
EOF

# 生成一个真随机 JWT secret 替换掉 REPLACE_ME
SECRET=$(openssl rand -base64 48 | tr -d '\n')
sed -i "s|CHAINSIP_JWT_SECRET=.*|CHAINSIP_JWT_SECRET=\"$SECRET\"|" /etc/chainsip/backend.env

chmod 600 /etc/chainsip/backend.env
chown chainsip:chainsip /etc/chainsip/backend.env
cat /etc/chainsip/backend.env
```

逐项说明：

| 变量 | 说明 |
|---|---|
| `CHAINSIP_DB_*` | 上一节建的库和账号 |
| `CHAINSIP_JWT_SECRET` | 登录令牌签名密钥，≥32 字节，泄露等于别人能伪造登录 |
| `CHAINSIP_CRYPTO_MASTER_KEY` | **加密机构链账户私钥的主密钥，必须与备份里那把一致** |
| `CHAINSIP_CHAIN_ENDPOINT` | 仅用于界面展示的节点地址，真正的连接地址在 `sdk/config.toml` |

> ⚠️ **主密钥这条最容易翻车。** `cs_institution_key` 存的是机构链账户私钥的 AES-256-GCM 密文，
> 是用 `chainsip-dev-master-key-change-me-before-production` 加密的。你如果把它改成随机值，
> 后端能启动、能登录、能查，但**任何一笔发交易都会报私钥解密失败**。
> 迁移已有数据 → 必须沿用上面这个旧值；想要新密钥 → 只能在空库 + 新链下重建账号。

### 6.3 先前台跑一次，看日志最快

```bash
cd /opt/chainsip/backend
set -a; . /etc/chainsip/backend.env; set +a
java -jar chainsip-backend.jar
```

看到这两行就说明成了：

```
Tomcat started on port(s): 8080 (http)
Started ChainsipApplication in 12.3 seconds
```

`Ctrl+C` 停掉，换 systemd 常驻。

### 6.4 装 systemd 常驻

```bash
cp /opt/chainsip/deploy/systemd/chainsip-backend.service /etc/systemd/system/
cp /opt/chainsip/deploy/systemd/fisco-chain.service     /etc/systemd/system/
systemctl daemon-reload

systemctl enable --now fisco-chain          # 已在跑就 status 一下即可
systemctl enable --now chainsip-backend
systemctl status chainsip-backend --no-pager | head -12
```

> `fisco-chain.service` 的 `ExecStart` 调的就是 `/opt/chainsip/deploy/chain-control.sh`，
> 所以 §5.3 那一步复制 `deploy/` 不能省。

### 6.5 健康检查

```bash
curl -s http://127.0.0.1:8080/api/public/health | head -c 600; echo
```

期望 `"db":{"ok":true}` 和 `"chain":{"ready":true}`，且 `blockNumber` 在涨。
`ready:false` 看返回的 `message`，它会直接写是证书、合约地址还是端口的问题。

---

## 7. 部署前端 + nginx

### 7.1 放静态文件

```bash
cp -r frontend/. /opt/chainsip/frontend/
chmod 755 /opt /opt/chainsip                  # nginx 要能穿过去
chmod -R a+r /opt/chainsip/frontend
chown -R chainsip:chainsip /opt/chainsip/frontend
ls /opt/chainsip/frontend      # index.html  assets
```

### 7.2 装站点配置

```bash
cp /opt/chainsip/deploy/nginx/chainsip.conf /etc/nginx/conf.d/
```

配置文件已包含：`/` 指向 `frontend`、`/api/` 反代 `127.0.0.1:8080`、gzip、
`/assets/` 30 天缓存、`index.html` 不缓存、`proxy_read_timeout 75s`（给上链交易留余量）。

### 7.3 让开系统自带的默认站点

不处理会报 `duplicate default server`，nginx 直接起不来：

```bash
# CentOS / ALinux
rm -f /etc/nginx/conf.d/default.conf
# Debian / Ubuntu
rm -f /etc/nginx/sites-enabled/default

# ALinux 的默认 server 写在 nginx.conf 里，注释掉那两行 listen 80 default_server
cp -n /etc/nginx/nginx.conf /etc/nginx/nginx.conf.bak
sed -i 's/^\(\s*\)listen 80 default_server/\1# listen 80 default_server/;
        s/^\(\s*\)listen \[::\]:80 default_server/\1# listen [::]:80 default_server/' /etc/nginx/nginx.conf
```

### 7.4 生效

```bash
nginx -t                 # syntax is ok / test is successful
systemctl reload nginx
curl -s -o /dev/null -w 'page:%{http_code}\n' http://127.0.0.1/
```

`page:200` 就通了。浏览器打开 **http://<ECS_PUBLIC_IP>/**，用 `admin / ChangeMe_123456` 登录。

---

## 8. 验收清单

按顺序打勾，任一项不过就停下看 §9：

```bash
# ① 链：5 进程 + 出块
pgrep -fc 'fisco-bcos -c config.ini'                        # 5
curl -s -X POST http://127.0.0.1:8545 -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","method":"getBlockNumber","params":[1],"id":1}'; echo

# ② 后端健康
curl -s http://127.0.0.1:8080/api/public/health | head -c 500; echo

# ③ 共识拓扑：4 出块 + 1 观察
TOKEN=$(curl -s -X POST http://127.0.0.1:8080/api/auth/login \
  -H 'Content-Type: application/json' -d '{"username":"admin","password":"ChangeMe_123456"}' \
  | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
curl -s http://127.0.0.1:8080/api/chain/topology -H "Authorization: Bearer $TOKEN"; echo
# 期望 sealerCount:4  observerCount:1

# ④ 数据库
mysql -u<DB_ADMIN_USER> -p'<DB_ADMIN_PASSWORD>' chainsip -e "select username,role from cs_account order by id;"

# ⑤ 对外：页面 200，接口未带 token 是 401（这是对的，不是错）
curl -s -o /dev/null -w 'page:%{http_code}\n' http://127.0.0.1/
curl -s -o /dev/null -w 'api:%{http_code}\n'  http://127.0.0.1/api/admin/accounts

# ⑥ 开机自启
systemctl is-enabled fisco-chain chainsip-backend nginx     # 三个 enabled
```

浏览器里再确认两件事：

1. 首页页眉第四格显示 **`group 1 · 4 共识 + 1 观察`**；
2. 顶栏右上角出现你的账号与链上角色印章。

到这一步四层（链 → MySQL → 后端 → 前端）就全通了。

---

## 9. 排错表

| 现象 | 原因 / 处理 |
|---|---|
| `ssh: connect to host ... timed out` | 安全组没放行你的 IP。用 Workbench 登录，或加规则 `你的IP/32 → 22` |
| 上传后 `sha256sum` 不一致 | 传输截断，重传；Workbench 大文件建议压缩后再传 |
| `java -version` 是 17 | `alternatives --config java` 选 1.8 |
| `mysql: command not found` | 镜像自带的是 MariaDB 或没装：`dnf install mysql-server` |
| 建账号报 `Your password does not satisfy the current policy` | 密码强度不够，或先 `SET GLOBAL validate_password.policy=LOW;` |
| 后端报 `Table 'chainsip.cs_account' doesn't exist` | sql 没导入或导错库：`mysql chainsip -e 'show tables;'` |
| 后端报 `Access denied for user 'chainsip'@'localhost'` | 账号建成了 `@'localhost'`，要 `@'127.0.0.1'` |
| `chain.ready=false`，message 提证书 | SDK 证书与链不配套，`backend/sdk/conf` 必须来自这个包 |
| `chain.ready=false`，message 提端口 | 链没起：`chain-control.sh status`；或 `config.toml` 的 peers 不是 `127.0.0.1:20200` |
| 后端启动报 `parse Config ... failed` | `config.toml` 的值必须全带引号，文件保持 ASCII |
| 页面 502 | 后端没起：`systemctl status chainsip-backend`、`tail -50 /var/log/chainsip/backend.log` |
| 502 但后端在跑，且是 ALinux | SELinux：`setsebool -P httpd_can_network_connect 1` |
| `nginx: duplicate default server` | §7.3 没做 |
| 发交易报**私钥解密失败** | `CHAINSIP_CRYPTO_MASTER_KEY` 与库里密文不是同一把，改回 §6.2 的旧值 |
| 登录成功但立刻退出 | 服务器时间不准，JWT 判过期：`timedatectl set-ntp true` |
| 进程被 OOM kill | 5 节点 + JVM 超内存：加 swap 或把 `-Xmx1536m` 改 `-Xmx1024m` |
| 链起不来，日志 `PBFT` 反复换视图 | 内存不足导致节点掉队；`free -h` 看一下，加 swap 再 `chain-control.sh start` |

---

## 10. 日常运维

| 场景 | 命令 |
|---|---|
| 看后端日志 | `tail -f /var/log/chainsip/backend.log` |
| 看链日志 | `tail -f /opt/fisco/nodes/127.0.0.1/node0/log/log_*.log` |
| 停整站 | `systemctl stop chainsip-backend fisco-chain nginx` |
| 改配置 | 编辑 `/etc/chainsip/backend.env` → `systemctl restart chainsip-backend` |
| 换后端 jar | 覆盖 jar → `systemctl restart chainsip-backend`（先打快照） |
| 换前端 | `cp -r frontend/. /opt/chainsip/frontend/`，不用 reload |
| 备份数据库 | `mysqldump -u<DB_ADMIN_USER> -p chainsip > ~/bak/$(date +%F).sql` |
| 备份链 | 先 `chain-control.sh stop`，再 `tar czf` 整个 `/opt/fisco`，最后 `start` |

**链和库必须一起备份、一起回滚。** 链上账本是权威，`cs_chain_tx_log`/`cs_chain_event` 只是索引；
只恢复其中一个会出现「链上有这笔缴费、链下索引没有」的错位，排查起来非常费时间。
ECS 上建议给系统盘配自动快照，一天一次就够。

---

## 附录 A：不想一步步敲，一次跑完

确认 §3 的依赖已装好、§4.3 的库和账号已建好之后，下面这段可以一次粘贴：

```bash
set -e
cd ~/chainsip-deploy-20261026-0928

# 链
tar xzf chainsip-chain.tar.gz -C /opt/fisco
chmod +x /opt/fisco/nodes/127.0.0.1/fisco-bcos /opt/fisco/nodes/127.0.0.1/*.sh
chown -R fisco:fisco /opt/fisco
cp -r deploy /opt/chainsip/ && chmod +x /opt/chainsip/deploy/*.sh
sudo -u fisco env CHAIN_HOME=/opt/fisco/nodes/127.0.0.1 /opt/chainsip/deploy/chain-control.sh start

# 数据库
mysql -u<DB_ADMIN_USER> -p'<DB_ADMIN_PASSWORD>' chainsip < sql/chainsip.sql

# 后端
cp backend/chainsip-backend.jar /opt/chainsip/backend/
cp -r backend/sdk /opt/chainsip/backend/
chown -R chainsip:chainsip /opt/chainsip
cp /opt/chainsip/deploy/systemd/*.service /etc/systemd/system/
systemctl daemon-reload && systemctl enable --now chainsip-backend

# 前端
cp -r frontend/. /opt/chainsip/frontend/ && chmod -R a+r /opt/chainsip/frontend
cp /opt/chainsip/deploy/nginx/chainsip.conf /etc/nginx/conf.d/
nginx -t && systemctl reload nginx

# 自检
sleep 5
curl -s http://127.0.0.1:8080/api/public/health | head -c 400; echo
curl -s -o /dev/null -w 'page:%{http_code}\n' http://127.0.0.1/
```

`/etc/chainsip/backend.env` 仍要先按 §6.2 写好，这段没包含它（密钥得你自己定）。

## 附录 B：不迁数据，在 ECS 上重建一条新链

适合想要干净环境、或者不想要我这份演示数据的情况。**合约地址会变，账号要重建。**

```bash
cd /opt/fisco
curl -#LO https://github.com/FISCO-BCOS/FISCO-BCOS/releases/download/v2.11.0/build_chain.sh
bash build_chain.sh -l "127.0.0.1:5" -p 30300,20200,8545 -o nodes -e /opt/fisco/fisco-bcos
chmod +x nodes/127.0.0.1/{start_all.sh,stop_all.sh,fisco-bcos}
bash nodes/127.0.0.1/start_all.sh
```

5 个节点默认全是共识节点。要复现「4 共识 + 1 观察」的拓扑，按 `docs/CHAIN-TOPOLOGY.md`
里 `scripts/add-observer-node.sh` 的做法，先用控制台 `removeNode` 摘掉 node4，再 `addObserver` 加回来。

重建之后合约是空的，需要用 FISCO 控制台部署 `ChainSIP` / `ChainSIPTransfer`，把新地址写进
`/etc/chainsip/backend.env`（`CHAINSIP_CORE_ADDRESS` / `CHAINSIP_TRANSFER_ADDRESS`），
并且用**空库**启动（`schema.sql` 会自动建表并创建 `admin`）。

## 附录 C：配域名和 HTTPS

1. 域名 A 记录指向 `<ECS_PUBLIC_IP>`；**大陆地域必须先完成 ICP 备案**，否则 80 端口会被拦。
2. 阿里云申请免费 DV 证书，下载 nginx 版，传到 `/etc/nginx/ssl/chainsip.pem|key`。
3. 在 `chainsip.conf` 的 server 块加：

```nginx
listen 443 ssl http2;
ssl_certificate     /etc/nginx/ssl/chainsip.pem;
ssl_certificate_key /etc/nginx/ssl/chainsip.key;
ssl_protocols TLSv1.2 TLSv1.3;
```

4. 另起 80 的 server 只做跳转：`return 301 https://$host$request_uri;`
5. `nginx -t && systemctl reload nginx`

答辩现场演示、没有域名，就用 `http://公网IP/` 直接访问，**不需要备案**（备案只针对域名绑定）。
