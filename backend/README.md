# ChainSIP 后端（Spring Boot 2.7 + JDK 8 + FISCO BCOS 2.x）

区块链养老保险平台的后端：**认证在链下，授权在链上**。链是 **FISCO BCOS 2.11.1**（4 节点，WSL 内原生部署，非 Docker）。

- **链下（本服务）**：平台账号、密码派生值、地址绑定、会话、链上事件索引、上链操作留痕、机构私钥托管。
- **链上（`../contracts` 里的合约）**：角色、公司/职工档案、账户余额、城市统筹基金池、转移状态机。
  链下角色写得再漂亮，合约也会按交易签名地址（`msg.sender`）再判一次权限。

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | **8** | 与 FISCO BCOS Java SDK 2.x（Java 8 字节码）匹配；实测 `<JDK8_HOME>` |
| Spring Boot | 2.7.18 | Web + Validation |
| MyBatis-Plus | 3.5.3.1 | 分页插件、BaseMapper |
| 数据库 | **MySQL 8** | 唯一数据源（已移除 H2）；库 `chainsip`，6 张表由 `schema.sql` 启动时自动创建 |
| **Redis** | **7.x** | 可选缓存/共享状态；本机 Docker 容器 `chainsip-redis`，默认连 `127.0.0.1:6379` 的 **db1** |
| **FISCO BCOS Java SDK** | **2.10.1** | 适配 FISCO BCOS 2.x 链；内含 WeDPR JNI 原生库，可在 Windows 直接运行 |
| BouncyCastle | `bcprov` + **`bcpkix` 1.77** | 2.x SDK 的 SSL 初始化要读 PEM，而它的 POM 只带 `bcprov`，**必须显式加 `bcpkix`**（否则报 `ClassNotFoundException: PEMParser`） |
| 合约语言 | **Solidity 0.6.10** | 2.x 官方支持上限；编译**必须开优化器**（见 §6.3） |
| 接口文档 | springdoc-openapi 1.6.15 | `/swagger-ui/index.html` |

---

## 1. 分层结构

```
backend/
├── pom.xml                        # JDK 护栏 + 依赖（SDK 2.10.1 / bcpkix / jjwt 0.11.5 / springdoc）
├── settings-2x.xml                # Maven 设置：本地仓库钉到 ~/.m2/repository，~/maven-offline-repository 作离线仓库
├── sdk/
│   ├── config.toml                # 2.x 格式：[cryptoMaterial] / [network] / [account] / [threadPool]
│   ├── conf/                      # ca.crt、sdk.crt、sdk.key（从链的 nodes/127.0.0.1/sdk 拷来）
│   └── native/                    # 启动时自动落地 libffi_java_sdk.dll（WeDPR JNI）
└── src/main/
    ├── java/com/chainsip/
    │   ├── ChainSipApplication.java
    │   ├── chain/                 # 链网关：ChainGateway 接口 + FISCO 2.x 实现 + ABI 导出
    │   ├── common/                # ApiResult / ErrorCode / BizException / 全局异常 / 合约错误中文化
    │   ├── config/                # 配置绑定、启动自检、幂等迁移、CORS、MyBatis-Plus、OpenAPI
    │   ├── controller/            # 8 个 Controller，按角色分组（见 §4）
    │   ├── crypto/                # PBKDF2 密码、AES-GCM 私钥加密、钱包签名验签、WeDPR 原生库引导
    │   ├── dto/                   # 请求体与视图对象（AccountView / EmployeeDetailView / Requests…）
    │   ├── entity/ + mapper/      # 6 张表（见 §5）
    │   ├── security/              # JWT 签发解析、登录拦截器、AuthContext、@RequireRole
    │   └── service/               # 业务层：认证、机构私钥、上链门面、各角色业务、层级开号（见 §2）
    └── resources/
        ├── application.yml        # MySQL 数据源、链配置（合约地址/群组/超时）、JWT、加密主密钥
        ├── schema.sql             # 6 张表（全部 create table if not exists，幂等）
        └── abi/                   # ChainSIP.abi/bin、ChainSIPTransfer.abi/bin（0.6.10 产物）
```

| 层 | 职责 | 关键约定 |
|---|---|---|
| `controller` | 只做 HTTP 语义：收参数、调 service、包 `ApiResult`；**不写业务规则** | 用类级 `@RequireRole` 做链下角色门禁（真正的权限仍在合约） |
| `service` | 全部业务规则：层级开号、注资、缴费、转移、列表收口、金额解析 | 写操作一律经 `ChainTxService`；读链一律带身份 |
| `chain` | 与 FISCO 2.x SDK 打交道的唯一出口 | `ChainGateway` 是接口，`FiscoChainGateway` 是唯一实现；换链只需换实现 |
| `crypto` | 密码派生、私钥加解密、签名与地址恢复、JNI 原生库落地 | 私钥明文只在内存里出现，绝不进日志/响应 |
| `security` | JWT 签发与解析、请求级登录上下文 `AuthContext`、`@RequireRole` | 认证在链下，链上授权由合约再判一次 |
| `entity` + `mapper` | 6 张链下表，MyBatis-Plus `BaseMapper` | 表结构由 `schema.sql` + 幂等迁移共同保证 |
| `config` | 配置绑定、启动自检与启动期任务（补列/回填/ADMIN 私钥/空库引导） | 启动期任务一律"失败只 warn，不阻断启动" |
| `common` | 统一响应体、错误码、业务异常、全局异常处理、合约错误→中文 | 响应里**不出现**合约错误名与 txHash |

---

## 2. 关键实现方法

### 2.1 链网关：`ChainGateway` / `FiscoChainGateway`

`chain/ChainGateway` 是接口（`send` / `call` / `isReady` / `statusMessage` / `refresh` / `latestBlock` / `nodeVersion`），
`chain/FiscoChainGateway` 是 2.x 的唯一实现。它集中吸收 2.x SDK 的所有"脏活"：

- **懒初始化 + 可重试**：`ensureInitialized()` 首次调用才建 `BcosSDK`；链没起时初始化失败只记 `initError`，
  应用照常启动，链相关接口返回 503 与排查提示；链起来后 `POST /api/chain/refresh` 重新初始化，不必重启。
- **`config.toml` 物化**：仓库里的 `sdk/config.toml` 用相对路径（`certPath = "conf"`、`keyStoreDir = "account"`），
  但 2.x 按**进程工作目录**解析。网关把配置读出来、把两个路径改写成**绝对路径**后写到临时文件，再交给 SDK。
