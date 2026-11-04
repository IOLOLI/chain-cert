# ChainSIP 前端（Vue 3 + Vite + Element Plus + Pinia）

管理端 SPA：**Vue 3（`<script setup>`）+ Vite + Element Plus + Pinia + Vue Router（hash 模式）+ axios**，对接 `../backend` 的 REST 接口。

前端只负责**表单、展示与交互**；所有链上写操作都由后端用它托管的机构私钥签名，页面拿到的只是 `SendResult`。

> 目标是"新人只读这一份就能上手改前端"：**§1 目录结构 → §2 实现方法 → §3 页面清单**是主线，§4 起是运行、后端约定与背景资料。
> 项目总体说明（链、合约、后端分层、部署）见仓库根目录 [../README.md](../README.md)。

---

## 1. 目录结构

```
frontend/
├── vite.config.js          # 开发代理（/api → http://127.0.0.1:8080）+ 构建分包
├── .env.development        # VITE_API_BASE、区块浏览器地址
├── index.html              # SPA 挂载点
└── src/
    ├── main.js             # 创建应用：Pinia + Router + Element Plus（含全局图标注册）
    ├── App.vue             # 根组件，只放 <router-view />
    ├── api/
    │   ├── http.js         # axios 实例 + 请求/响应拦截器（唯一出口）
    │   └── index.js        # 所有接口按角色分组导出
    ├── layout/
    │   └── MainLayout.vue  # 侧边菜单 + 顶栏 + 「我的信息」弹窗（改账号信息 / 改密码）
    ├── router/
    │   └── index.js        # 路由表 + meta.roles + 全局前置守卫
    ├── stores/
    │   └── user.js         # Pinia：登录态与当前账号资料（profile）
    ├── utils/
    │   ├── format.js       # 金额/时间/地址/角色/状态枚举格式化 + 复制文本
    │   └── contract.js     # 合约 struct 字段顺序映射（合约改字段只改这里）
    └── views/              # 各角色页面（见下表）
```

各层职责要点：

| 位置 | 职责与约定 |
|---|---|
| `api/http.js` | 统一 `baseURL`、超时、JWT 注入、`code !== 0` 与 HTTP 401/403/503 的**唯一**提示出口 |
| `api/index.js` | 只写"路径 + 方法"的薄封装，**不做业务判断**；按角色分组便于查找 |
| `layout/MainLayout.vue` | 菜单由路由 `meta` 生成并**按角色过滤**；顶栏展示角色/链上角色/地址缩写；「我的信息」弹窗里可自助**改账号信息**与**改密码** |
| `router/index.js` | 路由与角色门禁的第一道闸（真正的权限在合约） |
| `stores/user.js` | 登录态、`profile`，以及 `chainAddress` / `primaryAddress` / `onChainRole` 的取值优先级 |
| `utils/format.js` | 所有展示格式化的唯一来源（金额、时间、地址缩写、枚举中文、复制） |
| `utils/contract.js` | 链上 struct 的**字段顺序**映射（`mapStruct` / `mapPagedList`） |
| `views/` | 一个页面一个文件；只依赖 `api` / `stores` / `utils`，不互相 import |

**页面文件 → 路由对照**（同一张表在 §3 还带上了角色与主要接口）：

| 页面文件 | 路由 |
|---|---|
| `views/Login.vue` | `/login`（`meta.public`，不需要登录） |
| `views/Dashboard.vue` | `/dashboard`（`/` 的默认重定向目标） |
| `views/admin/Institutions.vue` | `/admin/institutions` |
| `views/admin/Persons.vue` | `/admin/persons` |
| `views/admin/Accounts.vue` | `/admin/accounts` |
| `views/bureau/Accounts.vue` | `/bureau/accounts` |
| `views/bureau/Inbox.vue` | `/bureau/inbox` |
| `views/sponsor/Accounts.vue` | `/sponsor/accounts` |
| `views/sponsor/Employees.vue` | `/sponsor/employees` |
| `views/sponsor/Payments.vue` | `/sponsor/payments` |
| `views/employee/Account.vue` | `/employee/account` |
| `views/employee/Transfer.vue` | `/employee/transfer` |
| `views/regulator/Overview.vue` | `/regulator/overview` |
| `views/common/IndexData.vue` | `/chain/index` |
| `views/common/Console.vue` | `/chain/console` |

> 已删除的页面：**「我的链上地址」`/wallet`**（含 `views/Wallet.vue` 与 `utils/wallet.js`）——地址在创建账号时自动生成并绑定，界面不再提供绑定页；顶栏下拉与首页「当前账号身份」卡片即可查看本账号地址。

---

## 2. 实现方法

