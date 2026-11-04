# 部署到阿里云 ECS

本项目有 4 层：**链（FISCO BCOS 5 节点）→ MySQL 8 → Spring Boot 后端 → Vue 静态前端**。
单台 ECS 就能全放下，本文按「一台 4C8G 的 ECS、Alibaba Cloud Linux 3、迁移现有链与数据」写，
最后附「在 ECS 上重建空链」和「拆到多台」两种变体。

---

## 0. 买机器之前先看这张表

| 项 | 建议 | 说明 |
|---|---|---|
| 规格 | **4 vCPU / 8 GiB** 起 | 5 个链节点约 1.2 GiB，MySQL 约 0.4 GiB，JVM 堆 1.5 GiB，留出余量给系统与构建。2C4G 能跑但要把堆降到 1 GiB |
| 镜像 | **Alibaba Cloud Linux 3** 或 Ubuntu 22.04 | ALinux 3 装 JDK 8 最省事（`java-1.8.0-openjdk-headless` 在 appstream 里） |
| 系统盘 | 40 GiB ESSD | 链数据 + 日志 + 依赖；本项目当前整链不到 1 GiB |
| 带宽 | 按使用流量 5 Mbps 够演示 | 页面是静态资源 + JSON，1.7 MiB 首屏 |
| 地域 | 有域名并已完成 ICP 备案再选大陆；否则先用**公网 IP 直连**或选中国香港 | 大陆地域绑域名访问 80/443 必须备案 |
| 数据库 | 先用机器上的 MySQL | 想省心可换 **RDS MySQL 8**（见附录 B），改 5 个环境变量即可 |

## 1. 端口：只开三个，其余千万别开

阿里云控制台 → 实例 → 安全组 → 配置规则 → **入方向**：

| 端口 | 来源 | 用途 |
|---|---|---|
| 22 | 你的公网 IP/32 | SSH |
| 80 | 0.0.0.0/0 | HTTP（nginx） |
| 443 | 0.0.0.0/0 | HTTPS（nginx） |

**绝对不要对公网放行**：`20200-20204`（SDK channel）、`30300-30304`（节点 p2p）、
`8545-8549`（节点 JSON-RPC）、`3306`（MySQL）。这些是集群内部通信口，
暴露出去等于把链的读写入口和数据库交给全网——尤其 JSON-RPC 上带 `sendRawTransaction`。
后端与链在同一台机器，走 `127.0.0.1` 就够。

> 安全组的「出方向」默认全放行，不用改（装包、同步时间要用）。

## 2. 登录机器，跑初始化

```bash
ssh <DEPLOY_USER>@<ECS_PUBLIC_IP>
```

本机（开发机）先打一个部署包，把这个包传上去：

```bash
# 开发机上，仓库根目录。--with-chain 会把虚拟机上的链目录一起打进来
bash deploy/pack.sh --with-chain
# 产物：dist-pkg/chainsip-deploy-20261026-0312.tar.gz

scp dist-pkg/chainsip-deploy-*.tar.gz <DEPLOY_USER>@<ECS_PUBLIC_IP>:~/
```

服务器上：

```bash
cd ~ && tar xzf chainsip-deploy-*.tar.gz && cd chainsip-deploy-*/
bash deploy/install.sh
```

`install.sh` 会做 6 件事，幂等，可以重复跑：

1. 装 `java-1.8.0-openjdk-headless`、`mysql-server`、`nginx`、`curl`；
2. 建运行用户 `chainsip` / `fisco` 与目录 `/opt/chainsip`、`/opt/fisco`、`/var/log/chainsip`；
3. 建库 `chainsip` + 数据库用户 `chainsip@127.0.0.1`（密码随机生成）；
4. 生成 `/etc/chainsip/backend.env`：**数据库密码、JWT secret、机构私钥主密钥全是随机的**（权限 600）；
5. 装 nginx 站点 `/etc/nginx/conf.d/chainsip.conf`，并让开系统自带的 `default_server`；
6. 装 systemd 单元 `fisco-chain.service`、`chainsip-backend.service` 并设为开机自启。

