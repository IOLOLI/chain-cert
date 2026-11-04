// SPDX-License-Identifier: MIT
// ⚠️ 编译前必须把 WeBASE 合约 IDE 的编译器版本切到 0.6.10！
//    默认可能是 0.4.x/0.5.x，会报 ParserError：
//      - 不认识 `abstract contract`（0.6.0 才引入）
//      - 不认识接口参数里的 `string calldata`
pragma solidity >=0.6.10 <0.7.0;
pragma experimental ABIEncoderV2;

/**
 * ChainSIP —— 核心合约（账户 + 资金 + 监管）
 *
 * 本文件由 contracts/ 下的多文件源展开而来：无任何 import，自包含（粘贴到控制台/WeBASE 直接用）。
 * 用途：WeBASE-Front 合约 IDE 直接粘贴编译（无任何 import）。
 *
 * 包含：SafeMath 库、Roles / Policy / PensionAccount / PensionRegulator / PensionBank 抽象基类，全部就地展开。
 * 部署：无构造参数，直接 deploy。部署者自动成为合约的第一个 ADMIN。
 * 链上 ADMIN 就是"部署时用的那个账户地址"；若它不是后端配置的 ADMIN 机构地址，
 * 需要用部署者账户调 addAdmin(后端地址)，否则 setTransferContract 等 ADMIN 操作会被回滚（NotAnAdmin）。
 *
 * 编译设置：solc 0.6.10，**必须勾选 Enable optimization**
 *   （关优化器时 ChainSIP 运行时代码 39919 B 会超过 EIP-170 的 24576 B 上限而被链拒绝；开优化器 24353 B）
 * 注意：0.6.10 不支持 --via-ir（该选项是 0.8 才有的）。
 */

/**
 * @title SafeMath
 * @notice ChainSIP 2.x（FISCO BCOS 2.x / solc 0.6.10）专用的溢出安全检查库。
 *
 * @dev 为什么要这个库：
 *      Solidity 0.6 没有 0.8 的内建溢出检查，`a + b` / `a - b` / `a * b` 在越界时是
 *      静默回绕的。养老保险平台里所有金额（余额、基金池、累计值、缴费月数）一旦回绕，
 *      三条资金守恒恒等式就会被破坏，因此所有**资金相关的算术**都改走本库，
 *      失败时用 `require` 回滚并给出可读原因。
 *
 *      使用方式：`using SafeMath for uint256;` 之后写 `a.add(b)` / `a.sub(b)` / `a.mul(b)` / `a.div(b)`。
 *      非资金相关的算术（数组下标、分页偏移、循环计数、名单长度偏移）保持原生运算符，
 *      因为它们由数组长度天然约束，不会溢出，走库只会白烧 gas。
 */
library SafeMath {
    /// @dev 加法溢出（0.8 语义：Panic 0x11）
    function add(uint256 a, uint256 b) internal pure returns (uint256) {
        uint256 c = a + b;
        require(c >= a, "SafeMath: addition overflow");
        return c;
    }

    /// @dev 减法下溢（0.8 语义：Panic 0x11）
    function sub(uint256 a, uint256 b) internal pure returns (uint256) {
        require(b <= a, "SafeMath: subtraction overflow");
        uint256 c = a - b;
        return c;
    }

    /// @dev 乘法溢出（0.8 语义：Panic 0x11）
    function mul(uint256 a, uint256 b) internal pure returns (uint256) {
        if (a == 0) return 0;
        uint256 c = a * b;
        require(c / a == b, "SafeMath: multiplication overflow");
        return c;
    }

    /// @dev 除零（0.8 语义：Panic 0x12）
    function div(uint256 a, uint256 b) internal pure returns (uint256) {
        require(b > 0, "SafeMath: division by zero");
        uint256 c = a / b;
        return c;
    }
}

/**
 * @title Roles
 * @notice ChainSIP 平台角色与权限基座。本文件由 0.8.19 版移植到 0.6.10（FISCO BCOS 2.x）。
 *
 * @dev 设计说明（与原赛题源码的关键差异）
 *      1. 原版用单一 `address public sponsor = msg.sender` 当"管理员"，权限判断散落在
 *         各个业务函数里且多处缺失（changeBureausAuthorized / authorizeTransfer /
 *         receiveTransfer 完全没有校验调用者），任何人都能改社保局授权、清空他人账户基金。
 *         这里改为"角色枚举 + 修饰器"，所有权限判断集中在基座，业务合约只用修饰器声明意图。
 *      2. 角色共四种：ADMIN（平台管理）/ BUREAU（社保局）/ SPONSOR（缴费单位）/ REGULATOR（监管）。
 *         "职工 EMPLOYEE" 不是一个角色，而是"拥有参保档案"的状态（见 PensionAccount
 *         的 EmployeeStatus），因此同一个地址可以既是某公司的 SPONSOR、又是自己作为
 *         自然人的参保职工，不会互相挤占。
 *      3. 一个地址只能持有一个机构角色，避免"既当运动员又当裁判"（例如公司地址不能同时是社保局）。
 *      4. 管理员可增删（addAdmin / removeAdmin），并禁止移除最后一个管理员，避免平台失控。
 *         公司（SPONSOR）注册入口在 PensionAccount，社保局（BUREAU）注册入口在
 *         ChainSIPTransfer，监管（REGULATOR）注册入口在 PensionRegulator，都只能由 ADMIN 调用。
 *
 * @dev 0.6.10 移植说明（语义等价，ABI 不变）：
 *      - 自定义 `error` 全部换成 `require(条件, "错误名"...)`；错误名保留，
 *        原来带参数的错误把参数用 abi.encodePacked 拼进原因串，便于链上定位。
 *      - 构造函数补 `public` 可见性（0.6 强制要求）。
 *      - `adminCount -= 1` 改走 SafeMath.sub（管理员计数是平台治理的关键计数，
 *        绝不希望在极端的下溢情况下静默回绕成 2^256-1 从而永久锁死 removeAdmin）。
 */