### 2.1 请求层：`http.js` + `api/index.js`

`src/api/http.js`（所有请求的唯一出口）：

- `baseURL = import.meta.env.VITE_API_BASE || '/api'`，`timeout = 30000`；
- **请求拦截器**：从 `localStorage[chainsip_token]` 取 token，加 `Authorization: Bearer <token>`；
- **响应拦截器（成功分支）**：后端统一返回 `{ code, message, data, timestamp }`；`code === 0` 时**直接把 `data` 返回给业务层**；`code !== 0` 则 `ElMessage.error(message)` 并 `reject`；
- **响应拦截器（失败分支）**：`401` 清 token（并跳登录页，除非已在 `/login`）；`403` 弹后端 message；`503`（链未就绪）用 `ElMessage.warning` 原样弹后端的排查提示；其它弹 `error`；
- **约定**：错误提示由这里统一弹，**业务层 `catch` 里不要再弹一次**（否则会出现两次 toast）。

`src/api/index.js` 按角色分组（只做路径封装）：

| 分组 | 方法（示例） | 说明 |
|---|---|---|
| `authApi` | `login` / `me` / `updateProfile` / `changePassword` | 认证与本人账号；**不提供自助注册**（无 `register`） |
| `chainApi` | `status` / `refresh` / `block` / `stats` / `call` / `send` | 链网关状态、区块、统计、通用 call/send（调试台用） |
| `adminApi` | `addSponsor` / `addBureau` / `addRegulator` / `createAccount` / `accounts` / `cities` / `pendingInstitutions` / `registerInstitution` / `institutionKeys` … | ADMIN |
| `bureauApi` | `inbound` / `outbound` / `authorize` / `receive` / `accounts` / `createAccount` / `cities` / `depositToSponsor` | BUREAU |
| `sponsorApi` | `addEmployee` / `suspend` / `setSalary` / `pay` / `employeeDetails` / `employees` / `account` / `payments` / `accounts` / `createAccount` | SPONSOR |
| `employeeApi` | `account` / `payments` / `transferState` / `escrow` / `applyTransfer` / `transfer` / `bureauOfCity` | EMPLOYEE |
| `regulatorApi` | `dashboard` / `systemStats` / `cityPool` / `transfer` / `accounts` / `sponsors` … | REGULATOR（只读） |
| `indexApi` | `events` / `txLogs` | 链上索引（事件 / 上链日志） |

### 2.2 状态：Pinia `stores/user.js`

- **state**：`token`（初值取自 `localStorage[chainsip_token]`）、`profile`（登录响应里的 `data.account` 或 `GET /api/auth/me`，经 `mapProfile` 补 `chainAddress`）；
- **getters（取值优先级是关键）**：

| getter | 取值优先级 |
|---|---|
| `role` / `displayName` / `institutionCode` | 直接读 `profile` 对应字段，缺省空串 |
| `wallets` | `profile.wallets`，缺省 `[]` |
| `onChainRole` | ① 顶层 `profile.onChainRole`（兼容字段）→ ② `wallets` 里 `primaryWallet === true` 的那项 → ③ 第一个钱包 → ④ `''`（**后端把 `roleOf` 放在每个钱包项上，顶层没有该字段**；职工账号链上本来没角色，此时为空是**正确结果**，不要造假值） |
| `primaryAddress` | 已验签钱包里 `primaryWallet` 的那条 → 第一条已验签 → `''` |
| `chainAddress` | ① `profile.chainAddress` → ② `primaryAddress` → ③ `''`（**页面一律用它**） |
| `isAdmin` / `isSponsor` / `isBureau` / `isRegulator` / `isEmployee` | 按 `role` 判断 |

- **actions**：
  - `login(payload)`：写 `token` + `localStorage` + `profile`；
  - `loadProfile()`：拉 `GET /api/auth/me` 覆盖 `profile`（路由守卫与"取链上地址前"会调用）；
  - **`setProfile(patch)`**：`{ ...profile, ...patch }` 局部合并——「我的信息」改完用户名/显示名后用它刷新界面，**不需要重新登录**；
  - `ensureChainAddress()`：本地没有 `chainAddress` 时补拉一次资料，失败也不抛错；
  - `logout()`：清 `token`、`profile` 与 `localStorage`。

### 2.3 路由与菜单

