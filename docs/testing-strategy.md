# ChainSIP 后端测试策略与 CI/CD 发布流水线

> 适用范围：`backend/`（Spring Boot 2.7 + MyBatis-Plus + FISCO BCOS 2.x Java SDK），不含合约层与前端。
> 平台无关：流水线以「阶段 + 命令」描述为主，第 9 节给出 GitHub Actions 参考实现并标注移植点（Gitee/GitLab/Jenkins 可直接映射）。

## 0. 结论速览

| 层级 | 测什么 | 依赖 | 工具 | 目标耗时 | 门禁 |
|---|---|---|---|---|---|
| L1 单元测试 | 纯逻辑：参数校验、ABI 映射、JWT、密码哈希、机构密钥加解密、事件解析 | 无 Spring / 无 DB / 无链 | JUnit5 + Mockito + AssertJ | < 60s | 必过 |
| L2 控制器切片 | 参数校验、角色门禁、HTTP 契约、错误码 | `@WebMvcTest` + MockBean | MockMvc | < 90s | 必过 |
| L3 集成测试 | 业务流程：开户层级、建档、缴费、转移状态流转、监管查询 | 真 MySQL（Testcontainers）+ **链替身** | `@SpringBootTest` + `FakeChainGateway` | < 5min | 必过 |
| L4 链集成测试 | 真实合约语义：部署、托管锁定/释放、对账恒等式 | 真 FISCO 链 | `@SpringBootTest` + tag `chain` | < 10min | 有链环境时必过，无链跳过 |
| L5 端到端 | 全链路 HTTP 冒烟（已有 `scripts/test_api.py`） | 完整环境 | Python 标准库脚本 | < 3min | 预发/发布前置 |

质量门禁：**覆盖率ratchet**（只增不降）——当前基线 line 40% / branch 25%，每次提 PR 只允许提高；service 层目标 line ≥ 70%。

---

## 1. 现状盘点与缺口

已有：

- `spring-boot-starter-test`（JUnit5 + Mockito + AssertJ + Spring Test）
- `maven-surefire-plugin`（`forkCount=0`，在本 JVM 内跑）
- 3 个测试类：`CryptoAndWalletTest`（L1 纯单测）、`ChainReadCheckTest`（L4，用 `Assumptions.assumeTrue` 自门禁）、`DeployContractsTest`（L4，`@EnabledIfSystemProperty(named="chainsip.deploy")` 自门禁）
  → 两个链测**已自带门禁**，无链环境下 `mvn test` 不会变红；新增的 L4 用例统一改用 `@Tag("chain")` + `-Pchain`，与既有约定并存
- `scripts/test_api.py`：约 40 步的全链路 E2E 脚本（L5，未接入 CI）

缺口：

| 缺口 | 影响 | 处理 |
|---|---|---|
| 无集成测试骨架与 DB 替身方案 | 业务流程只能靠手工点 | 引入 Testcontainers MySQL，`schema.sql` 幂等可自动建表 |
| 链依赖漏进普通测试 | 没链就跑不动 CI | 抽 `ChainGateway` 替身，L1–L3 完全不碰链；新 L4 统一 `@Tag("chain")` + `-Pchain` 隔离 |
| 无覆盖率度量 | 无法度量测试债 | JaCoCo report + ratchet 门禁 |
| 无 failsafe | IT 无法与 UT 分阶段 | 引入 `maven-failsafe-plugin` |
| 前端零测试依赖 | 不在本次范围 | 后续单独立项（Vitest） |

---

## 2. 分层策略

### L1 单元测试（不启动 Spring）

适用对象（纯函数、无外部 I/O）：

- `ChainParams` — 地址格式校验、非法输入报错
- `ChainViewMapper` — ABI 返回元组 → Map 的字段映射与缺字段容错
- `ChainEventParser` — 事件日志解析（正常/畸形输入）
- `JwtService` — 签发/解析/过期/篡改
- `PasswordHasher` — 同密码两次哈希不同、校验通过、错误密码失败
- `InstitutionKeyService` — AES-GCM 加解密往返、篡改密文失败
- `BureauCityService` / `AccountHierarchyService` 的纯判定逻辑（谁能创建谁）

写法：直接 `new`，不加载 Spring 上下文，单类 < 100ms。

