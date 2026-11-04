# 政策参数链上版本化（policyPacked / publishPolicy）

> 目标：消除「链下改政策、链上仍按旧常量扣款」的双口径漂移。
> 状态：已实现、已编译、已部署到本地 FISCO BCOS（group 1），并做过端到端验证。

## 1. 改造前后

| | 改造前 | 改造后 |
|---|---|---|
| 费率 / 基数 / 退休年龄 / 计发月数 / 周期 | 合约 `constant`，改政策无效 | 合约 `policyPacked`（uint256 打包），`publishPolicy` 版本化发布，链上业务实时读取 |
| 链下参数生效 | 只影响试算与核定单 | 生效版本自动发布上链，链上扣款/发放同步变化 |
| 漂移发现 | `policyConsistency` 对比「链下 vs 合约常量」，只能告警 | 对比「链下 vs 链上已发布版本」，可自动补发（每 10 分钟） |
| 试算 | 合约 `quotePayment` | 后端 `GET /api/sponsor/payments/quote`（合约字节码逼近上限，该方法已移除） |

## 2. 参数打包位布局（必须与 `contracts/ChainSIP.sol` 一致）

| 位 | 字段 | 默认值（bps / 整数） |
|---|---|---|
| 0..15 | 个人费率 bps | 800（8%） |
| 16..31 | 统筹费率 bps | 1900（19%） |
| 32..47 | 基础养老金年率 bps | 100（1%） |
| 48..63 | 个人账户计发月数 | 139 |
| 64..79 | 法定退休年龄 | 60 |
| 80..95 | 最低缴费月数 | 180 |
| 96..127 | 缴费/发放周期天数 | 30 |
| 128..175 | 缴费基数下限 | 3200 |
| 176..223 | 缴费基数上限 | 24000 |

`policyPacked == 0` 时合约回落到 `DEFAULT_POLICY`（与旧常量完全一致），保证未发布也不会算错。

## 3. 合约改动（`contracts/ChainSIP.sol`）

- 新增：`policyPacked`、`policyVersion`、`event PolicyPublished(version, packed, payloadHash, operator)`、`publishPolicy(packed, payloadHash)`（onlyAdmin）。
- 改为动态读取的 12 处：`_paymentBase`（上下限）、`_personalAmount` / `_overallAmount`（费率）、`makePayment`（周期、逾期判定）、`retireEmployee`（退休年龄、最低月数、计发月数）、`claimPension`（周期）、`_basePension`（基础年率）。
- 移除：`quotePayment`、`paymentBaseOf`（未被后端使用；试算改走链下政策参数）。
- 字节码：**24313B / 24576B**（solc 0.6.10，`runs=1`，`metadata.bytecodeHash=none`），余量 263B。
- 产物：`backend/src/main/resources/abi/ChainSIP.{abi,bin}` 已同步。

## 4. 后端

| 组件 | 作用 |
|---|---|
| `policy/service/PolicyChainService` | 参数打包/解包、`chainStatus()` 对账、`publish()` 发布、`@Scheduled` 每 10 分钟补发、`quote()` 试算 |
| `controller/PolicyController` | `POST /parameters` 保存后自动发布；新增 `GET /chain/status`（ADMIN）、`POST /chain/publish`（ADMIN） |
| `policy/PolicyConsistencyCheck` | 改为委托 `PolicyChainService.chainStatus()`，返回结构不变（`consistent/items/note`） |
| `controller/SponsorController` | 新增 `GET /api/sponsor/payments/quote?salary=`，替代合约 `quotePayment` |
| `test/PolicyChainPackingTest` | 校验打包位布局与合约 `DEFAULT_POLICY` 一致、解包往返、费率变化 |

`payloadHash` = SHA-256（参数规范化串）转 256 位整数，随 `PolicyPublished` 事件上链，可用于链下政策版本与链上事件对账。

## 5. 前端

- `admin/Policy.vue`：顶部新增「链上政策版本」面板（已同步/未同步/链网关未就绪 + 版本号），支持手动「发布到链上」；保存参数后自动刷新；BUREAU 无权限时整块隐藏。
- `sponsor/Payments.vue`：试算改调 `GET /api/sponsor/payments/quote`。
- `api/index.js`：新增 `adminApi.policyChainStatus / publishPolicyToChain`、`sponsorApi.quote`。

## 6. 本次部署记录（2026-11-03）

| 项 | 值 |
|---|---|
| 新 `ChainSIP` | `<POLICY_CONTRACT_ADDRESS_A>` |
| 新 `ChainSIPTransfer` | `<POLICY_CONTRACT_ADDRESS_B>` |
| `setTransferContract` | 已调用（block 46） |
| 政策发布 | v1 默认参数 → v2 个人 9% → v3 恢复 8%（block 47 起） |
| 旧合约 | `<CORE_CONTRACT_ADDRESS>`、`<TRANSFER_CONTRACT_ADDRESS>` 已不再被后端使用，链上旧数据保留但不迁移 |

应用配置：`backend/src/main/resources/application.yml` 的 `chainsip.chain.contracts.core/transfer` 已更新。

## 7. 端到端验证

用独立缴费单位 `qa_policy_sponsor` + 两名职工验证：

1. 8% 时缴费 → 个人账户 800、统筹 1900；
2. 把 `contrib.employee.rate` 改成 0.09 → 链上版本 v2，一致性 true；
3. 第二名职工缴费 → 个人账户 **900**、统筹 1900；
4. 恢复 0.08 → 链上版本 v3，一致性 true。

说明政策改动已真实作用于链上扣款。

## 8. 部署/回归清单

1. `mvn -o test -Dtest=PolicyChainPackingTest`（打包位布局回归）。
2. 部署：`mvn -o test -Dtest=DeployContractsTest -Dchainsip.deploy=true -Dchainsip.deploy.privateKey=<管理员私钥>`，把输出的两个地址写入 `application.yml`。
3. 启动后端后 `POST /api/admin/transfer-contract`（body `{address}`）完成接线。
4. `POST /api/admin/policy/chain/publish` 发布当前参数（或等 10 分钟自动补发）。
5. `GET /api/admin/policy/chain/status` 确认 `consistent=true`。
6. 链上数据重置后，到「机构管理 → 待上链注册」重新注册机构/单位。