- 路由表 `router/index.js`：`/login`（`meta.public`）+ `MainLayout` 下的子路由；根 `redirect: '/dashboard'`；末尾 `{ path: '/:pathMatch(.*)*', redirect: '/dashboard' }`；`createWebHashHistory`；
- **菜单不单独维护**：`MainLayout` 读取 `router.options.routes` 里 `/` 的 `children`，用 `!roles || roles.length === 0 || roles.includes(user.role)` 过滤后生成，所以**只改路由 `meta.roles`，菜单自动跟着变**；
- **全局守卫 `beforeEach`**：`meta.public` 直接放行 → 未登录跳 `/login?redirect=<fullPath>` → 没有 `profile` 时先 `loadProfile()`（失败即 `logout()` 回登录页）→ `meta.roles` 不含当前角色则 `ElMessage.warning('当前角色「X」无权访问该页面')` 并跳回 `/dashboard`；
- **当前角色门禁（`meta.roles` 现状）**：

| 路由 | 可见角色 |
|---|---|
| `/dashboard` | 全部（无 `roles`） |
| `/admin/*` | `ADMIN` |
| `/bureau/accounts`、`/bureau/inbox` | `BUREAU` |
| `/sponsor/accounts`、`/sponsor/employees`、`/sponsor/payments` | `SPONSOR` |
| `/employee/account`、`/employee/transfer` | `EMPLOYEE` |
| `/regulator/overview` | `REGULATOR`、`ADMIN` |
| **`/chain/index`（链上索引）** | **`ADMIN`、`REGULATOR`** |
| `/chain/console`（合约调试台） | `ADMIN`、`SPONSOR`、`BUREAU`、`REGULATOR` |

  > `/chain/index` 与 `/chain/console` 的可见性是**各自独立**配置的，改一个不要顺手改另一个。

### 2.4 链上地址从哪来

- 账号创建时后端**自动生成链上地址并同时写为绑定地址**（`cs_wallet`，`verified=true`、首个为主地址），`GET /api/auth/me` 与登录响应都带 `chainAddress` / `primaryAddress`；
- 前端**一律优先用 `chainAddress`**（`user.chainAddress`），`primaryAddress` 仅作兼容兜底，`wallets[]` 只用于「我的信息」里的绑定列表展示；
- 后端发起链上交易**始终使用系统为该账号生成的那个地址**（机构账号 = 创建时生成的机构私钥地址）；
- **界面不再提供绑定页**：原 `/wallet` 页面与手动绑定接口（`auth/wallets`、`wallet/message`、`wallet/bind`）前后端**均已删除**，后端只保留联调代签接口 `POST /api/auth/demo/sign`（前端不调用）。

### 2.5 两个「职工」口径（最容易踩的坑）

页面上有两个长得像但**数据源不同**的职工下拉，别混用：

| 下拉 | 数据来源 | 含义 / 用途 | 为空说明 |
|---|---|---|---|
| **选择职工账号（用于建档）**<br>（「职工管理」→「为职工建档」对话框里） | `GET /api/sponsor/accounts`（**账号口径**） | 建 `addEmployee` 时选它，选中后自动带出该账号的链上地址。**包含刚创建、还没建档的账号**；已建档的账号在下拉里标注 **已建档** 并**置灰不可选**，避免重复建档被合约 `AlreadyInsured` 回滚 | 本单位还没有职工账号 → 对话框里直接给「去创建职工账号」跳转到 `/sponsor/accounts` |
| **选择已建档职工（用于减员 / 调薪 / 缴费 / 查记录）**<br>（「职工管理」工具条、缴费管理页） | `GET /api/sponsor/employees/detail`（**链上已建档口径**） | 减员 / 调薪 / 缴费 / 查缴费记录都用它 | **还没建档时就是空的，这是正确行为不是 bug** → 空态直接给「为职工建档」按钮 + 跳转 `/sponsor/accounts` |

正确顺序：先在「职工账号」页建**职工账号** → 再到「职工管理 → 为职工建档」里**选这个账号建档**（地址自动带出）→ 建档后的职工才会出现在减员 / 调薪 / 缴费的下拉里。缴费记录同样只对"已建档职工"可查。

> 实现细节：已建档判定是前端用"账号地址是否出现在 `employees/detail` 的地址集合里"本地推导的（`filedAddressSet` / `accountFiled`）；若后端在 `GET /api/sponsor/accounts` 行上补 `insured` 字段，应改为**优先用该字段、缺失时回退到当前推导**。

### 2.6 关键交互约定（展示一律走 `utils/format.js`）

| 函数 | 用途与行为 |
|---|---|
| `formatAmount(value)` | 链上整数字符串 → **千分位**，不产生科学计数法；非纯数字原样返回；`null/undefined/''` → `-` |
| `formatTime(value)` | 秒级/毫秒级时间戳或 ISO 字符串 → `yyyy-MM-dd HH:mm:ss`；空或 `0` → `-` |
| `shortAddress(addr, head = 8, tail = 6)` | `0x1234…abcd`；配合 `el-tooltip` hover 显示完整地址 |
| `copyText(text)` | 复制：优先 Clipboard API（HTTPS/localhost），否则 `textarea + execCommand` 兜底，返回是否成功 |
| `employeeStatusName/Type`、`transferStatusName/Type` | 合约枚举 → 中文 / `el-tag` 类型 |
| `roleName` / `roleTagType` | 链下角色展示 |

