# ChainSIP 部署手册 · 打包产物说明与服务器落地

本文对应部署包 **`chainsip-deploy-<打包时间戳>.tar.gz`**（约 66.5 MiB / 69.8 MB）。
文件名里的时间戳每次出包都会变，下面的命令一律用通配符，照抄即可。

三份部署文档的分工，别混：

| 文档 | 讲什么 | 什么时候看 |
|---|---|---|
| **本文 `deploy/DEPLOY.md`** | **包里到底装了什么** + **区块链部分怎么落到服务器** + 全流程命令 | 你手上就是这个包，要照着装 |
| `docs/DEPLOY-ECS.md` | 机器怎么选、安全组怎么配、拆成多台怎么部、故障排查表 | 选型 / 架构 / 出问题 |
| `docs/DEPLOY-MANUAL.md` | 不依赖任何脚本、纯手敲的 8 步 | 想彻底搞懂每一步在干什么 |

---

## 0. 一分钟版

```bash
# ① 开发机（仓库根目录）
bash deploy/pack.sh --chain-tar dist-pkg/chainsip-chain.tar.gz

# ② 传到服务器
scp dist-pkg/chainsip-deploy-*.tar.gz <DEPLOY_USER>@<PUBLIC_HOST>:~/

# ③ 服务器上
cd ~ && tar xzf chainsip-deploy-*.tar.gz && cd chainsip-deploy-*/
bash deploy/install.sh                                    # 装 JDK8/MySQL/nginx、建库、建用户、装 systemd

tar xzf chainsip-chain.tar.gz -C /opt/fisco               # 链（5 节点 + 账本 + 合约）
chmod +x /opt/fisco/nodes/127.0.0.1/fisco-bcos && chown -R fisco:fisco /opt/fisco

mysql chainsip < sql/chainsip.sql                          # 业务数据
cp backend/chainsip-backend.jar /opt/chainsip/backend/     # 后端
cp -r backend/sdk                /opt/chainsip/backend/
cp -r frontend/.                 /opt/chainsip/frontend/
chown -R chainsip:chainsip /opt/chainsip

# 关键：迁移已有数据库时必须把主密钥改回旧值，否则一发交易就报私钥解密失败
sed -i 's/^CHAINSIP_CRYPTO_MASTER_KEY=.*/CHAINSIP_CRYPTO_MASTER_KEY=chainsip-dev-master-key-change-me-before-production/' \
    /etc/chainsip/backend.env

systemctl start fisco-chain
systemctl start chainsip-backend
nginx -t && systemctl reload nginx

curl -s http://127.0.0.1:8080/api/public/health          # status 应为 UP
```

浏览器打开 `http://<公网IP>/`，`admin / ChangeMe_123456` 登录，首页第四格显示 **`group 1 · 4 共识 + 1 观察`** 即全部打通。

---

## 1. 打包产物是什么

### 1.1 包的物理结构

解压后是一个目录，**55 个文件**、解压约 **74 MiB**（压缩后 66.5 MiB）：

```
chainsip-deploy-<时间戳>/
├── DEPLOY-QUICKSTART.txt        1.6 KB   十几行速查（和本文第 0 节一致）
├── README.md                     81 KB   项目总说明（部署 / 使用 / 排错 / 优化）
├── backend/
│   ├── chainsip-backend.jar      59 MB   Spring Boot 可执行 jar（JDK 8 编译）
│   └── sdk/                     640 KB   FISCO Java SDK 的连接配置与证书
│       ├── config.toml                   peers 已改成 127.0.0.1:20200
│       ├── conf/ca.crt                  链根 CA
│       ├── conf/sdk.crt + sdk.key       SDK 双向认证证书（TLS）
│       └── native/libffi_java_sdk.dll   Windows 用原生库（Linux 上不使用，见 §4.8）
├── frontend/                    1.6 MB   前端构建产物（index.html + assets/）
├── sql/
│   └── chainsip.sql              34 KB   MySQL 逻辑备份（6 张表）
├── chainsip-chain.tar.gz         13 MB   整条链的快照（解压 37 MB，5 个节点）
├── deploy/                       69 KB   **本文档** + nginx 站点 + 2 个 systemd 单元 + 3 个脚本
└── docs/                         24 KB   CHAIN-TOPOLOGY.md、DEPLOY-ECS.md
```

### 1.2 逐项说明：这是什么、丢了能不能补

