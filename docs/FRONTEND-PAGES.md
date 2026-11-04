# 前端页面设计（按后端接口反推）

这份文档记录**页面是怎么从后端代码推出来的**，不是从旧界面抄的。
改页面前先看这里：每个页面的数据来源、能做的动作、以及为什么需要这个页面，都能在 `controller/` 与 `dto/` 里找到依据。

## 一、四条设计约束（都来自代码）

1. **链下角色 ≠ 链上权限。** `@RequireRole` 只是第一道门禁，真正裁决在合约 `msg.sender`。
   所以凡是"能不能办"，界面一律把**链上角色/链上状态**单独展示出来，不和账号状态混在一列。
2. **账号在创建时就自带链上身份。** `AccountView.chainAddress` 恒为字符串（解析不到给空串），
   `AccountCreateResult.institutionCode / chainAddress` 对职工也返回真实值——**页面上不该出现"绑定钱包"这类入口**。
3. **链下建号 ≠ 链上可用。** `InstitutionRegistrationInfo.missing` 只有「未注册 / 未授权 / 空」三种取值，
   这决定了每套角色界面都必须有「待上链注册」这条队列，否则用户会遇到"登录得进、什么都做不了"。
4. **失败要能逐条处理。** 注册机构的接口即使失败也返回 HTTP 200 + `ok:false`
   （`InstitutionRegisterResult`），所以列表页要按行给结果，而不是用全局错误中断整批。

## 二、页面原型（四种，不要发明第五种）

| 原型 | 特征 | 用在 | 骨架 |
| --- | --- | --- | --- |
| **Operate** | 有写操作、有选择、有状态 | 机构与账号、平台账号、职工建档、单位账号 | 页面头 + 摘要行 + 主表 + 抽屉/弹窗表单 |
| **Queue** | 待办条目 + 详情 + 双动作 | 转移办理、养老金发放 | 页面头 + 待办计数 + 双列队列 + 详情抽屉 |
| **Monitor** | 只读、要一眼看懂、要对账 | 首页概览、监管大屏、辖区总览 | 页面头 + KPI 行 + 对账卡 + 科目表 |
| **Inspect** | 查日志、按条件过滤、分页 | 链下索引、合约调试台 | 页面头 + 过滤条 + 表格 + 分页 |

判据：这个页面**会不会发交易**？会 → Operate / Queue；不会 → Monitor / Inspect。

## 三、逐页对应关系

### 公共

| 页面 | 路由 | 角色 | 数据来源 | 关键字段 / 动作 |
| --- | --- | --- | --- | --- |
| 登录 | `/login` | 匿名 | `POST /auth/login` | `token`、`expiresInSeconds`、`account`、`wallets`；失败走 401 + `PASSWORD_ERROR` |

### ADMIN

| 页面 | 路由 | 数据 | 动作 |
| --- | --- | --- | --- |
| 机构与账号 | `/admin/institutions` ✅ | `GET /admin/accounts`、`GET /admin/institutions/pending`、`GET /admin/cities` | `POST /admin/accounts`（含 `onChainRegistered` 三态）、`POST /institutions/{code}/register`、`POST /accounts/{id}/status`、`POST /accounts/{id}/password` |
| 职工建档 | `/admin/persons` | — | `POST /admin/persons`（`PersonRegisterRequest`：姓名/年龄/身份证/城市/单位）、`POST /admin/employees/remove` |
| 平台账号 | `/admin/accounts` | 同「机构与账号」的账号表 | 与机构页合并后可以下线 |
| 资金与机构 | — | `GET /admin/system-stats`、`GET /admin/sponsors/{address}` | `POST /admin/deposit`、`POST /admin/transfer-contract`、`POST /admin/bureaus`、`POST /admin/bureaus/authorize`、`POST /admin/bureaus/grant-role` |

> 「机构与账号」已经吸收了 `admin/accounts` 的职责：账号、机构链账户、链上角色、待上链注册在一页里闭环。

### BUREAU

| 页面 | 路由 | 数据 | 动作 |
| --- | --- | --- | --- |
| 转移办理 | `/bureau/inbox` ✅ | `GET /bureau/transfers/outbound?bureau=`、`inbound`、`GET /bureau/transfers/{id}` | `POST /transfers/authorize`（true 放行 / false 驳回需 reason）、`POST /transfers/{id}/receive` |
| 辖区总览 | `/bureau/region` | `GET /bureau/city/overview`、`/city/sponsors`、`/city/employees`、`/city/insurance`、`/city/payments` | 只读 |
| 养老金支出 | `/bureau/pension` | `/bureau/pension/rules|overview|eligible|retirees|records` | `POST /bureau/pension/retire`（force=特殊审批）、`POST /bureau/pension/claim` |
| 单位账号 | `/bureau/accounts` | `GET /bureau/accounts`、`GET /bureau/cities` | `POST /bureau/accounts`（city 由后端按本局服务城市决定）、`POST /bureau/sponsors/deposit` |