### L2 控制器切片测试

`@WebMvcTest(XxxController.class)` + `@MockBean` 掉 Service 层，验证：

- 参数校验（`@Valid`）失败返回 400 且带字段名
- `@RequireRole` 门禁：越权返回 403
- 正常路径状态码与 JSON 结构（`jsonPath` 断言，锁住对外契约）
- 鉴权头缺失返回 401

不加载 DB、不加载链，靠 `JwtAuthInterceptor` 的 Mock 或 `@WithMockUser` 等价手段注入 `AuthContext`。

### L3 集成测试（真 DB + 链替身）

`@SpringBootTest(webEnvironment = RANDOM_PORT)` + Testcontainers MySQL 8.0：

- 数据源：容器 MySQL，`schema.sql`（`create table if not exists`）自动建表
- 链：`@TestConfiguration` 提供 `FakeChainGateway`（实现 `ChainGateway`，内存 Map 模拟 `call` 返回、`send` 回执），并用 `@MockBean`/`@Primary` 覆盖 `FiscoChainGateway`
- HTTP 层用 `TestRestTemplate` 打真实端口，覆盖完整拦截器 + 事务 + SQL

这是**投入产出比最高的一层**：业务回归基本靠它。

### L4 链集成测试（真链）

tag = `chain`，默认不跑（`mvn verify -Pchain` 或 `-Dgroups=chain`）。

- 复用现有 `DeployContractsTest` 的思路：部署 → 接线 → 业务调用
- **核心用例**：转移全流程 + 托管对账恒等式
  `totalOverallRights == totalCityPool + totalEscrowedOverall`，在「放行后、接收前」这个窗口内断言托管金额 > 0 且等式成立
- 断言 `getEscrow` 在接收后清零、`roleOf` 返回预期角色

### L5 端到端

`scripts/test_api.py`（纯标准库）已覆盖：认证 → 开机构 → 建档 → 缴费 → 减员 → 跨市转移 → 监管查询。
接入方式：CI 起后端 → 等健康检查 → 跑脚本 → 脚本非零退出即失败。

---

## 3. 目录与命名规范

```
backend/src/test/java/com/chainsip/
├── unit/            # L1，类名 *Test，无 Spring
│   ├── JwtServiceTest.java
│   ├── PasswordHasherTest.java
│   ├── ChainParamsTest.java
│   ├── ChainViewMapperTest.java
│   ├── ChainEventParserTest.java
│   └── InstitutionKeyServiceTest.java
├── slice/           # L2，类名 *ControllerTest，@WebMvcTest
│   ├── AuthControllerTest.java
│   ├── AdminControllerTest.java
│   └── RegulatorControllerTest.java
├── integration/     # L3，类名 *IT（failsafe）
│   ├── AuthHierarchyIT.java
│   ├── EmployeeLifecycleIT.java
│   ├── TransferFlowIT.java
│   └── RegulatorAuditIT.java
├── chain/           # L4，类名 *ChainIT，@Tag("chain")；现有两个链测可择机迁入
│   └── EscrowReconciliationChainIT.java
└── support/         # 测试基础设施
    ├── FakeChainGateway.java
    ├── MySqlContainerConfig.java   # Testcontainers 单例
    └── TestDataFactory.java        # 唯一用户名/地址生成
```

命名即分层：`*Test` = surefire（快），`*IT` = failsafe（慢），`*ChainIT` = 需真链。

---

## 4. 链替身设计（关键）

`ChainGateway` 是现成的接口缝隙（`isReady/statusMessage/refresh/call/send/latestBlock/nodeVersion`），`FiscoChainGateway` 是唯一实现。集成测试用 `@Primary` 的 `FakeChainGateway` 顶替：

```java
// backend/src/test/java/com/chainsip/support/FakeChainGateway.java
@TestConfiguration
public class FakeChainGateway implements ChainGateway {
    private final Map<String, CallResult> canned = new ConcurrentHashMap<>();
    public FakeChainGateway stub(String contract, String fn, CallResult result) { /* ... */ }
    @Override public boolean isReady() { return true; }
    @Override public String statusMessage() { return "fake-chain"; }
    @Override public boolean refresh() { return true; }
    @Override public CallResult call(String c, String fn, List<Object> p) { /* 返回 canned 或默认空结果 */ }
    @Override public SendResult send(ChainAccount a, String c, String fn, List<Object> p) { /* 返回 success 回执 */ }
    @Override public BlockInfo latestBlock() { return new BlockInfo(/* 递增块高 */); }
    @Override public String nodeVersion() { return "2.11.0-fake"; }
}
```