| 内容 | 体积 | 是什么 | 缺了怎么办 |
|---|---|---|---|
| `chainsip-backend.jar` | 59 MB | 后端全部代码 + 依赖（Spring Boot fat jar），运行时**不需要**再装 Maven | `cd backend && mvn -s settings-2x.xml package -DskipTests` 重打，约 40 秒 |
| `backend/sdk/` | 640 KB | 后端连链的 4 个证书 + `config.toml` | 从链目录 `nodes/127.0.0.1/sdk/` 复制；`peers` 改成服务器上的 channel 地址 |
| `frontend/` | 1.6 MB | 已构建的静态站点（`npm run build` 的产物） | `cd frontend && npm install && npm run build`，约 1 分钟 |
| `sql/chainsip.sql` | 34 KB | 业务库备份，**含演示数据**（见 §1.4） | 只能在原库上 `mysqldump`，或空库启动让 `schema.sql` 建表后重新造数据 |
| `chainsip-chain.tar.gz` | 13 MB | **整条链**：5 个节点的配置、证书、账本数据、合约状态 | 只能在链所在机器上重新快照（必须停链打，见 §4.1） |
| `deploy/` | 69 KB | 本文档 + `install.sh` / `chain-control.sh` / `pack.sh` / nginx 配置 / systemd 单元 | 仓库里就是原件，`git checkout` 即可 |

> **包里没有**（也不该有）：JDK、MySQL、nginx（由 `install.sh` 安装）；`node_modules`、`.git`、源码（服务器上不需要编译）；`/etc/chainsip/backend.env`（密码和密钥在服务器本地随机生成）。

### 1.3 校验

```bash
# 开发机：记下这个值
sha256sum dist-pkg/chainsip-deploy-*.tar.gz
# 服务器：上传后比对，两个值必须一致
sha256sum ~/chainsip-deploy-*.tar.gz
```

传 67 MB 的大文件，网络抖动导致尾部截断很常见；`tar tzf` 能过但解压报错时就是这个问题，重传即可。

### 1.4 包里带的数据

**数据库（6 张表）**

| 表 | 行数 | 内容 |
|---|---|---|
| `cs_account` | 9 | 账号（admin / bureau_demo / sponsor_demo / employee_demo / regulator_demo 等） |
| `cs_wallet` | 10 | 链上地址与私钥密文 |
| `cs_institution_key` | 9 | 机构链账户私钥（AES-256-GCM，**依赖主密钥**） |
| `cs_chain_tx_log` | 15 | 上链交易索引 |
| `cs_chain_event` | 0 | 合约事件索引（空表，正常） |
| `cs_login_log` | 95 | 登录审计 |

导入后可以用这几行核对（数字对不上说明导入被中断）：

```bash
mysql chainsip -e "
select 'account',count(*) from cs_account union all
select 'wallet',count(*) from cs_wallet union all
select 'tx_log',count(*) from cs_chain_tx_log union all
select 'inst_key',count(*) from cs_institution_key;"
```

**链（5 节点）**

| 节点 | 身份 | nodeid 前 12 位 | p2p | channel | JSON-RPC |
|---|---|---|---|---|---|
| node0 | sealer（共识） | `7d20e7258ff8` | 30300 | 20200 | 8545 |
| node1 | sealer（共识） | `53abf16c1020` | 30301 | 20201 | 8546 |
| node2 | sealer（共识） | `a7f1d925a9fb` | 30302 | 20202 | 8547 |
| node3 | sealer（共识） | `aadb6cda5c5b` | 30303 | 20203 | 8548 |
| node4 | **observer（监管）** | `b6b84d43a632` | 30304 | 20204 | 8549 |

群组 `group1`（groupId=1）、PBFT 共识、`chainId=1`；**已部署合约**：

- `ChainSIP`（核心账本）`<CORE_CONTRACT_ADDRESS>`
- `ChainSIPTransfer`（转移）`<TRANSFER_CONTRACT_ADDRESS>`

> 这两个地址写在 jar 的 `application.yml` 里（`chain.contract.*`）。**因为链是整份搬的，地址不变，所以不用改任何配置。**

---

## 2. 服务器准备

### 2.1 规格

| 项 | 最低 | 推荐 | 说明 |
|---|---|---|---|
| CPU / 内存 | 2C 4G | **4C 8G** | 5 个链节点约占 1.2 G，MySQL 0.4 G，JVM 堆 1.5 G |
| 磁盘 | 40 G | 60 G 起 | 当前全量不到 100 MB，留的是账本增长与备份空间 |
| 系统 | Ubuntu 20.04+ / Alibaba Cloud Linux 3 / CentOS 7+ | ALinux 3 | `install.sh` 同时支持 `apt` 与 `dnf/yum` |
| 带宽 | 1 Mbps 够用 | 3 Mbps | 前端首屏约 1.1 MB（element 包 gzip 后 304 KB） |

内存只有 2 G 的机器别硬上：5 个节点 + JVM 必然 OOM，`fisco-bcos` 被内核杀掉后表现为「链突然不出块」。

### 2.2 安全组（这一步配错，后面全废）

入方向只开三个：

| 端口 | 来源 | 用途 |
|---|---|---|
| 22 | 你的固定 IP `/32` | SSH（装完建议收回） |
| 80 | 0.0.0.0/0 | HTTP |
| 443 | 0.0.0.0/0 | HTTPS（配了证书再开） |