> 待办查询必须用**本局自己的链上地址**：合约 `_requireBureauReader(bureau)` 要求 `msg.sender == bureau`。
> 地址取不到时页面给阻塞式空态，不发请求。

### SPONSOR

| 页面 | 路由 | 数据 | 动作 |
| --- | --- | --- | --- |
| 职工管理 | `/sponsor/employees` | `GET /sponsor/employees/detail`（含姓名/状态/基数/上次缴费） | `POST /sponsor/employees`（建档）、`/employees/salary`、`/employees/suspend` |
| 缴费管理 | `/sponsor/payments` | `GET /sponsor/account?sponsor=`、`GET /sponsor/payments?employee=`、`/employees/detail` | `POST /sponsor/payments`（body 是 `{address}`） |
| 职工账号 | `/sponsor/accounts` | `GET /sponsor/accounts` | `POST /sponsor/accounts`（职工链上地址自动生成 `EMP_`） |

### EMPLOYEE

| 页面 | 路由 | 数据 | 动作 |
| --- | --- | --- | --- |
| 我的参保 | `/employee/account` | `GET /employee/account?employee=`、`/payments`、`/transfer-state`、`/escrow` | `POST /employee/pension/claim` |
| 关系转移 | `/employee/transfer` | `GET /employee/transfer-state`、`GET /employee/bureau-of-city?city=` | `POST /employee/transfer/apply`（`{toCity}`） |

> 职工页也**不需要手输地址**：地址取 `user.chainAddress`；`?employee=` 只是回显自己的地址。

### REGULATOR

| 页面 | 路由 | 数据 | 动作 |
| --- | --- | --- | --- |
| 监管大屏 | `/regulator/overview` ✅ | `GET /regulator/dashboard`（`systemName/version/adminCount/sponsorCount/bureauCount/regulatorCount/transferCount` + `stats` 八字段）、`GET /regulator/city-pool?city=`、`POST /chain/call` 取 `totalPensionPaid` | 只读（写操作会被合约拒绝） |

### 通用

| 页面 | 路由 | 角色 | 数据 |
| --- | --- | --- | --- |
| 链下索引 | `/chain/index` | ADMIN / REGULATOR | `GET /index/events`（employee/sponsor/eventName + page/size）、`GET /index/tx-logs`（institutionCode/functionName/success） |
| 区块链浏览器 | `/chain/explorer` | 所有已登录角色 | `GET /chain/explorer/overview`、`/blocks`、`/blocks/{id}`、`/transactions/{hash}`、`/addresses/{address}`、`/search` |
| 合约调试台 | `/chain/console` | ADMIN/SPONSOR/BUREAU/REGULATOR | `POST /chain/call`、`POST /chain/send` |

## 四、已落地 / 待迁移

| 状态 | 页面 |
| --- | --- |
| ✅ 已按本文件重写 | `admin/Institutions.vue`（机构与账号）、`bureau/Inbox.vue`（转移办理）、`regulator/Overview.vue`（监管大屏）、`Dashboard.vue`（首页概览）、`Login.vue`（沿用 v1 结构 + 新令牌） |
| ⏳ 待迁移 | `admin/Persons`、`bureau/Region`、`bureau/Pension`、`bureau/Accounts`、`sponsor/Employees`、`sponsor/Payments`、`sponsor/Accounts`、`employee/Account`、`employee/Transfer`、`common/IndexData`、`common/Console` |

迁移顺序建议按原型成批做：先把 Operate 类（账号/建档）统一成「页面头 + 摘要行 + 主表 + 抽屉表单」，再把 Queue 类（养老金）照 `Inbox` 的形态复制，最后 Inspect 类（索引/调试台）照同一个过滤条 + 分页。

## 五、共用组件

| 组件 | 用途 | 为什么抽出来 |
| --- | --- | --- |
| `components/PageHeader.vue` | 标题 + 一句话口径说明 + 右侧动作 | 每个页面都要交代"数据从哪来"，放一处口径才不会漂 |
| `components/AddressText.vue` | 地址缩写 + 悬浮看全文 + 点击复制 | 42 字符会撑破表格；复制必须是完整地址，两者必须绑在一起 |

## 六、已知的接口短板（建议后端补，前端已按现状兼容）

1. **`/bureau/transfers/inbound|outbound` 只返回 id 数组**，页面要显示职工/城市就必须逐条 `GET /transfers/{id}`。
   现在是并发拉取并按页上限 20 控制；理想做法是后端提供一个批量详情接口。
2. **`/sponsor/employees/detail` 的 limit 上限 50**，单位职工多时一页看不全，需要明确的"已截断"提示。
3. **`/regulator/accounts|sponsors` 返回的是裸数组**（`CallResult.values`），没有翻页元数据，监管侧还不能做成标准列表页。