- **目录类加载器（TCCL）**：SDK 内部会 `ClassLoader.getResource("")`，在 Spring Boot fat jar 下 JDK 的 JarLoader
  对空资源名抛 `IllegalArgumentException: name`。初始化期间临时把 TCCL 换成基于 `sdk/` 目录的 `URLClassLoader`。
- **原生库引导**：2.x 的 ECDSA 走 WeDPR JNI，默认写 `~/.fisco/nativeutils/native.lock`（Windows 上经常失败）。
  `crypto/WdprNativeLibraryBootstrap` 把 DLL 解压到 `sdk/native/` 并设 `java.library.ffipath`，绕开那个目录。
- **ABI 资源导出**：`chain/AbiResourceExporter` 启动时把 classpath 里的 `abi/*.abi|bin` 导出到临时目录，
  因为 SDK 的合约加载器只认**文件路径**。
- **证书自检**：`sdk/conf/` 下三个文件缺失时给出中文提示（`statusMessage()` / 启动日志）。

### 2.2 身份解析：`sendAs` / `callAs` / `readAccount`

**发交易用谁的私钥 = 链上 `msg.sender` = 合约判权的唯一依据。** `service/ChainTxService` 把这件事显式化：

| 方法 | 用谁的身份 | 典型场景 |
|---|---|---|
| `send()` | 当前登录账号的机构私钥（`currentInstitutionCode()`） | 单位缴费、单位建档、社保局放行 |
| `sendAs("ADMIN", …)` | **强制 ADMIN** | `addSponsorAccount` / `depositSponsor` 等 `onlyAdmin` 函数，由后端代签 |
| `call()` | 当前登录账号；无机构身份时**回退 ADMIN**（`readAccount()`） | 普通只读查询 |
| `callAs("ADMIN", …)` | **强制 ADMIN** | 社保局读下辖单位的 `getSponsorAccount`（BUREAU 读会被 `SponsorNotVisible` 回滚） |

**为什么读也要带身份**：合约里的可见性判断（`getInsuranceAccount` / `getSponsorAccount` / `getTransfer`）同样看 `msg.sender`。
2.x SDK 的 read 调用默认不带调用者身份，节点看到陌生地址就返回 `AccountNotVisible`。
实测确认过这不是"偷偷回退 ADMIN"：职工读自己的账户可见，读别人的账户会被合约回滚。

### 2.3 账号层级与自动注册：`AccountHierarchyService`

层级：**管理员 → 社保局 → 缴费单位 → 参保职工**（`ALLOWED_TARGETS` 表约束"谁能建谁"）。

一次开号（`doCreate`）做四件事：

1. **生成机构链账户**：BUREAU / SPONSOR / REGULATOR 用 `BUREAU_` / `SPONSOR_` / `REGULATOR_` + 6 位随机大写十六进制编码；
   EMPLOYEE 固定 `EMP_` 前缀且**忽略调用方传入的编码**（复用别家编码等于权限串号）。私钥 AES-256-GCM 密文落库。
2. **建账号**：写入 `cs_account`，同时写 `created_by` = 当前操作者账号 id（见 §2.5）。
3. **自动绑定链上地址**：`AuthService.bindPlatformAddress` 直接写 `cs_wallet`（`verified=true`，平台托管无需验签）。
4. **自动上链注册**（`createByAdmin` / `createSponsorByBureau` 两条路径）：
   BUREAU → `addBureaus` + `changeBureausAuthorized(true)`；SPONSOR → `addSponsorAccount`；REGULATOR → `addRegulatorAccount`。
   这些函数都是 `onlyAdmin`，所以**一律由后端用 ADMIN 私钥代签**（链上仍由 ADMIN 署名，治理模型不变）。

**"链上失败绝不让开号失败"**：注册过程整体 try/catch，失败只写 `onChainRegistered=false` + `onChainMessage`（中文），
该机构随后出现在 `GET /api/admin/institutions/pending`，用 `POST /api/admin/institutions/{code}/register`（body `{city}`，
**失败也是 HTTP 200**，看 `ok`）补一次 —— 那是**补救入口，不是必经步骤**。

`InstitutionRegistrationService` 负责这份待办清单与逐条补注册（含"只补授权不重复注册""已就绪直接返回"的幂等处理）。

### 2.4 自动绑定与幂等迁移

| 组件 | 干什么 |
|---|---|
| `config/AdminKeyBootstrap` | 每次启动把 `chainsip.crypto.admin-private-key` 写成 `code=ADMIN` 的机构密钥（链上链下同一个 ADMIN，免 `addAdmin` 交接），并把它绑到 `admin` 账号 |
| `AuthService.bindPlatformAddress` | 把平台自己持有私钥的地址写进 `cs_wallet`；幂等（同账号同地址不重复插）、撞到别人的绑定只 warn 不覆盖 |
| `service/DataSeeder.backfillPlatformBindings` | 启动时给所有"有机构密钥但还没绑定"的老账号补绑一次 |
| `config/WalletSchemaMigration` | 老库给 `cs_wallet` 幂等补 `remark` 列（先查 `information_schema` 再 `alter table`） |
| `config/AccountCreatedByMigration` | 老库给 `cs_account` 幂等补 `created_by` 列，并**从机构密钥备注回填**创建者 id（见下） |

两者都是 `ApplicationRunner` + `@Order(HIGHEST_PRECEDENCE)`，早于 `AdminKeyBootstrap` 与 `DataSeeder`；
MySQL 8 不支持 `ADD COLUMN IF NOT EXISTS`，所以统一"先查再改"；**DDL/回填失败只记日志，不阻断启动**。

`AccountCreatedByMigration` 的回填算法：遍历 `created_by is null` 的账号 → 用其 `institution_code` 取
`cs_institution_key.remark` → 正则 `(?:由|（|\()\s*(X)\s*创建` 抠出创建者用户名（现有备注形如
`由 bureau_demo 创建，服务城市：QA?`，也兼容 `（admin 创建，机构编码 …）` 这种钱包备注写法）→
`admin` 解析成 admin 账号 id → 其余按 `username` 查 id → 写回。**推断不出来就保持 NULL**（不瞎猜、不报错），
日志打印"回填 N 条，无法推断 M 条"。整个流程可重复执行。

### 2.5 列表收口：`listMyEmployeeAccounts` / `listSponsorsInMyCities`

`GET /api/admin/accounts` 是 ADMIN 全平台视角（返回全部）；**机构账号列表必须按登录者范围收口**，
都实现在 `AccountHierarchyService`：