abstract contract Roles {
    using SafeMath for uint256;

    /// @notice 机构角色。NONE 表示未注册。
    /// @dev REGULATOR（监管方）是纯只读身份：能查全平台账本与流水，但没有任何写权限——
    ///      它不匹配 onlyAdmin / onlyBureau / onlySponsor，也不参与任何业务状态机。
    enum Role {
        NONE,
        ADMIN,
        BUREAU,
        SPONSOR,
        REGULATOR
    }

    mapping(address => Role) private _roles;

    /// @notice 当前管理员数量，用于保护"最后一个管理员不可移除"
    uint256 public adminCount;

    event RoleGranted(address indexed account, Role indexed role, address indexed operator);
    event RoleRevoked(address indexed account, Role indexed role, address indexed operator);
    event AdminAdded(address indexed account, address indexed operator);
    event AdminRemoved(address indexed account, address indexed operator);

    /* ------------------------- 0.8 自定义错误（原样留档） -------------------------
    error ZeroAddress();
    error NotAuthorized(address caller, Role required);
    error AccountHasRole(address account, Role role);
    error RoleNotGrantable(Role role);
    error NotAnAdmin(address account);
    error LastAdminCannotBeRemoved();
    error AccountLacksRole(address account, Role role);
    error RoleNotRevocable(Role role);
    error NotAdminOrRegulator(address caller);
    ---------------------------------------------------------------------------- */

    modifier onlyAdmin() {
        _requireRole(msg.sender, Role.ADMIN);
        _;
    }

    modifier onlyBureau() {
        _requireRole(msg.sender, Role.BUREAU);
        _;
    }

    modifier onlySponsor() {
        _requireRole(msg.sender, Role.SPONSOR);
        _;
    }

    modifier onlyRegulator() {
        _requireRole(msg.sender, Role.REGULATOR);
        _;
    }

    /// @dev 审计类只读接口：ADMIN 与 REGULATOR 都可进入
    modifier onlyAdminOrRegulator() {
        Role role = _roles[msg.sender];
        // 0.8 原语义：revert NotAdminOrRegulator(msg.sender)
        require(role == Role.ADMIN || role == Role.REGULATOR, "NotAdminOrRegulator");
        _;
    }

    /// @dev 部署者自动成为首个 ADMIN。
    constructor() public {
        _roles[msg.sender] = Role.ADMIN;
        adminCount = 1;
        emit RoleGranted(msg.sender, Role.ADMIN, msg.sender);
        emit AdminAdded(msg.sender, msg.sender);
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    function roleOf(address account) public view returns (Role) {
        return _roles[account];
    }

    function hasRole(address account, Role role) public view returns (bool) {
        return _roles[account] == role;
    }

    function isAdmin(address account) external view returns (bool) {
        return _roles[account] == Role.ADMIN;
    }

    function isBureau(address account) external view returns (bool) {
        return _roles[account] == Role.BUREAU;
    }

    function isSponsor(address account) external view returns (bool) {
        return _roles[account] == Role.SPONSOR;
    }

    function isRegulator(address account) external view returns (bool) {
        return _roles[account] == Role.REGULATOR;
    }

    // ------------------------------------------------------------------
    // 管理员管理
    // ------------------------------------------------------------------

    /// @notice 新增管理员（仅 ADMIN）
    function addAdmin(address account) external onlyAdmin {
        require(account != address(0), "ZeroAddress");
        _grantRole(account, Role.ADMIN);
        emit AdminAdded(account, msg.sender);
    }

    /// @notice 移除管理员（仅 ADMIN，且不能移除最后一个）
    function removeAdmin(address account) external onlyAdmin {
        // 0.8 原语义：revert NotAnAdmin(account)
        require(_roles[account] == Role.ADMIN, "NotAnAdmin");
        require(adminCount > 1, "LastAdminCannotBeRemoved");
        _roles[account] = Role.NONE;
        adminCount = adminCount.sub(1);
        emit AdminRemoved(account, msg.sender);
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    function _requireRole(address account, Role role) private view {
        require(_roles[account] == role, "NotAuthorized");
    }

    /// @dev 授予角色。一个地址只能持有一个机构角色，重复授予或与其他角色冲突都会回滚。
    function _grantRole(address account, Role role) internal {
        require(account != address(0), "ZeroAddress");
        require(role != Role.NONE, "RoleNotGrantable");
        Role current = _roles[account];
        // 0.8 原语义：revert AccountHasRole(account, current)
        require(current == Role.NONE, "AccountHasRole");
        _roles[account] = role;
        emit RoleGranted(account, role, msg.sender);
    }

    /// @dev 收回角色（仅用于非 ADMIN 角色；ADMIN 必须走 removeAdmin，以保护"最后一个管理员"）。
    function _revokeRole(address account, Role role) internal {
        // 0.8 原语义：revert RoleNotRevocable(role)
        require(role != Role.ADMIN, "RoleNotRevocable");
        // 0.8 原语义：revert AccountLacksRole(account, role)
        require(_roles[account] == role, "AccountLacksRole");
        _roles[account] = Role.NONE;
        emit RoleRevoked(account, role, msg.sender);
    }
}

/**
 * @title Policy
 * @notice 缴费政策：缴费基数上下限、个人/单位费率、缴费周期，以及统一的金额计算。
 *         本文件由 0.8.19 版移植到 0.6.10（FISCO BCOS 2.x）。
 *
 * @dev 口径说明（务必与需求方确认后再改）
 *      - 个人账户：缴费基数 × 8%
 *      - 统筹账户：缴费基数 × 19%
 *      - 两笔费用均由职工所属单位代缴，因此单次缴费共从单位统筹账户余额中扣减 27%。
 *
 *      与原赛题源码的差异：
 *      1. 原版把 19% 理解成"个人 8% + 单位 11% 的合计"，并用 SPONSOR_RATE = 19 - 8 = 11 参与计算，
 *         与赛题背景"个人缴费比例为 8%，称为个人账户；U 的缴费比例为 19%，称为统筹账户"不一致。
 *         这里按背景描述实现：19% 全部进统筹账户，8% 全部进个人账户。
 *      2. 原版把月缴费额写死成常量 PERSONAL_AMOUNT = 2000、OVERALL_AMOUNT = 4750
 *         （按 25000 元工资、且忽略 24000 元基数上限算出），与"缴费基数上下限 [3200, 24000]"
 *         的规则自相矛盾。这里统一改为：先按上下限夹取缴费基数，再按费率计算金额。
 *         例：月薪 25000 → 基数 24000 → 个人 1920、统筹 4560、合计 6480（单位需备足 6480）。
 *      3. 缴费周期 30 天：既作为"同一周期内不得重复缴费"的护栏（原版可被无限次调用，
 *         任何人都能反复刷高职工余额、抽干单位账户），也作为"逾期未缴"的判定界。
 *
 * @dev 0.6.10 移植说明（语义等价，ABI 不变）：
 *      - 自定义 `error` 换成 `require(条件, "错误名")`。
 *      - 金额计算 `base * RATE / 100` 走 SafeMath.mul / div（费率乘法是资金计算的源头，
 *        0.6 无溢出检查，必须显式保护）；`quotePayment` 的两笔金额相加走 SafeMath.add。
 */
abstract contract Policy {
    using SafeMath for uint256;

    /// @notice 缴费基数下限：3200 元
    uint256 public constant MIN_INSURANCE_BASE = 3200;
    /// @notice 缴费基数上限：24000 元
    uint256 public constant MAX_INSURANCE_BASE = 24000;
    /// @notice 个人账户费率：8%
    uint256 public constant PERSONAL_RATE = 8;
    /// @notice 统筹账户费率：19%
    uint256 public constant OVERALL_RATE = 19;
    /// @notice 缴费周期 / 缴费期限：30 天（出厂默认；实际以 policyPacked 为准）
    uint256 public constant PAYMENT_CYCLE = 30 days;

    /* ------------------------- 政策参数版本化 -------------------------
       打包位布局（uint256）：
         [0..15]    个人费率 bps（800 = 8%）
         [16..31]   统筹费率 bps（1900 = 19%）
         [32..47]   基础养老金年率 bps（100 = 1%）
         [48..63]   个人账户计发月数（139）
         [64..79]   法定退休年龄（60）
         [80..95]   最低缴费月数（180）
         [96..127]  缴费/发放周期天数（30）
         [128..175] 缴费基数下限（3200）
         [176..223] 缴费基数上限（24000）
       发布后链上业务立即按新参数执行；未发布（policyPacked == 0）时回落到出厂默认值。
       每次发布 version+1 并 emit PolicyPublished（含完整参数包与 payloadHash），
       历史版本由事件追溯，链下台账与链上版本号一一对账。 */
    uint256 public policyPacked;
    uint32 public policyVersion;

    uint256 internal constant DEFAULT_POLICY = 800
        | (1900 << 16) | (100 << 32) | (139 << 48) | (60 << 64)
        | (180 << 80) | (30 << 96) | (3200 << 128) | (24000 << 176);

    function _policy() internal view returns (uint256) {
        uint256 packed = policyPacked;
        return packed == 0 ? DEFAULT_POLICY : packed;
    }

    function _cycle() internal view returns (uint256) {
        return ((_policy() >> 96) & 0xFFFFFFFF) * 1 days;
    }

    /* ------------------------- 0.8 自定义错误（原样留档） -------------------------
    error InvalidSalary(uint256 salary);
    error ZeroAmount();
    error EmptyString();
    ---------------------------------------------------------------------------- */

    function _paymentBase(uint256 salary) internal view returns (uint256) {
        uint256 packed = _policy();
        uint256 floorBase = (packed >> 128) & 0xFFFFFFFFFFFF;
        uint256 ceilingBase = (packed >> 176) & 0xFFFFFFFFFFFF;
        if (salary < floorBase) return floorBase;
        if (salary > ceilingBase) return ceilingBase;
        return salary;
    }

    function _personalAmount(uint256 base) internal view returns (uint256) {
        return base.mul(_policy() & 0xFFFF).div(10000);
    }

    function _overallAmount(uint256 base) internal view returns (uint256) {
        return base.mul((_policy() >> 16) & 0xFFFF).div(10000);
    }
}

/**
 * @title PensionAccount
 * @notice 账户管理：公司（缴费单位）注册、职工参保建档、减员、注销、薪资调整与档案查询。
 *         本合约属于**核心合约**（ChainSIP），跨城市转移由独立的 ChainSIPTransfer 合约驱动，
 *         核心合约只通过 transferContract 白名单暴露几个专用钩子给它。
 *         本文件由 0.8.19 版移植到 0.6.10（FISCO BCOS 2.x）。
 *
 * @dev 关键设计
 *      1. 职工身份用一个显式状态机表达，取代原版的零散布尔量：
 *         NONE(未建档) -> ACTIVE(正常参保，单位可缴费)
 *                      -> SUSPENDED(已停缴/减员，转移放行的前置条件)
 *                      -> TRANSFERRED(已转移到新城市，等待新单位参保)
 *                      -> REMOVED(已注销，档案留痕，身份证号不可复用)
 *      2. "一人一缴"在 ACTIVE 状态下强制：职工已由某单位参保时，其他单位无法重复建档，
 *         必须先由现单位减员（suspendEmployee）—— 对应赛题的"减员承诺"。
 *         减员后新单位可直接接手续保（余额与缴费年限保留，缴费周期重新起算）。
 *      3. 缴费单位名单用"数组 + 位置索引"维护，增删都是 O(1)，避免原版遍历导致的
 *         gas 随职工数线性增长。
 *      4. activeTransfer 标记"该职工是否有在途转移"，由独立的转移合约（ChainSIPTransfer）
 *         通过白名单钩子写入；建档与注销会因此被拦住，避免转移途中账户被改动。
 *         减员（suspendEmployee）**特意不拦**：职工先提交转移申请、再由单位停缴是正常业务顺序，
 *         若在此拦截，"已停缴"这一放行前置条件将永远无法达成，形成死锁。
 *      5. 为什么拆合约：ChainSIP 聚合了全部业务后运行字节码会超过 EVM 的 24576 字节上限，
 *         部署工具（WebASE / Remix）直接拒绝。因此把"社保局注册 + 转移状态机"拆到
 *         ChainSIPTransfer，核心合约只保留账户与资金，两者通过接口互相调用。
 *
 * @dev 0.6.10 移植说明（语义等价，ABI 不变）：
 *      - 文件头加 `pragma experimental ABIEncoderV2;`：`getInsuranceAccount` 直接返回
 *        InsuranceAccountView 结构体，0.6 的 external 函数用结构体做返回值必须开这个开关。
 *      - 自定义 `error` 换成 `require(条件, "错误名")`；带参数的错误名与语义保留，
 *        参数位置用同名注释标注，便于与 0.8 版错误一一对照。
 *      - 资金相关的加法（totalBalance = personalBalance + overallBalance、
 *        名单位置索引 pos + 1）走 SafeMath，避免 0.6 无溢出检查导致余额回绕。
 */
abstract contract PensionAccount is Roles, Policy {
    using SafeMath for uint256;

    enum EmployeeStatus {
        NONE,
        ACTIVE,
        SUSPENDED,
        TRANSFERRED,
        REMOVED,
        RETIRED
    }

    struct Sponsor {
        bool exists;
        string name;
        string city;
        uint64 registeredAt;
    }

    struct Employee {
        EmployeeStatus status;
        string name;
        uint16 age;
        string idNumber;
        string employer;
        uint64 startDate;
        uint256 salary;
        uint256 paymentBase;
        address sponsor;
        string city;
        uint256 personalBalance;
        uint256 overallBalance;
        uint32 paidMonths;
        uint32 overdueCount;
        uint64 registeredAt;
        uint64 lastPaymentAt;
        // ---- 养老金（支出侧）：退休后才写入 ----
        uint64 retiredAt; // 办理退休的时间，0 表示未退休
        uint64 lastPensionAt; // 上次领取养老金的时间，0 表示还没领过
        uint256 pensionPersonalMonthly; // 每月从个人账户发放的金额（退休时按余额 / 计发月数锁定）
        uint32 pensionClaimedMonths; // 已领取月数
    }

    /// @notice 建档入参。参数较多，统一用结构体在内部传递，避免栈过深。
    /// @dev city 不在入参中：参保城市一律取缴费单位注册时登记的城市，
    ///      避免同一单位名下出现多个参保城市导致转移时转出地无从判定。
    struct EmployeeInput {
        address employee;
        address sponsor;
        string name;
        uint16 age;
        string idNumber;
        string employer;
        uint64 startDate;
        uint256 salary;
    }

    /// @notice 对外返回的参保账户视图（结构体一次返回，避免前端多次调用）
    struct InsuranceAccountView {
        address employee;
        string name;
        string idNumber;
        string employer;
        string city;
        address sponsor;
        EmployeeStatus status;
        uint256 salary;
        uint256 paymentBase;
        uint256 personalBalance;
        uint256 overallBalance;
        uint256 totalBalance;
        uint32 paidMonths;
        uint32 overdueCount;
        uint64 registeredAt;
        uint64 lastPaymentAt;
        uint256 activeTransferId; // 0 表示无在途转移，否则为 (转移申请 id + 1)
        // ---- 养老金（支出侧）：未退休时均为 0 ----
        uint64 retiredAt;
        uint64 lastPensionAt;
        uint256 pensionPersonalMonthly;
        uint32 pensionClaimedMonths;
        uint16 age; // 退休资格判定要用（年龄在建档时录入，不会随时间自动增长）
    }

    mapping(address => Sponsor) internal sponsors;
    address[] internal sponsorList;

    mapping(address => Employee) internal employees;
    mapping(bytes32 => address) internal idIndex;
    mapping(address => address[]) internal sponsorEmployees;
    mapping(address => mapping(address => uint256)) internal sponsorEmployeeIndex; // 1-based，0 表示不在名单
    mapping(address => uint256) internal activeTransfer; // 申请 id + 1，0 表示无

    /// @notice 转移合约地址（唯一有权写"在途转移"标记并划转基金的合约），由 ADMIN 设置
    address public transferContract;

    event PolicyPublished(uint32 indexed version, uint256 packed, uint256 payloadHash, address indexed operator);

    /// @notice 发布新政策参数（ADMIN）：版本号自增，链上业务立即生效
    function publishPolicy(uint256 packed, uint256 payloadHash) external onlyAdmin returns (uint32) {
        require(packed != 0, "ZeroAmount");
        policyVersion += 1;
        policyPacked = packed;
        emit PolicyPublished(policyVersion, packed, payloadHash, msg.sender);
        return policyVersion;
    }

    event SponsorRegistered(address indexed sponsor, string name, string city, address indexed operator);
    event EmployeeRegistered(address indexed employee, address indexed sponsor, string idNumber, uint256 paymentBase);
    event EmployeeRehired(address indexed employee, address indexed sponsor, string city);
    event EmployeeSuspended(address indexed employee, address indexed operator, string reason);
    event EmployeeRemoved(address indexed employee, address indexed operator);
    event EmployeeSalaryUpdated(address indexed employee, uint256 salary, uint256 paymentBase);
    event TransferContractUpdated(address indexed oldContract, address indexed newContract, address indexed operator);
    event TransferFlagged(address indexed employee, uint256 transferRef);
    event TransferFlagCleared(address indexed employee);

    /* ------------------------- 0.8 自定义错误（原样留档） -------------------------
    error UnknownSponsor(address sponsor);
    error SponsorAlreadyExists(address sponsor);
    error UnknownEmployee(address employee);
    error AlreadyInsured(address employee, address sponsor);
    error AccountRemoved(address employee);
    error IdNumberAlreadyUsed(string idNumber, address owner);
    error IdNumberMismatch(address employee);
    error TransferInProgress(address employee);
    error NoActiveTransfer(address employee);
    error EmployeeNotActive(address employee, EmployeeStatus status);
    error EmployeeNotSuspended(address employee);
    error BalanceNotZero(uint256 personalBalance, uint256 overallBalance);
    error AccountNotVisible(address caller, address employee);
    error SponsorNotVisible(address caller, address sponsor);
    error CrossCityRequiresTransfer(address employee, string fromCity, string toCity);
    error NotTransferContract(address caller);
    error TransferContractUnchanged(address account);
    ---------------------------------------------------------------------------- */

    /// @dev 只有白名单里的转移合约能写"在途转移"标记，防止任意地址伪造转移状态把职工账户冻结
    modifier onlyTransferContract() {
        // 0.8 原语义：revert NotTransferContract(msg.sender)
        require(msg.sender == transferContract, "NotTransferContract");
        _;
    }

    // ------------------------------------------------------------------
    // 转移合约接线 —— ADMIN
    // ------------------------------------------------------------------

    /// @notice 设置转移合约地址（仅 ADMIN）。核心合约部署后再部署转移合约，然后调用本函数接线。
    function setTransferContract(address account) external onlyAdmin {
        require(account != address(0), "ZeroAddress");
        // 0.8 原语义：revert TransferContractUnchanged(account)
        require(account != transferContract, "TransferContractUnchanged");
        address old = transferContract;
        transferContract = account;
        emit TransferContractUpdated(old, account, msg.sender);
    }

    /// @notice 授予社保局（BUREAU）角色（仅 ADMIN，或已接线的转移合约在注册社保局时调用）。
    /// @dev 角色表只能由核心合约维护，而社保局注册入口在转移合约，因此这里开一个受控入口：
    ///      要么 ADMIN 直接授予，要么由白名单里的转移合约授予（它本身就是社保局注册表）。
    ///      其他角色（SPONSOR / REGULATOR / ADMIN）都不走这个入口，各自由 addSponsorAccount /
    ///      addRegulatorAccount / addAdmin 在核心合约内完成授予。
    function grantBureauRole(address bureau) external {
        // 0.8 原语义：revert NotAuthorized(msg.sender, Role.ADMIN)
        require(msg.sender == transferContract || roleOf(msg.sender) == Role.ADMIN, "NotAuthorized");
        _grantRole(bureau, Role.BUREAU);
    }

    // ------------------------------------------------------------------
    // 转移合约专用钩子
    // ------------------------------------------------------------------

    /// @notice 登记在途转移（仅转移合约）
    function setActiveTransfer(address employee, uint256 ref) external onlyTransferContract {
        // 0.8 原语义：revert UnknownEmployee(employee)
        require(employees[employee].status != EmployeeStatus.NONE, "UnknownEmployee");
        // 0.8 原语义：revert TransferInProgress(employee)
        require(activeTransfer[employee] == 0, "TransferInProgress");
        activeTransfer[employee] = ref;
        emit TransferFlagged(employee, ref);
    }

    /// @notice 清除在途转移标记（仅转移合约）
    function clearActiveTransfer(address employee) external onlyTransferContract {
        // 0.8 原语义：revert NoActiveTransfer(employee)
        require(activeTransfer[employee] != 0, "NoActiveTransfer");
        activeTransfer[employee] = 0;
        emit TransferFlagCleared(employee);
    }

    /// @notice 读取职工转移所需的档案快照（状态 / 在途转移编号 / 参保城市）
    function getTransferState(address employee)
        external
        view
        returns (EmployeeStatus status, uint256 activeTransferRef, string memory city)
    {
        Employee storage emp = employees[employee];
        // 0.8 原语义：revert UnknownEmployee(employee)
        require(emp.status != EmployeeStatus.NONE, "UnknownEmployee");
        return (emp.status, activeTransfer[employee], emp.city);
    }

    // ------------------------------------------------------------------
    // 公司（缴费单位）注册 —— ADMIN
    // ------------------------------------------------------------------

    /// @notice 注册公司账户并授予 SPONSOR 角色（仅 ADMIN）
    function addSponsorAccount(address sponsor, string calldata name, string calldata city) external onlyAdmin {
        require(sponsor != address(0), "ZeroAddress");
        // 0.8 原语义：revert SponsorAlreadyExists(sponsor)
        require(!sponsors[sponsor].exists, "SponsorAlreadyExists");
        require(bytes(name).length != 0 && bytes(city).length != 0, "EmptyString");

        _grantRole(sponsor, Role.SPONSOR);

        // 0.6 写法说明：0.8 里可以对 storage 结构体直接用 calldata 字符串成员构造，
        // 0.6 的类型推导更严格，这里显式转成 memory 再赋值，语义与结果完全一致。
        string memory nameM = name;
        string memory cityM = city;
        sponsors[sponsor] = Sponsor({
            exists: true,
            name: nameM,
            city: cityM,
            registeredAt: uint64(block.timestamp)
        });
        sponsorList.push(sponsor);

        emit SponsorRegistered(sponsor, name, city, msg.sender);
    }

    // ------------------------------------------------------------------
    // 职工建档 / 开社保账户
    // ------------------------------------------------------------------

    /// @notice ADMIN 全量录入职工档案（参保城市取缴费单位注册城市）
    function addPerson(
        address employee,
        string calldata name,
        uint16 age,
        string calldata idNumber,
        string calldata employer,
        uint64 startDate,
        uint256 salary,
        address sponsor
    ) external onlyAdmin {
        EmployeeInput memory input;
        input.employee = employee;
        input.sponsor = sponsor;
        input.name = name;
        input.age = age;
        input.idNumber = idNumber;
        input.employer = employer;
        input.startDate = startDate;
        input.salary = salary;
        _register(input);
    }

    /// @notice 所属 SPONSOR 自助为职工建档（单位固定为调用者，城市取单位所在城市）
    function addEmployee(
        address employee,
        string calldata name,
        uint16 age,
        string calldata idNumber,
        string calldata employer,
        uint64 startDate,
        uint256 salary
    ) external onlySponsor {
        EmployeeInput memory input;
        input.employee = employee;
        input.sponsor = msg.sender;
        input.name = name;
        input.age = age;
        input.idNumber = idNumber;
        input.employer = employer;
        input.startDate = startDate;
        input.salary = salary;
        _register(input);
    }

    // ------------------------------------------------------------------
    // 减员 / 注销 / 调薪
    // ------------------------------------------------------------------

    /// @notice 单位减员（停缴）。可由所属 SPONSOR 或 ADMIN 发起，是转移放行的前置条件。
    /// @dev 注意：这里**不能**用 activeTransfer 拦截。职工先提交转移申请（等待停缴）再由单位减员，
    ///      是正常业务顺序；若此处拦截，放行所需的"已停缴"状态将永远无法达成，形成死锁。
    ///      转移已放行（基金已托管）时职工状态为 SUSPENDED，下面的状态校验会自然拦住重复减员。
    function suspendEmployee(address employee, string calldata reason) external {
        Employee storage emp = employees[employee];
        // 0.8 原语义：revert UnknownEmployee(employee)
        require(emp.status != EmployeeStatus.NONE, "UnknownEmployee");
        // 0.8 原语义：revert EmployeeNotActive(employee, emp.status)
        require(emp.status == EmployeeStatus.ACTIVE, "EmployeeNotActive");
        // 0.8 原语义：revert NotAuthorized(msg.sender, Role.SPONSOR)
        require(msg.sender == emp.sponsor || roleOf(msg.sender) == Role.ADMIN, "NotAuthorized");

        emp.status = EmployeeStatus.SUSPENDED;
        emit EmployeeSuspended(employee, msg.sender, reason);
    }

    /// @notice 注销职工账户（仅 ADMIN）。要求账户余额已结清且无在途转移，档案保留留痕。
    function removeEmployee(address employee) external onlyAdmin {
        Employee storage emp = employees[employee];
        // 0.8 原语义：revert UnknownEmployee(employee)
        require(emp.status != EmployeeStatus.NONE, "UnknownEmployee");
        // 0.8 原语义：revert AccountRemoved(employee)
        require(emp.status != EmployeeStatus.REMOVED, "AccountRemoved");
        // 退休职工不允许再建档（否则会一边领养老金、一边重新缴费，待遇口径重复）
        require(emp.status != EmployeeStatus.RETIRED, "AccountRetired");
        // 0.8 原语义：revert TransferInProgress(employee)
        require(activeTransfer[employee] == 0, "TransferInProgress");
        // 0.8 原语义：revert BalanceNotZero(emp.personalBalance, emp.overallBalance)
        require(emp.personalBalance == 0 && emp.overallBalance == 0, "BalanceNotZero");

        if (emp.sponsor != address(0)) {
            _detachEmployee(emp.sponsor, employee);
            emp.sponsor = address(0);
        }
        emp.status = EmployeeStatus.REMOVED;

        emit EmployeeRemoved(employee, msg.sender);
    }

    /// @notice 调整职工工资并重算缴费基数。可由所属 SPONSOR 或 ADMIN 发起。
    function setSalary(address employee, uint256 salary) external {
        Employee storage emp = employees[employee];
        // 0.8 原语义：revert EmployeeNotActive(employee, emp.status)
        require(emp.status == EmployeeStatus.ACTIVE, "EmployeeNotActive");
        // 0.8 原语义：revert NotAuthorized(msg.sender, Role.SPONSOR)
        require(msg.sender == emp.sponsor || roleOf(msg.sender) == Role.ADMIN, "NotAuthorized");
        // 0.8 原语义：revert TransferInProgress(employee)
        require(activeTransfer[employee] == 0, "TransferInProgress");
        // 0.8 原语义：revert InvalidSalary(salary)
        require(salary != 0, "InvalidSalary");

        emp.salary = salary;
        emp.paymentBase = _paymentBase(salary);

        emit EmployeeSalaryUpdated(employee, salary, emp.paymentBase);
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    /// @notice 查询参保账户（本人 / 所属 SPONSOR / ADMIN / REGULATOR 审计）
    function getInsuranceAccount(address employee) external view returns (InsuranceAccountView memory view_) {
        Employee storage emp = employees[employee];
        // 0.8 原语义：revert UnknownEmployee(employee)
        require(emp.status != EmployeeStatus.NONE, "UnknownEmployee");
        _requireAccountReader(employee);

        view_ = InsuranceAccountView({
            employee: employee,
            name: emp.name,
            idNumber: emp.idNumber,
            employer: emp.employer,
            city: emp.city,
            sponsor: emp.sponsor,
            status: emp.status,
            salary: emp.salary,
            paymentBase: emp.paymentBase,
            personalBalance: emp.personalBalance,
            overallBalance: emp.overallBalance,
            // 资金合计：走 SafeMath.add，0.6 无内建溢出检查
            totalBalance: emp.personalBalance.add(emp.overallBalance),
            paidMonths: emp.paidMonths,
            overdueCount: emp.overdueCount,
            registeredAt: emp.registeredAt,
            lastPaymentAt: emp.lastPaymentAt,
            activeTransferId: activeTransfer[employee],
            retiredAt: emp.retiredAt,
            lastPensionAt: emp.lastPensionAt,
            pensionPersonalMonthly: emp.pensionPersonalMonthly,
            pensionClaimedMonths: emp.pensionClaimedMonths,
            age: emp.age
        });
    }

    /// @notice 查询本公司职工地址列表（所属 SPONSOR / ADMIN / REGULATOR 审计），带分页。
    /// @dev 名单包含已减员、已注销的历史记录，前端可按需过滤（配合 getInsuranceAccount 的状态字段）。
    function getPersonList(address sponsor, uint256 offset, uint256 limit)
        external
        view
        returns (address[] memory list, uint256 total, uint256 nextOffset)
    {
        require(sponsor != address(0), "ZeroAddress");
        // 0.8 原语义：revert NotAuthorized(msg.sender, Role.SPONSOR)
        require(
            msg.sender == sponsor || roleOf(msg.sender) == Role.ADMIN || roleOf(msg.sender) == Role.REGULATOR,
            "NotAuthorized"
        );

        address[] storage all = sponsorEmployees[sponsor];
        total = all.length;
        if (limit == 0 || offset >= total) {
            return (new address[](0), total, total);
        }

        uint256 end = offset + limit;
        if (end > total) end = total;

        list = new address[](end - offset);
        for (uint256 i = offset; i < end; ++i) {
            list[i - offset] = all[i];
        }
        nextOffset = end;
    }

    function getSponsorCount() external view returns (uint256) {
        return sponsorList.length;
    }

    /// @notice 查询已注册公司列表（ADMIN / REGULATOR 审计）
    function getSponsorList(uint256 offset, uint256 limit)
        external
        view
        onlyAdminOrRegulator
        returns (address[] memory list, uint256 total, uint256 nextOffset)
    {
        total = sponsorList.length;
        if (limit == 0 || offset >= total) {
            return (new address[](0), total, total);
        }
        uint256 end = offset + limit;
        if (end > total) end = total;
        list = new address[](end - offset);
        for (uint256 i = offset; i < end; ++i) {
            list[i - offset] = sponsorList[i];
        }
        nextOffset = end;
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    function _register(EmployeeInput memory in_) private {
        require(in_.employee != address(0) && in_.sponsor != address(0), "ZeroAddress");
        // 0.8 原语义：revert UnknownSponsor(in_.sponsor)
        require(sponsors[in_.sponsor].exists, "UnknownSponsor");
        require(bytes(in_.idNumber).length != 0, "EmptyString");
        // 0.8 原语义：revert InvalidSalary(in_.salary)
        require(in_.salary != 0, "InvalidSalary");
        // 0.8 原语义：revert TransferInProgress(in_.employee)
        require(activeTransfer[in_.employee] == 0, "TransferInProgress");

        Employee storage emp = employees[in_.employee];
        bytes32 idHash = keccak256(bytes(in_.idNumber));
        {
            address idOwner = idIndex[idHash];
            // 0.8 原语义：revert IdNumberAlreadyUsed(in_.idNumber, idOwner)
            require(idOwner == address(0) || idOwner == in_.employee, "IdNumberAlreadyUsed");
        }

        // 0.8 原语义：revert AlreadyInsured(in_.employee, emp.sponsor)
        require(emp.status != EmployeeStatus.ACTIVE, "AlreadyInsured");
        // 0.8 原语义：revert AccountRemoved(in_.employee)
        require(emp.status != EmployeeStatus.REMOVED, "AccountRemoved");

        bool firstTime = emp.status == EmployeeStatus.NONE;
        // 0.8 原语义：revert IdNumberMismatch(in_.employee)
        require(firstTime || keccak256(bytes(emp.idNumber)) == idHash, "IdNumberMismatch");
        // 跨城市必须走转移流程（由社保局放行 + 接收），否则单位只要先给职工减员，
        // 就能用外市单位直接把人"挖走"，把基金绕过转移流程带走。
        // 0.8 原语义：revert CrossCityRequiresTransfer(in_.employee, emp.city, sponsors[in_.sponsor].city)
        require(
            firstTime || keccak256(bytes(emp.city)) == keccak256(bytes(sponsors[in_.sponsor].city)),
            "CrossCityRequiresTransfer"
        );

        if (firstTime) {
            emp.name = in_.name;
            emp.idNumber = in_.idNumber;
            emp.registeredAt = uint64(block.timestamp);
            idIndex[idHash] = in_.employee;
        }

        // 换单位：从原单位名单摘除、加入新单位名单，并重新起算缴费周期；
        // 同一单位复保则保留上次缴费时间，避免借"减员-复保"绕过缴费周期护栏。
        if (emp.sponsor != in_.sponsor) {
            if (emp.sponsor != address(0)) _detachEmployee(emp.sponsor, in_.employee);
            _attachEmployee(in_.sponsor, in_.employee);
            emp.lastPaymentAt = 0;
        }

        emp.age = in_.age;
        emp.employer = in_.employer;
        emp.startDate = in_.startDate;
        emp.salary = in_.salary;
        emp.paymentBase = _paymentBase(in_.salary);
        emp.sponsor = in_.sponsor;
        emp.city = sponsors[in_.sponsor].city;
        emp.status = EmployeeStatus.ACTIVE;

        if (firstTime) {
            emit EmployeeRegistered(in_.employee, in_.sponsor, in_.idNumber, emp.paymentBase);
        } else {
            emit EmployeeRehired(in_.employee, in_.sponsor, emp.city);
        }
    }

    function _attachEmployee(address sponsor, address employee) private {
        sponsorEmployees[sponsor].push(employee);
        // 1-based 位置索引 = push 之后的数组长度（不是 length+1，否则 _detachEmployee 会越界/摘错人）
        sponsorEmployeeIndex[sponsor][employee] = sponsorEmployees[sponsor].length;
    }

    function _detachEmployee(address sponsor, address employee) internal {
        uint256 idx = sponsorEmployeeIndex[sponsor][employee];
        if (idx == 0) return;

        address[] storage list = sponsorEmployees[sponsor];
        uint256 last = list.length - 1;
        uint256 pos = idx - 1;

        if (pos != last) {
            address moved = list[last];
            list[pos] = moved;
            sponsorEmployeeIndex[sponsor][moved] = pos.add(1);
        }
        list.pop();
        delete sponsorEmployeeIndex[sponsor][employee];
    }

    function _requireAccountReader(address employee) internal view {
        if (msg.sender == employee) return;
        Role role = roleOf(msg.sender);
        if (role == Role.ADMIN || role == Role.REGULATOR) return;
        if (msg.sender == employees[employee].sponsor) return;
        // 0.8 原语义：revert AccountNotVisible(msg.sender, employee)
        require(false, "AccountNotVisible");
    }
}

/**
 * @title PensionRegulator
 * @notice 监管账户：全局只读审计身份（REGULATOR）。可查全平台账户、余额、缴费流水、
 *         资金总览与转移记录，但**不能修改任何业务状态、不能动任何钱**。
 *         本文件由 0.8.19 版移植到 0.6.10（FISCO BCOS 2.x）。
 *
 * @dev 设计原则
 *      1. 职责分离：监管方不持有 onlyAdmin / onlyBureau / onlySponsor 中的任何一个，
 *         因此注资、缴费、建档、注销、减员、放行、接收、授权等写操作对它全部不可用，
 *         不依赖"记得加判断"，而是由角色模型天然隔离。
 *      2. 监管方是全局作用域（一个监管身份看全平台），可注册多个账户（如人社厅 + 审计部门）。
 *      3. 监管账户的注册与注销都由 ADMIN 控制，注册/注销本身也发事件上链留痕；
 *         注销只收回角色并清理注册信息，历史监管行为在区块日志中永久可查。
 *      4. 监管方能看到的数据强度：账户明细含姓名、身份证号、工资等敏感字段，
 *         这正是隐私风险点——链上明文对监管方之外的人同样可读，
 *         后续若要收紧，需把敏感字段改为链上哈希 + 链下明文。
 *
 * @dev 0.6.10 移植说明（ABI 不变）：自定义 `error` 换成 `require(条件, "错误名")`；
 *      本合约无资金算术（名单操作只是数组下标），因此不需要 SafeMath。
 */
abstract contract PensionRegulator is PensionAccount {
    struct Regulator {
        bool exists;
        string name; // 监管人 / 岗位名称
        string agency; // 所属监管机构
        uint64 registeredAt;
    }

    mapping(address => Regulator) internal regulators;
    address[] internal regulatorList;

    event RegulatorRegistered(address indexed regulator, string name, string agency, address indexed operator);
    event RegulatorRemoved(address indexed regulator, address indexed operator);

    /* ------------------------- 0.8 自定义错误（原样留档） -------------------------
    error RegulatorAlreadyExists(address regulator);
    error UnknownRegulator(address regulator);
    ---------------------------------------------------------------------------- */

    // ------------------------------------------------------------------
    // 监管账户注册 —— ADMIN
    // ------------------------------------------------------------------

    /// @notice 注册监管账户并授予 REGULATOR 角色（仅 ADMIN）
    function addRegulatorAccount(address regulator, string calldata name, string calldata agency)
        external
        onlyAdmin
    {
        require(regulator != address(0), "ZeroAddress");
        // 0.8 原语义：revert RegulatorAlreadyExists(regulator)
        require(!regulators[regulator].exists, "RegulatorAlreadyExists");
        require(bytes(name).length != 0, "EmptyString");

        _grantRole(regulator, Role.REGULATOR);

        // 0.6 写法说明：显式转 memory，避免 storage 结构体初始化时的 calldata 类型推导问题
        string memory nameM = name;
        string memory agencyM = agency;
        regulators[regulator] = Regulator({
            exists: true,
            name: nameM,
            agency: agencyM,
            registeredAt: uint64(block.timestamp)
        });
        regulatorList.push(regulator);

        emit RegulatorRegistered(regulator, name, agency, msg.sender);
    }

    /// @notice 注销监管账户（仅 ADMIN）：收回 REGULATOR 角色并清理注册信息
    function removeRegulatorAccount(address regulator) external onlyAdmin {
        // 0.8 原语义：revert UnknownRegulator(regulator)
        require(regulators[regulator].exists, "UnknownRegulator");

        _revokeRole(regulator, Role.REGULATOR);
        delete regulators[regulator];
        _removeRegulatorFromList(regulator);

        emit RegulatorRemoved(regulator, msg.sender);
    }

    // ------------------------------------------------------------------
    // 查询（注册信息为公开信息）
    // ------------------------------------------------------------------

    function getRegulator(address regulator)
        external
        view
        returns (string memory name, string memory agency, uint64 registeredAt)
    {
        Regulator storage r = regulators[regulator];
        // 0.8 原语义：revert UnknownRegulator(regulator)
        require(r.exists, "UnknownRegulator");
        return (r.name, r.agency, r.registeredAt);
    }

    function getRegulatorCount() external view returns (uint256) {
        return regulatorList.length;
    }

    /// @notice 监管账户名单（ADMIN / REGULATOR）
    function getRegulatorList(uint256 offset, uint256 limit)
        external
        view
        onlyAdminOrRegulator
        returns (address[] memory list, uint256 total, uint256 nextOffset)
    {
        total = regulatorList.length;
        if (limit == 0 || offset >= total) {
            return (new address[](0), total, total);
        }
        uint256 end = offset + limit;
        if (end > total) end = total;
        list = new address[](end - offset);
        for (uint256 i = offset; i < end; ++i) {
            list[i - offset] = regulatorList[i];
        }
        nextOffset = end;
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /// @dev 监管账户数量以"个位数机构"计，这里用遍历实现交换删除，gas 影响可忽略；
    ///      职工/公司名单那种可能上万条的列表才必须用位置索引。
    function _removeRegulatorFromList(address regulator) private {
        uint256 len = regulatorList.length;
        for (uint256 i = 0; i < len; ++i) {
            if (regulatorList[i] == regulator) {
                regulatorList[i] = regulatorList[len - 1];
                regulatorList.pop();
                return;
            }
        }
    }
}

/**
 * @title PensionBank
 * @notice 费用管理：单位统筹账户注资、按月缴费、缴费记录与全平台资金总览。
 *         本文件由 0.8.19 版移植到 0.6.10（FISCO BCOS 2.x）。
 *
 * @dev 关键设计
 *      1. 记账制而非转账制：平台内部资金用余额记账（sponsorPool / personalBalance /
 *         overallBalance），链上不产生原生币转账。单位注资由 ADMIN 记账并上链留痕，
 *         实际资金结算仍在链下银行完成（赛题背景中平台 S 的角色）。
 *         好处是避免了 payable 转账带来的重入风险，且完全兼容 FISCO BCOS / 联盟链
 *         这种没有真实 ETH 流转的链环境。
 *      2. 权限：depositSponsor 仅 ADMIN；makePayment 仅该职工所属 SPONSOR
 *         （原版用全局 sponsor 变量判定，等于任何单位都能替全平台缴费）。
 *      3. 防重复缴费：同一职工同一缴费周期（30 天）内只能缴一次，原版可被无限次调用，
 *         任何人都能反复刷高职工余额、抽干单位账户。
 *      4. 逾期不再直接 revert：原版 `require(block.timestamp <= paymentTimestamp + 30 days)`
 *         会让逾期单位永远无法补缴，形成死锁。这里改为"逾期照常缴纳 + 链上留痕"，
 *         由 ADMIN / 劳动部门据此追责，更贴近"未在 30 天内缴纳则追究法律责任"的业务语义。
 *      5. 统筹基金与统筹权益分开记账（对齐"统筹基金在社保局手里"的业务口径）：
 *         - 资金账：单位缴费中的 19% 进入参保城市的统筹基金池 cityPool[城市]，
 *           跨城市转移时从转出城市池划出、进入转入城市池；
 *         - 权益账：职工名下的 overallBalance 记录其累计统筹缴费额（用于计算待遇），不是资金。
 *         个人账户 personalBalance 则是实账：资金一直挂在职工名下，转移时随人走。
 *      6. 三条恒等式，都可用 getSystemStats() / getCityPool() 对外核对：
 *         (a) totalDeposited == totalSponsorPool + totalPaid                        注资去向
 *         (b) totalPaid == totalPersonalFund + totalCityPool
 *                          + totalEscrowedPersonal + totalEscrowedOverall          缴费去向
 *         (c) totalCityPool + totalEscrowedOverall == totalOverallRights            基金与权益对账
 *
 * @dev 0.6.10 移植说明（语义等价，ABI 不变）：
 *      - 文件头加 `pragma experimental ABIEncoderV2;`：`getPaymentRecords` 返回
 *        PaymentRecord[] 结构体数组、`getSystemStats` 返回 SystemStats 结构体，
 *        0.6 的 external 函数用结构体做返回值必须开这个开关。
 *      - 自定义 `error` 换成 `require(条件, "错误名")`，错误名与语义一一对应。
 *      - **所有资金算术走 SafeMath**（这是本合约最关键的安全改造）：注资累加、
 *        缴费扣减、个人/统筹余额累加、城市基金池加减、托管锁定/释放、
 *        以及 8 个全局累计口径（totalDeposited/totalSponsorPool/totalPaid/
 *        totalPersonalFund/totalOverallRights/totalCityPool/totalEscrowedPersonal/
 *        totalEscrowedOverall）的加减，全部用 .add/.sub（费率乘法在 Policy 里用 .mul/.div）。
 *        这样三条资金守恒恒等式在 0.6 上不会被静默回绕破坏。
 */
abstract contract PensionBank is PensionRegulator {
    using SafeMath for uint256;

    struct PaymentRecord {
        uint64 timestamp;
        uint256 paymentBase;
        uint256 personalAmount;
        uint256 overallAmount;
        address sponsor;
        bool overdue;
    }

    /// @dev 公司统筹账户余额（单位注资进来的钱，用于代缴职工个人与统筹两部分费用）
    mapping(address => uint256) internal sponsorPool;
    mapping(address => uint256) internal sponsorPaidTotal;
    mapping(address => PaymentRecord[]) internal paymentRecords;

    /// @dev 城市统筹基金池：单位缴费中的统筹部分（19%）按参保城市归集，跨城市转移时划转
    mapping(string => uint256) internal cityPool;

    /// @notice 转移在途托管记录：放行时把职工名下的个人资金与统筹基金锁进这里，接收时释放。
    /// @dev 托管金额由核心合约自己记账，**不信任调用方传入的数字**，转移合约只能触发、不能改数。
    struct Escrow {
        uint256 personalFund;
        uint256 overallFund;
        uint32 paidMonths;
    }

    mapping(address => Escrow) internal escrows;

    /// @notice 统一的对账口径，一次返回，避免前端多次调用
    struct SystemStats {
        uint256 deposited; // 累计注资
        uint256 sponsorPoolTotal; // 公司统筹账户余额合计（已注资未缴的部分）
        uint256 paid; // 累计已缴
        uint256 personalFund; // 职工个人账户资金合计（实账）
        uint256 overallRights; // 职工统筹权益合计（记账，非资金）
        uint256 cityPoolTotal; // 城市统筹基金池合计（实账）
        uint256 escrowedPersonal; // 转移在途托管的个人账户资金
        uint256 escrowedOverall; // 转移在途托管的统筹基金
    }

    uint256 public totalDeposited;
    uint256 public totalSponsorPool;
    uint256 public totalPaid;
    uint256 public totalPersonalFund;
    uint256 public totalOverallRights;
    uint256 public totalCityPool;
    uint256 public totalEscrowedPersonal;
    uint256 public totalEscrowedOverall;

    /// @notice 累计已发放养老金（个人账户 + 统筹基金）。
    /// @dev 有了它，缴费去向的恒等式才是闭环的：
    ///      totalPaid == personalFund + cityPool + escrows + totalPensionPaid。
    uint256 public totalPensionPaid;

    // ------------------------------------------------------------------
    // 养老金支出（退休 + 按月发放）——把"缴纳"之外的另一半资金流补上
    // ------------------------------------------------------------------
    //
    // 为什么不在链上存发放流水：核心合约运行时代码已逼近 EIP-170 的 24576 字节上限，
    // 再放一个可遍历的数组/分页 getter 就装不下了。发放流水由事件 PensionClaimed 承载，
    // 链下由事件索引表（cs_chain_event）按 employee 过滤，这与项目里"名单类查询由事件驱动"
    // 的既有约定一致；资金侧的权威数据仍是 personalBalance / cityPool / 全局累计。

    /// @dev 退休门槛：法定退休年龄 60 岁、最低累计缴费 180 个月（15 年）。
    ///      国家统一口径，写成常量而不是 storage —— 既省字节码，也避免单个社保局擅自放宽。
    ///      特殊情形（病退 / 提前退休）走 retireEmployee(employee, true) 的豁免通道，
    ///      豁免事实会随 EmployeeRetired 事件上链留痕，供监管审计。
    uint16 internal constant RETIRE_AGE = 60;
    uint32 internal constant MIN_PAID_MONTHS = 180;

    /// @dev 个人账户养老金计发月数（60 岁退休对应 139 个月）。
    uint16 internal constant PENSION_PAYOUT_MONTHS = 139;

    event EmployeeRetired(
        address indexed employee,
        uint256 personalMonthly,
        uint256 baseMonthly,
        uint32 paidMonths,
        uint64 timestamp
    );
    event PensionClaimed(
        address indexed employee,
        uint256 personalAmount,
        uint256 overallAmount,
        uint256 totalAmount,
        uint64 timestamp
    );

    event SponsorDeposited(address indexed sponsor, uint256 amount, uint256 newBalance, address indexed operator);
    event PaymentMade(
        address indexed sponsor,
        address indexed employee,
        uint256 paymentBase,
        uint256 personalAmount,
        uint256 overallAmount,
        uint64 timestamp,
        bool overdue
    );

    /* ------------------------- 0.8 自定义错误（原样留档） -------------------------
    error NotYourEmployee(address sponsor, address employee);
    error InsufficientSponsorBalance(address sponsor, uint256 required, uint256 available);
    error PaymentCycleNotElapsed(address employee, uint64 nextPaymentAt);
    error CityPoolInsufficient(string city, uint256 required, uint256 available);
    error NoEscrow(address employee);
    error EscrowMismatch(address employee, uint256 personalFund, uint256 overallFund);
    ---------------------------------------------------------------------------- */

    // ------------------------------------------------------------------
    // 注资 —— ADMIN
    // ------------------------------------------------------------------

    /// @notice 给公司统筹账户注资（仅 ADMIN）。金额为记账额度，实际资金在链下结算。
    function depositSponsor(address sponsor, uint256 amount) external onlyAdmin {
        // 0.8 原语义：revert UnknownSponsor(sponsor)
        require(sponsors[sponsor].exists, "UnknownSponsor");
        // 0.8 原语义：revert ZeroAmount()
        require(amount != 0, "ZeroAmount");

        sponsorPool[sponsor] = sponsorPool[sponsor].add(amount);
        totalSponsorPool = totalSponsorPool.add(amount);
        totalDeposited = totalDeposited.add(amount);

        emit SponsorDeposited(sponsor, amount, sponsorPool[sponsor], msg.sender);
    }

    // ------------------------------------------------------------------
    // 缴费 —— 该职工所属 SPONSOR
    // ------------------------------------------------------------------

    /// @notice 为职工缴纳当月养老保险（个人 8% + 统筹 19%，均由单位承担）
    function makePayment(address employee) external onlySponsor {
        Employee storage emp = employees[employee];
        // 0.8 原语义：revert UnknownEmployee(employee)
        require(emp.status != EmployeeStatus.NONE, "UnknownEmployee");
        // 0.8 原语义：revert EmployeeNotActive(employee, emp.status)
        require(emp.status == EmployeeStatus.ACTIVE, "EmployeeNotActive");
        // 0.8 原语义：revert NotYourEmployee(msg.sender, employee)
        require(emp.sponsor == msg.sender, "NotYourEmployee");
        // 0.8 原语义：revert TransferInProgress(employee)
        require(activeTransfer[employee] == 0, "TransferInProgress");

        uint256 cycle = _cycle();
        uint64 lastPaid = emp.lastPaymentAt;
        if (lastPaid != 0) {
            // 0.6 写法说明：0.8 里 uint64 + uint256 常量可隐式提升，0.6 需要显式转 uint64；
            // PAYMENT_CYCLE = 30 days = 2592000，远小于 uint64 上限，转换无信息损失。
            uint64 nextPaymentAt = lastPaid + uint64(cycle);
            // 0.8 原语义：revert PaymentCycleNotElapsed(employee, nextPaymentAt)
            require(block.timestamp >= uint256(nextPaymentAt), "PaymentCycleNotElapsed");
        }

        uint256 base = emp.paymentBase;
        uint256 personalAmount = _personalAmount(base);
        uint256 overallAmount = _overallAmount(base);
        uint256 total = personalAmount.add(overallAmount);

        uint256 available = sponsorPool[msg.sender];
        // 0.8 原语义：revert InsufficientSponsorBalance(msg.sender, total, available)
        require(available >= total, "InsufficientSponsorBalance");

        uint64 baseline = lastPaid == 0 ? emp.registeredAt : lastPaid;
        bool overdue = block.timestamp > uint256(baseline) + cycle;

        // 先改状态，再写记录（checks-effects-interactions）
        sponsorPool[msg.sender] = available.sub(total);
        totalSponsorPool = totalSponsorPool.sub(total);

        emp.personalBalance = emp.personalBalance.add(personalAmount); // 个人账户实账
        emp.overallBalance = emp.overallBalance.add(overallAmount); // 统筹权益记账
        cityPool[emp.city] = cityPool[emp.city].add(overallAmount); // 统筹基金进入参保城市基金池
        emp.paidMonths += 1;
        emp.lastPaymentAt = uint64(block.timestamp);
        if (overdue) emp.overdueCount += 1;

        paymentRecords[employee].push(
            PaymentRecord({
                timestamp: uint64(block.timestamp),
                paymentBase: base,
                personalAmount: personalAmount,
                overallAmount: overallAmount,
                sponsor: msg.sender,
                overdue: overdue
            })
        );

        sponsorPaidTotal[msg.sender] = sponsorPaidTotal[msg.sender].add(total);
        totalPaid = totalPaid.add(total);
        totalPersonalFund = totalPersonalFund.add(personalAmount);
        totalOverallRights = totalOverallRights.add(overallAmount);
        totalCityPool = totalCityPool.add(overallAmount);

        emit PaymentMade(
            msg.sender,
            employee,
            base,
            personalAmount,
            overallAmount,
            uint64(block.timestamp),
            overdue
        );
    }

    // ------------------------------------------------------------------
    // 养老金支出 —— 退休办理 + 按月发放
    // ------------------------------------------------------------------
    //
    // 资金口径（三条恒等式的延伸）：
    //   totalPaid == totalPersonalFund + totalCityPool + escrows + totalPensionPaid
    // 即"缴进来的钱"只有四个去处：还在个人账户、还在城市统筹池、在途托管、已作为养老金发出去。
    // 发放时个人账户部分扣 personalBalance，基础养老金部分扣 cityPool[城市]，
    // 两者都同步扣全局累计，保证对账口径不漂。

    /// @notice 办理退休（仅 ADMIN；社保局经办时由后端代签）。
    /// @param employee 职工链上地址
    /// @param force true = 特殊审批（病退 / 提前退休），跳过年龄与缴费月数门槛；
    ///              链上事件仍记录真实年龄与缴费月数，豁免事实可被监管审计。
    /// @dev 常规办理（force=false）必须同时满足：达到 60 岁、累计缴费满 180 个月。
    ///      退休只锁定"每月个人账户金额"，不改余额；钱在每次 claimPension 时逐月扣。
    function retireEmployee(address employee, bool force) external onlyAdmin {
        Employee storage emp = employees[employee];
        // 0.8 原语义：revert EmployeeNotActive(employee, emp.status)
        require(
            emp.status == EmployeeStatus.ACTIVE || emp.status == EmployeeStatus.SUSPENDED,
            "EmployeeNotRetirable"
        );
        require(activeTransfer[employee] == 0, "TransferInProgress");
        uint256 packed = _policy();
        if (!force) {
            // 0.8 原语义：revert RetireAgeNotReached(employee, emp.age, RETIRE_AGE)
            require(emp.age >= uint16((packed >> 64) & 0xFFFF), "RetireAgeNotReached");
            // 0.8 原语义：revert PaidMonthsNotEnough(employee, emp.paidMonths, MIN_PAID_MONTHS)
            require(emp.paidMonths >= uint32((packed >> 80) & 0xFFFF), "PaidMonthsNotEnough");
        }

        uint256 personalMonthly = emp.personalBalance.div((packed >> 48) & 0xFFFF);
        uint256 baseMonthly = _basePension(emp.paymentBase, emp.paidMonths);
        require(personalMonthly != 0 || baseMonthly != 0, "ZeroAmount");

        emp.status = EmployeeStatus.RETIRED;
        emp.retiredAt = uint64(block.timestamp);
        emp.lastPensionAt = 0;
        emp.pensionPersonalMonthly = personalMonthly;
        emp.pensionClaimedMonths = 0;

        emit EmployeeRetired(employee, personalMonthly, baseMonthly, emp.paidMonths, uint64(block.timestamp));
    }

    /// @notice 发放一个月养老金。职工本人领取，或 ADMIN 代发（社保局经办发放）。
    function claimPension(address employee) external {
        Employee storage emp = employees[employee];
        // 0.8 原语义：revert EmployeeNotRetired(employee)
        require(emp.status == EmployeeStatus.RETIRED, "EmployeeNotRetired");
        require(msg.sender == employee || roleOf(msg.sender) == Role.ADMIN, "NotAuthorized");

        // 同一周期（30 天）只能领一次，防止一次性把账户抽干
        uint64 last = emp.lastPensionAt;
        if (last != 0) {
            uint64 nextPensionAt = last + uint64(_cycle());
            require(block.timestamp >= uint256(nextPensionAt), "PensionCycleNotElapsed");
        }

        uint256 overallPart = _basePension(emp.paymentBase, emp.paidMonths);
        uint256 personalPart = emp.pensionPersonalMonthly;
        // 余额不足时按剩余发放（最后一期），不发负数
        if (personalPart > emp.personalBalance) personalPart = emp.personalBalance;
        uint256 total = overallPart.add(personalPart);
        require(total != 0, "ZeroAmount");

        uint256 pool = cityPool[emp.city];
        // 0.8 原语义：revert CityPoolInsufficient(emp.city, overallPart, pool)
        require(pool >= overallPart, "CityPoolInsufficient");

        cityPool[emp.city] = pool.sub(overallPart);
        totalCityPool = totalCityPool.sub(overallPart);
        if (personalPart != 0) {
            emp.personalBalance = emp.personalBalance.sub(personalPart);
            totalPersonalFund = totalPersonalFund.sub(personalPart);
        }
        emp.lastPensionAt = uint64(block.timestamp);
        emp.pensionClaimedMonths += 1;
        totalPensionPaid = totalPensionPaid.add(total);

        emit PensionClaimed(employee, personalPart, overallPart, total, uint64(block.timestamp));
    }

    /// @notice 基础养老金（统筹基金支付）= 缴费基数 × 缴费年数 × 1% = 基数 × 月数 / 1200
    function _basePension(uint256 paymentBase, uint32 paidMonths) internal view returns (uint256) {
        return paymentBase.mul(paidMonths).mul((_policy() >> 32) & 0xFFFF).div(120000);
    }

    // ------------------------------------------------------------------
    // 转移合约专用钩子：基金托管锁定 / 释放
    // ------------------------------------------------------------------

    /// @notice 放行转移：把职工个人账户资金、统筹权益额与累计缴费月数从原城市锁进托管（仅转移合约）
    /// @dev 统筹基金同步从转出城市基金池划出，个人账户资金从职工名下移出，全部进入在途托管。
    function lockFundsForTransfer(address employee, string calldata fromCity)
        external
        onlyTransferContract
        returns (uint256 personalFund, uint256 overallFund, uint32 paidMonths)
    {
        Employee storage emp = employees[employee];
        // 0.8 原语义：revert EmployeeNotSuspended(employee)
        require(emp.status == EmployeeStatus.SUSPENDED, "EmployeeNotSuspended");
        // 0.8 原语义：revert NoActiveTransfer(employee)
        require(activeTransfer[employee] != 0, "NoActiveTransfer");
        // 0.8 原语义：revert CrossCityRequiresTransfer(employee, emp.city, fromCity)
        require(keccak256(bytes(emp.city)) == keccak256(bytes(fromCity)), "CrossCityRequiresTransfer");

        personalFund = emp.personalBalance;
        overallFund = emp.overallBalance;
        paidMonths = emp.paidMonths;

        uint256 pool = cityPool[fromCity];
        // 0.8 原语义：revert CityPoolInsufficient(fromCity, overallFund, pool)
        require(pool >= overallFund, "CityPoolInsufficient");

        cityPool[fromCity] = pool.sub(overallFund);

        emp.personalBalance = 0;
        emp.overallBalance = 0;
        emp.paidMonths = 0;

        escrows[employee] = Escrow({personalFund: personalFund, overallFund: overallFund, paidMonths: paidMonths});

        totalPersonalFund = totalPersonalFund.sub(personalFund);
        totalOverallRights = totalOverallRights.sub(overallFund);
        totalCityPool = totalCityPool.sub(overallFund);
        totalEscrowedPersonal = totalEscrowedPersonal.add(personalFund);
        totalEscrowedOverall = totalEscrowedOverall.add(overallFund);
    }

    /// @notice 接收转移：把托管的基金落到转入城市基金池与职工名下（仅转移合约）
    function unlockFundsForTransfer(address employee, string calldata toCity)
        external
        onlyTransferContract
        returns (uint256 personalFund, uint256 overallFund, uint32 paidMonths)
    {
        Escrow memory escrow = escrows[employee];
        // 0.8 原语义：revert NoEscrow(employee)
        require(
            escrow.personalFund != 0 || escrow.overallFund != 0 || escrow.paidMonths != 0,
            "NoEscrow"
        );
        // 0.8 原语义：revert NoActiveTransfer(employee)
        require(activeTransfer[employee] != 0, "NoActiveTransfer");
        delete escrows[employee];

        personalFund = escrow.personalFund;
        overallFund = escrow.overallFund;
        paidMonths = escrow.paidMonths;

        Employee storage emp = employees[employee];
        emp.personalBalance = personalFund;
        emp.overallBalance = overallFund;
        emp.paidMonths = paidMonths;
        // 转移接收后职工不再属于原单位：必须从原单位名单里摘掉。
        // 否则原单位 getPersonList 会继续留着他，同一人再接续参保时还会被 _attachEmployee 再加一次，
        // 造成"职工管理页出现两条张三"（链上名单重复、employeeCount 偏大）。
        if (emp.sponsor != address(0)) {
            _detachEmployee(emp.sponsor, employee);
        }
        emp.city = toCity;
        emp.sponsor = address(0); // 新城市尚未参保，须由新单位再次建档
        emp.status = EmployeeStatus.TRANSFERRED;

        cityPool[toCity] = cityPool[toCity].add(overallFund);

        totalPersonalFund = totalPersonalFund.add(personalFund);
        totalOverallRights = totalOverallRights.add(overallFund);
        totalCityPool = totalCityPool.add(overallFund);
        totalEscrowedPersonal = totalEscrowedPersonal.sub(personalFund);
        totalEscrowedOverall = totalEscrowedOverall.sub(overallFund);
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    /// @notice 查询在途托管金额（本人 / 所属单位 / ADMIN / REGULATOR）
    function getEscrow(address employee) external view returns (uint256 personalFund, uint256 overallFund, uint32 paidMonths) {
        _requireAccountReader(employee);
        Escrow memory escrow = escrows[employee];
        return (escrow.personalFund, escrow.overallFund, escrow.paidMonths);
    }

    /// @notice 查询缴费记录（本人 / 所属 SPONSOR / ADMIN），带分页
    function getPaymentRecords(address employee, uint256 offset, uint256 limit)
        external
        view
        returns (PaymentRecord[] memory records, uint256 total, uint256 nextOffset)
    {
        // 0.8 原语义：revert UnknownEmployee(employee)
        require(employees[employee].status != EmployeeStatus.NONE, "UnknownEmployee");
        _requireAccountReader(employee);

        PaymentRecord[] storage all = paymentRecords[employee];
        total = all.length;
        if (limit == 0 || offset >= total) {
            return (new PaymentRecord[](0), total, total);
        }

        uint256 end = offset + limit;
        if (end > total) end = total;

        records = new PaymentRecord[](end - offset);
        for (uint256 i = offset; i < end; ++i) {
            records[i - offset] = all[i];
        }
        nextOffset = end;
    }

    function getPaymentCount(address employee) external view returns (uint256) {
        return paymentRecords[employee].length;
    }

    /// @notice 查询公司资金账户（该公司自身 / ADMIN / REGULATOR 审计）
    function getSponsorAccount(address sponsor)
        external
        view
        returns (
            string memory name,
            string memory city,
            uint64 registeredAt,
            uint256 poolBalance,
            uint256 paidTotal,
            uint256 employeeCount
        )
    {
        require(sponsor != address(0), "ZeroAddress");
        // 0.8 原语义：revert SponsorNotVisible(msg.sender, sponsor)
        require(
            msg.sender == sponsor || roleOf(msg.sender) == Role.ADMIN || roleOf(msg.sender) == Role.REGULATOR,
            "SponsorNotVisible"
        );
        Sponsor storage s = sponsors[sponsor];
        // 0.8 原语义：revert UnknownSponsor(sponsor)
        require(s.exists, "UnknownSponsor");

        return (
            s.name,
            s.city,
            s.registeredAt,
            sponsorPool[sponsor],
            sponsorPaidTotal[sponsor],
            sponsorEmployees[sponsor].length
        );
    }

    /// @notice 查询某城市的统筹基金池余额（统筹基金属于公共基金，公开可查）
    function getCityPool(string calldata city) external view returns (uint256) {
        return cityPool[city];
    }

    /// @notice 平台资金/权益总览，供对账、审计与监管
    function getSystemStats() external view returns (SystemStats memory) {
        return _systemStats();
    }

    function _systemStats() internal view returns (SystemStats memory) {
        return SystemStats({
            deposited: totalDeposited,
            sponsorPoolTotal: totalSponsorPool,
            paid: totalPaid,
            personalFund: totalPersonalFund,
            overallRights: totalOverallRights,
            cityPoolTotal: totalCityPool,
            escrowedPersonal: totalEscrowedPersonal,
            escrowedOverall: totalEscrowedOverall
        });
    }
}

/// @dev 核心合约只需要从转移合约拿"机构数量"这类统计口径，用最小接口避免循环依赖
interface ITransferCounter {
    function getBureauCount() external view returns (uint256);
    function getTransferCount() external view returns (uint256);
}

/**
 * @title ChainSIP —— 区块链养老保险平台【核心合约】
 * @notice 账户 + 资金 + 监管，一个地址即可完成建档、注资、缴费、查询与审计。
 *         社保局注册与跨城市转移在独立的 ChainSIPTransfer 合约中，两个合约需分别部署。
 *         本文件由 0.8.19 版移植到 0.6.10（FISCO BCOS 2.x）。
 *
 * @dev 继承链（自基类到派生类）：
 *      Roles            —— 角色与权限修饰器（ADMIN / BUREAU / SPONSOR / REGULATOR）
 *      Policy           —— 缴费政策常量与金额计算
 *      PensionAccount   —— 公司注册、职工建档 / 减员 / 注销 / 查询、转移标记与钩子
 *      PensionRegulator —— 监管账户（只读审计身份）注册与名单
 *      PensionBank      —— 单位统筹账户注资、按月缴费、城市统筹基金池、基金托管与缴费记录
 *      ChainSIP         —— 核心入口
 *
 *      为什么拆成两个合约：全部业务聚合在一个合约时运行字节码约 27.4KB，超过 EVM 的
 *      24576 字节上限，WebASE / Remix 会直接拒绝部署。拆分后核心合约约 24KB（含养老金支出）、
 *      转移合约约 8KB，都在上限内且各留有余量（0.6.10 版实测见交付报告）。
 *
 *      部署顺序（WebASE / Remix / FISCO 控制台均适用，编译器请选 0.6.10，即 istanbul，
 *      不带 PUSH0；FISCO BCOS 2.x 官方支持到 0.6.10）：
 *      1) 部署 ChainSIP（部署者自动成为首个 ADMIN）；
 *      2) 部署 ChainSIPTransfer，构造参数填 ChainSIP 地址；
 *      3) ADMIN 调 ChainSIP.setTransferContract(转移合约地址) 完成接线；
 *      4) 在 ChainSIPTransfer 上 addBureaus 注册社保局 → changeBureausAuthorized 授权；
 *      5) 在 ChainSIP 上 addSponsorAccount / depositSponsor / addRegulatorAccount；
 *      6) 单位自助建档 addEmployee 或 ADMIN 建档 addPerson；
 *      7) 单位按月缴费 makePayment；职工转移走 ChainSIPTransfer.applyForTransfer；
 *      8) 职工达到退休条件后由 ADMIN 调 retireEmployee(职工, false) 办理退休（特殊情形传 true 豁免门槛），
 *         之后每 30 天可 claimPension 领取一次：基础养老金从城市统筹基金池出，个人账户养老金从个人账户出。
 *
 * @dev 0.6.10 移植说明（语义等价，ABI 不变）：
 *      - 文件头加 `pragma experimental ABIEncoderV2;`：`getRegulatorDashboard` 的返回值里
 *        含 SystemStats 结构体，0.6 的 external 函数用结构体做返回值必须开这个开关。
 *      - 本合约自身没有自定义 error，也没有资金算术（只是聚合查询），故不需要 SafeMath。
 */
contract ChainSIP is PensionBank {
    string public constant SYSTEM_NAME = "ChainSIP";
    string public constant VERSION = "1.0.0";

    /// @notice 监管大屏：一次调用返回全平台规模指标与资金/权益对账数据。
    /// @dev 仅 ADMIN / REGULATOR 可调；机构数量取自转移合约，未接线时返回 0。
    function getRegulatorDashboard()
        external
        view
        onlyAdminOrRegulator
        returns (
            string memory systemName,
            string memory version,
            uint256 adminCount_,
            uint256 sponsorCount,
            uint256 bureauCount,
            uint256 regulatorCount,
            uint256 transferCount,
            SystemStats memory stats
        )
    {
        address counter = transferContract;
        if (counter != address(0)) {
            bureauCount = ITransferCounter(counter).getBureauCount();
            transferCount = ITransferCounter(counter).getTransferCount();
        }

        return (
            SYSTEM_NAME,
            VERSION,
            adminCount,
            sponsorList.length,
            bureauCount,
            regulatorList.length,
            transferCount,
            _systemStats()
        );
    }
}