- **后端优先**：有 `statusName` 就用后端的，缺字段才回退本地枚举映射；
- **字段缺失一律容忍**：`-` 或地址缩写，**绝不报错**（例如 `formatAmount(undefined) === '-'`）；
- 金额输入统一传**字符串**（避免浮点/科学计数法），正整数校验用正则 + 去前导零。

### 2.7 空态与容错（列表类页面的统一写法）

1. **列表返回归一化**：接口可能返回裸数组，也可能被包一层（`{ list }` / `{ records }` / `{ rows }` / `{ data }` / `{ items }`），统一用页面内的 `normalizeList(data)` 归一化，取不到就返回 `[]`，**绝不抛错**（抛错会跳过 `loading` 复位）；
2. **`loading` 一律 `try / catch / finally`**：成功、失败、返回非数组都在 `finally` 复位；
3. **三态空文案**（`empty-text` 动态计算，不要写死"加载中"）：
   - 读取中 → 「正在读取…」；
   - 失败 → 「读取失败，请重试」（并清空旧数据，避免把上次结果当成最新）；
   - 真实的空 → 明确写出下一步该做什么，例如「本单位还没有已建档职工。请点「为职工建档」…」+ 跳转按钮；
4. **不要把 `loading` 文案写进 `empty-text`**：`el-table` 的 `empty-text` 在数据为空时**无条件显示**，写死"加载中"会让空数组看起来像卡死（真实踩过的坑）；正确的加载指示是 `v-loading="loading"`。

---

## 3. 页面清单与可见角色

| 路径 | 页面 | 可见角色 | 主要接口 |
|---|---|---|---|
| `/login` | 登录（含**测试账号一键填入**；无自助注册入口） | 公开 | `POST /api/auth/login` |
| `/dashboard` | 首页概览（链状态 / 区块 / 资金对账三条恒等式 / 当前账号身份对照） | 全部 | `GET /api/chain/status`、`/block`、`/stats`、`GET /api/auth/me` |
| `/admin/institutions` | 机构管理（公司 / 社保局 / 监管 / 合约接线 / 待上链注册） | ADMIN | `POST /api/admin/sponsors`、`/bureaus`、`/regulators`、`GET /api/admin/institutions/pending`、`POST /api/admin/institutions/{code}/register` |
| `/admin/persons` | 职工建档 / 注销 | ADMIN | `POST /api/admin/persons`、`/admin/employees/remove` |
| `/admin/accounts` | 平台账号与机构链账户 | ADMIN | `GET|POST /api/admin/accounts`、`GET /api/admin/cities`、`GET /api/admin/institution-keys` |
| `/bureau/accounts` | 缴费单位账号（创建缴费单位 / 给所属单位**注资**） | BUREAU | `GET|POST /api/bureau/accounts`、`GET /api/bureau/cities`、`POST /api/bureau/sponsors/deposit` |
| `/bureau/inbox` | 转移办理（转出放行 / 转入接收 / 待办） | BUREAU | `GET /api/bureau/transfers/inbound|outbound`、`POST /api/bureau/transfers/authorize|{id}/receive` |
| `/sponsor/accounts` | 职工账号（创建参保职工账号） | SPONSOR | `GET|POST /api/sponsor/accounts` |
| `/sponsor/employees` | 职工管理（建档 / 减员 / 调薪；列表与下拉共用明细接口） | SPONSOR | `GET /api/sponsor/employees/detail`、`POST /api/sponsor/employees`、`/suspend`、`/salary` |
| `/sponsor/payments` | 缴费管理（余额 / 试算 / 缴费 / 缴费记录） | SPONSOR | `GET /api/sponsor/account`、`GET /api/sponsor/employees/detail`、`POST /api/sponsor/payments`、`GET /api/sponsor/payments`、`GET /api/sponsor/payments/quote`（试算，链下政策参数） |
| `/employee/account` | 我的社保账户（余额 / 缴费记录） | EMPLOYEE | `GET /api/employee/account`、`GET /api/employee/payments` |
| `/employee/transfer` | 关系转移（申请 / 状态 / 托管金额 / 详情） | EMPLOYEE | `GET /api/employee/transfer-state|escrow`、`POST /api/employee/transfer/apply` |
| `/regulator/overview` | 监管大屏（规模 / 对账 / 城市基金池 / 公司名单） | REGULATOR、ADMIN | `GET /api/regulator/dashboard`、`/system-stats`、`/city-pool` |
| `/chain/index` | 链上索引（事件 / 上链日志） | **ADMIN / REGULATOR** | `GET /api/index/events`、`/index/tx-logs` |
| `/chain/console` | 合约调试台（通用 call / send） | ADMIN、SPONSOR、BUREAU、REGULATOR | `POST /api/chain/call`、`/chain/send` |

