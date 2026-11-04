# ChainSIP · 区块链养老保险平台

把养老保险业务放到联盟链上的全栈示例：**链上是权威账本与权限，链下只做认证与索引**。

**项目状态**：源码、合约、SQL 与部署脚本完整提供；链地址、合约地址、ADMIN 私钥、数据库口令等环境相关配置全部通过 `application.yml` 或环境变量注入，仓库不保存任何真实部署信息。后端（Spring Boot 2.7 + JDK 8 + FISCO Java SDK 2.10.1 + MySQL 8 + Redis 7）与前端（Vue 3 + Vite + Element Plus）可直接构建运行，首次登录使用 `admin / ChangeMe_123456`（生产务必修改）。

> **公共仓库模式**：仓库已移除真实用户名、口令、私钥、IP 与部署地址。首次运行请复制 .env.example 并按实际环境修改；安全约定与密钥轮换说明见 SECURITY.md。

> 本文档由根目录原来的三份说明（项目总览 / 部署教程 / 使用手册）合并而成：
> **部署与验收见第 5 节，日常使用见第 6 节，排错见第 7 节，本轮性能与工程化优化见第 9 节**。

## 目录导航

- [1. 项目简介](#1-项目简介)
- [2. 技术栈与端口](#2-技术栈与端口)
- [3. 目录结构](#3-目录结构)
- [4. 账号与角色](#4-账号与角色)
  - [4.1 账号创建层级](#41-账号创建层级)
  - [4.2 链上地址自动生成并绑定](#42-链上地址自动生成并绑定)
  - [4.3 两个职工口径](#43-两个职工口径)
  - [4.4 初始账号与角色能力](#44-初始账号与角色能力)
  - [4.5 链下角色与链上角色](#45-链下角色与链上角色)
- [5. 部署](#5-部署)
  - [5.1 快速开始](#51-快速开始)
  - [5.2 环境准备](#52-环境准备)
  - [5.3 数据库](#53-数据库)
  - [5.4 合约部署](#54-合约部署)
  - [5.5 后端构建与启动](#55-后端构建与启动)
  - [5.6 前端](#56-前端)
  - [5.7 配置速查](#57-配置速查)
  - [5.8 端到端验收清单](#58-端到端验收清单)
- [6. 使用手册](#6-使用手册)
  - [6.1 启动与自检](#61-启动与自检)
  - [6.2 登录与账号信息](#62-登录与账号信息)
  - [6.3 首页概览](#63-首页概览)
  - [6.4 按角色逐页操作](#64-按角色逐页操作)
  - [6.5 两条业务演练](#65-两条业务演练)
  - [6.6 合约调试台](#66-合约调试台)
  - [6.7 链上索引](#67-链上索引)
  - [6.8 对账怎么读](#68-对账怎么读)
  - [6.9 API 使用与接口速查](#69-api-使用与接口速查)
  - [6.10 合约错误名对照](#610-合约错误名对照)
  - [6.11 一句话速查](#611-一句话速查)
- [7. 常见问题](#7-常见问题)
  - [7.1 构建与环境](#71-构建与环境)
  - [7.2 链接与证书](#72-链接与证书)
  - [7.3 合约与链上账号](#73-合约与链上账号)
  - [7.4 数据库](#74-数据库)
- [8. 附录：迁移背景](#8-附录迁移背景)
- [子模块文档](#子模块文档)

---

## 1. 项目简介

一个把养老保险业务放到联盟链上的平台：**链上是权威账本，链下只做认证与索引**。

| | 链上（合约） | 链下（后端 / 前端） |
|---|---|---|
| 内容 | 角色、公司/职工档案、个人账户与统筹权益、城市统筹基金池、转移状态机 | 平台账号、密码派生值、地址绑定、会话、机构私钥托管、事件索引、上链留痕 |
| 权限 | **按交易签名地址（`msg.sender`）判权**，链下写错也照样回滚 | 只做第一道门（`@RequireRole`）与界面可见性 |

角色模型（链上 `Role` 枚举）：`ADMIN`(1) 平台管理员、`BUREAU`(2) 社保局、`SPONSOR`(3) 缴费单位、`REGULATOR`(4) 监管（**纯只读**）；`EMPLOYEE` 不是角色，而是"有参保档案"的状态。

**三条资金守恒恒等式**（链上 `getSystemStats`，首页、监管大屏与验收都按它核对）：

1. `deposited == sponsorPoolTotal + paid`
2. `paid == personalFund + cityPoolTotal + escrowedPersonal + escrowedOverall`
3. `cityPoolTotal == overallRights`

缴费规则：个人 8% 进个人账户（实账）、单位 19% 进**参保城市的统筹基金池**；缴费基数按 [3200, 24000] 夹取；同一职工 30 天内只能缴一次。

> 一句话记住：**链下决定你能看见什么，链上决定你能做成什么**（合约按交易签名地址 `msg.sender` 判权）。

---

## 2. 技术栈与端口

| 层 | 技术 | 版本 | 说明 |
|---|---|---|---|
| 链 | **FISCO BCOS** | **2.11.1** | 5 节点 = 4 共识（sealer）+ 1 监管观察节点（observer）、PBFT；群组 `group.1`（groupId=1），拓扑见 [`docs/CHAIN-TOPOLOGY.md`](docs/CHAIN-TOPOLOGY.md) |
| 合约语言 | **Solidity** | **0.6.10** | 2.x 官方支持上限；**编译必须开优化器**（见 §5.4） |
| 合约库 | 自研 | — | `Roles`（角色+修饰器）、`Policy`（费率/基数/周期）、`SafeMath`（0.6 无内建溢出检查，资金运算全靠它） |
| 后端框架 | Spring Boot | 2.7.18 | Web + Validation，内嵌 Tomcat |
| 后端语言 | **JDK** | **8** | 与 SDK 2.x 的 Java 8 字节码匹配（本机 `<JDK8_HOME>`） |
| 链 SDK | **FISCO BCOS Java SDK** | **2.10.1** | 2.x 最新；含 WeDPR JNI 原生库，可在 Windows 直连 |
| 链 SDK 依赖补充 | BouncyCastle `bcprov` + **`bcpkix`** | 1.77 | **必须显式加 `bcpkix`**：SDK 的 SSL 初始化要读 PEM，但它的 POM 只带 `bcprov` |
| ORM | MyBatis-Plus | 3.5.3.1 | 分页插件 + BaseMapper |
| 数据库 | **MySQL 8** | **必需** | 本项目已不再使用 H2：账号、地址绑定、机构私钥、上链留痕、事件索引都存这里 |
| 缓存 | **Redis** | **7.x（可选）** | 本机 Docker 容器 `chainsip-redis`，默认 `127.0.0.1:6379/db1`；用于热点查询缓存、防击穿、限流与写接口幂等，故障自动降级 |
| 鉴权 | jjwt | 0.11.5 | JWT；密码 PBKDF2-HMAC-SHA256（12 万次迭代）；机构私钥 AES-256-GCM 落库 |
| 接口文档 | springdoc-openapi | 1.6.15 | `/swagger-ui/index.html` |
| 前端框架 | **Vue 3** + **Vite** | 3.5 / 5.4 | 组合式 API |
| 前端 UI | Element Plus | 2.8 | 组件库（含图标） |
| 前端状态/路由/请求 | Pinia + Vue Router + axios | 2.2 / 4.4 / 1.7 | axios 统一拦截器（JWT、错误提示、503 链未就绪提示） |
| 钱包签名 | 联调辅助接口 `POST /api/auth/demo/sign` | — | 只用于联调（用请求里自带的私钥签名，不涉及服务端密钥）；**链上地址由后端在创建账号时自动生成并绑定，用户不需要签名**（见 §4.2） |
| 构建/部署工具 | Maven 3.9、**FISCO 控制台**（带 0.6 编译器） | — | 合约编译与部署全走控制台 / WeBASE；仓库内不再带任何 Node 构建脚本，也不需要本机装 solc |

**端口一览**

| 端口 | 用途 |
|---|---|
| 8080 | 后端 REST + Swagger |
| 5173 | 前端 dev server（Vite，代理 `/api` → 8080） |
| 20200-20203 | 链 channel 端口（SDK 连它；**不是** 30300） |
| 30300-30303 | 链 p2p 端口 |
| 8545-8548 | 链 jsonrpc 端口（可直接 curl 调试） |
| 3306 | MySQL（后端唯一数据源，必需） |
| 6379 | Redis（本机 Docker `chainsip-redis`；db0=同机其他项目，db1=ChainSIP） |

---

## 3. 目录结构

```
ChainSIP/
├── README.md                      # 本文：项目总览 + 部署 + 使用手册（三份根文档已合并于此）
├── contracts/                     # 合约（Solidity 0.6.10）
│   ├── Roles.sol  Policy.sol  SafeMath.sol
│   ├── PensionAccount.sol  PensionRegulator.sol  PensionBank.sol
│   ├── ChainSIP.sol              # 核心：账户 + 资金 + 监管
│   ├── ChainSIPTransfer.sol      # 转移：社保局 + 跨城市转移状态机
│   └── webase/                   # ★ 部署用：两个自包含文件（已展开基类，无 import）
│       ├── ChainSIP.sol          #   核心：账户 + 资金 + 监管
│       └── ChainSIPTransfer.sol  #   转移：社保局 + 跨城市转移状态机
├── backend/                       # Spring Boot 2.7 + JDK 8 + SDK 2.10.1（细节见 backend/README.md）
│   ├── settings-2x.xml           # Maven 设置：本地仓库与离线仓库（必需，见 §5.5）
│   ├── sdk/{config.toml, conf/, native/}
│   └── src/main/{java/com/chainsip/**, resources/**}
└── frontend/                      # Vue 3 + Vite + Element Plus（细节见 frontend/README.md）
```

后端分层：`controller`（REST）→ `service`（业务 + 上链门面）→ `chain`（`ChainGateway` 接口 + FISCO 实现 + ABI 导出）→ SDK；横切：`security`（JWT/拦截器/`@RequireRole`）、`crypto`（密码/私钥加密/钱包验签）、`entity`+`mapper`（6 张表）。

---

## 4. 账号与角色

### 4.1 账号创建层级

平台账号**由上一级创建下一级**，不提供自助注册：

```
管理员(ADMIN)  ──创建──▶  社保局(BUREAU)  ──创建──▶  缴费单位(SPONSOR)  ──创建──▶  参保职工(EMPLOYEE)
     │                        │                          │
     ├─ 管理员创建机构账号：自动上链注册（addBureaus+授权 / addSponsorAccount / addRegulatorAccount）
     └─ 社保局创建缴费单位：同样创建即自动上链注册（后端用 ADMIN 私钥代签）；仅注册失败才进「机构管理 → 待上链注册」补救
```

| 谁 | 在哪创建 | 能创建什么 | 建完还要做什么 |
|---|---|---|---|
| **管理员** | 「平台账号」`/admin/accounts` | 任意角色（含自己的下级社保局） | **创建即自动上链注册**：填了名称/城市时，后端会自动 `addBureaus`+授权 / `addSponsorAccount` / `addRegulatorAccount`；**只有注册失败**（缺名称/城市等）才落到「机构管理 → 待上链注册」补救 |
| **管理员** | 「机构管理」`/admin/institutions` | 链上注册、注资、合约接线、**待上链注册（补救入口）** | ① 部署合约后做一次 `setTransferContract`；② **若有注册失败的机构**，在「待上链注册」页签里填城市点一下「注册」补上（这是补救入口，正常自动注册成功时无需操作） |
| **社保局** | 「缴费单位账号」`/bureau/accounts` | **只能创建缴费单位（SPONSOR）** | **创建即自动上链注册**：合约里 `addSponsorAccount` 是 `onlyAdmin`，由**后端用 ADMIN 私钥代签**（链上仍由 ADMIN 署名）；**只有自动注册失败**的机构才出现在管理员的「机构管理 → 待上链注册」里，由管理员补一次 |
| **缴费单位** | 「职工账号」`/sponsor/accounts` | **只能创建参保职工（EMPLOYEE）** | ① 职工账号的链上地址**创建时自动生成**（响应里带 `chainAddress`）；② 单位用该地址在「职工管理」里建档（`addEmployee`）——职工**不需要**链上注册 |

要点：

- 上一级创建下一级的**平台账号**（用户名 / 密码 / 角色）；创建时**自动生成链上地址**（见 §4.2），**用户不需要手输地址，也不需要做任何绑定**；
- **机构的链上注册由后端自动完成**（合约里 `addBureaus`/`addSponsorAccount`/`addRegulatorAccount` 都是 `onlyAdmin`，ADMIN 类交易用 ADMIN 私钥签名）：
  - 管理员创建机构账号时**自动注册**，创建响应带 `onChainRegistered` / `onChainMessage`；
  - 社保局创建缴费单位时**同样创建即自动注册**：`addSponsorAccount` 是 `onlyAdmin`，由**后端用 ADMIN 私钥代签**（链上仍由 ADMIN 署名，治理模型不变）；
  - **只有自动注册失败**（未填城市 / 链未就绪 / 链上回滚等）才会留下待办：该机构出现在「机构管理 → **待上链注册**」页签（`GET /api/admin/institutions/pending`）里，管理员填入城市点「注册」（或「全部注册」批量）补一次即可 —— **这个页签是补救入口，不是必经步骤**；
  - **开社保局账号前会先做城市占用预检**：转移合约里一个城市只能绑一个社保局（`cityBureau`），后端在建号前先查 `bureauOfCity(city)`，命中已有社保局就直接 400 返回占用者名称与地址，**不会先建号再等链上 `CityAlreadyBound` 回滚**；链读不通时降级放行（仍由写交易阶段报真实原因）。「机构管理」的城市输入框也会列出已被占用的城市并做同样的前端预检；
  - 「待上链注册」以**链上状态**判定：`roleOf` 角色不匹配 = `未注册`；社保局已注册但未授权 = `未授权`（这种情况只补授权、不会重复注册；已就绪的行直接返回"已注册，无需重复操作"且不发交易）。补注册走 `POST /api/admin/institutions/{code}/register`（body `{city}` → `{code,role,address,ok,message}`，**失败也是 HTTP 200**，看响应里的 `ok`）；
- **EMPLOYEE 账号不需要链上注册**（职工由单位建档 `addEmployee` 时使用其自动生成的地址）；
- 未注册 / 未授权时机构发交易会被 `NotAuthorized` / `UnknownSponsor` 回滚，这正是去「待上链注册」补救的信号；
- 越权创建会被拒绝（HTTP 403，例如社保局试图创建管理员）；
- 下文出现的 `bureauA` / `sponsor1` / `regulator` / `employee1` 都是**建议你创建时使用的名字**，不是系统预置账号。

### 4.2 链上地址自动生成并绑定

链上地址**在创建账号时由后端自动生成，并同时写为账号的绑定地址**（`cs_wallet`，`verified=true`、首个为主地址）：

| 账号 | 地址从哪来 |
|---|---|
| ADMIN | 由配置项 `chainsip.crypto.admin-private-key`（或环境变量 `CHAINSIP_ADMIN_PRIVATE_KEY`）注入 —— 通常就是"你用 FISCO 控制台 / WeBASE 部署合约的那个账户"；启动后可在 `GET /api/chain/status` 的 `adminAddress` 里看到 |
| 社保局（BUREAU） | 管理员创建社保局账号时自动生成（`BUREAU_` + 6 位随机大写十六进制），创建响应与列表接口都给出 `chainAddress` |
| 缴费单位（SPONSOR） | 社保局创建缴费单位账号时自动生成（`SPONSOR_` + 6 位随机大写十六进制），创建响应里给出 `chainAddress` |
| 监管（REGULATOR） | 管理员创建监管账号时自动生成（`REGULATOR_` + 6 位随机大写十六进制），创建响应与列表接口都给出 `chainAddress` |
| 职工（EMPLOYEE） | 缴费单位创建职工账号时自动生成（`EMP_` + 6 位随机大写十六进制），创建响应里给出 `chainAddress` |

- 机构链账户私钥以 **AES-256-GCM 密文落库**，接口**不返回明文**；创建响应带 `institutionCode` 与 `chainAddress`，列表接口 `GET /api/admin/accounts`、`/api/bureau/accounts`、`/api/sponsor/accounts` 每行也带 `chainAddress`；其中 `GET /api/admin/accounts` 还带每行 `onChainRole`（合约 `roleOf` 结果，链未就绪时为 `null`，前端显示「—」而不是"链未就绪"）；
- `GET /api/auth/me` 与 `POST /api/auth/login` 的账号对象里都带 `chainAddress`（该账号自己的链上地址），**前端所有页面用它作为"我 / 本机构的链上地址"**；`primaryAddress`（已验签的主地址）同样不再为空，作为兼容字段保留；
- **创建后地址不会被改动**，后端发起链上交易**始终使用系统为该账号生成的地址**；重启后地址不变（机构私钥只在创建账号时生成一次）；
- **手动地址绑定功能已移除**：原来的「我的链上地址」绑定页与 `POST /api/auth/wallet/message`、`POST /api/auth/wallet/bind`、`GET /api/auth/wallets` 接口前后端都已删除，因此**不存在绑定失败的排错项**；启动时空库或老账号若缺少绑定记录，由后端启动流程按其机构链账户地址幂等补写；
- ADMIN 的地址必须与**链上 ADMIN（= 部署合约的那个账户）**一致，否则 ADMIN 类交易会被回滚成 `NotAnAdmin`（见 §5.4）；
- 其余机构的地址由后端在创建账号时**自动注册上链**（社保局创建缴费单位时由后端用 ADMIN 私钥代签），只有自动注册失败才需要到「待上链注册」补救。

### 4.3 两个职工口径

页面上有两个长得像但**数据源不同**的职工下拉，别混用：

| 下拉 | 数据来源 | 含义 / 用途 | 为空说明 |
|---|---|---|---|
| **选择职工账号（用于建档）**（「职工管理」→「为职工建档」对话框） | `GET /api/sponsor/accounts`（**账号口径**） | 建 `addEmployee` 时选它，选中后自动带出该账号的链上地址。**包含刚创建、还没建档的账号**；已建档的账号在下拉里标注 **已建档** 并**置灰不可选**，避免重复建档被合约 `AlreadyInsured` 回滚 | 本单位还没有职工账号 → 对话框里直接给「去创建职工账号」跳转到 `/sponsor/accounts` |
| **选择已建档职工（用于减员 / 调薪 / 缴费 / 查记录）**（「职工管理」工具条、缴费管理页） | `GET /api/sponsor/employees/detail`（**链上已建档口径**） | 减员 / 调薪 / 缴费 / 查缴费记录都用它 | **还没建档时就是空的，这是正确行为不是 bug** → 空态给「为职工建档」按钮 + 跳转 `/sponsor/accounts` |

正确顺序：先在「职工账号」页建**职工账号** → 再到「职工管理 → 为职工建档」里**选这个账号建档**（地址自动带出）→ 建档后的职工才会出现在减员 / 调薪 / 缴费的下拉里；缴费记录同样只对"已建档职工"可查。

### 4.4 初始账号与角色能力

MySQL 空库启动后端时**只创建一个平台管理员账号**（表由 `schema.sql` 自动建好）：

| 账号 | 密码 | 角色 | 能看到什么 |
|---|---|---|---|
| `admin` | `ChangeMe_123456` | 平台管理员 | 机构管理、职工建档、平台账号、监管大屏、合约调试台、链上索引 |

> 如需演示四条业务线，可按 §4.1 的层级自行创建下面这些示例账号（用户名仅作示例，密码使用创建时设置的值）：
>
> | 账号 | 角色 | 身份 | 说明 |
> |---|---|---|---|
> | `bureau_demo` | BUREAU | 社保经办机构（示例） | 建缴费单位账号、办理跨城转移 |
> | `sponsor_demo` | SPONSOR | 缴费单位（示例） | 建职工账号、缴费（链上已注资 10 万） |
> | `employee_demo` | EMPLOYEE | 参保职工（示例） | 查本人账户与缴费流水、申请转移 |
> | `regulator_demo` | REGULATOR | 监管机构（示例） | **监管账户**：只读审计，链上角色 `REGULATOR`，从监管观察节点读账 |
>
> 密码以 **PBKDF2 派生值**存进 MySQL 的 `cs_account` 表（不存明文），登录后可用
> `POST /api/auth/password`（自助改密码）或 `POST /api/admin/accounts/{id}/password`（管理员改他人密码）修改。

其余账号**不预置**，按 §4.1 的业务层级由上一级创建。各角色能力：

| 角色 | 能做什么 |
|---|---|
| **ADMIN** 平台管理员 | 创建各级账号、注册公司/社保局/监管账户（链上）、注资、职工建档、合约接线 |
| **BUREAU** 社保局 | 创建缴费单位账号；转移放行（需原单位已减员）/ 接收，基金在城市统筹池之间划转 |
| **SPONSOR** 缴费单位 | 创建职工账号；给自己单位职工建档/减员/调薪、按月缴费（个人 8% + 单位 19%，基数按 [3200,24000] 夹取，30 天防重复） |
| **REGULATOR** 监管 | 纯只读：查全平台账本、流水、对账 |
| **EMPLOYEE** 参保职工 | 查自己的账户与缴费记录、发起跨城市转移 |

### 4.5 链下角色与链上角色

**两个概念别混**：

- **链下角色**（§4.4 表）只决定界面能看什么，存在后端数据库里；
- **链上角色**（合约里的 `Role`）决定交易能不能成功，由**交易签名地址**决定；
- 首页概览会把两者并排显示；不一致时交易会被合约回滚 —— 这是"权限在链上"的正常表现；
- `EMPLOYEE` 不是链上角色，而是"有参保档案"的状态。

---

## 5. 部署

> 运行环境：**Windows + WSL2(Ubuntu 22.04) + FISCO BCOS 2.11.1**（链在 WSL 内以**原生进程**方式运行，不使用 Docker）。
> 本项目的链已经部署好；本节同样包含"链被重置后怎么重新搭 / 重新部署合约"的步骤。
>
> **要部署到云服务器（阿里云 ECS / 腾讯云 CVM）**：
> **[`deploy/DEPLOY.md`](deploy/DEPLOY.md) 是主文档**——打包产物逐项说明（每个文件是什么、体积、丢了怎么补）、
> 区块链部分怎么落到服务器（5 节点拓扑、端口、证书、systemd、验证）、后端/数据库/nginx 步骤、验收与排错表；
> 想理解选型、安全组与多机拆分看 [`docs/DEPLOY-ECS.md`](docs/DEPLOY-ECS.md)；
> 想纯手敲、不用任何脚本看 [`docs/DEPLOY-MANUAL.md`](docs/DEPLOY-MANUAL.md)。
> 本地按本节起服务的方式不变。

### 5.1 快速开始

```powershell
# 0) 先启动 MySQL（本项目不再使用 H2；库 chainsip 需已创建）
mysql -u<DB_ADMIN_USER> -p -e "CREATE DATABASE IF NOT EXISTS chainsip DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"

# 1) 链（WSL 里，通常已在运行）
bash ~/fisco-2.11/nodes/127.0.0.1/start_all.sh

# 2) 后端（构建 + 启动）
cd backend
mvn -s settings-2x.xml "-Dmaven.repo.local=~/.m2/repository" clean package
& '<JDK8_HOME>\bin\java.exe' -jar target\chainsip-backend.jar

# 3) 前端
cd ..\frontend
npm install
npm run dev            # http://127.0.0.1:5173
```

启动后先用 `admin / ChangeMe_123456` 登录；合约地址与 ADMIN 私钥的对齐见 §5.4。

### 5.2 环境准备

| 依赖 | 版本 | 检查命令 |
|---|---|---|
| JDK | **8** | `java -version` |
| Maven | 3.6+（实测 3.9.10） | `mvn -v` |
| Node.js | 18+（实测 20.20.1） | `node -v` |
| WSL2 + Ubuntu | 22.04 | `wsl -l -v` |
| FISCO BCOS | 2.11.1（4 共识 + 1 监管观察节点，已部署） | `pgrep -af fisco-bcos` |
| MySQL | **8.0**（实测 8.0.41） | `mysql --version`（**必需**：启动后端前要能连上） |
| Redis | **7.x**（可选缓存） | `docker exec chainsip-redis redis-cli ping`（期望 `PONG`） |

### 5.3 数据库

本项目**只使用 MySQL**（已移除 H2 依赖）。启动后端前必须满足两点：

- MySQL 服务已启动；
- 库 `chainsip` 已创建 —— 表结构由后端启动时按 `schema.sql` **自动创建**（全部 `create table if not exists`，幂等）。

**① 建库（只需一次）**

```sql
CREATE DATABASE chainsip DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
```

命令行一次性执行：

```powershell
mysql -u<DB_ADMIN_USER> -p -e "CREATE DATABASE chainsip DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"
```

**② 账号**：默认用 `chainsip` / `change-me`（写在 `application.yml` 里，仅本机开发方便）。
生产建议单独建账号，并用环境变量注入，不要把密码写进仓库：

```sql
CREATE USER 'chainsip'@'%' IDENTIFIED BY '强密码';
GRANT ALL PRIVILEGES ON chainsip.* TO 'chainsip'@'%';
FLUSH PRIVILEGES;
```

**③ 表结构**：由 `backend/src/main/resources/schema.sql` 自动创建（全部 `create table if not exists`，幂等），共 6 张：

| 表 | 用途 |
|---|---|
| `cs_account` | 平台账号（密码为 PBKDF2 派生值） |
| `cs_wallet` | 账号的链上地址（创建账号时自动写入，`verified=true`） |
| `cs_institution_key` | 机构链账户私钥（AES-256-GCM 密文 + IV） |
| `cs_chain_tx_log` | 上链操作留痕（机构、合约、函数、参数、txHash、成功与否、耗时） |
| `cs_chain_event` | 链上事件索引（供名单/报表/模糊查询） |
| `cs_login_log` | 登录审计 |

**④ 启动后端**（已无需 profile）

```powershell
java -jar target\chainsip-backend.jar
```

可用环境变量覆盖连接信息：

| 变量 | 默认 |
|---|---|
| `CHAINSIP_DB_HOST` | `127.0.0.1` |
| `CHAINSIP_DB_PORT` | `3306` |
| `CHAINSIP_DB_NAME` | `chainsip` |
| `CHAINSIP_DB_USER` | `chainsip` |
| `CHAINSIP_DB_PASSWORD` | `change-me` |

**⑤ 验证**

```powershell
mysql -u<DB_ADMIN_USER> -p -e "use chainsip; show tables;"
# 期望看到上面 6 张 cs_* 表
```

**⑥ 数据重置**

- 清空业务数据：`DROP DATABASE chainsip;` 后重新 `CREATE DATABASE chainsip ...`，再启动后端 → 自动建表 + 重新创建平台管理员账号（`admin / ChangeMe_123456`）；其余账号按层级重建；
- **链上数据不会跟着重置**：链上已注册的机构地址还在。重建账号时后端会**生成新的机构私钥与新地址**（ADMIN 的地址由 `chainsip.crypto.admin-private-key` 决定），重建账号时会**自动用新地址重新上链注册**（`addBureaus` / `addSponsorAccount` / `addRegulatorAccount`，ADMIN 私钥签名；社保局创建缴费单位时由后端代签）；若自动注册失败，则到「机构管理 → **待上链注册**」补一次。另一种做法是在 `POST /api/admin/institution-keys` 里**导入原来那把机构私钥**以复用旧地址。

> 若将来要装 WeBASE 1.5.5 管理台，它需要**另外两个库**（`webasenodemanager`、`webasesign`）与自己的账号，与后端这套互不影响。

### 5.3.1 Redis（可选缓存，本机 Docker）

本机已有 `chainsip-redis` 容器（`redis:7-alpine`），映射 `127.0.0.1:6379`，无密码。
**db0 已被同机其他项目占用**，ChainSIP 默认使用 **db1**，键统一以 `chainsip:` 开头。

```powershell
docker ps --filter name=chainsip-redis
docker start chainsip-redis                 # 容器没起时
docker exec chainsip-redis redis-cli ping   # 期望 PONG
```

Redis 参与缓存、限流与幂等：不可用时后端照常启动，缓存操作自动跳过并回退进程内缓存，
限流与幂等按 fail-open 放行，
`GET /api/public/health` 的 `data.redis.ok` 会变成 `false` 并在 `warnings` 提示。
连接信息可用 `CHAINSIP_REDIS_HOST/PORT/PASSWORD/DATABASE` 覆盖。

### 5.4 合约部署

合约源码在 `contracts/`，语言 **Solidity 0.6.10**。当前流程是**使用者自己在 FISCO 控制台 / WeBASE-Front 合约 IDE 里部署**，程序这边只需把地址接上并**对齐 ADMIN**。

#### 5.4.1 编译（两个硬性要求）

1. **编译器选 0.6.10**（2.x 链的支持上限）。
   ⚠️ **WeBASE 合约 IDE 的编译器版本要在编译时手动选**，它默认可能是 0.4.x/0.5.x ——
   文件里的 `pragma solidity >=0.6.10 <0.7.0;` **管不了 IDE 的选择**。用旧版本编译会报这两类 ParserError：

   | 报错 | 真正原因 |
   |---|---|
   | `Expected pragma, import directive or contract/interface/library definition` 指向 `abstract contract Xxx {` | `abstract contract` 是 **0.6.0** 才引入的语法，旧编译器不认识 |
   | `Expected ',' but got identifier` 指向 `string calldata xxx` | 接口参数里的 `calldata` 也是较新写法 |

   → 在编译对话框里把 **Solidity 版本切成 0.6.10** 即可；编译结果面板会显示实际使用的编译器版本，确认它是 0.6.10。
2. **必须勾选/开启优化器（optimizer，runs=200）**：
   - 开优化器：ChainSIP 运行时代码 **24353 B**、ChainSIPTransfer **10862 B**（上限 24576 B）✓
   - 关优化器：ChainSIP 会到 **39919 B**，超过 EIP-170 上限，**链上部署直接被拒** ✗

本仓库**不带任何编译脚本**，编译与部署全在 FISCO 控制台 / WeBASE 界面上完成；
文件里的 `pragma solidity >=0.6.10 <0.7.0;` 已经定死语言版本，照下面的方式选编译器即可。

#### 5.4.2 部署

**方式一（WeBASE 部署）**

1. WeBASE-Front（`http://localhost:5002`）→ 合约 IDE → 粘贴**两个自包含文件**（已展开基类、无 import）：
   - `contracts/webase/ChainSIP.sol` → 部署 **ChainSIP**（无参）
   - `contracts/webase/ChainSIPTransfer.sol` → 部署 **ChainSIPTransfer**，构造参数填核心合约地址

   > 为什么不是直接上传 `contracts/ChainSIP.sol`：WeBASE 的合约 IDE 对同目录相对 `import` 支持不好，
   > 会去找同目录的 `PensionBank.sol` 而报找不到文件。`contracts/webase/` 下的两个文件是把基类就地展开后的产物：
   > `ChainSIP.sol` 含 SafeMath + Roles + Policy + PensionAccount + PensionRegulator + PensionBank + ChainSIP；
   > `ChainSIPTransfer.sol` 含 `SafeMathTransfer`（本地改名，避免与核心文件顶层符号重名）+ 内联的 `IChainSIPCore` + ChainSIPTransfer。
   > 两者顶层符号不重名，**即使 WeBASE 把同目录文件一起编译也不会冲突**。
   >
   > 这两个文件与 `contracts/` 下的多文件源是同一份代码：`contracts/*.sol` 是权威源，`contracts/webase/` 是把基类就地展开后的自包含版本。
   > **改了合约，两份都要同步改**（或重新展开一次），否则部署的东西和仓库里的源会对不上。
   >
   > 注：编译产物里会出现 `SafeMath` / `SafeMathTransfer` 库，它们的函数都是 `internal`（编译期内联），
   > **不需要单独部署，也不需要手工链接**。
2. 编译器选 **0.6.10** 并**勾选优化器**（见 §5.4.1）→ 编译；
3. 部署 `ChainSIP`（无参）→ 记下核心合约地址；
4. 部署 `ChainSIPTransfer`，构造参数填上一步的核心合约地址 → 记下转移合约地址；
5. 用**部署合约的那个账户**调 `ChainSIP.setTransferContract(转移合约地址)`（核心合约才会认转移合约）；
6. **对齐链上 ADMIN**（关键，容易漏）—— 见下面的「ADMIN 对齐」。

**方式二（推荐：控制台部署，FISCO BCOS console）**

两个要点（都容易踩）：

1. 控制台的合约目录是 **`console/contracts/solidity/`**（`deploy` 也支持直接给绝对路径）；
2. ⚠️ **默认下载的控制台内置的是 solc 0.4.25**，编译 0.6 合约必须换成带 0.6 编译器的控制台 ——
   否则会报和 WeBASE 一样的 `ParserError`（不认识 `abstract contract`）。

```bash
# 1) 换成内置 0.6 编译器的控制台（先备份 conf/ 与 accounts/）
cd ~/fisco-2.11/console && cp -r conf accounts /tmp/console-backup-$(date +%s)/
curl -#LO https://gitee.com/FISCO-BCOS/console/releases/download/v2.9.2/download_console.sh
bash download_console.sh -v 0.6          # 网络不通就照 §5.5 换镜像；GitHub 慢时用上面的 Gitee 地址
# 还原刚才备份的 conf/ 与 accounts/，并按 §5.5 把 SDK 证书拷进 conf/

# 2) 放合约（也可以不放，直接在控制台里 deploy 绝对路径）
cp <PROJECT_DIR>/contracts/webase/ChainSIP.sol         ~/fisco-2.11/console/contracts/solidity/
cp <PROJECT_DIR>/contracts/webase/ChainSIPTransfer.sol ~/fisco-2.11/console/contracts/solidity/

# 3) 进控制台（**加完文件必须重启控制台**，否则 deploy 会报 "xxx does not exist"）
cd ~/fisco-2.11/console && bash start.sh
```

> 若不想换控制台，也可以在控制台里用绝对路径部署（`deploy <PROJECT_DIR>/contracts/webase/ChainSIP.sol`），
> 但**编译器版本仍然是控制台内置的那个** —— 0.4.25 版本无论哪种方式都编译不了 0.6 合约。

控制台里依次执行（`listAccount` 可看当前签名账户地址，部署者就是它）：

```
[group:1]> deploy ChainSIP
contract address: 0x....            ← 记为核心合约地址
[group:1]> deploy ChainSIPTransfer 0x<核心合约地址>
contract address: 0x....            ← 记为转移合约地址
[group:1]> call ChainSIP 0x<核心合约地址> setTransferContract 0x<转移合约地址>
[group:1]> call ChainSIP 0x<核心合约地址> addAdmin 0x<后端 ADMIN 地址>     ← 关键，见下方 ADMIN 对齐
```

**方式三（可选：用后端自带脚本部署，便于快速验证）**

用后端自带的部署测试部署（**不内置任何私钥**：部署者私钥从 `-Dchainsip.deploy.privateKey` 或环境变量 `CHAINSIP_ADMIN_PRIVATE_KEY` 读取）：

```powershell
cd backend
mvn -s settings-2x.xml "-Dmaven.repo.local=~/.m2/repository" test -Dtest=DeployContractsTest `
  -Dchainsip.deploy=true -Dchainsip.deploy.privateKey=<部署者私钥64位hex>
```

> 这个测试**默认不参与构建**（`@EnabledIfSystemProperty`），因为它在链上产生真实交易：每执行一次就部署一对新合约。
> 平时的 `mvn package` 不会碰它；如果你用控制台/WeBASE 部署合约，可以完全不用它。
> ⚠️ 部署者会成为合约的第一个 ADMIN：**想让链上链下是同一个 ADMIN，就把下面「ADMIN 对齐」那把私钥填进去**
> （也可以直接把 `CHAINSIP_ADMIN_PRIVATE_KEY` 设好，这里不传 `-Dchainsip.deploy.privateKey` 也能跑）。

它会打印部署者地址、两个合约地址，并做一次链上校验（`roleOf(deployer)` 应为 `[1]` = ADMIN）：

```
### 部署者地址（该账户会成为合约第一个 ADMIN）= 0x<取决于你传入的私钥>
### ChainSIP         = <CORE_CONTRACT_ADDRESS>      ← 示例
### ChainSIPTransfer = <TRANSFER_CONTRACT_ADDRESS>      ← 示例
```

#### 5.4.3 ADMIN 对齐（必做）

合约里的第一个 ADMIN 就是**部署合约时用的那个账户地址**。后端签 ADMIN 类交易用的是自己配置的 **ADMIN 机构私钥**，
两者不一致时，`setTransferContract` / `addBureaus` / `addSponsorAccount` / `addRegulatorAccount` 都会被合约以 `NotAnAdmin` 回滚，
**创建机构账号时的自动上链注册也会一起失败**（创建响应 `onChainRegistered: false`）。两种做法：

- **做法 A（省事）**：用部署者账户调 `ChainSIP.addAdmin(后端 ADMIN 地址)`。
  后端 ADMIN 地址在启动后可从 `GET /api/chain/status` 的 **`adminAddress`** 字段看到；
- **做法 B（推荐）**：把部署者的私钥填进后端配置项 `chainsip.crypto.admin-private-key`
  （或环境变量 `CHAINSIP_ADMIN_PRIVATE_KEY`）—— 启动组件 `AdminKeyBootstrap` 会在每次启动时把它写成 `code=ADMIN`
  的机构密钥，链上链下就是同一个 ADMIN，**链上只需保留一个 ADMIN**，不必再 `addAdmin` 交接；
  也可以走 `POST /api/admin/institution-keys` 导入（需先把 PEM 私钥转成 64 位十六进制）。

> 留空该配置时后端 ADMIN 没有机构私钥，任何 ADMIN 类交易都发不出去
> （`GET /api/chain/status` 的 `adminAddress` 为空、`adminOnChainRole` 为 `NONE` 或空）。

#### 5.4.4 接线与验证

先核对"后端用来签名的 ADMIN 地址"与"链上 ADMIN"是不是同一个：启动后端后看 `GET /api/chain/status` 的
**`adminAddress`**（后端 ADMIN 机构地址）与 **`adminOnChainRole`**（合约里该地址的角色，应为 `ADMIN`）。
若 `adminAddress` 为空，说明没配 `chainsip.crypto.admin-private-key`（ADMIN 没有机构私钥、发不出交易）；
若 `adminOnChainRole` 不是 ADMIN，说明没做 §5.4.3 的对齐（`addAdmin`，或把部署者私钥配进该配置项）。

把两个合约地址写进 `backend/src/main/resources/application.yml`（默认留空，等你部署后填）：

```yaml
chainsip:
  chain:
    contracts:
      core: "${CHAINSIP_CORE_ADDRESS:0xae519fc2...}"
      transfer: "${CHAINSIP_TRANSFER_ADDRESS:0x73b647cb...}"
```

最后用后端 ADMIN 调一次 `ChainSIP.setTransferContract(转移合约地址)`
（前端「机构管理 → 合约接线」页点一下即可，或 `POST /api/admin/transfer-contract`），核心合约才会认转移合约。

验证（三选一）：

```powershell
# ① 后端状态：coreDeployed / transferDeployed 应为 true
#    需要先登录拿 JWT，swagger 里点一下最快
# ② 直接问节点：返回不是 0x 就说明地址上有合约代码
curl.exe -s -X POST http://127.0.0.1:8545 -H "Content-Type: application/json" ^
  -d "{\"jsonrpc\":\"2.0\",\"method\":\"getCode\",\"params\":[\"<ACCOUNT_ADDRESS>\",1],\"id\":1}"
# ③ 链上资金总览（有业务数据后应非 0）
curl.exe -s -X POST http://127.0.0.1:8545 -H "Content-Type: application/json" ^
  -d "{\"jsonrpc\":\"2.0\",\"method\":\"getBlockNumber\",\"params\":[1],\"id\":1}"
```

#### 5.4.5 改了合约怎么办

编译与部署都在控制台 / WeBASE 里做（仓库不带编译脚本），改完按这四步走：

1. **同步两份源码**：先改 `contracts/*.sol`（权威多文件源），再把改动同步到 `contracts/webase/` 的两个自包含文件；
2. **用控制台编译并部署**：把 `contracts/webase/ChainSIP.sol` / `ChainSIPTransfer.sol` 拷进控制台的合约目录
   （`~/fisco-2.11/console/contracts/solidity/`），或 `deploy <绝对路径>`；编译选 **0.6.10 + 优化器**，
   然后按 §5.4.2 重新部署，把新地址写进 `application.yml`；
3. **同步后端的 ABI/bin**（关键，文件名必须一致）：
   控制台编译后会在它的合约目录下产出同名 `.abi` / `.bin`（FISCO 控制台一般在
   `contracts/sdk/abi` 与 `contracts/sdk/bin`，**以控制台实际输出为准**），
   把 `ChainSIP` 与 `ChainSIPTransfer` 的这两个文件**按同名覆盖**到
   `backend/src/main/resources/abi/`：

   ```
   backend/src/main/resources/abi/ChainSIP.abi          ← 核心合约 ABI
   backend/src/main/resources/abi/ChainSIP.bin          ← 核心合约部署字节码
   backend/src/main/resources/abi/ChainSIPTransfer.abi  ← 转移合约 ABI
   backend/src/main/resources/abi/ChainSIPTransfer.bin  ← 转移合约部署字节码
   ```

   （后端启动时按这四个固定文件名加载；文件已经有了，**只有合约接口或字节码变了才需要覆盖**。）
4. 重新打包后端：`cd backend` → `mvn -s settings-2x.xml "-Dmaven.repo.local=~/.m2/repository" clean package`。

> 合约设计上**尽量保持 ABI 兼容**（函数名/参数/返回值/事件不变），所以正常的业务迭代里，后端 service 层与前端页面都不用改。
> **例外：本次「养老金支出」是新增能力**——多了 `retireEmployee(address,bool)` / `claimPension(address)` 两个函数，
> 参保账户视图也多了 `retiredAt / lastPensionAt / pensionPersonalMonthly / pensionClaimedMonths / age` 五个字段，
> 因此**必须重新部署核心合约**，见 §5.4.6。

#### 5.4.6 升级到「养老金支出」版本（必须重新部署核心合约）

链上合约不可升级，本次 ABI 有新增，**旧地址上的合约没有这些函数**，直接调会失败。

**升级前的典型症状（先认这个，再谈步骤）**：

| 现象 | 说明 |
| --- | --- |
| 首页/监管大屏弹出 `code=2002 链上调用失败：ChainSIP#totalPensionPaid — Revert instruction` | 后端 ABI 已是新版，链上还是旧字节码。`totalPensionPaid` 是新增的 public getter，旧合约里没有这个 selector，EVM 直接回滚 |
| 养老金支出页顶部黄条「链上核心合约仍是升级前的版本」 | 后端探针（`GET /api/chain/status` 的 `contractCompat.pensionReady=false`）已经判定过，不用再猜 |
| 「办理退休 / 发放」全部失败 | `retireEmployee` / `claimPension` 同样只存在于新合约 |

> 这三种现象的共同原因是**只重启了后端、没有重新部署合约**。重启后端只会让内存里的 ABI 变新，
> 不会让链上已部署的字节码变新 —— 合约一旦部署就不可修改，只能重新部署并把新地址写进配置。
> 首页「链上合约 → 合约版本」一行会显示 `已升级（含养老金）` 或 `待重新部署`，可直接用来确认是否已切换。

升级步骤：

1. **重新编译并部署核心合约**：`contracts/ChainSIP.sol`，0.6.10 + 优化器（runs=200），运行时代码 24313 B，无构造参数；
2. **重新部署转移合约**：`ChainSIPTransfer`（构造参数填**新**核心合约地址），然后用 ADMIN 调
   `ChainSIP.setTransferContract(新转移合约地址)` 接线（也可以在前端「机构管理 → 合约接线」点一下）；
3. **重新上链注册机构**：新合约是一本空账本 —— 社保局（`addBureaus` + 授权）、缴费单位（`addSponsorAccount`）、
   监管（`addRegulatorAccount`）都要重新注册。**链下账号和机构私钥不用重建**（地址不变），
   在「机构管理 → 待上链注册」里补一次即可；
4. **重建职工档案**：链上职工账户不会迁移，需要重新建档（可直接用 60 岁以上的年龄录入，便于验收退休功能）；
5. **更新地址**：把新地址写进 `application.yml` 的 `chainsip.chain.contracts.core/transfer`
   （或环境变量 `CHAINSIP_CORE_ADDRESS` / `CHAINSIP_TRANSFER_ADDRESS`），重启后端；
6. **ABI/bin 已随仓库更新**（`backend/src/main/resources/abi/ChainSIP.abi|bin`），
   如果你自己编译了，按 §5.4.5 第 3 步覆盖同名文件即可。

> ⚠️ 旧合约地址上的余额、缴费记录、转移申请都不会迁移。要保留演示效果，按 §6.5 剧本 A 重新走一遍建档与缴费；
> 退休门槛（180 个月）在演示环境很难靠真实缴费攒够，可用「养老金支出 → 特殊审批退休」直接验证发放链路。

### 5.5 后端构建与启动

#### 5.5.1 证书与 SDK 配置

链在 WSL 里，SDK 需要三样证书：

```bash
# WSL 里执行：把链的 SDK 证书拷到后端
cp ~/fisco-2.11/nodes/127.0.0.1/sdk/{ca.crt,sdk.crt,sdk.key} <PROJECT_DIR>/backend/sdk/conf/
```

`backend/sdk/config.toml`（**2.x 格式**，注意与 2.x 官方文档以外的老教程不同）：

```toml
[cryptoMaterial]
certPath = "conf"        # 网关会自动改写成绝对路径后交给 SDK（2.x 按“进程工作目录”解析相对路径）
cryptoType = "0"         # 所有数字都要加引号，否则 TOML 解析抛 ClassCastException
[network]
peers = ["127.0.0.1:20200"]   # channel 端口
[account]
keyStoreDir = "account"
accountFileFormat = "pem"
[threadPool]
channelProcessorThreadSize = "16"
receiptProcessorThreadSize = "16"
maxBlockingQueueSize = "102400"
```

群组 ID 不在 `config.toml` 里，而在 `application.yml`：`chainsip.chain.group-id: 1`。

#### 5.5.2 构建（本机有个必须注意的坑）

本机的两个全局 Maven 设置都把本地仓库钉在**只读**目录（`<MAVEN_HOME>\...\repo` 与 `%USERPROFILE%\maven-offline-repository`），所以构建**必须**带项目内的 `settings-2x.xml` 与本地仓库参数，且**路径用正斜杠并加引号**：

```powershell
cd backend
mvn -s settings-2x.xml "-Dmaven.repo.local=~/.m2/repository" clean package
```

- 写成 `-Dmaven.repo.local=<PROJECT_DIR>\.m2`（反斜杠）会被 `mvn.cmd` 拆坏，报 `No plugin found for prefix '.repo.local=E'`；
- 不带 `-s settings-2x.xml` 会写到只读仓库，报 `AccessDeniedException`。

#### 5.5.3 启动与初始账号

```powershell
# 启动前确认 MySQL 已启动、库 chainsip 已存在（见 §5.3）
& '<JDK8_HOME>\bin\java.exe' -jar target\chainsip-backend.jar

# 连接信息可用环境变量覆盖：
#   CHAINSIP_DB_HOST / CHAINSIP_DB_PORT / CHAINSIP_DB_NAME / CHAINSIP_DB_USER / CHAINSIP_DB_PASSWORD
```

启动后：

- 接口文档 <http://127.0.0.1:8080/swagger-ui/index.html>
- 健康/链状态 `GET /api/chain/status`（`ready: true` 表示 SDK 已连上链）

**初始账号**（数据库为空时自动创建，启动日志会提示 ADMIN 机构链账户怎么配）：

| 账号 | 密码 | 角色 | 机构链账户地址 |
|---|---|---|---|
| admin | ChangeMe_123456 | ADMIN | 由 `chainsip.crypto.admin-private-key`（或环境变量 `CHAINSIP_ADMIN_PRIVATE_KEY`）决定，见下方说明 |

其余账号**不预置**，按层级创建：管理员创建社保局 → 社保局创建缴费单位 → 缴费单位创建职工（见 §4.1）。
创建 BUREAU / SPONSOR / REGULATOR / EMPLOYEE 账号时，后端会**自动生成该账号的链账户**（`BUREAU_` / `SPONSOR_` / `REGULATOR_` / `EMP_` + 6 位随机大写十六进制；私钥以 AES-256-GCM 密文落库，接口不返回明文），
创建响应里带 `institutionCode` 与 `chainAddress`（列表接口 `GET /api/admin/accounts`、`/api/bureau/accounts`、`/api/sponsor/accounts` 每行也带 `chainAddress`）。
**链上注册由后端自动完成**（`addBureaus`+授权 / `addSponsorAccount` / `addRegulatorAccount` 都是 `onlyAdmin`，用 ADMIN 私钥签名）：
管理员创建机构账号时自动注册；**社保局创建缴费单位时同样创建即自动注册**（后端用 ADMIN 私钥代签，链上仍由 ADMIN 署名）。
**只有自动注册失败**（未填城市 / 链未就绪 / 链上回滚等）才需要在管理员的「机构管理 → **待上链注册**」页签里补一次 —— **那是补救入口，不是必经步骤**。
**EMPLOYEE 账号不需要链上注册**（职工由单位建档 `addEmployee` 时使用其自动生成的地址）。

> ADMIN 自己的机构链账户不是随机生成的：把"用 FISCO 控制台 / WeBASE 部署合约的那个账户"的私钥填进
> `chainsip.crypto.admin-private-key`（建议用环境变量 `CHAINSIP_ADMIN_PRIVATE_KEY`，不要写进仓库），
> 启动组件 `AdminKeyBootstrap` 会在**每次启动**时把它写成 `code=ADMIN` 的机构密钥，
> 于是链上 ADMIN（部署合约的账户）与链下后端是同一个地址，**不需要 `addAdmin` 交接**。
> **留空则 ADMIN 无法发起交易**：`GET /api/chain/status` 的 `adminAddress` 为空、`adminOnChainRole` 为 `NONE` 或空。
> 生产还应改掉初始密码（`POST /api/auth/password`），并替换 `chainsip.crypto.master-key` 与 `chainsip.security.jwt.secret`。

> 唯一保留的开发用开关是 `chainsip.crypto.allow-demo-sign`（默认 `true`）：它只控制**联调辅助接口**
> `POST /api/auth/demo/sign`（用请求里自带的私钥对消息签名，不涉及服务端密钥），生产建议置 `false`。

#### 5.5.4 在 IDEA 里运行

1. `File → Open` → 选 **`backend/pom.xml`**（不要打开仓库根目录，否则满屏 cannot resolve symbol）；
2. Project SDK = **1.8**，Language level = **8**；
3. Maven → *User settings file* 选 **`backend/settings-2x.xml`**，Runner JRE = 1.8，然后 Reload；
4. Run Configuration：Main class `com.chainsip.ChainSipApplication`，模块 `chainsip-backend`，**Working directory = `$ProjectFileDir$`**（即 backend，否则读不到 `./sdk/config.toml`）。

> 更详细的 IDEA 配置与报错对照见 [backend/README.md](backend/README.md) 的「在 IntelliJ IDEA 里运行」一节。

### 5.6 前端

```powershell
cd frontend
npm install
npm run dev        # http://127.0.0.1:5173
```

- 项目内已放 `.npmrc`（镜像 `registry.npmmirror.com` + 加大超时与重试）。若某个包反复超时（`ETIMEDOUT`），换腾讯镜像即可：
  `npm install --registry=https://mirrors.cloud.tencent.com/npm/`
- IDEA 的 npm 工具窗口默认不显示 HTTP 日志，看起来像"卡住"；排查时用 Terminal 跑并加 `--loglevel=http`；
- Vite 已把 `/api` 代理到 `http://127.0.0.1:8080`，所以**后端要先起来**，无需配 CORS。

打包：

```powershell
npm run build      # 产物在 frontend/dist
```

生产部署时把 `VITE_API_BASE` 指向后端地址（见 `.env.development`）。

### 5.7 配置速查

**`backend/src/main/resources/application.yml`**

| 键 | 默认 | 说明 |
|---|---|---|
| `server.port` | 8080 | 后端端口 |
| `spring.datasource.url` | `jdbc:mysql://127.0.0.1:3306/chainsip?...` | 唯一数据源（MySQL）；可用 `CHAINSIP_DB_*` 覆盖 |
| `spring.redis.*` | `127.0.0.1:6379/db1` | 可选缓存；可用 `CHAINSIP_REDIS_*` 覆盖，Redis 故障自动降级 |
| `chainsip.security.jwt.secret` | dev 值 | 生产必须换（≥32 字节） |
| `chainsip.crypto.master-key` | dev 值 | 机构私钥落库加密主密钥（AES-256-GCM），生产用 `CHAINSIP_CRYPTO_MASTER_KEY` 注入 |
| `chainsip.crypto.admin-private-key` | 空 | ADMIN 机构链账户私钥（64 位十六进制，可带 `0x`）；填"部署合约那个账户"的私钥，可用 `CHAINSIP_ADMIN_PRIVATE_KEY` 注入；**留空则 ADMIN 发不出交易** |
| `chainsip.crypto.allow-demo-sign` | true | 是否开放联调辅助接口 `POST /api/auth/demo/sign`（用请求里自带的私钥签名，不涉及服务端密钥），生产建议 `false` |
| `chainsip.chain.sdk-config-file` | `./sdk/config.toml` | SDK 配置路径 |
| `chainsip.chain.group-id` | 1 | 群组编号 |
| `chainsip.chain.contracts.core` / `.transfer` | 已填 | 合约地址 |

**环境变量**：`CHAINSIP_DB_HOST/PORT/NAME/USER/PASSWORD`、`CHAINSIP_REDIS_HOST/PORT/PASSWORD/DATABASE`、`CHAINSIP_CRYPTO_MASTER_KEY`、`CHAINSIP_ADMIN_PRIVATE_KEY`、`CHAINSIP_SDK_CONFIG`、`CHAINSIP_GROUP_ID`、`CHAINSIP_CORE_ADDRESS`、`CHAINSIP_TRANSFER_ADDRESS`。

**链的常用命令（WSL）**

```bash
bash ~/fisco-2.11/nodes/127.0.0.1/start_all.sh     # 启链（重启 WSL 后需手动执行）
bash ~/fisco-2.11/nodes/127.0.0.1/stop_all.sh      # 停链
pgrep -af fisco-bcos                               # 看进程
tail -f ~/fisco-2.11/nodes/127.0.0.1/node0/log/*.log
curl -s -X POST http://127.0.0.1:8545 -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","method":"getBlockNumber","params":[1],"id":1}'
```

### 5.8 端到端验收清单

按顺序跑一遍（swagger 或前端页面都行），每一步都给了**期望结果**：

> 前置：先按 §4.1 建好账号（后端启动见 §5.5）—— admin 登录创建社保局账号（请求可带 `name` / `city`）、该社保局创建缴费单位账号、该缴费单位创建职工账号。
> **创建账号时链上地址自动生成，并且自动上链注册**：创建响应里带 `institutionCode`、`chainAddress`、`onChainRegistered`（`true` 已注册 / `false` 注册失败 / `null` 不涉及）与 `onChainMessage`。
> 下面第 3-4 步的"注册"接口是**自动注册失败时的补救路径**（正常流程无需手动调用）：先查 `GET /api/admin/institutions/pending`，列表为空即说明已自动注册完成。

| # | 操作 | 期望 |
|---|---|---|
| 1 | `GET /api/chain/status` | `ready: true`、`coreDeployed: true`、`transferDeployed: true` |
| 2 | admin 登录 → `POST /api/admin/transfer-contract`（转移合约地址） | 成功；重复调用会以 `TransferContractUnchanged` 回滚（正常） |
| 3 | 创建社保局账号（`POST /api/admin/accounts`，带角色 BUREAU、`name`、`city`）→ 需要时 `POST /api/admin/bureaus/authorize` | **自动注册**：响应 `onChainRegistered: true`；重复授权回 `AuthorizationUnchanged(true)`。**失败时**（`onChainRegistered: false`）：`GET /api/admin/institutions/pending` 会列出它，用 `POST /api/admin/institutions/{code}/register`（body `{city}`）补一次（失败也返回 HTTP 200，看 `ok`） |
| 4 | 创建缴费单位账号（admin `POST /api/admin/accounts` 或社保局 `POST /api/bureau/accounts`，带 `name`、`city`）→ `POST /api/admin/deposit` | 缴费单位**创建即自动注册**（社保局创建时由后端用 ADMIN 私钥代签，响应同样带 `onChainRegistered`）；注资成功后公司账户 `poolBalance` 增加。失败时同样走「待上链注册」补救（`POST /api/admin/sponsors` 是手工补注册接口） |
| 5 | `POST /api/admin/regulators` | 成功；重复回 `RegulatorAlreadyExists`；创建监管账号时同样会自动注册 |
| 6 | 缴费单位账号登录（建议命名为 sponsor1）→ 先 `POST /api/sponsor/accounts` 创建职工账号，再 `POST /api/sponsor/employees`（用职工账号自动生成的 `chainAddress` 建档） | 成功；同一身份证重复建档回 `AlreadyInsured`。**职工不需要链上注册** |
| 7 | `POST /api/sponsor/payments` | 成功；30 天内重复缴费回 `PaymentCycleNotElapsed`（正常） |
| 8 | `GET /api/sponsor/employees/account?employee=0x...` | 个人 = 基数×8%、统筹 = 基数×19%（基数按 [3200,24000] 夹取） |
| 9 | `GET /api/chain/stats` | 三条恒等式全部成立（数值示例：注资 3000000 = 公司池 2997300 + 已缴 2700；已缴 2700 = 个人 800 + 城市池 1900） |
| 10 | `GET /api/regulator/dashboard` | 规模统计 + 对账数据（监管只读） |
| 11 | 转移链路：职工申请 → 单位减员 → 社保局放行 → 转入地接收 | 状态依次 PENDING → (未减员则放行失败) → 放行后资金进托管 → 接收后落到新城市池 |
| 12 | `GET /api/index/tx-logs` | 能看到前面每一步的上链记录（含 txHash、成功/回滚原因） |

> 已知遗留：`GET /api/index/events` 目前返回 0（回执事件字段形态未适配），不影响余额/权益类读取，只影响"链上索引"页的事件列表。

---

## 6. 使用手册

### 6.1 启动与自检

按顺序起三样东西，**链 → 后端 → 前端**：

| 顺序 | 组件 | 命令 | 地址 |
|---|---|---|---|
| 0 | **MySQL（必需）** | 启动 MySQL 服务，库 `chainsip` 需已创建 | `127.0.0.1:3306`（库 `chainsip`） |
| 0.5 | Redis（可选缓存） | `docker start chainsip-redis` | `127.0.0.1:6379`（db1） |
| 1 | 链（虚拟机内） | 虚拟机 `<CHAIN_HOST>` 上 `bash ~/fisco/nodes/127.0.0.1/start_all.sh` | channel 20200（后端 `sdk/config.toml` 的 peers 指向它） |
| 2 | 后端 | `cd backend; java -jar target\chainsip-backend.jar` | <http://127.0.0.1:8080>（Swagger：`/swagger-ui/index.html`） |
| 3 | 前端 | `cd frontend; npm run dev` | <http://127.0.0.1:5173> |

**一分钟自检**：

- **先看 `GET /api/public/health`（免登录）**：`status: UP` 表示数据库与链都通；
  `data.redis.ok` 表示缓存是否可用（不可用只会进 `warnings`，不改变 `status`）；
  `status: DEGRADED` 时看 `warnings` 与 `chain.message`，它会直接写明是数据库还是链的问题；
- 再看 `GET /api/chain/status` → 期望 `ready: true`、`coreDeployed: true`、`transferDeployed: true`；
- 若 `ready: false`，看 `message` 字段（会带上"根因链"）；链没起来时认证/账号类接口仍可用，链相关接口返回 503。

### 6.2 登录与账号信息

登录页顶部的**快捷填入标签**可以一键填入 `admin / ChangeMe_123456`（空库启动时唯一自动创建的账号）；标签里列出的其它名字（`sponsor1` 等）只有在你按 §4.1 建好同名账号之后才能登录。**平台不提供自助注册**：账号一律由上一级创建，登录页没有「注册新账号」入口，避免绕过层级。

- **链上地址**：登录后 `GET /api/auth/me`（`POST /api/auth/login` 的账号对象同样）会返回本账号的 **`chainAddress`** —— 这是**创建账号时自动生成的链上地址**，前端所有页面用它作为"我 / 本机构的链上地址"，所以**你不需要手输地址，也不需要做任何绑定操作**（详见 §4.2）；
- **修改密码**（`POST /api/auth/password`，body `{ oldPassword, newPassword }`）：顶栏「我的信息」→「修改密码」，需填**当前密码**做二次确认；前端与后端都会校验（必填、新密码 ≥ 6 位、两次输入一致），**修改成功后需要重新登录**（前端清登录态并跳回登录页）；
- **修改账号信息**（`POST /api/auth/profile`，body `{ newUsername, newDisplayName, password }`）：顶栏「我的信息」→「修改账号信息」，可改用户名 / 显示名，两个新字段**留空表示不修改**（都留空会提示"请至少填写一项要修改的内容"），新用户名需满足 3-32 位字母/数字/下划线；**成功后不需要重新登录**，前端用返回值局部刷新登录态；
- 失败提示由统一的 axios 拦截器弹出后端 `message` 原文；
- 管理员改**他人**密码用 `POST /api/admin/accounts/{id}/password`；生产环境请务必改掉初始密码 `ChangeMe_123456`。

> 手动地址绑定功能（原「我的链上地址」页与 `wallet/message`、`wallet/bind`、`wallets` 接口）**已移除**，因此没有"绑定失败"的排错项；联调代签接口 `POST /api/auth/demo/sign` 仍保留，但只受 `chainsip.crypto.allow-demo-sign` 开关控制、前端不调用。

### 6.3 首页概览

所有角色登录后先看这一页（`/dashboard`），它是"系统现在什么状态"的总览：

| 区块 | 含义 |
|---|---|
| 链网关状态 | 是否连上链、群组号（2.x 固定 `1`）、当前配置的合约地址 |
| 当前区块高度 | 直接来自链 |
| 累计注资 / 累计已缴 | 链上系统总览的两个口径 |
| **资金 / 权益对账** | 六项资金 + **三条恒等式**的成立情况（见 §6.8） |
| 当前账号身份 | 链下角色、链上角色、机构链编码、本账号链上地址 —— 两边不一致时这里能立刻看出来 |
| 最新区块 | 区块号、哈希、最新交易哈希、出块时间 |

### 6.4 按角色逐页操作

**页面与角色**

| 页面 | 路径 | 可见角色 |
|---|---|---|
| 首页概览（链状态 / 区块 / 资金对账 / 身份对照） | `/dashboard` | 全部 |
| 机构管理（缴费单位 / 社保局 / 监管 / 合约接线 / 待上链注册） | `/admin/institutions` | ADMIN |
| 职工建档 / 注销 | `/admin/persons` | ADMIN |
| 平台账号与机构链账户 | `/admin/accounts` | ADMIN |
| 缴费单位账号（社保局创建缴费单位 / 给所属单位注资） | `/bureau/accounts` | BUREAU |
| 辖区总览（辖区内单位 / 职工 / 参保记录 / 缴纳记录） | `/bureau/region` | BUREAU |
| 养老金支出（退休办理 / 按月发放 / 发放流水） | `/bureau/pension` | BUREAU |
| 转移办理（转出放行 / 转入接收 / 待办） | `/bureau/inbox` | BUREAU |
| 职工账号（缴费单位创建参保职工） | `/sponsor/accounts` | SPONSOR |
| 职工管理（建档 / 减员 / 调薪） | `/sponsor/employees` | SPONSOR |
| 缴费管理（余额 / 试算 / 缴费 / 缴费记录） | `/sponsor/payments` | SPONSOR |
| 我的社保账户（余额 / 缴费记录） | `/employee/account` | EMPLOYEE |
| 关系转移（申请 / 状态 / 托管金额 / 详情） | `/employee/transfer` | EMPLOYEE |
| 监管大屏（规模 / 对账 / 城市基金池 / 公司名单） | `/regulator/overview` | REGULATOR、ADMIN |
| 区块链浏览器（区块 / 交易 / 地址） | `/chain/explorer` | **所有已登录角色** | 直接读节点账本：出块高度、区块内交易、交易回执与事件、地址的链上角色 |
| 链上索引（事件 / 上链日志） | `/chain/index` | **仅 ADMIN / REGULATOR** |
| 合约调试台（通用 call / send） | `/chain/console` | ADMIN、SPONSOR、BUREAU、REGULATOR |

菜单由路由配置的 `meta.roles` 自动过滤，路由守卫再做一次拦截。

#### 6.4.1 ADMIN 平台管理员

**① 机构管理（`/admin/institutions`）** —— 五个页签：

| 页签 | 做什么 | 关键点 |
|---|---|---|
| 缴费单位（公司） | 填 `地址 + 名称 + 城市` 注册公司；填 `地址 + 金额` 注资 | **城市决定该公司的职工进哪个城市统筹基金池**；注资是链上记账（实际资金结算在链下）。创建缴费单位账号时后端已自动注册；此处是手工补注册 / 改城市用 |
| 社保局 | 注册社保局（`地址 + 名称 + 服务城市`）；用开关切换**授权** | 新注册的社保局**默认未授权**，必须切换授权才能办理转移放行/接收；创建社保局账号时后端已自动注册+授权 |
| 监管账户 | 注册 / 注销监管账户 | 监管是**纯只读**身份：能查全平台，写操作会被合约拒绝 |
| **待上链注册** | **补救入口**：列出**自动注册失败**的机构（`GET /api/admin/institutions/pending`），行内填城市点「注册」或「全部注册」补一次 | 自动注册成功时这里是**空的**，正常流程不必进来；`未注册` = 角色不匹配，`未授权` = 社保局已注册未授权（只补授权、不重复注册）；已就绪的行直接返回"已注册，无需重复操作"且不发交易；接口**失败也是 HTTP 200**，看响应里的 `ok` |
| 合约接线 | 写入 `transferContract` 地址、补授 BUREAU 角色 | 部署两个合约后**必须做这一步**，核心合约才认转移合约 |

**② 职工建档 / 注销（`/admin/persons`）**：管理员可以全量录入，也可以由单位自助建档。

- 建档需要：职工地址、姓名、年龄、身份证号（链上唯一，防重复参保）、用工单位名称、入职日期、月工资、缴费单位地址；
- **参保城市由"缴费单位注册的城市"自动推导**，不需要填；
- 注销要求**账户余额为 0 且无在途转移**（资金必须走转移流程结清，不能在链上直接销毁权益）。

**③ 平台账号与机构链账户（`/admin/accounts`）**：

- 开通账号：用户名、密码、角色、机构编码（关联到哪把机构私钥）；请求可带 `name` 与 `city`，**创建即自动生成链账户并自动上链注册**，响应用 `onChainRegistered`（`true` 已注册 / `false` 注册失败 / `null` 不涉及，如 ADMIN、EMPLOYEE）与 `onChainMessage` 说明结果；
- 机构链账户：**通常由创建账号时自动生成**，也可以在这里登记/导入自己的机构私钥（**AES-256-GCM 密文落库，接口不返回私钥**），可停用；
- **只有创建时自动注册失败**才需要到「机构管理 → 待上链注册」补一次（公司→`addSponsorAccount`，社保局→`addBureaus`，监管→`addRegulatorAccount`）；**职工账号不需要链上注册**；列表接口每行都带 `chainAddress`。

**④ 监管大屏、合约调试台、链上索引**：见 §6.4.5、§6.6、§6.7。

#### 6.4.2 SPONSOR 缴费单位

**① 职工账号（`/sponsor/accounts`）**：创建参保职工账号（角色 EMPLOYEE）。职工账号的链上地址在创建时**自动生成**，响应里带 `chainAddress`，创建后即可用于建档。

**② 职工管理（`/sponsor/employees`）**

| 操作 | 说明 |
|---|---|
| 为职工建档 | 只能给自己名下职工建档；一人同时只能被一家单位参保（详见 §4.3 两个职工口径） |
| 减员（停缴） | **跨城市转移的前置条件**：原单位没减员，社保局放行会失败 |
| 调薪 | 会按 [3200, 24000] 重新夹取缴费基数 |

**③ 缴费管理（`/sponsor/payments`）**

- 左侧是本公司统筹账户：可用余额、累计已缴、职工数；余额不足时缴费会被拒；
- 「试算」按输入的月工资算出：缴费基数、个人 8%、统筹 19%、单位合计 27%；
- 「缴费」对指定职工发一笔缴费：个人 8% 进个人账户（实账），单位 19% 进**参保城市的统筹基金池**；
- **同一职工 30 天内只能缴一次**，重复提交会收到 `PaymentCycleNotElapsed`（正常保护，不是故障）；
- 逾期补缴允许，但会留痕（缴费记录里标"逾期缴纳"，职工账户记 `逾期次数`）；
- 职工用**姓名下拉选择**（数据来自 `GET /api/sponsor/employees/detail`，无需传 `sponsor`），下拉为空说明本单位还没有已建档职工，先到「职工管理」建档。

#### 6.4.3 EMPLOYEE 参保职工

**① 我的社保账户（`/employee/account`）**：姓名、身份证号、参保城市、所属单位、状态、月工资与缴费基数、**个人账户余额**、**统筹账户权益**、累计缴费月数、逾期次数、上次缴费时间、在途转移编号；下方是缴费明细。

> 可见性由**合约**判定：本人 / 所属单位 / ADMIN / REGULATOR 能看，其他人查会被链上拒绝（`AccountNotVisible`）。

> **已退休的职工**会多出一张「养老金」卡片：每月应发（基础养老金 + 个人账户养老金）、已领月数、上次/下次可领时间，
> 以及「领取本月养老金」按钮（`POST /api/employee/pension/claim`，用职工**自己的私钥**签名，
> 合约允许 `msg.sender == employee`）。同一职工 30 天内只能领一次。

**② 关系转移（`/employee/transfer`）**

1. 「发起转移申请」填目标城市 → 提交后状态为**待放行（PENDING）**；
2. 转出地社保局复核：
   - 若**原单位还没减员**，复核会失败（这是硬前置）；
   - 放行后状态变为**已放行**，此时个人账户资金与统筹基金**进入托管锁定**（页面会显示"在途托管金额"）；
3. 转入地社保局**接收**后，基金落到新城市的统筹基金池，职工在新城市建立参保档案（状态回到"正常参保"）。

**转移状态含义**：`1 待放行` → `2 已放行（资金托管中）` → `3 已接收`；被驳回是 `4 已驳回`（附原因，链上留痕）。

#### 6.4.4 BUREAU 社保局

**① 缴费单位账号（`/bureau/accounts`）**：创建缴费单位账号（角色 SPONSOR，**创建即自动上链注册**，由后端用 ADMIN 私钥代签），并可给**本局所属**的缴费单位注资（`POST /api/bureau/sponsors/deposit`，链上 `depositSponsor`，同样由后端用 ADMIN 私钥代签；仅限本局服务城市内的单位）。

**② 转移办理（`/bureau/inbox`）**

| 面板 | 你能做什么 |
|---|---|
| 转出地待办 | 对"待放行"的申请点**放行**或**复核失败**（驳回需填原因）。放行前页面会先核对职工是否已停缴 |
| 转入地待办 | 对"已放行"的申请点**接收**，基金落到本城市、为职工建立本城市档案 |

- 只有申请单上记录的转出地/转入地社保局本人、且该社保局处于**已授权**状态才能操作；
- 顶部会显示本单位的**授权状态**：未授权时任何操作都会被合约拒绝。

**③ 辖区总览（`/bureau/region`）**：按**链上服务城市**看本局辖区内的全部缴费单位、这些单位名下的参保职工，
以及每个职工的**参保记录**与**缴纳记录**（右侧抽屉）。辖区范围来自链上（转移合约里本局绑定的服务城市），
不是"本局自己创建的账号"——别的社保局或管理员创建、但城市落在本局辖区内的单位同样会出现。

**④ 养老金支出（`/bureau/pension`）**——「缴纳」的对称面，两个页签：

| 页签 | 你能做什么 | 关键规则 |
|---|---|---|
| 待办退休 | 列出本辖区**未退休**职工，带「退休条件」：已达标 / 还差几岁、几个月；达标点**办理退休**，未达标可走**特殊审批退休** | 常规退休要求**满 60 周岁且累计缴费满 180 个月**；特殊审批（病退/提前退休）跳过门槛，豁免事实随链上事件 `EmployeeRetired` 留痕 |
| 退休发放 | 列出已退休人员：月应发、个人账户余额、已领月数、上次发放、下次可领；点**发放**（`claimPension`），点**流水**看每笔 txHash | **基础养老金**从参保城市统筹基金池扣，**个人账户养老金**从职工个人账户扣；同一职工 **30 天内只能发一次** |

- 退休后职工状态变为 `已退休`，**不能再缴费、不能重新建档**（`AccountRetired`），余额在每次发放时逐月扣减；
- 页面顶部的「累计已发」来自链上 `PensionClaimed` 事件索引（`cs_chain_event`），链上直接绕过后端发的交易不在其中。

#### 6.4.5 REGULATOR 监管方

**监管大屏（`/regulator/overview`）**：规模统计（单位数/社保局数/监管数/转移数）、资金与权益对账 + 三条恒等式、按城市查统筹基金池、公司名单（审计用）。

监管是**只读身份**：能查全平台账本与流水，但注资、缴费、建档、放行等写操作会被合约拒绝 —— 界面上也不提供入口。

### 6.5 两条业务演练

#### 剧本 A：从零到"第一个月缴费"

前置：先按 §4.1 建好账号 —— 管理员创建社保局账号（建议命名为 `bureauA`，同时填名称与服务城市 `A市`）、再用 `bureauA` 创建缴费单位账号（建议命名为 `sponsor1`，填名称 `甲公司` 与城市 `A市`）。
下文的 `bureauA` / `sponsor1` 都指**你自己创建的那两个账号**；创建账号时后端会**自动生成链上地址并自动上链注册**，地址见创建响应里的 `chainAddress`（列表接口每行也带）。

| 步 | 谁 | 操作 | 期望 |
|---|---|---|---|
| 1 | admin | 机构管理 → 社保局：确认 `bureauA` 的机构地址**已自动注册**（服务城市 `A市`）→ 打开授权开关 | 已注册；开关变已授权（创建时未填城市才会落到「待上链注册」，那里补一次即可） |
| 2 | admin | 机构管理 → 公司：确认 `sponsor1` 的机构地址**已自动注册**（名称 `甲公司` / 城市 `A市`） | 已注册（创建 `sponsor1` 时自动完成；**只有它出现在「待上链注册」里**才需要在这里补一次） |
| 3 | admin | 同页注资：同一地址 / `1000000` | 成功；公司账户余额显示 1000000 |
| 4 | sponsor1 | 先在「职工账号」建职工账号（建议命名 `employee1`），再到「职工管理」建档：职工 `0x…aa`、姓名、身份证号、月工资 `10000` | 建档成功，列表出现该职工 |
| 5 | sponsor1 | 缴费管理 → 填职工地址 → 缴费 | 成功；试算显示基数 10000、个人 800、统筹 1900 |
| 6 | 任何人 | 首页概览 → 资金/权益对账 | 个人资金 800、城市池 1900、统筹权益 1900、已缴 2700、公司池 9997300，**三条恒等式成立** |

> 第 4 步的 `0x…aa` 是你要建档的**职工链上地址**：职工账号（建议命名为 `employee1`，由 `sponsor1` 创建）在**创建时自动生成**该地址，创建响应里给出 `chainAddress`，直接用它建档即可（无需链上注册）。

再次对同一职工点缴费 → 会得到 `PaymentCycleNotElapsed`（30 天周期保护）✓

#### 剧本 B：跨城市转移全链路

前置：甲公司在 `A市`，另有一家社保局在 `B市`（用 admin 创建一个服务城市为 `B市` 的社保局账号，**创建即自动注册+授权**）。下文的 `employee1` / `sponsor1` 同样指你自己创建的职工账号与缴费单位账号。

| 步 | 谁 | 操作 | 期望 |
|---|---|---|---|
| 1 | employee1 | 关系转移 → 目标城市填 `B市` → 提交 | 状态：待放行 |
| 2 | A市社保局 | 转移办理 → 转出地待办 → 点"放行" | **失败**（原单位未减员）—— 这是设计如此 |
| 3 | sponsor1 | 职工管理 → 对职工点"减员" | 成功；职工状态变"已停缴" |
| 4 | A市社保局 | 再点"放行" | 成功；状态变"已放行"，页面出现**在途托管金额**（个人资金 + 统筹基金） |
| 5 | B市社保局 | 转移办理 → 转入地待办 → 点"接收" | 成功；基金进入 B 市统筹基金池，职工在 B 市建立档案（状态"正常参保"） |
| 6 | 任何人 | 首页概览 → 对账 | 三条恒等式重新成立；城市池从 A 市划转到 B 市 |

### 6.6 合约调试台

给联调用的通用调用面板（`/chain/console`）：

- **只读调用**：选合约（`ChainSIP` 核心 / `ChainSIPTransfer` 转移）、填函数名与 JSON 数组参数，例如
  - `getSystemStats` + `[]` → 八个资金口径；
  - `roleOf` + `["0x地址"]` → 链上角色（1=ADMIN，2=BUREAU，3=SPONSOR，4=REGULATOR，0=未注册）；
  - `getInsuranceAccount` + `["0x职工"]` → 参保账户全字段；
- **发交易**：会用**当前账号所属机构的私钥**签名 —— 这不是后门，合约仍会按签名地址判权，角色不符照样回滚；
- 返回区展示原始 JSON，方便对照 ABI。

### 6.7 链上索引

`/chain/index`（**仅 ADMIN / REGULATOR**）两个页签：

- **链上事件**：按职工地址 / 单位地址 / 事件名过滤（如 `PaymentMade`）；
- **上链操作日志**：按机构编码 / 函数名 / 成功与否过滤，能看到每一次 `send` 的参数、txHash、区块号、以及**回滚原因** —— 排查"为什么这笔交易失败"先看这里。

> 已知遗留：事件索引目前可能为空（回执事件字段形态未适配）；**上链操作日志是完整可用的**，余额/权益等读取也都直接来自链上。

### 6.8 对账怎么读

首页与监管大屏都会显示六项资金与三条恒等式：

| 口径 | 含义 |
|---|---|
| `deposited` | 平台给所有公司统筹账户的累计注资 |
| `sponsorPoolTotal` | 所有公司统筹账户的**当前余额合计** |
| `paid` | 累计已缴（单位 27% 的总和） |
| `personalFund` | 所有职工个人账户资金合计（8% 部分） |
| `cityPoolTotal` | 各城市统筹基金池合计（19% 部分） |
| `overallRights` | 所有职工统筹账户权益合计 |
| `escrowedPersonal` / `escrowedOverall` | 转移在途托管的个人资金 / 统筹基金 |

三条恒等式（页面会逐条给"成立 / 不成立"）：

1. `deposited = sponsorPoolTotal + paid`
2. `paid = personalFund + cityPoolTotal + escrowedPersonal + escrowedOverall`
3. `cityPoolTotal = overallRights`

任何时候这**三条都应成立**。不成立说明有异常（正常业务操作不会破坏它们）——请把当时的操作顺序与 `/api/index/tx-logs` 一起提供给维护者。

### 6.9 API 使用与接口速查

所有接口都是 `{code, message, data, timestamp}` 结构，`code: 0` 为成功，认证用 `Authorization: Bearer <token>`。

```powershell
# 1) 登录拿 token
$login = Invoke-RestMethod -Uri http://127.0.0.1:8080/api/auth/login -Method Post `
  -ContentType 'application/json' -Body '{"username":"admin","password":"ChangeMe_123456"}'
$h = @{ Authorization = "Bearer $($login.data.token)" }

# 2) 链状态与系统总览
(Invoke-RestMethod -Uri http://127.0.0.1:8080/api/chain/status -Headers $h).data
(Invoke-RestMethod -Uri http://127.0.0.1:8080/api/chain/stats  -Headers $h).data.values[0]

# 3) 注资（公司注册通常在创建缴费单位账号时已自动完成；下面这步只在自动注册失败、需要手工补注册时才用）
Invoke-RestMethod -Uri http://127.0.0.1:8080/api/admin/sponsors -Headers $h -Method Post `
  -ContentType 'application/json' -Body '{"sponsor":"0x…（创建缴费单位账号时返回的 chainAddress）","name":"甲公司","city":"A市"}'
Invoke-RestMethod -Uri http://127.0.0.1:8080/api/admin/deposit -Headers $h -Method Post `
  -ContentType 'application/json' -Body '{"sponsor":"0x…（同上）","amount":"1000000"}'

# 3.1) 补救入口：查看自动注册失败的机构，并逐个/批量补注册（正常情况列表为空）
(Invoke-RestMethod -Uri http://127.0.0.1:8080/api/admin/institutions/pending -Headers $h).data
Invoke-RestMethod -Uri http://127.0.0.1:8080/api/admin/institutions/<code>/register -Headers $h -Method Post `
  -ContentType 'application/json' -Body '{"city":"A市"}'   # 失败也返回 HTTP 200，看响应里的 ok

# 4) 万能调用（等价于调试台）
Invoke-RestMethod -Uri http://127.0.0.1:8080/api/chain/call -Headers $h -Method Post -ContentType 'application/json' `
  -Body '{"contract":"ChainSIP","function":"getSystemStats","params":[]}'
```

**接口分组速查**（完整列表见 <http://127.0.0.1:8080/swagger-ui/index.html>）：

| 分组 | 代表接口 |
|---|---|
| 认证 | `/api/auth/login`、`/me`、`/password`、`/profile`、`/demo/sign` |
| 链信息 | `/api/chain/status`、`/block`、`/stats`、`/call`、`/send`、`/refresh` |
| ADMIN | `/api/admin/sponsors`、`/deposit`、`/persons`、`/employees/remove`、`/bureaus`、`/bureaus/authorize`、`/regulators`、`/transfer-contract`、`/accounts`、`/institution-keys`、`/institutions/pending`、`/institutions/{code}/register`、`/cities`、`/system-stats` |
| BUREAU | `/api/bureau/accounts`、`/sponsors/deposit`、`/cities`、`/transfers/inbound`、`/outbound`、`/authorize`、`/{id}/receive`、`/info`、`/list`、`/city-bureau` |
| SPONSOR | `/api/sponsor/accounts`、`/employees`、`/employees/detail`、`/employees/suspend`、`/employees/salary`、`/employees/account`、`/payments`、`/account` |
| EMPLOYEE | `/api/employee/account`、`/payments`、`/transfer-state`、`/escrow`、`/transfer/apply`、`/transfer/{id}`、`/bureau-of-city` |
| REGULATOR | `/api/regulator/dashboard`、`/system-stats`、`/city-pool`、`/transfers/{id}`、`/transfers/count`、`/bureaus/count`、`/accounts`、`/sponsors` |
| 索引 | `/api/index/events`、`/api/index/tx-logs` |

### 6.10 合约错误名对照

合约用 `require(cond, "错误名")`，失败时页面会显示错误名（"链上操作日志"里也能看到）。以下是合约里**真实存在的全部错误名**，按场景分组：

**权限类**

| 错误名 | 含义 / 处理 |
|---|---|
| `NotAnAdmin` | 你的地址不是链上 ADMIN。**用 WeBASE 部署合约时最常见**：链上 ADMIN 是 WeBASE 的部署账户，而链下后端用的是自己配的 ADMIN 机构私钥 → 用部署者账户调 `ChainSIP.addAdmin(后端 ADMIN 地址)`（后端地址看 `/api/chain/status` 的 `adminAddress`），或把部署者私钥填进后端的 `chainsip.crypto.admin-private-key`（见 §5.4.3）。若 `adminAddress` 本身就是空的，说明没配这把私钥，**所有 ADMIN 类交易都发不出去**（包括创建机构账号时的自动上链注册，创建响应会是 `onChainRegistered: false`） |
| `NotAdminOrRegulator` | 该查询只对 ADMIN/监管开放 |
| `NotAuthorized` | 该地址未在链上注册，或社保局未授权 → 到管理员的「机构管理 → **待上链注册**」补一次（这里会以链上状态列出未注册/未授权的机构），或打开社保局授权开关 |
| `AccountLacksRole` / `AccountHasRole` | 缺少/已拥有某个角色 |
| `RoleNotGrantable` / `RoleNotRevocable` | 该角色不允许授予/撤销 |
| `LastAdminCannotBeRemoved` | 不能移除最后一个 ADMIN（保护机制） |

**未注册类**

| 错误名 | 含义 / 处理 |
|---|---|
| `UnknownSponsor` | 职工所属单位未注册 → 该单位本应在**创建缴费单位账号时自动注册**；若没有，到管理员的「机构管理 → 待上链注册」补一次（`addSponsorAccount`）再建档 |
| `UnknownEmployee` / `UnknownBureau` / `UnknownRegulator` | 对应的链上实体不存在 → 机构到「待上链注册」补一次；**职工不需要注册**，出现 `UnknownEmployee` 一般是单位建档（`addEmployee`）没成功 |
| `BureauNotAuthorized` | 社保局未授权 → ADMIN 在机构管理里打开授权开关 |
| `CityNotRegistered` / `CityAlreadyBound` | 城市没有对应社保局 / 该城市已绑定别的社保局 |
| `NotTransferBureau` / `NotTransferContract` | 不是该申请单上的转出/转入地社保局；调用者不是已接线的转移合约 |

**参保与缴费类**

| 错误名 | 含义 / 处理 |
|---|---|
| `AlreadyInsured` | 该身份证已参保（一人一缴）→ 原单位先减员或走转移 |
| `IdNumberAlreadyUsed` / `IdNumberMismatch` | 身份证号被占用 / 与档案不一致 |
| `PaymentCycleNotElapsed` | 同一职工 30 天内已缴费 → 正常保护，等满周期 |
| `InsufficientSponsorBalance` | 公司统筹账户余额不足 → 让 ADMIN 先注资 |
| `EmployeeNotActive` | 职工不是"正常参保"状态（已停缴/已转出/已注销） |
| `EmployeeNotSuspended` | 转移放行的前置条件：**原单位必须先减员**（状态需为"已停缴"） |
| `BalanceNotZero` | 注销要求账户余额为 0（资金须走转移流程结清） |
| `AccountRemoved` | 该账户已注销 |
| `InvalidSalary` | 工资金额不合法 |

**转移类**

| 错误名 | 含义 / 处理 |
|---|---|
| `CrossCityRequiresTransfer` | 跨城市换单位必须走转移流程，不能直接建档 |
| `SameCityTransfer` | 同城市之间不允许发起转移 |
| `InvalidTransferStatus` | 申请单状态不允许该操作（例如未放行就想接收） |
| `TransferInProgress` | 已有在途转移，不能重复发起 |
| `NoActiveTransfer` | 没有在途转移可操作 |
| `NotYourEmployee` | 只能操作自己单位的职工 |
| `TransferContractUnchanged` / `AuthorizationUnchanged(true)` / `SponsorAlreadyExists` / `RegulatorAlreadyExists` | 重复操作、值没变化 → 幂等保护，无需处理 |

**可见性与参数类**

| 错误名 | 含义 / 处理 |
|---|---|
| `AccountNotVisible` / `TransferNotVisible` | 该账户/申请对你不可见（本人、所属单位、ADMIN、REGULATOR 才可查）→ 正常走 API 不会遇到；若遇到请报告维护者 |
| `ZeroAddress` / `ZeroAmount` / `EmptyString` | 参数为空/为零 |
| `CityPoolInsufficient` | 城市统筹基金池余额不足（通常出现在资金异常场景） |
| 页面提示"链未就绪"（HTTP 503） | 后端连不上链 → 看 `/api/chain/status` 的 `message`；确认链进程在跑、20200 端口在听（见 §7.2） |

### 6.11 一句话速查

- **要用起来**：起链 → 起后端 → 起前端 → admin 按层级创建公司/社保局/监管账号（链上地址自动生成、自动上链注册）→ 注资/授权 → sponsor 建档缴费 → 到首页对账；
- **要排错**：先看"链上操作日志"和错误名（§6.10），再看 `/api/chain/status` 的 `message`；
- **要记住的一句话**：**链下决定你能看见什么，链上决定你能做成什么**。

---

## 7. 常见问题

> 合约错误名（`NotAnAdmin`、`UnknownSponsor`、`PaymentCycleNotElapsed`、`AccountNotVisible` …）的含义与处理见 **§6.10**，本节只列环境、链路、部署与数据库层面的问题。

### 7.1 构建与环境

| 现象 | 原因与处理 |
|---|---|
| `AccessDeniedException`（Maven 写仓库） | 全局 settings 把仓库钉在只读目录 → 必须带 `-s settings-2x.xml "-Dmaven.repo.local=~/.m2/repository"` |
| `No plugin found for prefix '.repo.local=E'` | 参数用了反斜杠 → 改正斜杠并加引号 |
| `Unable to access jarfile` | 后端还在运行占用 jar → 先停进程再打包 |
| IDEA 满屏 cannot resolve symbol | 打开方式不对（应打开 `backend/pom.xml`），或 Maven 未重新导入 |
| `Unsupported class file major version 68` / `NoSuchFieldError: JCTree$JCImport` | 用了 JDK 22+ → 换 JDK 8（或 11/17/21） |
| `npm install` 长时间无输出后失败 | 镜像对个别包回源超时 → `--registry=https://mirrors.cloud.tencent.com/npm/` |

### 7.2 链接与证书

| 现象 | 原因与处理 |
|---|---|
| `parse Config ... failed, error info:`（后面空白） | 2.x SDK 把三种底层异常都包成这一句。看日志里的"根因链"：**① 缺 `bcpkix` 依赖**；**② `config.toml` 数字没加引号**；**③ `keyStoreDir` 目录不存在**（SDK 会退化到 `getResource("")`，在 fat jar 下抛 `IllegalArgumentException: name` —— 网关已自动建目录 + 换类加载器） |
| `ClassNotFoundException: org.bouncycastle.openssl.PEMParser` | 同上 ①：`pom.xml` 必须显式加 `org.bouncycastle:bcpkix-jdk18on` |
| `Missed certificates: [conf\ca.crt,…] currentPath: <目录>` | 证书没放到 `backend/sdk/conf/`，或 `certPath` 不对（网关会物化成绝对路径） |
| `Failed to load library`（JNI） | 2.x 的 ECDSA 走 WeDPR JNI；网关已把原生库解压到 `sdk/native/` 并设 `java.library.ffipath`，绕开 `~/.fisco/nativeutils` 的写权限问题 |
| `/api/chain/status` 一直 `ready:false` | 依次查：链进程在不在（`pgrep -af fisco-bcos`）、20200 是否监听、证书三个文件是否齐全、`groupId` 是否为 1 |
| Windows 连不上 WSL 的 20200 | WSL2 localhost 转发通常可用；不行就用 `hostname -I` 拿到的 IP 填 `peers` |

### 7.3 合约与链上账号

| 现象 | 原因与处理 |
|---|---|
| 部署失败 / "code too large" | 编译**没开优化器** → 勾选 optimizer（runs=200）后重编（见 §5.4.1） |
| 交易回滚 `NotAnAdmin`，或 `adminOnChainRole` 为空/`NONE`，或创建机构账号返回 `onChainRegistered: false` | 后端 ADMIN 地址不是链上管理员，或后端**没配** `chainsip.crypto.admin-private-key`（此时 `adminAddress` 为空）→ 用部署者账户调 `addAdmin(后端 ADMIN 地址)`（地址看 `/api/chain/status` 的 `adminAddress`），或按 §5.4.3 做法 B 把部署者私钥填进该配置项后重启；修好后已建机构可到「机构管理 → **待上链注册**」补注册 |
| 回滚 `UnknownSponsor` / `NotAuthorized` | 该机构地址还没在链上注册（正常应在**创建账号时自动完成**）→ 到管理员的「机构管理 → **待上链注册**」补一次：`GET /api/admin/institutions/pending` 看列表，`POST /api/admin/institutions/{code}/register`（body `{city}`）补注册；也可按 §5.8 的第 3-4 步重走一次（那是补救路径） |
| 重启后机构地址变了 | 机构私钥只在**创建账号时生成一次**（`cs_institution_key` 密文落库，重启不变）；会变的是 ADMIN —— `chainsip.crypto.admin-private-key` 每次启动都会重写 `code=ADMIN` 的机构密钥。改过它就要在链上确认 ADMIN 一致：看 `GET /api/chain/status` 的 `adminAddress` / `adminOnChainRole`，必要时用部署者账户调 `addAdmin(adminAddress)`，或把部署者私钥填进该配置项 |
| 编码报 `Illegal embedded sign character` | 参数值里含**连字符**（`Company-A`）会被当数字解析 → 业务值别用连字符（中文名不受影响） |
| 交易回滚只看到错误名 | 0.6.10 用 `require(cond,"错误名")`，回执给的是标准 `Error(string)`，错误名与合约里的 `require` 一一对应（逐个查 §6.10） |

### 7.4 数据库

| 现象 | 原因与处理 |
|---|---|
| 启动报 `Communications link failure` / 连不上数据库 | MySQL 没启动或库不存在 → 先起 MySQL 并建库 `chainsip`（见 §5.3） |
| MySQL `Access denied` | 账号密码不对（默认 chainsip/change-me）；或用 `CHAINSIP_DB_USER/PASSWORD` 覆盖 |
| 表不存在 | 后端还没成功启动过；启动时会执行 `schema.sql`（幂等建表），看启动日志有无 SQL 报错 |

---

## 8. 附录：迁移背景

本项目最初按 **FISCO BCOS 3.x** 编写，后整体迁移到 **2.x**（当前状态）。给后来者的三点结论：

1. **合约降到 Solidity 0.6.10**（2.x 官方上限）。0.6 没有内建溢出检查，因此所有资金运算都改走自研 `SafeMath`（37 处）；自定义 `error` 全部改成 `require(cond,"错误名")`；结构体返回值需要 `pragma experimental ABIEncoderV2`。
2. **ABI 保持完全兼容**（函数名/参数/返回值/事件一律不变），所以后端 service 层、数据库层、前端所有页面**都不用改**，只换了 SDK 与编译目标。验证方式：两版 ABI 的 **144 条选择器条目零差异**，且同一套 **292 步业务剧本**在真实 EVM 上跑两版字节码结果完全一致。
3. **链上行为差异只有一处**：revert data 从自定义 error 选择器变成标准 `Error(string)`（错误名保留）。

迁移期的一次性证据文档（编译结果 / 字节码尺寸 / 行为差分转录 / 两版选择器逐条对比 / 两版 ABI JSON）**不在当前工作树中**，见 git 历史；
逐条 API 替换点（含 `javap` 依据）见 `backend/src/main/java/com/chainsip/chain/FiscoChainGateway.java` 顶部注释。

---

## 子模块文档

| 文档 | 内容 |
|---|---|
| [backend/README.md](backend/README.md) | 后端细节：分层、目录结构、接口一览、关键设计（机构私钥 / 读接口带身份 / 事件索引）、IDEA 运行与报错表 |
| [frontend/README.md](frontend/README.md) | 前端细节：页面与角色、账号创建层级、两个职工口径、与后端的三条关键约定、构建打包 |

> 本文档由根目录原有的三份说明合并而成，并已反映当前真实行为（链上地址创建账号时自动生成并绑定、机构链上注册自动完成、只有失败才走「待上链注册」补救）。子模块文档与本文冲突时，以本文与代码为准。

---

## 9. 本轮优化记录

这一轮不动业务语义，只做"让项目更像一个能交付的工程"的优化。完整清单、实测数据与验证命令见
[docs/OPTIMIZATION.md](docs/OPTIMIZATION.md)，这里给出结论。

| 方向 | 改了什么 | 效果 |
|---|---|---|
| 数据库 | 新增 `SchemaIndexMigration`：启动时按 `information_schema` 幂等补齐 16 个二级索引（原来除主键/唯一键外**一个索引都没有**） | 事件/留痕/账号类列表查询不再全表扫；老库新库都自动生效 |
| 可观测 | 新增免登录 `GET /api/public/health`：数据库延迟、链就绪与区块高度、运行时长、JVM 内存、配置告警 | 一条命令判断"是库挂了还是链挂了"，探针可直接接 |
| 连接池 | HikariCP 显式配置（池 20 / 最小 5 / 10s 超时 / 连接校验） | 上链请求与列表查询混跑时不再抢那 10 个默认连接 |
| 传输 | 开启 HTTP 压缩（≥2KB）+ 优雅停机 | 列表类 JSON 响应体积降 ~50%（实测 1031B → 479B） |
| 日志 | `com.chainsip` 默认 INFO（`CHAINSIP_LOG_LEVEL=DEBUG` 可临时打开） | 生产/演示时不再打印每次链调用与 SQL |
| 前端 | Element Plus 图标从"全量注册约 300 个"改为"只注册实际用到的 15 个" | element 分包 1.1MB → 947KB（gzip 334KB → 304KB），零行为变化 |
| 仓库卫生 | `.gitignore` 补 `*.key` / `sdk-regulator/` / `*.log` / `.workbuddy/` | 监管节点私钥与运行时日志不会被误提交 |
| Bug 修复 | `AuthService.toView`：机构账号的 `onChainRole` 之前恒为 null | 顶栏"链上角色"不再永远显示 NONE，与 `/api/chain/status` 一致 |