**绝对不要放行**下面这些。它们都是集群内部的进程间端口，后端和链在同机，走 `127.0.0.1` 就够：

- `30300-30304` p2p：节点互相握手，暴露等于让任意人加入你的群组
- `20200-20204` channel：SDK 通道，带客户端证书鉴权，但仍不该对外
- `8545-8549` JSON-RPC：**`sendRawTransaction` 就在这上面**，暴露等于把链的写入口交出去
- `3306` MySQL：同上

`install.sh` 只会装 nginx/MySQL 的默认配置，MySQL 也只监听 `127.0.0.1`，但**外层安全组是第一道门，别指望第二道**。

### 2.3 系统依赖

`install.sh` 自动装，手动装的话是这些：

```bash
# ALinux 3 / CentOS
dnf install -y java-1.8.0-openjdk-devel mysql-server nginx tar curl

# Ubuntu 22.04
apt-get update && apt-get install -y openjdk-8-jdk mysql-server nginx tar curl
```

> **`java -version` 必须是 1.8。** ALinux 3 的 `dnf install java-1.8.0-openjdk` 有时会把 17 一并装上并设为默认，jar 是 JDK 8 编译的，跑在 17 上会因为反射/模块限制直接崩。用 `alternatives --config java` 切回 1.8。

---

## 3. 部署顺序：为什么必须先链、再库、再后端

后端启动时会做三件事：初始化 FISCO SDK → 探测合约 → 预热钱包。**链没起，这三步全失败**，但 Spring 容器仍会启动成功（`chain.ready=false`），你打开网页能看到登录框、能登录、能查询，只有一发交易才 500 —— 这种"半死"状态最容易误判成配置问题。

所以顺序固定为：

```
① 链（fisco-chain）  →  ② 数据库（MySQL）  →  ③ 后端（chainsip-backend）  →  ④ nginx
```

下面 §4 是链，§5-§7 依次是库、后端、nginx。

---

## 4. 区块链部分怎么部署

这是本文的重点。先讲清楚这东西到底是什么形状，再讲怎么搬。

### 4.1 你要搬的是什么

FISCO BCOS 2.x 的节点是**纯用户态进程**：`fisco-bcos` 一个可执行文件 + 一个配置目录，不依赖 Docker、不依赖 MySQL、不依赖任何系统服务。一个节点目录长这样：

```
/opt/fisco/nodes/127.0.0.1/node0/
├── config.ini              端口、证书路径、日志级别
├── conf/
│   ├── group.1.genesis     创世块（决定 chainId、共识算法、预编译合约）
│   ├── group.1.ini         群组配置
│   ├── node.crt / node.key 节点身份证书（p2p 层）
│   ├── ca.crt              链根 CA
│   ├── channel_cert/       SDK 通道证书（channel 层）
│   └── node.nodeid         节点 ID（由 node.key 派生，公钥 128 hex）
├── data/                   账本数据（RocksDB）+ 区块
├── log/                    日志
├── fisco-bcos              可执行文件（软链到上一级）
└── start.sh / stop.sh
```

**结论：整条链 = 一个目录。** 把它 `tar` 走，解到新机器的同一路径，就能原地继续运行——合约地址、账号地址、区块高度、交易历史全部不变。

这就是为什么推荐**搬链**而不是重建链：

| | 搬已有链 | 重建新链 |
|---|---|---|
| 合约地址 | `0x04d6…0374` 不变，配置一行不用改 | 全变，要改 `application.yml` 或环境变量 |
| 数据库 `cs_wallet` 里的地址 | 继续有效 | 全部对不上，账号得重建 |
| 演示数据 / 已缴流水 | 全在 | 从零开始 |
| 耗时 | 5 分钟 | 20~30 分钟 |
| 风险 | 快照必须停机打（见下） | 需要 console 部署合约，容易卡在证书上 |

### 4.2 快照为什么必须停机打

节点的 `data/` 是 RocksDB，运行时文件句柄打开、WAL 在写。**运行中 `tar` 出来的是撕裂状态**：可能缺某个 SST 文件、或者 manifest 指向不存在的文件，新机器上启动会报 `Corruption: ...` 或者干脆静默不出块。

所以正确姿势是**停链 → 打包 → 启链**。这一步在打包阶段已经做过（`deploy/pack.sh --chain-tar` 复用的就是这份快照），记录如下：

```
停链后剩余进程: 0
打包:          13 MB /tmp/chainsip-chain.tar.gz，顶层目录 nodes/
重新启链:      5 个进程
重启后区块:    0x16  ← 与停链前一致，说明账本没被破坏
```

> 如果你自己重新出包，用仓库里的 `.workbuddy/snapshot_chain.sh`（就是上面这套动作），或手工：
> `bash stop_all.sh` → `cd ~/fisco && tar czf /tmp/chain.tar.gz --exclude='nodes/*/log/*' nodes` → `bash start_all.sh`