- **`GET /api/sponsor/accounts`（本单位职工）** = **并集**：
  ① *链上归属*：账号的链上地址 ∈ `getPersonList(本单位链上地址)`（本单位读自己的职工名单，合约允许）；
  ② *创建归属*：`cs_account.created_by` == 当前登录账号 id。
  没有机构编码 / 本单位没有链上地址 / 读链失败时，**链上归属贡献空集合**（绝不会"查不到就返回全部"），
  此时只剩创建归属生效；两者都没命中就是空数组 + HTTP 200。
- **`GET /api/bureau/accounts`（本局下辖缴费单位）**：先取本局链上服务城市
  （`BureauCityService`，与 `GET /api/bureau/cities` 同链路），再逐个读单位的链上 `city`
  （**必须用 ADMIN 身份读**，BUREAU 读 `getSponsorAccount` 会被 `SponsorNotVisible` 回滚），
  只保留 `city ∈ 服务城市`；**读不到城市的单位不返回**（宁缺勿滥），本局服务城市读不到时返回空数组 + 200。

### 2.6 错误中文化：`common/ContractErrorMessages`

合约 `require(cond, "Xxx")` 回滚时回执里带的是**错误名与 txHash**（如 `PaymentCycleNotElapsed`）。
这些东西**不进界面**：接口响应只给一句中文人话；原始错误名与 txHash 保留在**后端日志**与 **`cs_chain_tx_log.message`**。

- 静态映射表：`contracts/*.sol` 里全部 `require(..., "…")` / `revert Xxx(...)` 的 **47 个错误名 + 8 条 SafeMath 消息** → 中文；
- `detect(raw)`：按**长度倒序**匹配错误名（避免子串误判）；
- `friendly(raw[, fallback])`：命中→中文，未命中→兜底（`操作未成功（合约拒绝了这笔交易），请联系管理员`）；
- `sanitize(message)`：用于"拼接型"消息，命中错误名**整句替换**成中文，未命中则抹掉 `txHash=0x…`；
- `forClient(code, message)`：`GlobalExceptionHandler` 的统一出口，额外把 `code=2002`（只读调用失败）也换成中文。

应用点：`ChainTxService.send`（回执失败）、`GlobalExceptionHandler.handleBiz`、
`BureauBizService`、`AccountHierarchyService`、`InstitutionRegistrationService`。
**成功交易的 `txHash` 仍作为数据出现在 `SendResult.transactionHash` 里**（那是数据，不是报错文案）。完整对照表见 §7.2。

### 2.7 安全

| 关注点 | 实现 |
|---|---|
| 登录密码 | `crypto/PasswordHasher`：PBKDF2-HMAC-SHA256 + 16 字节随机盐 + 12 万次迭代，存 `pbkdf2$迭代次数$盐$派生值`；校验用 `MessageDigest.isEqual` 常量时间比较。**密码绝不上链、不写日志** |
| 机构私钥 | `crypto/AesGcmCipher`：AES-256-GCM 加密后落 `cs_institution_key`（密文 + IV 两列），只在发交易的瞬间解密到内存；接口永不返回私钥 |
| 会话 | `security/JwtService` + `JwtAuthInterceptor`：JWT 带 `uid/role/inst`，拦截 `/api/**`；未登录 401、角色不符 403。拦截器只对 `HandlerMethod` 生效，**未知路径自然落到 404**（不会被拦截器抛成 500） |
| 角色门禁 | `@RequireRole`（类级或方法级）：ADMIN / BUREAU / SPONSOR / REGULATOR 各自收口；`/api/index/**`（链上索引）仅 ADMIN / REGULATOR |
| 自助改密码 / 改资料 | `POST /api/auth/password`（验原密码）、`POST /api/auth/profile`（改用户名/显示名，需当前密码）——**只能改自己**（账号取自 JWT），改用户名同时守 `uk_cs_account_username` 唯一约束 |
| 越权边界 | 链下 `@RequireRole` 只是第一道闸门；真正的越权由合约按 `msg.sender` 拒绝。列表类接口额外做范围收口（§2.5） |

---

## 3. 配置

### 3.1 `src/main/resources/application.yml` 关键项

```yaml
server:
  port: 8080
spring:
  datasource:                      # 唯一数据源：MySQL 8（已移除 H2）
    url: jdbc:mysql://${CHAINSIP_DB_HOST:127.0.0.1}:${CHAINSIP_DB_PORT:3306}/${CHAINSIP_DB_NAME:chainsip}?...
  sql.init:
    mode: always                   # 启动执行 schema.sql（全部 create table if not exists，幂等）
  redis:                           # 本机 Docker：chainsip-redis，映射 127.0.0.1:6379
    host: ${CHAINSIP_REDIS_HOST:127.0.0.1}
    port: ${CHAINSIP_REDIS_PORT:6379}
    password: ${CHAINSIP_REDIS_PASSWORD:}
    database: ${CHAINSIP_REDIS_DATABASE:1}   # db0 留给同机其他项目使用，ChainSIP 用 db1
    timeout: 3000ms
  jackson:
    default-property-inclusion: non_null
chainsip:
  jwt:
    secret: "..."                  # 生产必须替换，长度至少 32 字节
    header: Authorization
    prefix: "Bearer "
    expire-minutes: 720
  crypto:
    master-key: "..."              # 机构私钥落库加密主密钥（AES-256-GCM）
    admin-private-key: "${CHAINSIP_ADMIN_PRIVATE_KEY:}"   # 可选：ADMIN 机构链账户私钥
    allow-demo-sign: true          # 仅控制联调辅助接口 POST /api/auth/demo/sign；生产建议 false
  chain:
    enabled: true
    sdk-config-file: "${CHAINSIP_SDK_CONFIG:./sdk/config.toml}"
    group-id: "${CHAINSIP_GROUP_ID:1}"          # 2.x 群组不在 config.toml 里，由 getClient(groupId) 指定
    peers: ["127.0.0.1:20200"]                  # 仅用于状态展示；真正连接看 config.toml
    abi-resource-dir: "abi"
    contracts:
      core: "${CHAINSIP_CORE_ADDRESS:0x...}"     # 未配置时链相关接口 503，应用本身照常启动
      transfer: "${CHAINSIP_TRANSFER_ADDRESS:0x...}"
    timeout-millis: 15000
```

### 3.2 `sdk/config.toml`（2.x 格式）

```toml
[cryptoMaterial]
certPath = "conf"        # 2.x 按“进程工作目录”解析；网关会改写成绝对路径后再用
cryptoType = "0"         # 所有数字都要加引号，否则 TOML 解析抛 ClassCastException
[network]
peers = ["127.0.0.1:20200"]   # channel 端口，不是 p2p(30300)、不是 jsonrpc(8545)
[account]
keyStoreDir = "account"       # 该目录不存在会触发 SDK 的退化路径（见 §7.1）
accountFileFormat = "pem"
```

