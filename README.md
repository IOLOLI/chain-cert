# ChainSIP · 区块链养老保险平台

ChainSIP 是一个把养老保险核心业务放到联盟链上的全栈示例项目。

设计原则：**链上保存权威账本、角色权限与资金流转，链下只做登录认证、业务索引和界面展示**。即使链下数据库被篡改，最终资金与账户状态仍以链上合约和交易签名地址为准。

> 当前仓库为公共仓库模式：不包含真实用户名、密码、私钥、IP、域名、证书或生产数据库备份。首次运行请复制 `.env.example` 并填写自己的环境配置。

---

## 项目功能

- **多角色协同**
  - `ADMIN`：平台管理、机构管理、职工建档、政策参数维护、监管大屏
  - `BUREAU`：社保局，创建缴费单位、办理跨城转移
  - `SPONSOR`：缴费单位，创建职工账号、缴纳养老保险
  - `EMPLOYEE`：参保职工，查询个人账户、缴费流水、发起转移
  - `REGULATOR`：监管账户，只读审计链上账本
- **链上资金账本**
  - 个人账户、单位缴费、城市统筹基金池、在途托管资金全部由合约记账
  - 提供三条资金守恒恒等式，首页与监管大屏可实时对账
- **养老保险业务**
  - 单位注资、职工缴费、个人账户与统筹权益计算
  - 缴费基数区间、个人/单位费率、缴费周期等参数可配置
- **跨城转移**
  - 社保局发起、转入转出状态机、在途资金托管、完成/退回
  - 链上按交易签名地址校验权限，越权操作直接回滚
- **政策参数版本化**
  - 参数定义、生效版本、变更审计、链上同步状态
- **监管与审计**
  - 监管大屏、区块/交易/事件索引、链上账本浏览器
  - 链上交易留痕与失败原因可追溯
- **缓存与高并发扩展**
  - Redis 热点查询缓存、缓存击穿保护、接口限流、写接口幂等
  - Redis 不可用时自动降级，不阻断核心业务
- **安全设计**
  - JWT 登录、PBKDF2 密码派生、机构私钥 AES-256-GCM 加密落库
  - 后端角色校验 + 链上 `msg.sender` 双重权限控制

---

## 技术栈

| 模块 | 技术 | 版本 |
|---|---|---|
| 区块链 | FISCO BCOS | 2.11.x |
| 智能合约 | Solidity | 0.6.10（必须开启优化器） |
| 链 SDK | FISCO BCOS Java SDK | 2.10.1 |
| 后端 | Spring Boot | 2.7.18 |
| 后端语言 | JDK | 8 |
| ORM | MyBatis-Plus | 3.5.3.1 |
| 数据库 | MySQL | 8.x |
| 缓存（可选） | Redis | 7.x |
| 鉴权 | jjwt | 0.11.5 |
| 接口文档 | springdoc-openapi | 1.6.15 |
| 前端 | Vue 3 + Vite | 3.5 / 5.4 |
| 前端 UI | Element Plus | 2.8 |
| 前端状态/路由/请求 | Pinia + Vue Router + axios | 2.2 / 4.4 / 1.7 |
| 构建工具 | Maven / npm | 3.9 / Node.js 18+ |

### 默认端口

| 端口 | 用途 |
|---|---|
| `8080` | 后端 REST API、Swagger |
| `5173` | 前端 Vite 开发服务器 |
| `3306` | MySQL |
| `6379` | Redis（可选） |
| `20200-20203` | FISCO BCOS channel 端口（SDK 使用） |
| `30300-30303` | FISCO BCOS p2p 端口（节点互连） |
| `8545-8548` | FISCO BCOS JSON-RPC 端口（调试用） |

---

## 系统架构

```text
浏览器
  │
  ▼
Vue 3 + Vite + Element Plus
  │  /api
  ▼
Spring Boot 2.7 + JDK 8
  ├── MySQL 8          账号、地址绑定、私钥密文、事件索引、上链留痕
  ├── Redis 7（可选）   热点缓存、限流、幂等键
  └── FISCO BCOS 2.11  ChainSIP / ChainSIPTransfer 合约
```

---

## 目录结构

```text
chain-cert/
├── README.md
├── .env.example              # 环境变量模板
├── SECURITY.md               # 公共仓库安全约定
├── contracts/                # Solidity 合约源码
│   ├── ChainSIP.sol
│   └── ChainSIPTransfer.sol
├── backend/                  # Spring Boot 后端
│   ├── src/main/java/
│   ├── src/main/resources/
│   │   ├── application.yml
│   │   ├── schema.sql
│   │   └── abi/
│   └── sdk/                  # FISCO SDK 配置（证书需自行放入 conf/）
└── frontend/                 # Vue 3 前端
```

