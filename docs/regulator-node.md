# 监管方节点（Observer）接入说明

> 本文记录 chain-cert 项目为监管方搭建的独立链上账本入口：**FISCO BCOS 观察节点（observer）**。
> 观察节点只同步全部区块数据，**不参与 PBFT 共识、不打包出块**，监管方通过它获得一份可独立校验的完整账本副本，全程不经运营方后端。

## 1. 架构

```
┌─────────────── 运营方域 ───────────────┐      ┌──────── 监管方域 ────────┐
│                                        │      │                          │
│  node0-3（PBFT 共识节点，打包出块）      │ P2P  │  node4（observer 观察节点 │
│  后端 Java(8080) + WeBASE(5002)        │◄────►│  只同步区块，不出块）      │
│  主控制台 ~/fisco/console（连 20200/01）│ 同步 │  监管控制台               │
│                                        │      │  ~/fisco/console-regulator│
│                                        │      │  （只连 20204）           │
└────────────────────────────────────────┘      └──────────────────────────┘
```

- 共识列表（`getSealerList`）始终只有 node0-3，PBFT 出块阈值不受 node4 影响；
- node4 出现在 `getObserverList`，实时跟随主链块高（已验证：主链发交易后 observer 块高同步 +1）；
- 监管方数据路径：自己的控制台 → 自己的节点，运营方后端无法选择性转发或篡改展示层。

## 2. 节点信息

| 项 | 值 |
|---|---|
| 链目录 | `~/fisco/nodes_proj/<CHAIN_HOST>/`（`build_chain.sh -l <CHAIN_HOST>:4 -R -o nodes_proj`） |
| 节点目录 | `~/fisco/nodes_proj/<CHAIN_HOST>/node4` |
| P2P / Channel / JSON-RPC 端口 | 30304 / **20204** / 8549 |
| nodeId | `<OBSERVER_NODE_ID>` |
| 监管控制台 | `~/fisco/console-regulator`（config.toml peers 仅 `<CHAIN_HOST>:20204`） |
| 启停 | `cd node4 && bash start.sh` / `bash stop.sh`（独立于 start_all.sh，需单独启动） |

## 3. 扩容步骤复盘（2.x 手动扩容）

0. **建链时加 `-R`**：`build_chain.sh -l <IP>:4 -e <binary> -o nodes_proj -R`。`-R` 让 channel 走 ECDSA 证书（与 P2P 同一 CA），避开 RSA channel CA 缺 `CA:TRUE` 扩展导致 java-sdk 报 `General OpenSslEngine problem` 的老问题；同时端口监听 `0.0.0.0`，Windows 后端可直接连。
   另外 build_chain 生成的**根 CA 仍缺 `CA:TRUE` 扩展**，需用同一把 `cert/ca.key` 重签并替换全部 `ca.crt`（含 `cert/ca.crt`、各节点 `conf/ca.crt`、`sdk/ca.crt`、控制台 `conf/ca.crt`），否则 java-sdk PKIX 校验拒绝信任锚。
1. **生成证书**（未改动 node0-3 任何文件）：
   - `openssl ecparam -name secp256k1 -genkey` 生成 node.key，CSR（`/CN=node4/O=fisco-bcos/OU=node`）用 `nodes_proj/cert/agency/agency.{crt,key}` 签发，**必须带扩展** `basicConstraints=critical,CA:FALSE`、`keyUsage=digitalSignature,nonRepudiation,keyEncipherment`（FISCO 握手时会解析 CA basic，缺扩展报 `Get ca basic failed` / `get nodeID failed`）；
   - `node.crt` 必须是**三段捆绑**：叶子 + agency + 根（`cat leaf agency.crt ca.crt > node.crt`），只发单叶子会被对端以 unknown ca 拒绝；
   - `channel_cert/` 在 `-R` 模式下沿用同一套 ECDSA 证书；
   - `node.nodeid` = node.key 公钥去掉 `04` 前缀的 64 字节 hex（128 字符）。