> 链没起也能启动后端：认证、账号、地址绑定都不依赖链；链相关接口返回 503 并给出排查提示。
> 链起来后调 `POST /api/chain/refresh` 重新初始化，不必重启。

### 3.3 环境变量一览

| 变量 | 作用 | 默认 |
|---|---|---|
| `CHAINSIP_DB_HOST` / `CHAINSIP_DB_PORT` / `CHAINSIP_DB_NAME` / `CHAINSIP_DB_USER` / `CHAINSIP_DB_PASSWORD` | 数据源 | `127.0.0.1` / `3306` / `chainsip` / `chainsip` / `change-me` |
| `CHAINSIP_ADMIN_PRIVATE_KEY` | ADMIN 机构链账户私钥（部署合约的那个账户） | 空（则 ADMIN 发不出交易） |
| `CHAINSIP_SDK_CONFIG` | SDK 配置文件路径 | `./sdk/config.toml` |
| `CHAINSIP_GROUP_ID` | 群组号 | `1` |
| `CHAINSIP_CORE_ADDRESS` / `CHAINSIP_TRANSFER_ADDRESS` | 两个合约地址 | 见 `application.yml` |
| `CHAINSIP_REDIS_HOST` / `CHAINSIP_REDIS_PORT` / `CHAINSIP_REDIS_PASSWORD` / `CHAINSIP_REDIS_DATABASE` | Redis 连接 | `127.0.0.1` / `6379` / 空 / `1` |

### 3.4 链与证书（链已在 WSL 里跑好）

当前链：**FISCO BCOS 2.11.1**，目录 `~/fisco-2.11`，4 节点，
端口 **30300-30303(p2p) / 20200-20203(channel) / 8545-8548(jsonrpc)**，群组 `group.1`（**groupId = 1**）。

```bash
# 启停（WSL）
bash ~/fisco-2.11/nodes/127.0.0.1/start_all.sh
bash ~/fisco-2.11/nodes/127.0.0.1/stop_all.sh

# SDK 证书 → 后端（三个文件即可）
cp ~/fisco-2.11/nodes/127.0.0.1/sdk/{ca.crt,sdk.crt,sdk.key} <PROJECT_DIR>/backend/sdk/conf/
```

### 3.5 Redis（本机 Docker）

本机已有容器 `chainsip-redis`（`redis:7-alpine`），端口映射 `127.0.0.1:6379`，无密码。
它的 **db0 已被同机其他项目使用**，所以 ChainSIP 默认使用 **db1**，两边的键互不干扰。

```powershell
# 查看/启动容器
docker ps --filter name=chainsip-redis
docker start chainsip-redis
docker exec chainsip-redis redis-cli ping     # 期望 PONG
```

- **用途（缓存 + 并发保护）**：所有键统一以 `chainsip:` 开头。
  - `chain:contract-compat:v1`：`GET /api/chain/status` 的合约版本探针，60 秒；
  - `bureau:overview:*` / `bureau:sponsors:*` / `bureau:employees:*`：辖区总览、单位与职工扫描，30~45 秒；
  - `chain:insurance:*` / `chain:sponsor:*` / `chain:persons:*`：单个链上账户与职工名单，30 秒；
  - `regulator:dashboard:v1` / `regulator:system-stats:v1` / `regulator:city-pool:*`：监管大屏聚合，20 秒；
  - `index:events:*` / `index:tx-logs:*`：链上索引前 3 页，15 秒；
  - `policy:parameters:v1`：政策参数全量版本，10 分钟（本地副本最多 5 秒）；
  - `rate:*`：限流计数；`idem:*`：写接口幂等键；`load:*`：缓存回源锁。
- **防击穿**：`RedisCacheService.getOrLoad()` 在同一进程内用分段锁只放一个线程回源，
  多实例再用 Redis 短锁协调；拿不到锁的实例短暂等待后按 fail-open 自行回源。
- **限流**：`chainsip.rate-limit.*` 控制普通接口、重查询接口和登录尝试的每分钟额度；
  Redis 不可用时自动放行。
- **幂等**：转出申请、养老金领取、缴费、注资等写接口在 120 秒内对同一账号的相同请求返回 409，
  防止双击/重试造成重复上链。
- **降级**：Redis 连不上不会阻断启动。缓存操作自动跳过并回退进程内缓存；
  `GET /api/public/health` 会返回 `data.redis.ok=false`，并在 `warnings` 里提示。
- **验证**：健康检查的 `data.redis` 里能看到 `host/port/database/latencyMs`；
  `docker exec chainsip-redis redis-cli -n 1 keys 'chainsip:*'` 可看到实际缓存键。

---

## 4. 接口一览

| 分组 | 主要接口 | 角色 |
|---|---|---|
| 01 认证 | `POST /api/auth/login`、`GET /api/auth/me`、`POST /api/auth/password`（自助改密码）、`POST /api/auth/profile`（自助改用户名 / 显示名，需当前密码）、`POST /api/auth/demo/sign`（仅联调辅助，无业务调用方） | 公开 / 已登录 |
| 02 链信息 | `GET /api/chain/status`、`/block`、`/stats`；`POST /api/chain/call`、`/send`、`/refresh` | 已登录（send 需机构账号） |
| 03 ADMIN | `POST /api/admin/sponsors`、`/deposit`、`/persons`、`/bureaus`、`/regulators`、`/transfer-contract`、`/accounts`、`/institution-keys`；`GET /api/admin/institutions/pending`、`POST /api/admin/institutions/{code}/register`（**待上链注册补救**） | ADMIN |
| 04 SPONSOR | `POST /api/sponsor/employees`、`/employees/suspend`、`/employees/salary`、`/payments`、`/accounts`；`GET /api/sponsor/employees`、`/employees/detail`（带姓名的职工明细）、`/employees/account`、`/account` | SPONSOR（`GET /accounts` 只列**本单位**职工） |
| 05 EMPLOYEE | `GET /api/employee/account`、`/payments`、`/transfer-state`、`/escrow`；`POST /api/employee/transfer/apply`、`/pension/claim`（职工本人领取养老金） | 已登录（可见性由合约判定） |
| 06 BUREAU | `POST /api/bureau/accounts`、`/transfers/authorize`、`/transfers/{id}/receive`、`/sponsors/deposit`（给本局下辖单位注资，后端用 ADMIN 私钥代签）、`/pension/retire`、`/pension/claim`；`GET /api/bureau/transfers/inbound`、`/outbound`、`/cities`、`/info`、`/list`、`/city/overview`、`/city/sponsors`、`/city/employees`、`/city/insurance`、`/city/payments`、`/pension/rules`、`/pension/overview`、`/pension/eligible`、`/pension/retirees`、`/pension/records` | BUREAU（`GET /accounts` 只列**本局服务城市内**单位） |
| 07 REGULATOR | `GET /api/regulator/dashboard`、`/system-stats`、`/city-pool`、`/transfers/{id}`、`/sponsors` | ADMIN / REGULATOR（纯只读） |
| 08 链下索引 | `GET /api/index/events`、`/api/index/tx-logs` | **ADMIN / REGULATOR**（审计/监管用途） |

