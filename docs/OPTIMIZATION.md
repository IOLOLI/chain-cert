# ChainSIP 优化记录

> 本文只记录"动了什么、为什么动、前后差多少、怎么验证"。
> 业务语义（链上判权、资金恒等式、角色层级）一条没改；所有改动都可以单独回退。

## 0. 结论速览

| # | 优化项 | 关键数字 | 风险 |
|---|---|---|---|
| 1 | 补 16 个查询索引（启动时幂等迁移） | 列表类查询由 `type=ALL` 全表扫变为索引查找 | 低（缺索引才建，失败只记日志） |
| 2 | 免登录健康检查 `GET /api/public/health` | 原来只能"登录→看首页"，现在一条 curl 定位 | 无（只读） |
| 3 | HikariCP 显式配置 | 默认 10 → 池 20 / 最小 5 / 连接校验 | 低 |
| 4 | HTTP 压缩 + 优雅停机 | 列表 JSON 1031B → 479B（约 -54%） | 无 |
| 5 | 日志默认 INFO | 不再打印每次链调用与 SQL | 无（可用环境变量开回 DEBUG） |
| 6 | 前端图标按需注册 | element 分包 1.1MB → 947KB（gzip 334→304KB） | 无（同名注册，行为不变） |
| 7 | 仓库卫生 | `*.key` / `sdk-regulator/` / `*.log` / `.workbuddy/` 不再可被误提交 | 无 |

## 1. 基线体检

优化前先测，避免"凭感觉优化"。

### 1.1 接口耗时（本机，链在虚拟机内）

```
/api/chain/status          12ms
/api/admin/system-stats    42ms
/api/admin/accounts        17ms
/api/chain/block           20ms
```

结论：**接口本身不慢**，链读在局域网内只要 20–40ms，所以这一轮不做缓存、不做异步化——
加了收益也只有几十毫秒，反而引入"刚缴完费看到旧余额"的脏读风险。

### 1.2 数据库索引

```
information_schema.statistics 里，chainsip 库全部表只有：
  PRIMARY + uk_cs_account_username / uk_cs_wallet_address / uk_cs_institution_code / uk_cs_institution_address
```

即：**业务查询列上一个二级索引都没有**。而列表接口的过滤条件全落在这些列上：

- `GET /api/index`（链上索引）：`employee_address` / `sponsor_address` / `event_name`；
- `GET /api/index/tx`（上链留痕）：`institution_code` / `function_name` / `success`；
- 账号与钱包：`role` / `institution_code` / `created_by` / `account_id`。

数据量小时看不出差别，上链事件写到万级以后就是"越翻页越慢"。

### 1.3 前端产物

```
dist/assets/element-*.js   1.1 MB  (gzip 334 KB)
dist/assets/vendor-*.js    160 KB  (gzip  62 KB)
```

element 分包占了大头，来源有两处：`app.use(ElementPlus)` 全量注册组件，以及
`import * as ElementPlusIconsVue` 全量注册约 300 个图标。

### 1.4 配置层

- 连接池未配置，用 Hikari 默认 10 个连接；一次上链请求要占住连接做"发交易 + 写留痕 + 写事件索引"；
- `com.chainsip: DEBUG`，每次链调用与 SQL 都打日志；
- 响应未压缩，列表类 JSON 动辄 1KB+；
- 没有健康检查，排障只能靠登录后看首页提示或翻日志。

## 2. 逐项改动

### 2.1 数据库索引：`config/SchemaIndexMigration.java`

不写进 `schema.sql` 的原因：`create table if not exists` 只对新库生效，而 MySQL 8 不支持
`create index if not exists`，写进脚本第二次启动就会因 `Duplicate key name` 直接失败。

实现方式：启动时查 `information_schema.statistics`，缺哪个建哪个，单条失败只记日志不阻断启动。
共 16 个索引：

```
cs_account        role / institution_code / created_by
cs_wallet         account_id
cs_chain_tx_log   institution_code / function_name / success / sender_address / created_at
cs_chain_event    employee_address / sponsor_address / event_name / block_number / created_at
cs_login_log      username / created_at
```

启动日志（第一次运行）：