### 4.3 落盘

包内快照的**顶层目录是 `nodes/`**，所以必须解到 `/opt/fisco`，解出来才是 `/opt/fisco/nodes/127.0.0.1`：

```bash
mkdir -p /opt/fisco
tar xzf chainsip-chain.tar.gz -C /opt/fisco
ls /opt/fisco/nodes/127.0.0.1
# 期望看到：node0 node1 node2 node3 node4 sdk fisco-bcos start_all.sh stop_all.sh cert
```

> 解到 `/opt/fisco/nodes/` 就会变成 `/opt/fisco/nodes/nodes/127.0.0.1`，`chain-control.sh` 找不到节点。这是最常见的路径错误。

### 4.4 权限与属主

节点进程以 `fisco` 用户运行（`install.sh` 已建好这个用户）。这里要做的是**改属主**，不是改可执行位。

快照是在 Linux 上 `tar` 的，权限位本身是保留的（`tar tzvf` 能看到 `-rwxr-xr-x nodes/127.0.0.1/fisco-bcos`），但**属主是打包机的用户**（`user/user`），到服务器上必须换成 `fisco`，否则节点写 `data/` 和 `log/` 会 `Permission denied`：

```bash
chown -R fisco:fisco /opt/fisco
```

顺手确认一下可执行位还在（经过 unzip、某些网盘或 Windows 中转传到服务器的文件会丢）：

```bash
ls -l /opt/fisco/nodes/127.0.0.1/fisco-bcos
# 应该是 -rwxr-xr-x…；不是的话补一刀
chmod +x /opt/fisco/nodes/127.0.0.1/fisco-bcos /opt/fisco/nodes/127.0.0.1/node*/start.sh /opt/fisco/nodes/127.0.0.1/node*/stop.sh
```

缺可执行位的表现是 `chain-control.sh` 报「找不到节点程序」或 `Permission denied`，但文件明明在那儿。属主错的表现是节点起来了、几秒后退出，日志里是写文件失败。

### 4.5 检查配置里有没有旧环境的残留

节点配置里**不应该有绝对路径**（FISCO 的 `config.ini` 全部用相对路径），但值得花 10 秒确认没有写死旧 IP：

```bash
cd /opt/fisco/nodes/127.0.0.1
grep -rn "/home/\|192\.168\." node*/config.ini node*/conf/*.ini 2>/dev/null
```

没有输出就是干净的。

`listen_ip` 是 `0.0.0.0`（5 个节点都是），这**不是**问题：节点之间用 `127.0.0.1:3030x` 互连，`0.0.0.0` 只是监听所有本地网卡。安全组不放行这些端口，外部就连不上，实际效果等同于只允许本机。

### 4.6 启动

两种方式，选一种：

```bash
# 方式 A：脚本（推荐，会等到出块才返回）
/opt/chainsip/deploy/chain-control.sh start

# 方式 B：原生命令
cd /opt/fisco/nodes/127.0.0.1 && bash start_all.sh
```

`chain-control.sh` 干的事：按 `node0..node4` 顺序逐个 `start.sh` → 每起一个等 2 秒 → 最后轮询 `getBlockNumber`，**最多等 90 秒**，出块才返回 0。

首次启动大约 10~20 秒。看进度：

```bash
tail -f /opt/fisco/nodes/127.0.0.1/node0/log/log_*.log
# 看到 "+++++++++++++++++++++++++++++++++++++++++++++" 和 "g:1,nodeIdx:0" 一类字样就在出块
```

### 4.7 验证：四组命令，逐个过

**① 进程数必须是 5**

```bash
pgrep -f '[f]isco-bcos -c config.ini' | wc -l     # 期望 5
```

> 方括号是有意的：`pgrep -f` 会把「正在执行这条命令的 shell」自己也算进去，写成 `[f]isco` 就不会自我匹配。另外 `ps` 里看到的路径是 `/opt/fisco/nodes/127.0.0.1/node0/../fisco-bcos`（`start.sh` 用 `$SHELL_FOLDER/../fisco-bcos` 启动），带 `nodeN/..` 是正常的，不是路径错了。

少于 5 就是某个节点没起来，去 `nodeN/log/` 看最后一个日志文件。

**② 区块高度在推进**（隔 10 秒看两次，数字必须变大）

```bash
rpc() { curl -s -X POST -H 'Content-Type: application/json' \
  --data "{\"jsonrpc\":\"2.0\",\"method\":\"$1\",\"params\":[1],\"id\":1}" \
  http://127.0.0.1:8545; echo; }

rpc getBlockNumber      # {"result":"0x16"}   十六进制，0x16 = 22
sleep 10
rpc getBlockNumber      # 应该比上一次大
```