> `deploy/`、`scripts/`、`docs/` 属于本地开发辅助目录，不随公共仓库发布。

---

## 快速开始

### 环境要求

- JDK 8
- Maven 3.9+
- Node.js 18+ 与 npm
- MySQL 8
- FISCO BCOS 2.11（本地或虚拟机）
- Redis 7（可选）
- Docker（可选，用于快速启动 MySQL/Redis）

---

### 1. 启动数据库

#### 方式 A：本机 MySQL

```bash
# Linux
sudo systemctl enable --now mysql
# 部分发行版服务名是 mysqld
# sudo systemctl enable --now mysqld
```

```bash
# macOS
brew services start mysql
```

#### 方式 B：Docker

```bash
docker run -d \
  --name chainsip-mysql \
  -p 3306:3306 \
  -e MYSQL_ROOT_PASSWORD='<ROOT_PASSWORD>' \
  -e MYSQL_DATABASE=chainsip \
  mysql:8.0
```

#### 创建业务库和账号

```sql
mysql -u<DB_ADMIN_USER> -p
```

```sql
CREATE DATABASE IF NOT EXISTS chainsip
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_general_ci;

CREATE USER IF NOT EXISTS 'chainsip'@'127.0.0.1'
  IDENTIFIED BY '<DB_PASSWORD>';

GRANT ALL PRIVILEGES ON chainsip.* TO 'chainsip'@'127.0.0.1';
FLUSH PRIVILEGES;
```

后端首次启动时会自动执行 `backend/src/main/resources/schema.sql` 建表，不需要手工导入建表 SQL。

验证：

```bash
mysql -u<DB_ADMIN_USER> -p -e "show databases like 'chainsip';"
```

---

### 2. 可选：启动 Redis

Redis 只用于缓存、限流和幂等，不启动也能运行。

```bash
docker run -d \
  --name chainsip-redis \
  -p 6379:6379 \
  redis:7-alpine

docker exec chainsip-redis redis-cli ping
# 期望输出：PONG
```

默认使用 `127.0.0.1:6379` 的 `db1`，可通过 `CHAINSIP_REDIS_*` 环境变量覆盖。

---

### 3. 部署区块链

以下命令以 FISCO BCOS 2.11.0、Linux/macOS 为例。Windows 建议在 WSL2 或 Linux 虚拟机中执行。

#### 3.1 生成并启动 4 节点链

```bash
mkdir -p ~/fisco && cd ~/fisco

curl -#LO https://github.com/FISCO-BCOS/FISCO-BCOS/releases/download/v2.11.0/build_chain.sh
chmod +x build_chain.sh

# 4 个节点；端口顺序：p2p, channel, jsonrpc
bash build_chain.sh -l 127.0.0.1:4 -p 30300,20200,8545

cd nodes/127.0.0.1
bash start_all.sh
```

检查节点是否出块：

```bash
curl -s -X POST http://127.0.0.1:8545 \
  -H 'Content-Type: application/json' \
  --data '{"jsonrpc":"2.0","method":"getBlockNumber","params":[1],"id":1}'
```

返回结果中的 `result` 应持续增长。

> 端口不要混淆：`20200` 是 SDK 使用的 channel 端口，`30300` 是节点之间的 p2p 端口，`8545` 是 JSON-RPC 调试端口。

#### 3.2 配置后端 SDK 证书

把链生成的 SDK 证书复制到后端：

```bash
mkdir -p backend/sdk/conf
cp ~/fisco/nodes/127.0.0.1/sdk/ca.crt   backend/sdk/conf/
cp ~/fisco/nodes/127.0.0.1/sdk/sdk.crt  backend/sdk/conf/
cp ~/fisco/nodes/127.0.0.1/sdk/sdk.key  backend/sdk/conf/
```

确认 `backend/sdk/config.toml`：

```toml
[network]
peers = ["127.0.0.1:20200"]
```

> `backend/sdk/conf/` 下的证书和私钥已被 `.gitignore` 排除，不会进入公共仓库。

#### 3.3 部署合约

合约使用 Solidity `0.6.10` 编译，**必须开启优化器**，否则 `ChainSIP` 运行时代码会超过 EIP-170 大小限制。

**方式 A：FISCO 控制台 / WeBASE**