菜单由路由配置的 `meta.roles` 自动过滤，路由守卫再做一次拦截（见 §2.3）。

> **缴费管理的两个细节**：① 职工用**姓名下拉选择**（`GET /api/sponsor/employees/detail`，无需传 `sponsor`），选中后按该职工的链上地址调用 `POST /api/sponsor/payments`；
> ② 选中职工会**自动读取**缴费记录（`GET /api/sponsor/payments?employee=0x…&offset&limit`，返回 `{ records, total, nextOffset }`），缴费成功后也会自动刷新，「查缴费记录」按钮保留为手动刷新；表格空态区分「请先选择已建档职工 / 正在读取 / 读取失败，请重试 / 该职工暂无缴费记录」。

---

## 4. 构建与运行

```bash
cd frontend
npm install
npm run dev          # http://127.0.0.1:5173
```

开发态由 Vite 代理把 `/api` 转到 `http://127.0.0.1:8080`（见 `vite.config.js`），所以**后端要先起来**，不用改跨域配置。

```bash
npm run build        # 产物在 dist/
npm run preview
```

生产部署时把 `VITE_API_BASE` 设为后端地址（如 `https://api.example.com/api`），见 `.env.development` 的说明。

**受限 / 沙箱环境**：`npm run build` 可能失败于

```
Error: spawn EPERM   (node_modules/esbuild/lib/main.js, ensureServiceIsRunning)
failed to load config from vite.config.js
```

这是 **esbuild 需要 spawn 子进程、被沙箱禁止**导致的，**与代码无关**。此时改用静态校验（不需要构建产物）：

```powershell
# 1）全量 .vue：SFC 解析 + <script setup> 编译 + 模板编译
node -e "const fs=require('fs'),p=require('path'),c=require('@vue/compiler-sfc');let bad=0;const walk=d=>fs.readdirSync(d,{withFileTypes:true}).forEach(e=>{const f=p.join(d,e.name);if(e.isDirectory())walk(f);else if(e.name.endsWith('.vue')){const r=c.parse(fs.readFileSync(f,'utf8'),{filename:f});if(r.errors&&r.errors.length){bad++;console.log('ERR',f,r.errors.map(x=>x.message).join('; '))}else{const s=c.compileScript(r.descriptor,{id:f});if(s.errors&&s.errors.length){bad++;console.log('ERR-SCRIPT',f,s.errors)}try{c.compileTemplate({source:r.descriptor.template.content,filename:f,id:f})}catch(err){bad++;console.log('ERR-TEMPLATE',f,err.message)}}}});walk('src');console.log(bad?'FAILED '+bad:'ALL VUE FILES OK')"

# 2）全部 JS 语法检查
Get-ChildItem src -Recurse -Include *.js | ForEach-Object { node --check $_.FullName }
```

---

## 5. 与后端的约定

**5.1 统一响应与错误**

后端统一 `{ code, message, data, timestamp }`，`code === 0` 表示成功（`data` 由 `http.js` 直接返回给业务层）。HTTP 语义：`401` 未登录 / token 失效，`403` 权限不足，`503`（`code 2001`）链未就绪。**错误提示统一在 `http.js` 弹后端 `message` 原文，业务层 catch 里只做状态收敛，不要重复弹 toast**（这正是"改密码/改账号信息/注资/缴费"这些写操作的统一失败路径）。

**5.2 认证在链下，授权在链上**

登录只解决"你是谁"（后端 JWT）；链上操作的权限由合约按 **交易签名地址**（`msg.sender`）判定。所以界面上的"链下角色"与"链上角色"可能不一致 —— 不一致时交易会被合约回滚，这是有意设计。