> 高度不变有两种情况：**没有交易且开了空块抑制**（本链配置是持续出块，正常应一直涨）；或者 PBFT 进了换视图循环，去 `node0/log` 搜 `viewchange`。

**③ 群组里必须是 5 个节点**

```bash
rpc getGroupPeers | grep -o '"[0-9a-f]\{128\}"' | wc -l    # 期望 5
```

这条返回的 5 个 nodeid 应当和 §1.4 表格里的前缀一一对上。只有 4 个说明 node4 没进群（它是最容易被漏掉的，因为它是观察节点）。

**④ 区分"4 共识 + 1 观察"**

JSON-RPC 查不到 sealer/observer 名单（那是共识预编译合约的接口）。用后端接口看，这是最准的：

```bash
TOKEN=$(curl -s -X POST http://127.0.0.1:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"ChangeMe_123456"}' | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
curl -s http://127.0.0.1:8080/api/chain/topology -H "Authorization: Bearer $TOKEN"
# {"sealerCount":4,"observerCount":1,"nodeCount":5,"description":"4 个共识节点 + 1 个观察节点"}
```

（这条要等 §6 后端起来才能用。链刚部署完时用 ②③ 验证就够了。）

### 4.8 交给后端：SDK 的证书与配置

后端不直接读节点目录，它通过 **SDK 通道**（channel，20200 端口）连链，需要独立的 4 个文件：

```
/opt/chainsip/backend/sdk/
├── config.toml      peers = ["127.0.0.1:20200"]
├── conf/ca.crt      链根 CA
├── conf/sdk.crt     SDK 客户端证书
└── conf/sdk.key     私钥（600 权限，别提交到任何仓库）
```

这三张证书必须和链是**同一套**（由同一个链 CA 签发）。所以：**直接复制包里的 `backend/sdk/`，不要自己重新签**。混用不同批次签发的证书，SDK 握手会报 `SSL handshake failed` 或 `certificate verify failed`。

```bash
cp -r backend/sdk /opt/chainsip/backend/
chmod 600 /opt/chainsip/backend/sdk/conf/sdk.key
chown -R chainsip:chainsip /opt/chainsip/backend/sdk
```

`config.toml` 里只需关心一行（打包时已自动改好）：

```toml
[network]
peers = ["127.0.0.1:20200"]     # 节点 channel 端口，不是 p2p 的 30300
```

> **端口别搞混**：`20200` 是 channel（SDK 用），`30300` 是 p2p（节点之间用），`8545` 是 JSON-RPC（控制台用）。写错成 30300 的典型报错是 `connect failed` / 一直重连。

**关于原生库**：SDK 的签名运算走 JNI 原生库。`webank-blockchain-java-crypto` 这个 jar 里同时带了 `.dll` / `.so` / `.dylib`，启动时按操作系统自动选。包里的 `backend/sdk/native/libffi_java_sdk.dll` 是 Windows 版，**Linux 上不会被用**；反过来，启动时会把 Linux 的 `.so` 解压到**工作目录下的 `sdk/native/`**，所以：

```bash
# 这个目录必须可写，且工作目录必须是 /opt/chainsip/backend
chown -R chainsip:chainsip /opt/chainsip/backend/sdk
```

不可写的话，Spring 启动阶段就会抛 `ExceptionInInitializerError: Failed to load library`，而且报错主体是 `native.lock (拒绝访问)`，看着完全不像权限问题。

### 4.9 常驻与开机自启

```bash
systemctl daemon-reload
systemctl enable --now fisco-chain
systemctl status fisco-chain --no-pager
```

`fisco-chain.service` 是 `Type=forking` + `RemainAfterExit=yes`，`ExecStart` 调 `/opt/chainsip/deploy/chain-control.sh start`（该脚本由 `install.sh` 从包内 `deploy/` 复制过去）。

> **如果你在 §4.6 选了方式 B（手工 `start_all.sh`）**，交给 systemd 之前要先停掉：
> `cd /opt/fisco/nodes/127.0.0.1 && bash stop_all.sh`，否则 `chain-control.sh start` 会检测到「已有节点在运行」而拒绝启动，`systemctl` 直接报 `Job for fisco-chain.service failed`。
> 之后统一用 `systemctl` 管，**不要再手工 `start_all.sh`**，否则 systemd 管不到那批进程。

```bash
systemctl stop fisco-chain      # 优雅停止 5 个节点
systemctl restart fisco-chain   # 重启（约 20 秒）
/opt/chainsip/deploy/chain-control.sh status   # 进程数 + 最新区块
```

### 4.10 链的备份与体检

**备份**：只备 `/opt/fisco/nodes/127.0.0.1/node*/data` 是不够的（`conf/` 里的证书和 `group.1.genesis` 丢了，链就认不回来了）。正确做法是**冷备整目录**：

```bash
systemctl stop fisco-chain
tar czf /backup/chain-$(date +%F).tar.gz -C /opt fisco
systemctl start fisco-chain
```