要点：**替身只替代传输层，不替代业务语义**。业务语义（谁有权限、金额怎么算）仍由真实 Service + 合约真身共同保证，因此 L4/L5 不可省。

---

## 5. 用例清单（按模块）

| 模块 | 用例 | 级别 | 断言重点 |
|---|---|---|---|
| 认证 | 登录成功/密码错误/禁用账号 | L2+L3 | token 结构、401/403、锁定策略 |
| 认证 | JWT 过期与篡改 | L1 | 解析失败而非异常穿透 |
| 层级 | ADMIN 可建 BUREAU/SPONSOR/REGULATOR/EMPLOYEE | L3 | 越级创建被拒 |
| 层级 | BUREAU 只能建 SPONSOR，SPONSOR 只能建 EMPLOYEE | L3 | 矩阵断言 |
| 机构 | 链账户自动生成与私钥落库（加密） | L3 | 库中无明文、加解密往返 |
| 机构 | name/city 缺失时进「待上链注册」列表 | L3 | pending 列表可查、补录后出列 |
| 公司 | 注资、余额、缴费（8% + 19%） | L3 | 个人/统筹分账正确、月份累加 |
| 职工 | 建档、调薪、减员（状态 SUSPENDED） | L3 | 状态机不允许跳步 |
| 转移 | 申请 → 放行（托管锁定）→ 接收（释放） | L3 + **L4** | 放行后 escrow>0；接收后清零；城市变更 |
| 转移 | 拒绝分支（approved=false） | L3 | 状态 REJECTED、**资金不动** |
| 转移 | `EscrowMismatch` 防御 | L4 | 篡改金额时整体回滚 |
| 监管 | dashboard / system-stats / city-pool 只读 | L2+L3 | 无写接口、越权 403 |
| 监管 | 对账恒等式 | **L4** | `totalOverallRights == totalCityPool + totalEscrowed*` |
| 事件 | 链上事件入 MySQL、分页查询 | L3 | 仅 ADMIN/REGULATOR 可见 |
| 链状态 | 未配置合约地址时返回 503 且提示明确 | L3 | 不抛 500 |
| 配置 | ADMIN 私钥注入（链上链下对齐） | L1 | 启动日志可见、地址推导一致 |

---

## 6. 测试数据与幂等

- **唯一性**：用户名/机构名统一由 `TestDataFactory` 生成，后缀 `System.currentTimeMillis()`，避免重复跑撞名（沿用 `test_api.py` 的做法）。
- **隔离**：每个 IT 用 `@Sql` 或 `TestDataFactory` 自建数据；**禁止**用例间共享可变状态。
- **容器复用**：Testcontainers 用静态单例（`@TestConfiguration` + `start()` 一次），整轮 IT 只拉起一次 MySQL，节省 ~30s/轮。
- **链上数据**：L4 用独立群组或独立合约实例（每次部署新地址），避免脏数据影响对账断言。

---

## 7. 覆盖率与质量门禁

- JaCoCo：`report` 绑定 `test` 阶段，`check` 绑定 `verify` 阶段。
- **Ratchet（棘轮）策略**：不设一步到位的高阈值，而是「基线只增不降」。
  - 起始基线：LINE ≥ 0.40，BRANCH ≥ 0.25（按当前代码量估算，落地后以实际报告为准）
  - 每个 PR 提高覆盖率或持平；下降即失败
  - `service`、`security`、`crypto` 包单独设更高目标（line ≥ 0.70）
- 排除项：`dto`、`entity`、生成代码、`*Application`、config 类。

---

## 8. Maven 改造（已落地到 `backend/pom.xml`）

要点：新增 `testcontainers.version` 属性、Testcontainers MySQL 依赖（test scope）、`maven-failsafe-plugin`（跑 `*IT`）、JaCoCo（report + ratchet check）、surefire 排除 `*IT` 与 `chain` tag。