- **链上角色取值**：后端把 `roleOf` 的结果放在**每个钱包项**上（`wallets[].onChainRole`），顶层没有该字段；`stores/user.js` 的 `onChainRole` getter 按「顶层字段（兼容）→ 主钱包 `primaryWallet` → 第一个钱包 → 空串」取值，页面统一用它；职工账号在链上本来没有角色，此时显示"未查询到"是**正确结果**。
- **`/api/chain/stats` 的 `values` 可能是单 tuple 外层又包了一层数组**（`[[…]]`），首页取数前先解包（`rawValues` 为字符串时同样 `JSON.parse` 后解包）。
- **`GET /api/chain/status` 里的 ADMIN 身份字段**：除 `ready` / `message` / `groupId` / `coreContract` / `transferContract` / `sdkConfigFile` 外，还有 `adminAddress`（后端用来签名的 ADMIN 机构链上地址）与 `adminOnChainRole`（该地址在合约里的角色名，未注册时为空）。首页「链网关状态与后端 ADMIN 身份」卡片把两者并排展示：**必须指向同一个地址**，管理员发起的链上操作（补注册机构地址、注资等）才不会被合约回滚。

**5.3 顶栏「我的信息」：两个自助区块**（任意角色，都要填**当前密码**做二次确认）

- **修改账号信息**（`POST /api/auth/profile`，body `{ newUsername, newDisplayName, password }`）：改用户名 / 显示名，两个新字段**留空表示不修改**（都留空提示"请至少填写一项要修改的内容"），新用户名需 3-32 位字母 / 数字 / 下划线；**成功后不需要重新登录**，前端用返回的 `{ username, displayName }` 局部刷新 store 资料（`user.setProfile`）。
- **修改密码**（`POST /api/auth/password`，body `{ oldPassword, newPassword }`）：前后端各校验一次（必填、新密码 ≥ 6 位、两次输入一致），**修改成功后需要重新登录**（前端清登录态并跳回登录页）。
- 两个区块的失败提示都由 `http.js` 统一弹后端 `message` 原文；弹窗关闭（`@closed`）时两个表单都会重置。

**5.4 字段缺失一律容忍**

后端并行补字段时，前端按**兼容写法**取值，未就绪的字段回退显示 `-` 或地址缩写，不报错：

- 列表返回多包一层 → `normalizeList` 兼容；
- `employees/detail` 明细：有 `statusName` 用后端的，缺则本地枚举映射；无 `name` 显示地址缩写；`lastPaymentAt` 缺失回退 `lastPaidAt`；`personalBalance` / `overallBalance` / `paidMonths` 缺失显示 `-`；
- 「所属单位 / 缴费单位」优先 `sponsorName`，为空回退 `sponsor` 地址缩写（hover 看完整地址）。

**5.5 账号创建与机构链账户（背景资料）**

平台账号**逐级创建**：管理员 → 社保局 → 缴费单位 → 参保职工；越权创建后端直接 403（前端由 `http.js` 弹提示）。

| 层级 | 谁创建 | 页面 / 接口 | 建完还需要做什么 |
|---|---|---|---|
| 平台管理员 ADMIN | 后端初始化（空库只建 `admin / ChangeMe_123456`） | — | 把"部署合约那个账户"的私钥填进后端 `chainsip.crypto.admin-private-key`（或环境变量 `CHAINSIP_ADMIN_PRIVATE_KEY`），链上链下即为同一个 ADMIN，不需要 `addAdmin` 交接；**留空则 ADMIN 发不出交易** |
| 社保局 BUREAU | 管理员 | `/admin/accounts` → `POST /api/admin/accounts` | 创建时后端**自动完成链上注册**（`addBureaus`），**只有自动注册失败**才需要管理员到「机构管理 → 待上链注册」补一次；**授权是单独一步**（「机构管理 → 社保局」里切换，新注册默认未授权，未授权不能办理转移放行 / 接收） |
| 缴费单位 SPONSOR | 社保局 | `/bureau/accounts` → `POST /api/bureau/accounts` | **城市继承自创建它的社保局服务城市**（下拉选择，不给手填）；后端用 **ADMIN 私钥代签，创建即自动上链注册**，创建成功即可为职工建档、缴费；**只有自动注册失败**（社保局未注册服务城市、链未就绪、链上回滚等）的机构才需要管理员到「机构管理 → 待上链注册」补一次 |
| 参保职工 EMPLOYEE | 缴费单位 | `/sponsor/accounts` → `POST /api/sponsor/accounts` | 后端在创建时**自动生成该职工的链上地址，并同时把它写为账号的绑定地址**，单位在「职工管理」里选中该账号即可建档（地址自动带出，链上 `addEmployee`）；用户**无需**做任何绑定操作（界面也不再提供绑定页） |

要点：