或者更省事的**快照策略**：云盘快照 + MySQL 数据目录 `var/lib/mysql` 放进**同一个快照组**。链是权威账本，`cs_chain_tx_log` 只是索引，**只恢复一个会出现「链上有这笔缴费、链下索引查不到」的错位**，到时候只能手工补索引。

**体检**（每周或出问题先跑这个）：

| 检查 | 命令 | 正常值 |
|---|---|---|
| 进程 | `pgrep -f '[f]isco-bcos -c config.ini' \| wc -l` | 5 |
| 出块 | `rpc getBlockNumber` | 持续增长 |
| 群组 | `rpc getGroupPeers` | 5 个 nodeid |
| 共识视图 | `rpc getConsensusStatus` | `leaderFailed:false`，`currentView` 增长缓慢 |
| 磁盘 | `df -h /opt` | 剩余 > 20% |
| 内存 | `free -m` | 5 节点常驻约 1.2 G |
| 日志错误 | `grep -ci 'error' node0/log/log_*.log` | 个位数；持续增长要查 |

### 4.11 附录：不搬旧链，重建一条新的

只有这两种情况才该重建：**旧链的证书/私钥已经不可用了**，或者你**就是想要一条干净的链**做演示。代价见 §4.1 的对照表。

```bash
# 1) 装 fisco 二进制（约 15 MB，需要能访问 github）
mkdir -p ~/fisco && cd ~/fisco
# 从包内链目录里拿现成的可执行文件也行，省一次下载：
#   tar xzf chainsip-chain.tar.gz -C /tmp && cp /tmp/nodes/127.0.0.1/fisco-bcos ~/fisco/

# 2) 生成 4 个共识节点（p2p 30300 / channel 20200 / rpc 8545 起递增）
bash build_chain.sh -l 127.0.0.1:4 -p 30300,20200,8545

# 3) 启动并用控制台确认出块
cd nodes/127.0.0.1 && bash start_all.sh
#    控制台需要单独下载（节点目录里的 console.tar.gz 是坏的 404 存根，见下）
bash download_console.sh && cd console && cp -r ../nodes/127.0.0.1/sdk/conf . ...
#    进控制台后：deploy ChainSIP   /  deploy ChainSIPTransfer

# 4) 加第 5 个观察节点（监管）
#    证书从 nodes/cert/agency 与 nodes/cert/agency/channel 签，端口 30304/20204/8549
#    控制台执行：addObserver <node4 的 nodeid>

# 5) 把新合约地址写进后端环境变量
vi /etc/chainsip/backend.env
#   CHAINSIP_CORE_ADDRESS=0x...
#   CHAINSIP_TRANSFER_ADDRESS=0x...
systemctl restart chainsip-backend
```

> **`console.tar.gz` 只有 52 字节**，内容是 `{"status":404,...}`——当初节点目录里下载失败留下的坏文件。要控制台请执行 `download_console.sh`（需要外网），或从别的机器拷一份。文档里所有验证都用 JSON-RPC + 后端接口完成，不依赖控制台。
>
> 加观察节点的完整脚本见仓库 `scripts/add-observer-node.sh`，原理与排错见 `docs/CHAIN-TOPOLOGY.md`。

---

## 5. 数据库

`install.sh` 已经建好库、建好用户、生成随机密码写进 `/etc/chainsip/backend.env`。你只需要导入数据。

```bash
mysql chainsip < sql/chainsip.sql
```

导入后核对行数（§1.4 的四个数字）。若报 `ERROR 1045`，说明 `install.sh` 那步建用户失败，手工补：

```bash
mysql -u<DB_ADMIN_USER> -p
CREATE DATABASE IF NOT EXISTS chainsip DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
CREATE USER IF NOT EXISTS 'chainsip'@'127.0.0.1' IDENTIFIED BY '<这个密码要和 backend.env 里一致>';
GRANT ALL PRIVILEGES ON chainsip.* TO 'chainsip'@'127.0.0.1';
FLUSH PRIVILEGES;
```

**主密钥必须沿用旧值**（本节最重要的一条）：

```bash
grep CHAINSIP_CRYPTO_MASTER_KEY /etc/chainsip/backend.env
# 迁移已有数据库 → 必须是 chainsip-dev-master-key-change-me-before-production
```

`cs_institution_key` 里存的是机构链账户私钥的 AES-256-GCM 密文，密钥就是这把。`install.sh` 首次安装会**随机生成**一把；如果你导入的是旧库而密钥是新随机的，现象是：**能登录、能查询、一切正常，但一发交易就 500，日志里是 `AEADBadTagException` 或「私钥解密失败」**。改回旧值后 `systemctl restart chainsip-backend` 即可。

反过来说，如果你导入的是**空库**（自己重建链那种），就不要改，随机的反而更安全。

---

## 6. 后端