## 3. 恢复链（这是能不能保留数据的关键）

```bash
tar xzf chainsip-chain.tar.gz -C /opt/fisco          # 得到 /opt/fisco/nodes/127.0.0.1/node0..4
chmod +x /opt/fisco/nodes/127.0.0.1/fisco-bcos
chown -R fisco:fisco /opt/fisco
systemctl start fisco-chain                          # 依次拉起 node0→node4，并等出块
systemctl status fisco-chain --no-pager
```

为什么整份目录搬过去就能直接用，不需要改配置：

- `config.ini` 里 `listen_ip` / `channel_listen_ip` 已经是 `0.0.0.0`，IP 变了照样监听；
- p2p 的 `node.0..3=127.0.0.1:3030x` 是**同机端口**，迁移后依然是同机，拓扑不变（含 node4 这条观察节点）；
- `data/group1` 里是完整账本：合约 `ChainSIP` / `ChainSIPTransfer` 的代码与状态都在里面，
  所以**合约地址不用重新部署**，`0x04d6…0374` / `0x374e…433d` 继续有效；
- 节点证书与 nodeid 由各自的 `node.key` 决定，与主机 IP、主机名无关，不用重签。

验证：

```bash
curl -s -X POST http://127.0.0.1:8545 -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","method":"getBlockNumber","params":[1],"id":1}'; echo
# {"id":1,"jsonrpc":"2.0","result":"0x16"}   ← 区块高度与你本机一致
/opt/chainsip/deploy/chain-control.sh status
```

## 4. 恢复数据库

```bash
mysql chainsip < sql/chainsip.sql
mysql chainsip -e "select count(*) from cs_account; select count(*) from cs_payment;"
```

> ⚠️ **主密钥必须沿用旧值，否则机构私钥全都解不开。**
> `cs_institution_key` 里存的是机构链账户私钥，用 `CHAINSIP_CRYPTO_MASTER_KEY` 做 AES-256-GCM 加密。
> 迁移已有数据时，把 `/etc/chainsip/backend.env` 里的这一项改成开发环境那把：
>
> ```
> CHAINSIP_CRYPTO_MASTER_KEY=chainsip-dev-master-key-change-me-before-production
> ```
>
> 不换的话，后端能启动、能登录，但一发交易就报私钥解密失败（因为这个主密钥解不开旧密文）。
> 想真正换掉它，只能在**空库 + 新链**场景下重建账号（见附录 A），或者用
> `POST /api/admin/institution-keys` 在旧主密钥生效期间把每把私钥重新导入一次。

## 5. 放后端（jar + SDK 证书）

```bash
mkdir -p /opt/chainsip/backend
cp backend/chainsip-backend.jar /opt/chainsip/backend/
cp -r backend/sdk /opt/chainsip/backend/            # 与 jar 同级：sdk/config.toml + sdk/conf/*.crt|key
chown -R chainsip:chainsip /opt/chainsip/backend
```

`backend/sdk/config.toml` 里 `peers` 已被 `pack.sh` 改写成 `["127.0.0.1:20200"]`，确认一下：

```bash
grep -n '^peers' /opt/chainsip/backend/sdk/config.toml
```

三个证书文件（`ca.crt`/`sdk.crt`/`sdk.key`）是**这条链**的 SDK 证书，必须与 `chainsip-chain.tar.gz`
里的链成套。换链就要换证书，否则 TLS 握手失败、日志里是 `SSLHandshakeException`。

启动：

```bash
systemctl start chainsip-backend
systemctl status chainsip-backend --no-pager
tail -f /var/log/chainsip/backend.log
curl -s http://127.0.0.1:8080/api/public/health | head -c 400; echo
```

`/api/public/health` 返回里要看到 `"db":{"ok":true,...}` 和 `"chain":{"ready":true,...}`，
且 `blockNumber` 在涨。`ready:false` 时返回的 `message` 会写清是证书、合约地址还是端口问题。

## 6. 放前端 + nginx

```bash
mkdir -p /opt/chainsip/frontend
cp -r frontend/. /opt/chainsip/frontend/
chown -R chainsip:chainsip /opt/chainsip/frontend

nginx -t && systemctl reload nginx
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1/      # 200
```