```xml
<!-- surefire：只跑 *Test，排除 IT 与链用例（链用例由 profile chain 打开） -->
<plugin>
    <artifactId>maven-surefire-plugin</artifactId>
    <configuration>
        <forkCount>0</forkCount>
        <excludes>
            <exclude>**/*IT.java</exclude>
        </excludes>
        <excludedGroups>chain</excludedGroups>
    </configuration>
</plugin>
<!-- failsafe：跑 *IT（L3）；L4 由 -Pchain 打开 -->
<plugin>
    <artifactId>maven-failsafe-plugin</artifactId>
    <version>3.2.5</version>
    <executions>
        <execution>
            <goals><goal>integration-test</goal><goal>verify</goal></goals>
        </execution>
    </executions>
    <configuration>
        <forkCount>0</forkCount>
        <includes><include>**/*IT.java</include></includes>
        <excludedGroups>${test.excludedGroups}</excludedGroups>
    </configuration>
</plugin>
```

`chain` profile：`-Pchain` 时把 `test.excludedGroups` 置空、并允许通过 `CHAINSIP_SDK_CONFIG` 指向真实链证书目录。

---

## 9. CI/CD 流水线

### 9.1 阶段划分（平台无关）

| # | 阶段 | 命令 | 产物/门禁 |
|---|---|---|---|
| 1 | 静态护栏 | `mvn -B enforcer:enforce` | JDK 8/11/17/21 区间校验 |
| 2 | 编译 | `mvn -B -DskipTests compile` | 编译失败即止 |
| 3 | 单元 + 切片 | `mvn -B test` | surefire 报告 |
| 4 | 集成 | `mvn -B verify` | failsafe + JaCoCo check（ratchet） |
| 5 | 链集成（可选） | `mvn -B verify -Pchain` | 有链环境才执行 |
| 6 | 打包 | `mvn -B -DskipTests package` | `target/chainsip-backend.jar` |
| 7 | 冒烟 E2E | 起 jar → 等 `/actuator/health` → `python scripts/test_api.py` | 脚本退出码 |
| 8 | 制品归档 | 上传 jar + 报告（保留 30 天） | 版本号 = `1.0.0-<git短sha>` |
| 9 | 部署 | 按环境注入配置后启动 | dev 自动 / prod 手动审批 |
| 10 | 发布后验证 | 重跑 E2E + 关键接口探活 | 失败自动回滚 |

### 9.2 GitHub Actions 参考实现（已落地 `.github/workflows/backend-ci.yml`）

关键设计：

- **矩阵**：`jdk: [8, 17]`（8 是生产基线，17 验证未来可迁移性）
- **缓存**：`~/.m2`（key = pom 哈希），命中后构建从 ~3min 降到 ~40s
- **MySQL**：用 `services:` 起 `mysql:8.0`（比 Testcontainers 更稳，CI 环境通常无 Docker-in-Docker）；本地开发用 Testcontainers
- **链阶段**：独立 job + `if: ${{ vars.CHAIN_CI_ENABLED == 'true' }}`，没链就跳过
- **移植点**：
  - Gitee：`workflow` 语法基本一致，把 `actions/cache` 换成 `cache` 镜像动作或直接用 `mvn -o` + 自托管 runner 上的本地仓库
  - GitLab CI：每个 job 一个 stage，`services: [mysql:8.0]`，缓存用 `cache: key: files: [backend/pom.xml]`
  - Jenkins：stage 直接映射，`services` 换成 docker sidecar 或外部 MySQL 实例

### 9.3 环境与配置注入

| 环境 | 触发 | 配置来源 | 审批 |
|---|---|---|---|
| dev | push 到 `main` 自动 | 仓库内 `application.yml` 默认值 | 无 |
| test | 打 tag `v*` 自动 | CI Secrets：`CHAINSIP_DB_*`、`CHAINSIP_ADMIN_PRIVATE_KEY`、`CHAINSIP_CORE_ADDRESS`、`CHAINSIP_TRANSFER_ADDRESS` | 无 |
| prod | 手动 `workflow_dispatch` | 同上 + 人工确认 | **必需** |

**绝不明文入库的项**（一律走 Secrets）：ADMIN 私钥、DB 密码、JWT 密钥、master-key。当前 `application.yml` 里写死的 ADMIN 私钥仅为本地开发便利，prod 必须用环境变量覆盖且定期轮换。