```
SchemaIndexMigration : 已创建索引 idx_cs_account_role(role) on cs_account
... （共 16 条）
SchemaIndexMigration : 本次启动补齐了 16 个查询索引（共 16 个），后续启动会跳过已存在的索引
```

第二次启动会走 `indexExists` 分支，静默跳过——幂等。

### 2.2 健康检查：`controller/HealthController.java`

挂在 `/api/public/**` 白名单下，**不需要登录**（登录都进不去时更需要它）：

```json
{
  "status": "UP",
  "db":    { "ok": true, "latencyMs": 0, "product": "8.0.46" },
  "chain": { "ready": true, "blockNumber": "8", "groupId": 1, "coreContract": "0x04d6…" },
  "uptimeSeconds": 52,
  "memory": { "usedMb": 288, "maxMb": 7195, "usagePercent": 4 },
  "warnings": ["JWT 密钥仍是仓库默认值…", "…"]
}
```

两个设计取舍：

1. **`status` 只反映关键依赖**（数据库、链），配置类提醒只进 `warnings`。
   否则一套功能完全正常的本地环境会因为"还在用开发默认密钥"永远显示 DEGRADED，探针就失去意义。
2. **不检查"是否配置 ADMIN 机构私钥"**。ADMIN 私钥的正常位置是库里的 `cs_institution_key`
   （建账号时自动生成、AES-GCM 加密落库），配置项只是"接管已存在账户"的可选用法，空的完全正常。

### 2.3 连接池 / 压缩 / 优雅停机

```yaml
spring.datasource.hikari:
  maximum-pool-size: 20
  minimum-idle: 5
  connection-timeout: 10000
  connection-test-query: SELECT 1
server:
  compression: { enabled: true, min-response-size: 2048 }
  shutdown: graceful
```

优雅停机是为了上链交易：`kill` 时先停止接收新请求、等在途请求跑完，避免"交易发出去了但留痕没写"。

实测压缩效果（`Accept-Encoding: gzip`）：

| 接口 | 原始 | gzip |
|---|---|---|
| `/api/admin/accounts` | 1031 B | 479 B |
| `/api/public/health` | 841 B | 635 B |

### 2.4 日志级别

`com.chainsip: ${CHAINSIP_LOG_LEVEL:INFO}`。默认 INFO，需要排查链调用时：

```bash
CHAINSIP_LOG_LEVEL=DEBUG java -jar target/chainsip-backend.jar
```

### 2.5 前端图标

`main.js` 从 `import * as ElementPlusIconsVue` 改成逐个引入实际用到的 15 个
（12 个来自路由 `meta.icon` + 模板里的 `ArrowDown`/`Lock`/`User` + 兜底 `Menu`）。

注意 `Switch`：路由里写的是 `'Switch'`，模板用 `<component :is="item.icon">` 按名字查找，
所以必须 `Switch as SwitchIcon` 后**仍以 `Switch` 为名**注册。

效果：

```
优化前  element 1,100 KB (gzip 334 KB)
优化后  element   947 KB (gzip 304 KB)
```

### 2.6 顺手修掉的一个真实 bug：机构账号的"链上角色"恒为 NONE

这是用无头浏览器截图验收时发现的——顶栏显示"链上角色：NONE"，而同页的
`/api/chain/status` 明明返回 `adminOnChainRole = ADMIN`，自相矛盾。

根因在 `AuthService.toView`：

```java
// 改之前
String address = account.getInstitutionCode() == null ? primaryVerifiedAddress(account.getId()) : null;
view.setOnChainRole(address == null ? null : onChainRole(address));
```

只要账号有机构编码（ADMIN / BUREAU / SPONSOR / REGULATOR 全都是），`address` 就被直接置成
`null`，`onChainRole` 恒为 null。而**同一个方法里**的 `chainAddress` 用的是
`chainAddressOf(institutionCode)`，于是出现"地址是机构密钥地址、角色却是空"的错位。

改法：让两者同源，都用 `chainAddressOf`：