**几处值得留意的接口语义**（实现见 §2）：

- `POST /api/auth/password`：body `{oldPassword, newPassword}` → `data:true`；原密码错 400「原密码不正确」、新密码少于 6 位 400「新密码至少 6 位」、新旧相同 400「新密码不能与原密码相同」。
- `POST /api/auth/profile`：body `{newUsername?, newDisplayName?, password}`（两个新字段留空=不改）→ `data:{username,displayName}`；
  用户名规则 3-32 位字母/数字/下划线，占用或格式非法分别给 400。
- `GET /api/sponsor/employees/detail`（SPONSOR，不需 `sponsor` 参数）：先取本单位职工地址列表（分页），再**逐个**读链上参保账户补数据。每行：
  `address` / `name` / `idNumber` / `status` / `statusName` / `city` / `salary` / `paymentBase` /
  `personalBalance`（个人账户余额）/ `overallBalance`（统筹权益）/ `paidMonths`（累计缴费月数）/
  `overdueCount`（逾期次数）/ `lastPaymentAt`（上次缴费时间）与其**兼容别名** `lastPaidAt`（两者同值）/ `suspended`。
  数值缺字段兜底 `0`、文本兜底空串；单个职工读取失败只把该行降级为 `status:-1` / `statusName:"读取失败"`；
  `offset` 默认 0、`limit` 默认 20、**上限 50**（合约没有批量 getter，只能逐个读，故封顶）。
- 账户接口（`GET /api/employee/account`、`GET /api/sponsor/employees/account`，同一映射）在既有字段外
  **多返回 `sponsorName`**（所属缴费单位名称）：「职工档案的 `sponsor` 地址 → `getSponsorAccount(sponsor).name`」，
  读不到为空串（不报错），前端可"优先显示 `sponsorName`、缺失时回退 `sponsor` 地址"。
- `POST /api/bureau/sponsors/deposit`：body `{sponsor, amount}`（amount 为字符串，支持大数）。
  链上 `depositSponsor` 是 `onlyAdmin`，故由后端用 ADMIN 私钥代签；**授权边界**：先校验
  `单位链上 city ∈ 本局服务城市`，不通过直接 403 且**不发交易**；单位未注册 → 400；金额为 0/非法 → 400。
- **养老金支出**（`PensionBizService`，是"缴纳"的对称面）：
  - `POST /api/bureau/pension/retire` body `{employee, force}`：链上 `retireEmployee(employee,bool)` 是 `onlyAdmin`，
    后端用 ADMIN 私钥代签。`force=false` 预校验「60 岁 + 累计 180 个月」（不通过直接 400，不白烧交易）；
    `force=true` 是特殊审批（病退/提前退休），跳过门槛，链上事件仍记录真实年龄与月数；
  - `POST /api/bureau/pension/claim` 与 `POST /api/employee/pension/claim`：同一个链上 `claimPension`，
    前者 ADMIN 代签、后者用**职工本人私钥**（合约允许 `msg.sender == employee`）。同一职工 **30 天一次**；
  - 发放资金口径：`基础养老金 = 缴费基数 × 缴费月数 / 1200` 从**城市统筹基金池**扣，
    `个人账户养老金 = 退休时个人账户余额 / 139` 从**职工个人账户**扣；
  - `GET /api/bureau/pension/records`：发放流水来自 `cs_chain_event` 的 `PensionClaimed` 事件
    （链上不存可遍历数组，核心合约已接近 24576 B 上限）；
  - 对账口径随之延伸为：`totalPaid = totalPersonalFund + totalCityPool + 托管 + 已发放养老金`。
- **两个机构账号列表按登录者范围收口**（`/api/admin/accounts` 例外，ADMIN 保持全部）：口径见 §2.5。

---

## 5. 数据库

6 张表（`cs_*`，全部 `create table if not exists`，由 `schema.sql` 启动时创建；列变更由 §2.4 的幂等迁移补齐）：

| 表 | 存什么 | 关键列 |
|---|---|---|
| `cs_account` | 平台账号（登录凭据） | `username`（唯一）、`password_hash`（PBKDF2）、`role`、`institution_code`、`status`、**`created_by`** |
| `cs_wallet` | 账号 ↔ 链上地址绑定 | `account_id`、`address`、`verified`、`primary_wallet`、`remark`（绑定来源） |
| `cs_institution_key` | 机构链账户（身份 + 私钥） | `code`、`role`、`address`、`private_key_cipher`、`iv`、`enabled`、`remark` |
| `cs_chain_tx_log` | 上链操作留痕（每次 `send` 一笔） | `institution_code`、`function_name`、`params_json`、`success`、`message`、`tx_hash`、`block_number` |
| `cs_chain_event` | 链上事件索引 | `tx_hash`、`event_name`、`employee_address`、`sponsor_address`、`amount`、`payload` |
| `cs_login_log` | 登录日志 | `account_id`、`username`、`client_ip`、`success`、`message` |

**`created_by` 说明**：创建者账号 id。层级开号时由 `AccountHierarchyService.doCreate` 写入
（ADMIN 建的写 ADMIN 的 id、社保局建的写该社保局的 id、单位建的职工写该单位的 id）；
系统初始化的 `admin` 为 `NULL`；老库由 `AccountCreatedByMigration` 补列并回填。
它和"链上归属"一起构成 §2.5 的并集判定。

**链上 / 链下边界**：

- **链上（权威）**：角色（`roleOf`）、公司/职工档案、账户余额与缴费基数、城市统筹基金池、转移状态机。
- **链下（本服务）**：账号与密码派生值、地址绑定、会话、事件索引、上链留痕、机构私钥托管。
- 链下的角色字段只用于"界面展示 + 挑选发交易用哪把私钥"；链上链下不一致时，**以链上为准**（交易会被合约回滚）。
- 链上刻意不提供"遍历所有公司/职工"的接口（避免链上循环与 gas 爆炸），名单/报表/模糊查询走链下索引表
  `cs_chain_event`（事件驱动，可随时重放重建）；`cs_chain_tx_log` 则记录"是谁、什么参数、发起了什么链上操作"。