2. **搭目录**：`cp -r node0 node4`，替换全部证书，**清空 data/**（避免继承旧节点状态），改 `config.ini` 端口 30304/20204/8549，peers（`node.0~node.4=`）指向 node0-3 及自身。
3. **启动并提升**：`bash node4/start.sh` 后主控制台 `addObserver <nodeId>`；observer 列表变更经 PBFT 共识生效。
4. **自建 SDK 证书必须转 PKCS#8**：`openssl pkcs8 -topk8 -nocrypt`，否则 java-sdk 报 `Input stream does not contain valid private key`（控制台自带的 sdk.key 本就是 PKCS#8）。

## 4. 监管方接入方法

**监管方专属证书包**（TLS 身份 `CN=regulator`，与运营方 SDK 证书区分，由同一 channel CA 签发）：

- 虚拟机：`~/fisco/nodes_proj/sdk-regulator/`（`ca.crt / sdk.crt / sdk.key`，sdk.key 已转 PKCS#8）
- 项目仓库：`sdk-regulator/`（同三件套 + `config.toml` 模板，peers 只指向 node4 的 20204）
- 虚拟机上的监管控制台 `~/fisco/console-regulator` 已改用该专属证书（已验证连通，块高同步）

监管方在**自己的机器**上接入：

1. 使用 `sdk-regulator/` 里的三件套证书放入 SDK/控制台的 `conf/`；
2. 控制台/Java SDK 的 `config.toml` peers 只填监管节点：`peers=["<node4主机IP>:20204"]`；
3. 常用对账命令（控制台）：

```bash
bash console.sh getBlockNumber          # 块高
bash console.sh getSyncStatus           # 同步状态
bash console.sh getSealerList           # 应只有 node0-3（证明监管方不在共识内）
bash console.sh getObserverList         # 应含 node4 自身
# 平台总账（只读）：totalOverallRights = totalCityPool + totalEscrowedOverall
bash console.sh call ChainSIP <core地址> getSystemStats
# 在途托管（转移放行后、接收前）：
bash console.sh call ChainSIP <core地址> getEscrow <职工地址>
```

4. 更底层校验：监管方可直接 `getBlockByNumber` 遍历原始区块、核对事件日志，账本完整性由链保证，不依赖任何 API。

## 5. 边界说明

- observer **不出块、不投票**，随时可加可撤（`removeObserver`），不影响共识安全；
- 监管方节点能看到链上**全部明文数据**——这正是监管需求；业务敏感场景可另议权限分群（FISCO 群组机制）；
- 当前 REGULATOR 链上角色（只读查询门禁）与节点层监督是**互补的两层**：角色层管"业务系统里谁能查什么"，节点层保证"监管方手里有一份运营方改不了的原始账本"。


## 6. 本次环境扩容记录（2026-11-03，WSL2 本机）

> 上面 1–5 节是设计口径；这里是本机实际执行时用到的路径与命令，二者环境不同（本机是 127.0.0.1 单机链）。

| 项 | 本机实际值 |
|---|---|
| 链目录 | `~/fisco/nodes/127.0.0.1/`（node0–node3） |
| 新节点目录 | `~/fisco/nodes/127.0.0.1/node4` |
| 端口 | P2P 30304 / Channel 20204 / JSON-RPC 8549 |
| nodeId | `ef7028fd4d4f1e4bae3520558137c2fae82c89204b0714aed2a4c1aa50293b512218c8ece6a508c1034cba7f819fb6fd6f24ba11fe7a47d7709b420854b397e9` |
| 证书 CA | `~/fisco/nodes/cert/`（agency + agency/channel） |

### 6.1 执行步骤

1. 生成 node4 目录（脚本，已修一处证书扩展问题）：
   ```bash
   bash scripts/add-observer-node.sh   # 在 WSL 里以 root 执行
   ```
   - 脚本复制 node0 的目录结构、清空 data/log、改端口与 peers；
   - **channel 证书的 CA 没有 SKI**，扩展里只能写 `subjectKeyIdentifier=hash`，
     写 `authorityKeyIdentifier=keyid` 会报 `unable to get issuer keyid`（脚本已修）。
2. 启动新节点（**不在 start_all.sh 里，需要单独启**）：
   ```bash
   cd ~/fisco/nodes/127.0.0.1/node4 && bash start.sh
   ```
3. 确认 P2P 已连上 node0–node3（`ss -tnp | grep 3030`，应看到 4 条 ESTAB），
   此时它的 RPC 还会报 `Don't send request to this node who doesn't belong to the group`，属正常。
4. 加入 observer：本机没装 console，用 Java SDK 的共识接口（不需要合约地址）：
   ```bash
   cd backend
   mvn -o test -Dtest=AddObserverNodeTest -Dchainsip.addObserver=true      -Dchainsip.observerNodeId=<nodeId>      -Dchainsip.deploy.privateKey=<管理员私钥>
   ```
   返回 `{"code":1,"msg":"Success"}`，随后 `getObserverList` 里出现 node4。

### 6.2 验收结果

- `getSealerList`：仍是 4 个（node0–node3），共识阈值不变；
- `getObserverList`：包含 node4；
- 块高：node4 RPC(8549) 与 node0 RPC(8545) 一致（实测均为 `0x52`），
  日志出现 `Download block commit succ ... number=82`；
- 后端 `GET /api/chain/topology`：`sealers=4, observers=1, nodeIds=5`。

### 6.3 启停

```bash
# 停
cd ~/fisco/nodes/127.0.0.1/node4 && bash stop.sh
# 起
cd ~/fisco/nodes/127.0.0.1/node4 && bash start.sh
```

> 重启整条链用 `start_all.sh` 不会带上 node4，需要再单独执行一次 node4 的 start.sh；
> observer 身份是链上共识状态，重启后仍然有效，不用重新 addObserver。