```bash
cp backend/chainsip-backend.jar /opt/chainsip/backend/
cp -r backend/sdk              /opt/chainsip/backend/
chown -R chainsip:chainsip /opt/chainsip/backend
systemctl daemon-reload
systemctl enable --now chainsip-backend
systemctl status chainsip-backend --no-pager -l
```

看启动日志（`StandardOutput` 已重定向到文件）：

```bash
tail -f /var/log/chainsip/backend.log
# 关键行：Started ChainSipApplication in x seconds
#         Chain gateway ready, group=1 block=22
```

验证：

```bash
curl -s http://127.0.0.1:8080/api/public/health | python3 -m json.tool
```

期望 `"status": "UP"`，并且：

| 字段 | 含义 | 异常时看什么 |
|---|---|---|
| `db.latencyMs` | 数据库往返延迟 | 几百毫秒说明库有问题 |
| `chain.ready` | `true` | `false` → 链没起 / 证书不对 / `CHAIN_ENDPOINT` 端口写错 |
| `chain.blockNumber` | 与 §4.7② 的数字一致 | 不一致说明后端连的是另一条链 |
| `warnings` | 配置告警（如仍在用默认 JWT 密钥） | 逐条处理，不影响 UP 判定 |

> `/api/public/health` 是免登录的，用于监控探针。它返回的是：数据库延迟、链是否就绪、当前区块高度、配置告警，以及合约地址与 `chain.peers`（这些本来就是链上公开信息，交易里都能看到）。**不返回任何私钥、密码或 JWT 密钥。** 如果你不希望连合约地址都暴露在公网，把 nginx 里 `location = /api/public/health` 限成内网访问即可。

**JVM 参数**：systemd 单元里固定 `-Xmx1536m`。4 G 内存的机器把它调到 `1024m`，否则会跟链抢内存。

---

## 7. nginx

`install.sh` 已经装好站点配置（`/etc/nginx/conf.d/chainsip.conf`），你只需要放静态文件：

```bash
mkdir -p /opt/chainsip/frontend
cp -r frontend/. /opt/chainsip/frontend/
chmod -R a+r /opt/chainsip/frontend
chown -R chainsip:chainsip /opt/chainsip/frontend
nginx -t && systemctl reload nginx
```

站点做三件事：

- `/api` → `127.0.0.1:8080`，`proxy_read_timeout 75s`（上链交易要等区块确认，默认 60s 会在慢的时候截断成 504）
- `/assets` → 长缓存（文件名带 hash，可放心 `immutable`）
- `/` → `index.html` **不缓存**，否则发版后用户拿到的还是旧入口

SELinux（只有 ALinux 系需要）：

```bash
getenforce                                   # Enforcing 才需要往下走
setsebool -P httpd_can_network_connect 1     # 否则 /api 502
chcon -R -t httpd_sys_content_t /opt/chainsip/frontend   # 否则静态文件 403
```

> 注意区别：**502 = nginx 连不上后端**（布尔值）；**403 = nginx 读不了文件**（上下文）。两者都要处理，只做一半会出现"首页 403、接口 502"的怪状态。

---

## 8. 端到端验收

按顺序过一遍，任何一项不过就停在那里查：

```bash
# 1 链：5 进程 + 出块
pgrep -f '[f]isco-bcos -c config.ini' | wc -l    # 5
curl -s -X POST -H 'Content-Type: application/json' \
  --data '{"jsonrpc":"2.0","method":"getBlockNumber","params":[1],"id":1}' http://127.0.0.1:8545

# 2 链：群组 5 节点
curl -s -X POST -H 'Content-Type: application/json' \
  --data '{"jsonrpc":"2.0","method":"getGroupPeers","params":[1],"id":1}' http://127.0.0.1:8545 | grep -o '"[0-9a-f]\{128\}"' | wc -l

# 3 库：6 张表 + 数据
mysql chainsip -e "show tables; select count(*) from cs_account;"

# 4 后端：UP 且 chain.ready=true
curl -s http://127.0.0.1:8080/api/public/health

# 5 后端：拓扑 4+1
TOKEN=$(curl -s -X POST http://127.0.0.1:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"ChangeMe_123456"}' | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
curl -s http://127.0.0.1:8080/api/chain/topology -H "Authorization: Bearer $TOKEN"

# 6 nginx：首页 200、接口通
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1/
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1/api/public/health
```

浏览器打开 `http://<公网IP>/`，`admin / ChangeMe_123456`：

- 首页四格页眉：区块高度、累计注资、累计已缴、`group 1 · 4 共识 + 1 观察`
- 「资金对账」三段恒等式后面盖「平」章
- 顶栏右侧有链网关状态（每 20 秒刷新区块高度）
- 用 `regulator_demo / ChangeMe_123456` 登录，监管大屏能看到「4 个出块节点 + 1 个观察节点」