```java
String address = account.getInstitutionCode() == null
        ? primaryVerifiedAddress(account.getId())
        : chainAddressOf(account.getInstitutionCode(), addressByCode);
view.setOnChainRole(address == null || address.trim().isEmpty() ? null : onChainRole(address));
```

影响面：`/api/auth/login`、`/api/auth/me`、账号列表三处都走这个方法，改完三处一起正确。
验收方式：登录响应里 `account.onChainRole` 从 `null` 变为 `ADMIN`，顶栏与「当前账号身份」卡片同步显示 ADMIN。

> 这个 bug 不影响链上判权（真正的权限一直按签名地址在合约里判），
> 但它会让演示时"链上链下是否一致"这个核心卖点看起来是矛盾的，属于必须修的展示层错误。

## 3. 试过但没采纳：Element Plus 完整按需引入

思路是把 `app.use(ElementPlus)` 换成 `unplugin-vue-components` + `unplugin-auto-import`，
让组件从 `element-plus/es/components/xxx` 子路径进入，同时裁掉整份 CSS。实测结论：**放弃**。

原因有两个，都记下来避免以后重复踩：

1. **收益远小于预期**。Element Plus 2.x 的 `es/index.mjs` 里 `defaults.mjs` 会用
   `makeInstaller([...全部组件])` 做顶层副作用，只要任何一处从根入口导入，tree-shaking 就整体失效：
   `importStyle: false` 时产物 946KB，`importStyle: 'css'` 也只降到 821KB，
   加上被迫合并进 vendor 的依赖，总体只省约 7%。真正起作用的是"别从根入口导入 ElMessage"，
   而项目里 16 个文件都在这么做。
2. **开发态不稳定**。改成子路径后，Vite 会在运行中不断发现新的
   `element-plus/es/components/*/style/css` 依赖并整体替换预构建目录；
   在受限环境（批量删除受限）里这一步会让 dev server 直接退出，`optimizeDeps.noDiscovery` 也拦不住。

结论：**图标按需注册保留（零风险、纯收益），组件级按需引入不做**。
如果以后确实要压这部分体积，正确做法是换成按需引入的 UI 库或自研组件层，而不是和打包器的副作用搏斗。

## 4. 验证方法

```bash
# 1) 健康检查（免登录）
curl -s http://127.0.0.1:8080/api/public/health | python -m json.tool

# 2) 索引是否齐全（应输出 16 行）
mysql -u<DB_ADMIN_USER> -p chainsip -e "
  select table_name, index_name, group_concat(column_name order by seq_in_index)
  from information_schema.statistics
  where table_schema='chainsip' and index_name like 'idx_%'
  group by table_name, index_name;"

# 3) 压缩是否生效（对比 size_download）
curl -s -o /dev/null -w '%{size_download}\n' http://127.0.0.1:8080/api/admin/accounts -H "Authorization: Bearer $TOKEN"
curl -s -o /dev/null -H 'Accept-Encoding: gzip' -w '%{size_download}\n' http://127.0.0.1:8080/api/admin/accounts -H "Authorization: Bearer $TOKEN"

# 4) 前端体积
cd frontend && npm run build     # 看 element-*.js 与 gzip 列
```

## 5. 后续可以继续做的（按性价比排序）

1. **链读批量合并**：Dashboard 一次页面要打 3 个接口（status / block / stats），
   其中 status 与 stats 各含一次链读；可以在后端合并成一个 `/api/overview`，把 3 次 RTT 压成 1 次。
2. **列表查询改游标分页**：现在用 `offset/limit`，深翻页时 MySQL 仍要跳过前 N 行；
   事件表天然有序（自增 id），改 `id < lastId` 的游标分页对大数据量更友好。
3. **事件索引异步化**：`indexEvents` 目前在请求线程里同步写库，可以改为写入队列后批量落库，
   把上链接口的响应时间从"发交易 + 两次写库"降到"发交易 + 一次入队"。
4. **前端首屏骨架屏**：目前 Dashboard 是"空白 → 一次性渲染"，加骨架屏体感会更好。
5. **`DeployContractsTest` 与运行中的链隔离**：它会在当前链上真的部署一套新合约，
   本地误跑会污染演示环境，建议加 `@Disabled` 或独立的 profile 开关。