- **管理员创建的机构账号（社保局 / 缴费单位 / 监管）创建即自动上链注册**：`POST /api/admin/accounts` 可带 `name` 与 `city`，响应 `data` 用 `onChainRegistered`（`true` 已注册 / `false` 注册失败 / `null` 不涉及机构注册，如 ADMIN、EMPLOYEE）与 `onChainMessage` 说明结果。「机构管理 → 待上链注册」是**补救入口**：只有自动注册失败的机构才会留在那里，管理员点一下「注册」补一次即可。
- **社保局创建的缴费单位同样"创建即自动注册"**：合约里注册公司的接口是 `onlyAdmin`，社保局本身没有权限，所以由后端用 **ADMIN 私钥代签**完成注册；`POST /api/bureau/accounts` 的响应同样用 `onChainRegistered` / `onChainMessage`。待注册列表来自 `GET /api/admin/institutions/pending`，补注册走 `POST /api/admin/institutions/{code}/register`，body `{ "city": "A市" }`（缴费单位/社保局必填，监管可留空）；该接口**失败也返回 HTTP 200**，要看响应里的 `ok`。
- **缴费单位的城市继承自创建它的社保局服务城市（下拉选择，不给手填）**：社保局在「缴费单位账号」页建账号时，城市从 `GET /api/bureau/cities`（本局自己的服务城市）取——**只注册了 1 个城市就自动带出并置灰**，**多个城市必须选一个**，**一个都没有**（本局尚未在链上注册服务城市 / 链未就绪）时下拉禁用并提示"本社保局尚未在链上注册服务城市，请联系平台管理员"，此时不阻止提交、由后端返回准确报错，页面不提供手填入口；提交时仍会带上选中的 `city`（`city` 在后端已变为可选参数）。管理员在「平台账号」页建**缴费单位**时的城市同样改成下拉，选项来自 `GET /api/admin/cities`（链上已注册的全部城市，显示"城市（社保局名）"，提交只传 `city`）；读不到已注册城市时回退为文本输入并提示先确认社保局已在链上注册+授权。管理员建**社保局**时城市仍为手填（注册新社保局就是"占用一个城市"，允许链上还没有的新城市）。
- 创建社保局 / 缴费单位账号时会自动生成一把**机构私钥**并落库（接口不返回私钥）。机构链上地址在创建响应里给出：社保局创建缴费单位时是 `chainAddress`，管理员创建社保局时是 `institutionCode`（地址可在「平台账号 → 机构链账户」或 `GET /api/admin/institution-keys` 看到）。**链上注册也在创建时自动完成**；只有自动注册失败时才需要走「机构管理 → 待上链注册」补救，**在补注册成功之前**，该机构发起的链上交易会被合约回滚。
- 职工账号**不需要**机构编码、也没有机构私钥；后端在创建职工账号时会**自动生成该职工的链上地址**并在响应里以 `chainAddress` 返回（兼容字段），`GET /api/sponsor/accounts` 的每一行也会带上该职工的 `chainAddress`。
- **「职工管理」的列表与下拉共用 `GET /api/sponsor/employees/detail`**（`sponsorApi.employeeDetails(offset, limit)`，SPONSOR，**不需要传 sponsor**）：返回 `address` / `name` / `status` / `statusName` / `city` / `paymentBase` / `lastPaidAt` / `suspended`，并已补上 `personalBalance` / `overallBalance` / `paidMonths` / `overdueCount` / `lastPaymentAt` / `salary`。页面按兼容写法取值（缺字段回退，不报错）。列表**不再**调用只返回地址的 `GET /api/sponsor/employees`，也**不再逐行调 `getInsuranceAccount`**（原来每行一次，N+1）。
- **社保局可给自己所属的缴费单位注资**（`/bureau/accounts` 列表每行的「注资」按钮 → `POST /api/bureau/sponsors/deposit`，body `{ sponsor, amount }`，`amount` 为字符串）：链上走 `depositSponsor`，是给该单位**统筹账户"增加"余额（累计注资）**，不能改成任意数值，金额只接受正整数；后端用 **ADMIN 私钥代签**。仅限**本局服务城市内**的单位（不属于本局 → 403），单位未注册 / 金额非法 → 400。**平台管理员也能在「机构管理 → 缴费单位」注资**；单位可用余额可在该单位登录后的「缴费管理」里看到。机构链上地址为空的异常行，「注资」按钮禁用并给出提示。
- 首页概览会同时显示「后端 ADMIN 链上地址」与「链上 ADMIN 角色」：两者必须是同一个地址，管理员发起的链上操作才不会被合约回滚。

**5.6 本项目用到的接口（补充清单）**

| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| POST | `/api/auth/profile` | 任意 | 改自己的用户名 / 显示名（需当前密码），返回 `{ username, displayName }`，无需重新登录 |
| POST | `/api/auth/password` | 任意 | 改自己的密码（`{ oldPassword, newPassword }`），成功后需重新登录 |
| GET | `/api/bureau/accounts` | BUREAU | 本局下辖的缴费单位账号 |
| POST | `/api/bureau/accounts` | BUREAU | 创建缴费单位账号；body `{username,password,displayName,name?,city?,institutionCode?}`，`city` 可选：本局只有一个服务城市时后端自动使用，多个时必须给且必须在服务城市内，未注册服务城市 → 400 |
| POST | `/api/bureau/sponsors/deposit` | BUREAU | 给**本局所属**的缴费单位注资（链上 `depositSponsor`，后端用 ADMIN 私钥代签）；body `{ sponsor, amount }`（`amount` 传字符串）→ `data` 为 SendResult；403 不属于本社保局 / 400 单位未注册、金额非法 / 401 未登录 |
| GET | `/api/bureau/cities` | BUREAU | **本社保局自己的服务城市**数组，如 `["A市"]`；未注册 / 链未就绪时返回空数组（HTTP 200） |
| GET | `/api/admin/cities` | ADMIN | 链上已注册的全部城市 `[{city, bureau, bureauName}]`，给"缴费单位"的城市下拉用；链未就绪时空数组 |
| GET | `/api/sponsor/accounts` | SPONSOR | 本单位的职工账号（建档下拉的口径，含未建档） |
| POST | `/api/sponsor/accounts` | SPONSOR | 创建职工账号 |
| GET | `/api/sponsor/employees/detail` | SPONSOR | 本单位参保职工明细（`?offset&limit`，**不需要传 sponsor**）：`address`、`name`、`status`、`statusName`、`city`、`paymentBase`、`lastPaidAt`、`suspended`、`personalBalance`、`overallBalance`、`paidMonths`、`overdueCount`、`lastPaymentAt`、`salary` |
| POST | `/api/sponsor/payments` | SPONSOR | 为职工缴费，body `{ address }`（**地址**，不是姓名）；同一职工 30 天内只能缴一次 |
| GET | `/api/sponsor/payments` | SPONSOR | 某职工的缴费记录 `?employee=0x…&offset&limit` → `{ records, total, nextOffset }`，`records[]` 字段：`timestamp` / `paymentBase` / `personalAmount` / `overallAmount` / `sponsor` / `overdue` |
| GET | `/api/admin/institutions/pending` | ADMIN | 待上链注册 / 待授权的机构列表（字段：`code`、`role`、`name`、`address`、`onChainRole`、`registered`、`authorized`、`missing`） |
| POST | `/api/admin/institutions/{code}/register` | ADMIN | 注册单个机构，body `{ "city": "A市" }`（可空）→ `{ code, role, address, ok, message }`，**失败也是 HTTP 200**，看 `ok` |
| GET | `/api/index/events`、`/api/index/tx-logs` | ADMIN、REGULATOR（页面入口） | 链接下索引：事件与上链日志 |

---

## 6. 测试账号

登录页只保留平台管理员账号，其他业务账号由管理员按层级创建：

| 账号 | 密码 | 角色 |
|---|---|---|
| admin | ChangeMe_123456 | 平台管理员 |

密码仅用于本地测试链，生产环境必须通过环境变量替换。

生产 / 空库启动时后端**只创建一个平台管理员账号**；其余账号**不预置**，要按 §5.5 的创建层级在页面里逐级建出来（社保局 → 缴费单位 → 职工）。**账号由上级逐级创建，不提供自助注册**（登录页没有「注册新账号」入口，`authApi` 里也没有 `register`，避免绕过层级）。

---

## 7. 常见问题

| 现象 | 处理 |
|---|---|
| 登录后马上被弹回登录页 | 后端没起来，或 JWT 密钥变了导致 token 失效；重新登录 |
| 页面提示"链未就绪" | 链没起或证书没放：看后端 `GET /api/chain/status` 的 `message`，它会给出具体原因 |
| 所有请求 404 | 后端端口不是 8080，改 `vite.config.js` 的 proxy target |
| 按钮点了提示"没有权限" | 链下角色不匹配（403）；或合约回滚（ADMIN / REGULATOR 可在"链上索引 → 上链操作日志"里看到回滚信息） |
| 监管页面按钮少 | 监管是只读角色，写操作在合约层就被拒绝，前端也不提供入口 |
| 列表一直显示"加载中" | 检查是否把加载文案写进了 `empty-text`（应该用 `v-loading` + 动态空态），并确认 `loading` 在 `finally` 里复位、返回值经过 `normalizeList` |
| 「职工管理」下拉是空的 | 那两个下拉都只列**已建档**职工；先到「职工账号」建账号，再「为职工建档」（见 §2.5） |
| `npm run build` 报 `spawn EPERM` | 沙箱禁止 esbuild spawn 子进程，与代码无关；改用 §4 的静态校验命令 |