---

## 6. 构建与运行

### 6.1 构建

> ⚠️ 本机两个全局 Maven 设置都把本地仓库钉在**只读**目录，所以构建必须带 `settings-2x.xml` 与本地仓库参数；
> 路径要用**正斜杠并加引号**（反斜杠会被 `mvn.cmd` 拆坏）。

```powershell
# 0) 先启动 MySQL 并建库（本项目不使用 H2）
mysql -u<DB_ADMIN_USER> -p -e "CREATE DATABASE IF NOT EXISTS chainsip DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"

# 1) 构建 + 启动
cd backend
mvn -s settings-2x.xml "-Dmaven.repo.local=~/.m2/repository" clean package
& '<JDK8_HOME>\bin\java.exe' -jar target\chainsip-backend.jar
```

端口冲突时可用 `--server.port=8082` 起一个临时实例（本仓库联调常用这一招）。

### 6.2 初始账号与自检

**初始账号**（仅在数据库为空时自动创建；启动日志会提示 ADMIN 机构链账户怎么配）：

| 账号 | 密码 | 角色 | 关联机构链账户 |
|---|---|---|---|
| admin | ChangeMe_123456 | ADMIN | 由 `chainsip.crypto.admin-private-key`（或环境变量 `CHAINSIP_ADMIN_PRIVATE_KEY`）注入；**留空则没有机构私钥、发不出交易** |

其余账号**不预置**，按层级创建：管理员 → 社保局 → 缴费单位 → 职工（见 §6.4）。
创建 BUREAU / SPONSOR / REGULATOR / EMPLOYEE 时后端**自动生成该账号的链账户**
（`BUREAU_` / `SPONSOR_` / `REGULATOR_` / `EMP_` + 6 位随机大写十六进制），响应带 `institutionCode` 与 `chainAddress`；
`GET /api/auth/me` 与 `POST /api/auth/login` 的账号对象里同样带 `chainAddress`。
密码只以 PBKDF2 派生值存进 `cs_account`（不存明文），登录用户可用 `POST /api/auth/password` 自助修改。

**自检命令**：

```powershell
# 接口文档
start http://127.0.0.1:8080/swagger-ui/index.html

# 健康检查（含 MySQL / Redis / 链网关）
curl.exe http://127.0.0.1:8080/api/public/health

# 链状态（含 adminAddress / adminOnChainRole / 两个合约是否已配置）
curl.exe -H "Authorization: Bearer <token>" http://127.0.0.1:8080/api/chain/status

# 数据库：6 张 cs_* 表是否就绪
mysql -u<DB_ADMIN_USER> -p<DB_PASSWORD> -e "use chainsip; show tables;"

# 上链留痕（排障用：这里能看到合约错误名与 txHash 原文）
mysql -u<DB_ADMIN_USER> -p<DB_PASSWORD> -e "use chainsip; select created_at,institution_code,function_name,success,message,tx_hash from cs_chain_tx_log order by id desc limit 20;"
```

### 6.3 合约与地址

合约源码在 `../contracts`（**Solidity 0.6.10**）。**部署/编译都必须开启优化器**：关掉时 ChainSIP 运行时代码
39919 B 超过 EIP-170 的 24576 B 上限会被拒；开启后 24353 B（ChainSIPTransfer 10862 B）。

**当前已部署（部署者 = 后端 ADMIN 机构地址，故后端就是链上 ADMIN，无需 `addAdmin` 交接）：**

| 合约 | 地址 |
|---|---|
| ChainSIP | `<ACCOUNT_ADDRESS>` |
| ChainSIPTransfer | `<SPONSOR_ADDRESS>` |

**链被重置后重新部署**（可选工具；**默认不参与构建**，因为它会在链上产生真实交易，必须显式开启；
**不内置任何私钥**：部署者私钥取自 `-Dchainsip.deploy.privateKey` 或环境变量 `CHAINSIP_ADMIN_PRIVATE_KEY`）：

```powershell
cd backend
mvn -s settings-2x.xml "-Dmaven.repo.local=~/.m2/repository" test -Dtest=DeployContractsTest `
  -Dchainsip.deploy=true -Dchainsip.deploy.privateKey=<部署者私钥64位hex>
```

**改了合约后同步 ABI/bin**：在 FISCO 控制台（或 WeBASE 合约 IDE）用 **0.6.10 + 优化器**编译
`contracts/webase/ChainSIP.sol` 与 `contracts/webase/ChainSIPTransfer.sol`；
控制台编译后会在其合约目录下产出 `abi` / `bin` 文件（FISCO 控制台一般在 `contracts/sdk/abi` 与
`contracts/sdk/bin`，**以控制台实际输出为准**）。把产出的文件按同名覆盖到 `src/main/resources/abi/`
（文件名必须完全一致，后端按这四个固定名字加载）：

```
src/main/resources/abi/ChainSIP.abi          ← 核心合约 ABI
src/main/resources/abi/ChainSIP.bin          ← 核心合约部署字节码
src/main/resources/abi/ChainSIPTransfer.abi  ← 转移合约 ABI
src/main/resources/abi/ChainSIPTransfer.bin  ← 转移合约部署字节码
```

这四个文件已在仓库里，**只有合约接口或字节码变了才需要覆盖**。

### 6.4 首次使用顺序

```
账号创建层级：管理员 ──▶ 社保局 ──▶ 缴费单位 ──▶ 参保职工
（bureauA / sponsor1 / regulator / employee1 都是"建议你创建时使用的名字"，不是系统预置账号）
（链上地址在创建账号时自动生成，链上注册也自动完成；下面的 register 类接口只用于自动注册失败后的补救）