`/etc/nginx/conf.d/chainsip.conf` 已包含：静态资源根目录、`/api/` 反代到 `127.0.0.1:8080`、
gzip、`/assets/` 长缓存、`index.html` 不缓存、`proxy_read_timeout 75s`（上链交易 15s 超时留足余量）。

浏览器打开 `http://<ECS公网IP>/`，用 `admin / ChangeMe_123456` 登录，
首页页眉第四格应显示 **`group 1 · 4 共识 + 1 观察`** —— 说明链、后端、前端三层都通了。

## 7. 验收清单

按顺序打勾，任一项不过就停下看下一节：

```bash
# 1 链：5 个进程，区块高度在涨
pgrep -fc 'fisco-bcos -c config.ini'                 # 5
/opt/chainsip/deploy/chain-control.sh status

# 2 共识拓扑：4 出块 + 1 观察
TOKEN=$(curl -s -X POST http://127.0.0.1:8080/api/auth/login \
  -H 'Content-Type: application/json' -d '{"username":"admin","password":"ChangeMe_123456"}' \
  | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
curl -s http://127.0.0.1:8080/api/chain/topology -H "Authorization: Bearer $TOKEN"; echo

# 3 数据库
mysql chainsip -e "select id,username,role from cs_account order by id;"

# 4 对外：首页 200，接口 401（未带 token 是正常的）
curl -s -o /dev/null -w 'page:%{http_code}\n' http://127.0.0.1/
curl -s -o /dev/null -w 'api:%{http_code}\n'  http://127.0.0.1/api/admin/accounts

# 5 开机自启
systemctl is-enabled fisco-chain chainsip-backend nginx
```

## 8. 域名与 HTTPS（可选）

1. 域名解析到 ECS 公网 IP（A 记录）；大陆地域先完成 **ICP 备案**，否则 80 端口会被拦。
2. 阿里云控制台申请免费 DV 证书，下载 **nginx 版**，把 `.pem` / `.key` 传到
   `/etc/nginx/ssl/chainsip.pem|key`。
3. 在 `chainsip.conf` 的 server 块里加：

```nginx
listen 443 ssl http2;
ssl_certificate     /etc/nginx/ssl/chainsip.pem;
ssl_certificate_key /etc/nginx/ssl/chainsip.key;
ssl_protocols TLSv1.2 TLSv1.3;
add_header Strict-Transport-Security "max-age=31536000" always;
```

4. 另起一个 80 的 server 只做跳转：`return 301 https://$host$request_uri;`
5. `nginx -t && systemctl reload nginx`。

> 如果是答辩现场演示、没有域名，用 IP + 80 直接访问就行，**不需要备案**（备案只针对域名绑定）。

## 9. 日常运维

| 场景 | 命令 |
|---|---|
| 改配置/密钥 | 编辑 `/etc/chainsip/backend.env` → `systemctl restart chainsip-backend` |
| 看后端日志 | `tail -f /var/log/chainsip/backend.log` |
| 看链日志 | `tail -f /opt/fisco/nodes/127.0.0.1/node0/log/log_*.log` |
| 停整站 | `systemctl stop chainsip-backend fisco-chain nginx` |
| 升级后端 | 换 jar → `systemctl restart chainsip-backend`（重启前先做云盘快照） |
| 升级前端 | `rsync -a --delete frontend/ /opt/chainsip/frontend/`，无需 reload nginx |
| 备份 | `mysqldump ... chainsip > ~/bak/$(date +%F).sql` + 云盘自动快照策略 |
| 灾难恢复 | 用快照回滚系统盘；链数据在 `/opt/fisco`，库在 `/var/lib/mysql`，两者**必须一起回滚**，否则链与链下索引会错位 |

**链与库要一起备份**：链上账本是权威，链下 `cs_chain_tx_log` / `cs_chain_event` 只是索引，
只回滚其中一个会出现"链上有这笔缴费、链下索引没有"的错位（对账页面能看出来，但排查很费时间）。

## 10. 故障排查