### 9.4 部署与回滚

- 启动：`java -jar chainsip-backend.jar --spring.profiles.active=prod`（配置外置到 `/etc/chainsip/application-prod.yml`）
- 数据库变更：目前靠 `schema.sql`（`create table if not exists` 幂等）+ 启动期 migration 类。**建议引入 Flyway**做版本化迁移（见风险项）。
- 回滚：保留上一版 jar（`/opt/chainsip/releases/<version>`），软链 `current` 切换，30s 内完成；DB 变更必须向后兼容（只加列、不改列语义）。
- 发布后监控：启动日志关键字（`AdminKeyBootstrap` 注入成功）、`/api/chain/status` 连通性、E2E 冒烟结果、MySQL 连接池使用率、HTTP 5xx 比例。

---

## 10. 落地排期

| 批次 | 内容 | 产出 |
|---|---|---|
| 第一批（地基） | pom 改造（failsafe/JaCoCo/Testcontainers，已落地）、`support/`（FakeChainGateway + MySQL 容器 + TestDataFactory）、固化覆盖率基线实际值 | CI 可在无链环境跑绿 |
| 第二批（L1+L2） | 7 个单测 + 3 个控制器切片 | 快反馈门禁 |
| 第三批（L3） | 4 个业务 IT（层级/职工/转移/监管） | 业务回归网 |
| 第四批（L4+L5） | Escrow 对账链测 + `test_api.py` 接入 CI | 全链路保障 |

---

## 11. 风险与已知技术债

| 风险 | 说明 | 缓解 |
|---|---|---|
| **链不可用导致 CI 红** | L4 依赖真链，VM 关机/证书过期都会红 | tag + profile 隔离，默认跳过；链 job 失败不阻塞打包 |
| **Testcontainers 需要 Docker** | 本地/CI 无 Docker 时 L3 跑不了 | CI 用 `services:mysql`，本地可用外部 MySQL 环境变量覆盖 |
| **覆盖率基线可能低于阈值** | 首批落地时 JaCoCo check 会失败 | 先用 `report` 只生成报告，跑一轮取实际值后再固化阈值（当前阈值是按代码量估算，**尚未实测**） |
| **schema 无版本化** | `schema.sql` 幂等但无迁移历史，回滚困难 | 引入 Flyway，把 `schema.sql` 拆成 V1__init.sql |
| **私钥写进配置文件** | `application.yml` 含真实 ADMIN 私钥 | 仅 dev；prod 必须环境变量注入 + 轮换（当前仓库若公开需立即轮换） |
| **测试数据污染链上账本** | L4 每次部署新合约会产生历史数据 | 用独立群组或定期重置链，且对账断言基于「本轮部署的合约」 |
| 覆盖率不等于质量 | 高覆盖也可能缺边界断言 | 以「用例清单」验收，覆盖率只作辅助信号 |

---

## 12. 本地可复制命令

```bash
cd backend

# L1 + L2（快，无外部依赖）
mvn -q test

# L3 集成（需 Docker；或设置外部 MySQL 环境变量跳过容器）
mvn -q verify

# L4 链集成（需要链在 <CHAIN_HOST>:20200 可达 + 证书就位）
mvn -q verify -Pchain -DCHAINSIP_SDK_CONFIG=./sdk/config.toml

# 覆盖率报告
open target/site/jacoco/index.html

# L5 端到端（后端已启动）
python ../scripts/test_api.py http://127.0.0.1:8080
```

> 本文档配套：`backend/pom.xml`（已改，XML 已校验）、`.github/workflows/backend-ci.yml`（已建）。
> **未验证声明**：
> 1. 覆盖率阈值（0.40/0.25）与耗时目标为设计估算，**未实测**；本机无 Maven（构建在 IDE/其他环境），
>    首次在有 Maven 的环境跑 `mvn verify` 后应按实际报告固化基线，必要时用 `-Pcoverage-report-only` 先只出报告。
> 2. `test_api.py` 尚未接入 CI（workflow 里已留阶段 7，需在真实环境验证一次退出码语义）。
> 3. L1–L4 的具体测试代码尚未编写（第二批起落地）。