0)  admin 登录 → POST /api/admin/accounts        创建社保局账号（BUREAU，可带 name/city；自动生成机构链账户并自动上链注册）
1)              POST /api/admin/bureaus/authorize 授权该社保局（注册已在创建时自动完成；若 onChainRegistered=false 才需补救）
2)  （补救）admin → GET /api/admin/institutions/pending 查看未注册/未授权机构；POST /api/admin/institutions/{code}/register（body {city}）补注册
3)  bureauA 登录 → POST /api/bureau/accounts     创建缴费单位账号（SPONSOR，创建即自动上链注册，由后端用 ADMIN 私钥代签）
4)  admin 登录 → POST /api/admin/deposit         给单位统筹账户注资（POST /api/admin/sponsors 是手工补注册接口，正常不需要）
5)              POST /api/admin/regulators      创建监管账号（可选，创建即自动注册）
6)  sponsor1 登录 → POST /api/sponsor/accounts   创建职工账号（EMPLOYEE，自动生成链上地址）
7)  sponsor1     → POST /api/sponsor/employees   用职工账号自动生成的 chainAddress 建档
8)              POST /api/sponsor/payments      按月缴费
9)  employee1 登录 → GET /api/employee/account   查账户与缴费记录
10)             POST /api/employee/transfer/apply 发起跨城市转移
11) bureauA 登录 → POST /api/bureau/transfers/authorize 放行（前置：原单位已减员）
12)             POST /api/bureau/transfers/{id}/receive 接收
13) regulator 登录 → GET /api/regulator/dashboard 监管大屏（只读）
```

> **链上注册由后端自动完成**：`addBureaus` / `addSponsorAccount` / `addRegulatorAccount` 都是 `onlyAdmin`。
> **只有自动注册失败**（未填城市 / 链未就绪 / 链上回滚等）才留下待办，用
> `POST /api/admin/institutions/{code}/register`（body `{city}` → `{code,role,address,ok,message}`；**失败也是 HTTP 200**，看 `ok`；
> `未授权` 行只补授权、不重复注册；已就绪的行直接返回"已注册，无需重复操作"且不发交易）补一次 —— **这是补救入口，不是必经步骤**。
> 若单位的链上地址没能注册成功，第 7 步建档会因合约 `UnknownSponsor`/`NotAuthorized` 回滚，
> 这正是"权限在链上"的体现；另外合约有 **30 天缴费周期守卫**，同一职工一个周期内重复缴费会被
> `PaymentCycleNotElapsed` 回滚（正常行为，界面会显示成「当前周期已缴费」）。

### 6.5 在 IntelliJ IDEA 里运行

**用哪个 JDK（最常见的坑）**：本项目是 **Java 8 + Spring Boot 2.7 + Spring 5.3**，可用 JDK **8 / 11 / 17 / 21**；
**不要用 JDK 22 及以上**（Spring 5.3 的 ASM 不认新版本 class 文件，报 `Unsupported class file major version 68`；
Lombok 1.18.30 也不支持，报 `NoSuchFieldError: JCTree$JCImport`）。pom 里已加 JDK 护栏，用错版本构建阶段就会提示。

| JDK | 结果 |
|---|---|
| **JDK 8**（`<JDK8_HOME>`） | ✅ 编译、测试、启动、连链全部实测通过（推荐） |
| JDK 21 | 未在 2.x 下回归验证；如遇 JNI（WeDPR 原生库）加载问题请切回 JDK 8 |
| JDK 24 | ❌ Spring 5.3 与 Lombok 均不支持 |

**配置步骤**：

1. **用 pom 打开工程**：`File → Open` → 选 **`backend/pom.xml`** → *Open as Project*。
   不要打开仓库根目录 `<PROJECT_DIR>`（根目录不是 Maven 工程，IDEA 会把 `backend/src/main/java` 挂到根模块名下，
   而根模块没有依赖，结果满屏 "cannot resolve symbol"）。若已这么打开：
   `File → Project Structure → Modules`，在根模块 Sources 里取消勾选 `backend/src/main/java`。
2. **改 SDK**：`Project Structure → Project` → *SDK* 选 **1.8**（没有就 `Add SDK → JDK` 指向 jdk1.8.0_202），
   *Language level* 选 **8**；再到 `Modules → chainsip-backend → Sources` 把 Language level 设为 *Project default*。
3. **Maven**：`Settings → Build Tools → Maven` → *User settings file* 选 **`backend/settings-2x.xml`**
   （否则本地仓库落到只读目录报 AccessDenied），*Runner → JRE* 选 **1.8**，然后 `Reload All Maven Projects`。
4. **运行配置**：`Run → Edit Configurations → + → Spring Boot`
   - Main class：`com.chainsip.ChainSipApplication`
   - Use classpath of module：`chainsip-backend`
   - **Working directory：`$ProjectFileDir$`**（即 `backend` 目录，否则读不到 `./sdk/config.toml`）
5. **Lombok**：新版 IDEA 已内置插件；若仍报 `cannot find symbol: getXxx()`，检查
   `Settings → Build → Compiler → Annotation Processors` 里 *Enable annotation processing* 是否勾上。

> 不想动 IDEA 配置的临时办法：在 IDEA 的 Terminal 里 `set JAVA_HOME=<JDK8_HOME>` 后运行
> `mvn -s settings-2x.xml "-Dmaven.repo.local=~/.m2/repository" spring-boot:run`，
> 这条路径只走 Maven 自己的 JDK 设置，与 IDEA 的 Project SDK 无关。

---

## 7. 常见问题

### 7.1 报错对照表

| 现象 | 原因与处理 |
|---|---|
| `parse Config ... failed, error info:`（后面是空的） | 2.x SDK 把这几种底层异常包成了这一句。真实原因看日志里的"根因链"：① 缺 `bcpkix`（→ 加依赖）；② `config.toml` 里的数字没加引号；③ `keyStoreDir` 目录不存在，SDK 退化到 `getResource("")`，在 fat jar 下抛 `IllegalArgumentException: name`（网关已自动建目录 + 换类加载器） |
| `Missed certificates: [conf\ca.crt,…] currentPath: <启动目录>` | 2.x 的 `certPath` 按**进程工作目录**解析；网关会把配置物化并改写成绝对路径，正常不会遇到 |
| `ClassNotFoundException: org.bouncycastle.openssl.PEMParser` | 缺 `bcpkix-jdk18on`（SDK 只传递了 `bcprov`） |
| `NumberFormatException: For input string: "x1"` | 2.x 的 `createKeyPair(String)` **不接受 `0x` 前缀、不接受大写**；代码里已统一 normalize |
| TLS / 证书相关错误 | `sdk/conf/` 下缺 `ca.crt`、`sdk.crt`、`sdk.key`，或 `config.toml` 中 `certPath` 不对 |
| `Failed to load library`（JNI） | 2.x 的 ECDSA 走 WeDPR JNI，默认会写 `~/.fisco/nativeutils/native.lock`；网关已改为把原生库解压到 `sdk/native/` 并设 `java.library.ffipath`，绕开该目录 |
| 读接口返回 `AccountNotVisible` / `SponsorNotVisible` | 读调用没带（或带错了）身份，见 §2.2；确认走的是 `ChainGateway.call(account, …)`，需要越权读取时用 `ChainTxService.callAs` |
| 编码报 `Illegal embedded sign character` | 参数值里含**连字符**（如 `Company-A`）会被 SDK 当数字解析；业务值避免连字符（前端中文名不受影响） |
| 界面看不到真实失败原因 | 合约错误名与 txHash **只出现在后端日志与 `cs_chain_tx_log.message`**，不接口返回（见 §2.6 与 §7.2） |
| 合约部署失败 / 合约过大 | 编译必须选 **0.6.10 且勾选优化器**；两个合约运行字节码约 22.4KB / 10.9KB，均在 24576 上限内 |
| 创建机构账号返回 `onChainRegistered: false` | 自动上链注册没成功（未填 `city`、链未就绪、链上回滚，或 ADMIN 私钥没配/不是链上 ADMIN）→ 先查 `GET /api/admin/institutions/pending`，再用 `POST /api/admin/institutions/{code}/register`（body `{city}`，失败也是 HTTP 200，看 `ok`）补一次；若因 `NotAnAdmin`，先把 `chainsip.crypto.admin-private-key` 配成链上 ADMIN。**这是补救路径**，正常情况创建账号时已自动注册 |
| 重启后机构地址变了 | 机构私钥只在**创建账号时生成一次**（`cs_institution_key` 密文落库，重启不变）；会变的是 ADMIN —— `chainsip.crypto.admin-private-key` 每次启动都会重写 `code=ADMIN` 的机构密钥。改过它就要确认链上一致：看 `GET /api/chain/status` 的 `adminAddress` / `adminOnChainRole`，必要时用部署者账户调 `addAdmin(adminAddress)`，或用 `POST /api/admin/institution-keys` 导入原来那把私钥 |
| `Unsupported class file major version 68` | 用了 JDK 24；换 JDK 8 / 21 |
| `NoSuchFieldError: ...JCTree$JCImport` | Lombok 与该 JDK 不兼容；换 JDK 8 / 21 |
| `cannot find symbol: method getXxx()` | 注解处理未开启 / IDEA 用的不是 Maven 模块的类路径 |
| 满屏 `cannot resolve symbol`（spring、mybatis 等） | 工程打开方式不对（见 §6.5 第 1 步）或 Maven 未重新导入 |
| `AccessDeniedException`（Maven 写仓库） | 全局/用户级 settings 把本地仓库钉在只读目录；用 `settings-2x.xml` + `"-Dmaven.repo.local=~/.m2/repository"` |
| `No plugin found for prefix '.repo.local=E'` | 参数写成了反斜杠形式；改用正斜杠并加引号 |
| `Unable to access jarfile` | jar 正在被写入或正被运行占用；Windows 下先停进程再打包 |
| `NoClassDefFoundError: javax/xml/bind/DatatypeConverter` | 旧版 jjwt 在 JDK 9+ 上的问题；本项目已用 0.11.5，不会再出现 |

### 7.2 合约错误 → 中文提示

合约 `require(cond, "Xxx")` 回滚时，回执里带的是**错误名与 txHash**
（例如 `交易已上链但合约执行失败：PaymentCycleNotElapsed（txHash=0x60e5…）`）。
这些东西**不进界面**：接口响应只给一句中文人话；原始错误名与 txHash 保留在**后端日志**与
**`cs_chain_tx_log.message`** 里。映射集中维护在 `common/ContractErrorMessages.java`
（`contracts/*.sol` 里全部错误名的全量清单，共 47 个 + 8 条 SafeMath + 兜底）。

| 界面看到的中文提示 | 真实原因（合约错误名） |
|---|---|
| 当前周期已缴费（同一职工 30 天内只能缴一次） | `PaymentCycleNotElapsed` |
| 单位统筹账户余额不足，请先让管理员或社保局注资 | `InsufficientSponsorBalance` |
| 当前账号不是链上管理员，无法执行该操作 | `NotAnAdmin` |
| 当前账号无权执行该操作 | `NotAuthorized` |
| 当前账号无权查看该数据 | `AccountNotVisible` / `SponsorNotVisible` / `TransferNotVisible` |
| 该职工已参保，无需重复建档 | `AlreadyInsured` |
| 该职工尚未在链上建档 | `UnknownEmployee` |
| 该缴费单位尚未在链上注册 | `UnknownSponsor` |
| 该社保局尚未在链上注册 / 尚未获得授权 | `UnknownBureau` / `BureauNotAuthorized` |
| 该城市已绑定其他社保局 | `CityAlreadyBound` |
| 该城市尚未注册社保局，请先完成社保局登记 | `CityNotRegistered` |
| 该身份证号已被其他职工使用 | `IdNumberAlreadyUsed` |
| 请先为该职工办理减员，再办理转移 | `EmployeeNotSuspended` |
| 该职工不属于本单位，无法操作 | `NotYourEmployee` |
| 该职工已有在途的转移申请，请先办结 | `TransferInProgress` |
| 该转移申请当前的状态不支持此操作 | `InvalidTransferStatus` |
| 账户仍有余额，请先结清后再办理 | `BalanceNotZero` |
| 金额必须大于 0 | `ZeroAmount` |
| 提交的信息不完整 | `ZeroAddress` / `EmptyString` |
| 操作未成功（合约拒绝了这笔交易），请联系管理员 | 其余未收录的错误名（兜底，绝不透出错误名） |

> 排查真实原因：`SELECT created_at, institution_code, function_name, message, tx_hash FROM cs_chain_tx_log ORDER BY id DESC LIMIT 20;`
> 以及后端日志里的 `上链失败：<合约>#<函数> by <机构> 原因=…`。
> 注意：**交易成功**时 `txHash` 仍会作为正常数据出现在 `SendResult.transactionHash` 里（那是数据，不是报错文案）。

> 完整部署教程（含环境准备、数据库、合约部署与 ADMIN 对齐、后端/前端、端到端验收、排错）见仓库根目录 [README.md](../README.md) 第 5 节与第 7 节。

---

## 8. 已知遗留与下一步

1. **修 `/api/index/events`**：适配 2.x 回执里的事件字段形态，让链上事件索引重新有数据
   （`ChainEventParser` 尚未适配，该接口目前返回 0 条）；`cs_chain_tx_log` 不受影响，一直是全的；
2. 区块事件监听器常驻同步索引，替代"发交易后顺带写索引"；
3. 机构私钥接入 KMS/HSM；Redis 已接入（本机 Docker、db1、缓存 + 健康检查），
   后续多实例部署可继续把一次性 nonce/会话状态迁到 Redis；
4. `crypto/WalletSignatureVerifier.verify` / `recoverCandidates` 目前无业务调用方
   （原服务于已删除的手动地址绑定），保留以备将来扩展。