| 现象 | 原因与处理 |
|---|---|
| `nginx` 起不来：`duplicate default server` | 系统自带站点还占着 80。`rm /etc/nginx/sites-enabled/default`；ALinux 改 `/etc/nginx/nginx.conf` 里那两行 `listen 80 default_server`（`install.sh` 已处理） |
| 页面打开是 502 | 后端没起或崩了：`systemctl status chainsip-backend`、`journalctl -u chainsip-backend -n 50` |
| 502 但后端在跑 | SELinux：`setsebool -P httpd_can_network_connect 1` |
| `/api/public/health` 里 `chain.ready=false` | 看返回的 `message`：多半是节点没起（`chain-control.sh status`）、`peers` 端口不是 20200、或 SDK 证书与链不配套 |
| 后端启动报 `parse Config ... failed` | `sdk/config.toml` 里所有值必须带引号；文件保持 ASCII |
| 登录报 500 / `Table doesn't exist` | 库没导入或连错库：`mysql chainsip -e 'show tables;'`，确认 `CHAINSIP_DB_*` |
| 发交易报私钥解密失败 | `CHAINSIP_CRYPTO_MASTER_KEY` 与库里密文不是同一把（见 §4） |
| 浏览器能开页面、接口 401 循环 | 时间不同步导致 JWT 过期判断异常：`timedatectl set-ntp true` |
| 内存吃紧被 OOM kill | 5 个节点 + JVM 超了物理内存：加 swap（`fallocate -l 4G /swapfile`）或把 `-Xmx` 降到 1g |

---

## 附录 A：不迁移，在 ECS 上重建一条 5 节点链

适合想要"干净环境"或换掉演示数据的情况。**合约地址会变，账号要重建。**

```bash
# 1) 建链（4 共识 + 1 观察，与本机拓扑一致）
mkdir -p /opt/fisco && cd /opt/fisco
curl -#LO https://github.com/FISCO-BCOS/FISCO-BCOS/releases/download/v2.11.0/build_chain.sh
bash build_chain.sh -l "127.0.0.1:5" -p 30300,20200,8545 -o nodes -e /opt/fisco/fisco-bcos
chmod +x nodes/127.0.0.1/{start_all.sh,stop_all.sh,fisco-bcos}
bash nodes/127.0.0.1/start_all.sh
curl -s -X POST http://127.0.0.1:8545 -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","method":"getBlockNumber","params":[1],"id":1}'

# 2) 把 node4 从共识降为观察节点（与本机做法一致，见 docs/CHAIN-TOPOLOGY.md）
cd /opt/fisco/console 2>/dev/null || { echo '需要先下载 console 2.11.0'; }
printf 'removeNode <node4的nodeid>\n' | ./start.sh      # 先从 sealer 摘掉
printf 'addObserver <node4的nodeid>\n' | ./start.sh     # 再挂成观察节点

# 3) 部署合约、把新地址写进 backend.env，再空库启动后端（schema.sql 会自动建表并创建 admin）
```

`docs/CHAIN-TOPOLOGY.md` 里 `scripts/add-observer-node.sh` 的做法同样适用于这里：
只要链已经跑起来，加节点不需要停链。

## 附录 B：拆到多台（更接近生产）

| 组件 | 放哪 | 说明 |
|---|---|---|
| 链 5 节点 | 2 台 ECS（3+2）或 5 台 | p2p 走**内网 VPC**，`config.ini` 里的 `node.x` 换成内网 IP；观察节点可以放到"监管方"那台 |
| MySQL | 阿里云 **RDS MySQL 8** | 白名单填 ECS 内网 IP；后端只需改 `CHAINSIP_DB_HOST` |
| 后端 | 1 台 ECS | 只开 8080 给负载均衡/nginx |
| 前端 | OSS + CDN 或 nginx | 静态资源放 OSS 最省；`/api/` 需同域或配 CORS |

拆分后 **不要用公网 IP 连链的 channel 端口**：channel 走 TLS 但端口暴露在公网仍是风险，
正确做法是 ECS 与链节点同 VPC 内网互通，安全组只允许内网网段。