1. 使用 FISCO 控制台部署 `contracts/ChainSIP.sol`，构造参数为空。
2. 记录 `ChainSIP` 合约地址。
3. 部署 `contracts/ChainSIPTransfer.sol`，构造参数填写 `ChainSIP` 地址。
4. 用部署账户调用 `ChainSIP.setTransferContract(<ChainSIPTransfer地址>)`。

**方式 B：使用后端部署测试**

```bash
cd backend

mvn -s settings-2x.xml test \
  -Dtest=DeployContractsTest \
  -Dchainsip.deploy=true \
  -Dchainsip.deploy.privateKey='<64位十六进制私钥>'
```

执行成功后会输出：

```text
ChainSIP = 0x...
ChainSIPTransfer = 0x...
```

把这两个地址写入环境变量：

```bash
CHAINSIP_CORE_ADDRESS=0x...
CHAINSIP_TRANSFER_ADDRESS=0x...
CHAINSIP_ADMIN_PRIVATE_KEY=<部署者私钥>
```

> 部署者地址会成为合约的第一个 `ADMIN`。如果部署者与后端配置的 ADMIN 不是同一个地址，需要由部署者调用 `addAdmin(<后端ADMIN地址>)` 完成交接。

---

### 4. 配置环境变量

复制模板：

```bash
cp .env.example .env
```

`.env` 不会自动被 Spring Boot 读取，需要在 shell、IDE 或 systemd 中注入。Linux/macOS 可以这样加载：

```bash
set -a
. ./.env
set +a
```

至少确认以下配置：

```bash
CHAINSIP_DB_HOST=127.0.0.1
CHAINSIP_DB_PORT=3306
CHAINSIP_DB_NAME=chainsip
CHAINSIP_DB_USER=chainsip
CHAINSIP_DB_PASSWORD=<DB_PASSWORD>

CHAINSIP_JWT_SECRET=<至少32字节随机字符串>
CHAINSIP_CRYPTO_MASTER_KEY=<至少32字节随机字符串>

CHAINSIP_BOOTSTRAP_ADMIN_USERNAME=admin
CHAINSIP_BOOTSTRAP_ADMIN_PASSWORD=<首次登录密码>

CHAINSIP_CORE_ADDRESS=0x...
CHAINSIP_TRANSFER_ADDRESS=0x...
```

生产环境必须替换所有 `change-me` / `ChangeMe_*` 占位值。

---

### 5. 启动后端

```bash
cd backend

mvn clean package -DskipTests

java -jar target/chainsip-backend.jar
```

> 如果 FISCO SDK 依赖下载较慢或失败，可使用仓库内的 Maven 镜像配置：
> `mvn -s settings-2x.xml clean package -DskipTests`。

也可以直接开发模式启动：

```bash
mvn spring-boot:run
```

启动后检查：

```bash
curl http://127.0.0.1:8080/api/public/health
```

接口文档：

```text
http://127.0.0.1:8080/swagger-ui/index.html
```

---

### 6. 启动前端

```bash
cd frontend

npm install
npm run dev
```

访问：

```text
http://127.0.0.1:5173
```

Vite 已把 `/api` 代理到 `http://127.0.0.1:8080`，开发时不需要额外配置跨域。

---

### 7. 首次登录

首次启动空库时，后端会创建引导管理员：

```text
用户名：admin
密码：以 CHAINSIP_BOOTSTRAP_ADMIN_PASSWORD 为准
```

如果没有设置该环境变量，默认值为 `ChangeMe_123456`。**登录后请立即修改密码。**

---

## 链上校验

登录获取 Token 后，可以检查链连接与合约状态：

```bash
curl -H "Authorization: Bearer <TOKEN>" \
  http://127.0.0.1:8080/api/chain/status
```

重点字段：

- `ready`：链是否可用
- `coreDeployed`：核心合约是否已配置
- `transferDeployed`：转移合约是否已配置
- `blockNumber`：当前区块高度

---

## 安全说明

- 不要提交 `.env`、`backend/config/`、`backend/sdk/conf/`、数据库备份、节点证书或任何私钥。
- 生产环境使用随机强口令，并定期轮换 JWT 密钥、AES 主密钥、数据库密码和 ADMIN 私钥。
- 如果密钥曾经进入 Git 历史，仅删除文件不够，需要立即轮换并清理历史。
- 仓库不提供真实部署地址；所有链地址、合约地址和机构私钥都应由部署者自行生成并保管。

---

## License

本项目仅用于学习、毕业设计与技术演示。生产使用前请完成安全评估、合规审查和密钥托管方案设计。