演示账号（密码统一 `ChangeMe_123456`）：`bureau_demo`（社保局）、`sponsor_demo`（缴费单位）、`employee_demo`（职工）、`regulator_demo`（监管）。

---

## 9. 排错表

| 现象 | 根因 | 处理 |
|---|---|---|
| `chain-control.sh` 报「找不到节点程序」 | `fisco-bcos` 缺可执行位 | `chmod +x /opt/fisco/nodes/127.0.0.1/fisco-bcos` |
| 启动后 `ls` 有节点但进程只 0~2 个 | 解压路径错了一层 / 权限不足 | 确认存在 `/opt/fisco/nodes/127.0.0.1/node0`；`chown -R fisco:fisco /opt/fisco` |
| 进程 5 个但区块不动 | PBFT 换视图循环 | `grep -c viewchange node0/log/log_*.log`；多为 CPU 1 核或内存不足 |
| `getBlockNumber` 超时 | 节点还没起来就查了 | 等 20 秒；`tail node0/log/log_*.log` |
| 后端 `chain.ready=false` | 链没起 / 证书不配套 / peers 端口错 | 先确认 §4.7；再查 `sdk/config.toml` 是不是 `20200` |
| 后端启动即崩，`Failed to load library` | `sdk/native` 不可写 | `chown -R chainsip:chainsip /opt/chainsip/backend/sdk` |
| 能登录但一发交易 500，`AEADBadTagException` | 主密钥与库不匹配 | §5 改回 `chainsip-dev-master-key-change-me-before-production` |
| nginx 502 | SELinux 布尔值 / 后端没起 | `setsebool -P httpd_can_network_connect 1`；`systemctl status chainsip-backend` |
| nginx 403 | SELinux 文件上下文（`/opt` 默认 `usr_t`） | `chcon -R -t httpd_sys_content_t /opt/chainsip/frontend` |
| `duplicate default server` | 系统自带站点占着 80 | 删 `conf.d/default.conf`；ALinux 还要注释 `nginx.conf` 里的 `listen 80 default_server` |
| `java -version` 是 17 | 默认 JDK 被换 | `alternatives --config java` 选 1.8 |
| `parse Config failed` | `config.toml` 缩进/引号被编辑器改坏 | 从包里重新 `cp -r backend/sdk` |
| 首页能开但接口全 404 | `proxy_pass` 少写了斜杠或路径 | `nginx -T \| grep -A3 'location /api'` |
| 访问超时（不是 502） | 安全组 80 没放行 | 控制台加入方向 80/0.0.0.0 |
| 磁盘满了 | 节点日志没轮转 | `du -sh node*/log`；`config.ini` 里 `max_log_file_size` 调到 100 MB |

---

## 10. 日常运维速查

```bash
# 服务
systemctl status fisco-chain chainsip-backend nginx
systemctl restart chainsip-backend
journalctl -u chainsip-backend -n 100 --no-pager

# 链
/opt/chainsip/deploy/chain-control.sh status
/opt/chainsip/deploy/chain-control.sh stop / start

# 日志
tail -f /var/log/chainsip/backend.log
tail -f /opt/fisco/nodes/127.0.0.1/node0/log/log_*.log
ls /var/log/chainsip/          # 含 OOM 时的 heap dump

# 备份（链 + 库一起备，别分开）
systemctl stop fisco-chain
tar czf /backup/chain-$(date +%F).tar.gz -C /opt fisco
mysqldump -u chainsip -p chainsip > /backup/chainsip-$(date +%F).sql
systemctl start fisco-chain

# 更新版本（只换 jar 和前端，链与库不动）
systemctl stop chainsip-backend
cp ~/new/chainsip-backend.jar /opt/chainsip/backend/
cp -r ~/new/frontend/. /opt/chainsip/frontend/
chown -R chainsip:chainsip /opt/chainsip
systemctl start chainsip-backend && systemctl reload nginx
```

---

## 附：这份包是怎么做出来的

```bash
# 开发机，仓库根目录
export PATH="/c/Program Files/MySQL/MySQL Server 8.0/bin:$PATH"
bash deploy/pack.sh --chain-tar dist-pkg/chainsip-chain.tar.gz
```

`pack.sh` 依次做六件事：`mvn package` → 复制 `sdk/` 并把 `peers` 改成 `127.0.0.1:20200` → `npm run build` → `mysqldump` → 复制 `deploy/` 与 `docs/` → 打成 tar.gz，顺手把脚本行尾规整成 LF。

参数：

| 参数 | 作用 |
|---|---|
| 无 | 出 jar + 前端 + 库备份，**不含链**（服务器上重建链时用） |
| `--no-db` | 不含库备份（服务器上重建空库时用） |
| `--chain-tar <file>` | 复用已有的链快照（推荐，快照应离线打） |
| `--with-chain` | 直接从虚拟机 `<CHAIN_HOST>` 拉链目录（**运行中打不解包，仅供临时排查**） |
