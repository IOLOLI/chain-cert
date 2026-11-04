// SPDX-License-Identifier: MIT
// ⚠️ 编译前必须把 WeBASE 合约 IDE 的编译器版本切到 0.6.10！
//    默认可能是 0.4.x/0.5.x，会报 ParserError：
//      - 不认识 `abstract contract`（0.6.0 才引入）
//      - 不认识接口参数里的 `string calldata`
pragma solidity >=0.6.10 <0.7.0;
pragma experimental ABIEncoderV2;

/**
 * ChainSIPTransfer —— 转移合约（社保局 + 跨城市转移状态机）
 *
 * 本文件由 contracts/ 下的多文件源展开而来：无任何 import，自包含（粘贴到控制台/WeBASE 直接用）。
 * 用途：WeBASE-Front 合约 IDE 直接粘贴编译（无任何 import）。
 *
 * 包含：SafeMathTransfer 库（本地改名，避免与核心文件顶层符号重名）、IChainSIPCore 接口（内联，用于调用核心合约）。
 * 说明：原多文件版里本合约用到的 Roles.Role.* / PensionAccount.EmployeeStatus.* 已按枚举定义展开成数字并保留注释。
 * 部署：构造参数填 **核心合约 ChainSIP 的地址**。
 * 部署后还需：用核心合约的 ADMIN 账户调 ChainSIP.setTransferContract(本合约地址)，核心合约才会认它。
 *
 * 编译设置：solc 0.6.10，**必须勾选 Enable optimization**
 *   （关优化器时 ChainSIP 运行时代码 39919 B 会超过 EIP-170 的 24576 B 上限而被链拒绝；开优化器 22411 B）
 * 注意：0.6.10 不支持 --via-ir（该选项是 0.8 才有的）。
 */

/**
 * @title SafeMathTransfer
 * @notice ChainSIP 2.x（FISCO BCOS 2.x / solc 0.6.10）专用的溢出安全检查库。
 *
 * @dev 为什么要这个库：
 *      Solidity 0.6 没有 0.8 的内建溢出检查，`a + b` / `a - b` / `a * b` 在越界时是
 *      静默回绕的。养老保险平台里所有金额（余额、基金池、累计值、缴费月数）一旦回绕，
 *      三条资金守恒恒等式就会被破坏，因此所有**资金相关的算术**都改走本库，
 *      失败时用 `require` 回滚并给出可读原因。
 *
 *      使用方式：`using SafeMathTransfer for uint256;` 之后写 `a.add(b)` / `a.sub(b)` / `a.mul(b)` / `a.div(b)`。
 *      非资金相关的算术（数组下标、分页偏移、循环计数、名单长度偏移）保持原生运算符，
 *      因为它们由数组长度天然约束，不会溢出，走库只会白烧 gas。
 */
library SafeMathTransfer {
    /// @dev 加法溢出（0.8 语义：Panic 0x11）
    function add(uint256 a, uint256 b) internal pure returns (uint256) {
        uint256 c = a + b;
        require(c >= a, "SafeMathTransfer: addition overflow");
        return c;
    }

    /// @dev 减法下溢（0.8 语义：Panic 0x11）
    function sub(uint256 a, uint256 b) internal pure returns (uint256) {
        require(b <= a, "SafeMathTransfer: subtraction overflow");
        uint256 c = a - b;
        return c;
    }

    /// @dev 乘法溢出（0.8 语义：Panic 0x11）
    function mul(uint256 a, uint256 b) internal pure returns (uint256) {
        if (a == 0) return 0;
        uint256 c = a * b;
        require(c / a == b, "SafeMathTransfer: multiplication overflow");
        return c;
    }

    /// @dev 除零（0.8 语义：Panic 0x12）
    function div(uint256 a, uint256 b) internal pure returns (uint256) {
        require(b > 0, "SafeMathTransfer: division by zero");
        uint256 c = a / b;
        return c;
    }
}

/// @dev 转移合约对核心合约的调用面：只保留转移业务真正需要的读写钩子，
///      核心合约通过 transferContract 白名单限定只有本合约能调这些写钩子。
interface IChainSIPCore {
    function roleOf(address account) external view returns (uint8);

    function getTransferState(address employee)
        external
        view
        returns (uint8 status, uint256 activeTransferRef, string memory city);

    function setActiveTransfer(address employee, uint256 ref) external;
    function clearActiveTransfer(address employee) external;

    function grantBureauRole(address bureau) external;

    function lockFundsForTransfer(address employee, string calldata fromCity)
        external
        returns (uint256 personalFund, uint256 overallFund, uint32 paidMonths);

    function unlockFundsForTransfer(address employee, string calldata toCity)
        external
        returns (uint256 personalFund, uint256 overallFund, uint32 paidMonths);
}

/**
 * @title ChainSIPTransfer —— 区块链养老保险平台【转移合约】
 * @notice 社保局（BUREAU）注册与授权、城市绑定、跨城市养老保险关系转移状态机。
 *         账户与资金状态全部存放在核心合约 ChainSIP 中，本合约只负责转移流程与机构管理，
 *         通过白名单钩子（lockFundsForTransfer / unlockFundsForTransfer）驱动核心合约划账。
 *         本文件由 0.8.19 版移植到 0.6.10（FISCO BCOS 2.x）。
 *
 * @dev 关键设计
 *      1. 权限来自核心合约：本合约不重复维护角色表，而是调用 core.roleOf(msg.sender)，
 *         避免两套角色数据不同步。核心合约未接线或未注册的角色一律拒绝。
 *      2. 转出地 / 转入地由"参保城市 → 社保局"的绑定关系推导，不由申请人传入地址，
 *         避免申请人指定任意社保局。
 *      3. 转移状态机：PENDING -> AUTHORIZED（基金已托管锁定）-> RECEIVED，
 *         另有 REJECTED 分支（复核失败，例如未办理停缴）。
 *      4. 基金托管金额由核心合约自己记账，本合约只能触发、不能改数；接收时还会拿
 *         申请单金额与核心合约的托管金额做一次防御性核对。
 *      5. 新注册社保局默认未授权，授权由 ADMIN 显式开启（对应赛题"切换社保局授权"）。
 *
 * @dev 0.6.10 移植说明（语义等价，ABI 不变）：
 *      - 文件头加 `pragma experimental ABIEncoderV2;`：`getTransfer` 直接返回
 *        TransferApplication 结构体，0.6 的 external 函数用结构体做返回值必须开这个开关。
 *      - 构造函数补 `public`：`constructor(address core) public { ... }`（0.6 强制要求）。
 *      - `immutable` 保留（0.6.5+ 支持，0.6.10 可用）：coreAddress 仍是部署期常量，
 *        不占 storage，读取语义与 0.8 版一致。
 *      - 自定义 `error` 换成 `require(条件, "错误名")`，错误名与语义一一对应。
 *      - 本合约只有 `id + 1`（转在途标记）与数组下标这类非资金计数，唯一改走 SafeMathTransfer 的
 *        是 activeTransfer 引用 `id.add(1)`，与 0.8 的溢出检查语义对齐；金额本身不在本合约计算。
 */
contract ChainSIPTransfer {
    using SafeMathTransfer for uint256;

    // 角色编号必须与核心合约 Roles.Role 枚举顺序一致
    uint8 private constant ROLE_ADMIN = uint8(1) /* Roles.Role.ADMIN */;
    uint8 private constant ROLE_BUREAU = uint8(2) /* Roles.Role.BUREAU */;
    uint8 private constant ROLE_REGULATOR = uint8(4) /* Roles.Role.REGULATOR */;

    uint8 private constant STATUS_ACTIVE = uint8(1) /* PensionAccount.EmployeeStatus.ACTIVE */;
    uint8 private constant STATUS_SUSPENDED = uint8(2) /* PensionAccount.EmployeeStatus.SUSPENDED */;

    enum TransferStatus {
        NONE,
        PENDING,
        AUTHORIZED,
        RECEIVED,
        REJECTED
    }

    struct Bureau {
        bool exists;
        string name;
        bool authorized;
        uint64 registeredAt;
    }

    struct TransferApplication {
        uint256 id;
        address employee;
        string fromCity;
        string toCity;
        address fromBureau;
        address toBureau;
        TransferStatus status;
        uint256 personalFund;
        uint256 overallFund;
        uint32 paidMonths;
        uint64 appliedAt;
        uint64 authorizedAt;
        uint64 receivedAt;
        string reason;
    }

    /// @notice 核心合约地址（部署时传入，不可更改）
    address public immutable coreAddress;

    mapping(address => Bureau) internal bureaus;
    address[] internal bureauList;
    mapping(string => address) internal cityBureau;
    mapping(address => string[]) internal bureauCities;
    mapping(address => uint256[]) internal outboundTransfers; // 转出地 -> 申请 id
    mapping(address => uint256[]) internal inboundTransfers; // 转入地 -> 申请 id

    TransferApplication[] internal transfers;

    event BureauRegistered(address indexed bureau, string name, string city, address indexed operator);
    event BureauAuthorizationChanged(address indexed bureau, bool authorized, address indexed operator);
    event TransferApplied(
        uint256 indexed id,
        address indexed employee,
        string fromCity,
        string toCity,
        address fromBureau,
        address toBureau
    );
    event TransferAuthorized(
        uint256 indexed id,
        address indexed employee,
        uint256 personalFund,
        uint256 overallFund,
        uint32 paidMonths
    );
    event TransferRejected(uint256 indexed id, address indexed employee, address indexed bureau, string reason);
    event TransferReceived(
        uint256 indexed id,
        address indexed employee,
        address fromBureau,
        address toBureau,
        string toCity,
        uint256 personalFund,
        uint256 overallFund,
        uint32 paidMonths
    );

    /* ------------------------- 0.8 自定义错误（原样留档） -------------------------
    error ZeroAddress();
    error EmptyString();
    error NotAuthorized(address caller, uint8 requiredRole);
    error UnknownBureau(address bureau);
    error CityAlreadyBound(string city, address bureau);
    error CityNotRegistered(string city);
    error SameCityTransfer(string fromCity, string toCity);
    error BureauNotAuthorized(address bureau);
    error NotTransferBureau(address caller, address expected);
    error InvalidTransferStatus(uint256 id, TransferStatus status);
    error UnknownTransfer(uint256 id);
    error TransferNotVisible(address caller, uint256 id);
    error AuthorizationUnchanged(address bureau, bool authorized);
    error EmployeeNotActive(address employee, uint8 status);
    error TransferInProgress(address employee);
    error EscrowMismatch(uint256 id, uint256 expectedPersonalFund, uint256 actualPersonalFund);
    ---------------------------------------------------------------------------- */

    modifier onlyAdmin() {
        // 0.8 原语义：revert NotAuthorized(msg.sender, ROLE_ADMIN)
        require(_roleOf(msg.sender) == ROLE_ADMIN, "NotAuthorized");
        _;
    }

    modifier onlyBureau() {
        // 0.8 原语义：revert NotAuthorized(msg.sender, ROLE_BUREAU)
        require(_roleOf(msg.sender) == ROLE_BUREAU, "NotAuthorized");
        _;
    }

    modifier onlyAdminOrRegulator() {
        uint8 role = _roleOf(msg.sender);
        // 0.8 原语义：revert NotAuthorized(msg.sender, ROLE_REGULATOR)
        require(role == ROLE_ADMIN || role == ROLE_REGULATOR, "NotAuthorized");
        _;
    }

    /// @param core 核心合约 ChainSIP 的部署地址
    constructor(address core) public {
        // 0.8 原语义：revert ZeroAddress()
        require(core != address(0), "ZeroAddress");
        coreAddress = core;
    }

    // ------------------------------------------------------------------
    // 社保局注册与授权 —— ADMIN
    // ------------------------------------------------------------------

    /// @notice 注册社保局并绑定其服务城市（仅 ADMIN）。同一社保局可多次调用以绑定多个城市。
    function addBureaus(address bureau, string calldata name, string calldata city) external onlyAdmin {
        require(bureau != address(0), "ZeroAddress");
        require(bytes(city).length != 0, "EmptyString");

        if (!bureaus[bureau].exists) {
            // 角色表在核心合约里，这里通过受控入口授予 BUREAU 角色（核心合约只信任本合约与 ADMIN）
            _core().grantBureauRole(bureau);

            // 0.6 写法说明：显式转 memory，避免 storage 结构体初始化时的 calldata 类型推导问题
            string memory nameM = name;
            bureaus[bureau] = Bureau({
                exists: true,
                name: nameM,
                authorized: false, // 新注册社保局默认未授权，须由 ADMIN 显式开启
                registeredAt: uint64(block.timestamp)
            });
            bureauList.push(bureau);
        } else if (bytes(name).length != 0) {
            bureaus[bureau].name = name;
        }

        address bound = cityBureau[city];
        // 0.8 原语义：revert CityAlreadyBound(city, bound)
        require(bound == address(0) || bound == bureau, "CityAlreadyBound");
        if (bound == address(0)) {
            cityBureau[city] = bureau;
            bureauCities[bureau].push(city);
        }

        emit BureauRegistered(bureau, name, city, msg.sender);
    }

    /// @notice 切换社保局授权状态（仅 ADMIN）。授权是办理转移业务（放行 / 接收）的必要条件。
    function changeBureausAuthorized(address bureau, bool authorized) external onlyAdmin {
        // 0.8 原语义：revert UnknownBureau(bureau)
        require(bureaus[bureau].exists, "UnknownBureau");
        // 0.8 原语义：revert AuthorizationUnchanged(bureau, authorized)（含字符串版本）
        require(
            bureaus[bureau].authorized != authorized,
            authorized ? "AuthorizationUnchanged(true)" : "AuthorizationUnchanged(false)"
        );

        bureaus[bureau].authorized = authorized;
        emit BureauAuthorizationChanged(bureau, authorized, msg.sender);
    }

    // ------------------------------------------------------------------
    // 转移流程
    // ------------------------------------------------------------------

    /// @notice 职工本人发起跨城市转移申请。转出地/转入地由参保城市与目标城市的绑定关系推导。
    function applyForTransfer(string calldata toCity) external returns (uint256 id) {
        address employee = msg.sender;
        (uint8 status, uint256 activeRef, string memory fromCity) = _core().getTransferState(employee);

        // 0.8 原语义：revert EmployeeNotActive(employee, status)
        require(status == STATUS_ACTIVE || status == STATUS_SUSPENDED, "EmployeeNotActive");
        // 0.8 原语义：revert TransferInProgress(employee)
        require(activeRef == 0, "TransferInProgress");
        require(bytes(toCity).length != 0, "EmptyString");

        address fromBureau = cityBureau[fromCity];
        // 0.8 原语义：revert CityNotRegistered(fromCity)
        require(fromBureau != address(0), "CityNotRegistered");

        address toBureau = cityBureau[toCity];
        // 0.8 原语义：revert CityNotRegistered(toCity)
        require(toBureau != address(0), "CityNotRegistered");
        // 0.8 原语义：revert SameCityTransfer(fromCity, toCity)
        require(toBureau != fromBureau, "SameCityTransfer");
        // 0.8 原语义：revert BureauNotAuthorized(toBureau)
        require(bureaus[toBureau].authorized, "BureauNotAuthorized");

        id = transfers.length;
        string memory toCityM = toCity;
        transfers.push(
            TransferApplication({
                id: id,
                employee: employee,
                fromCity: fromCity,
                toCity: toCityM,
                fromBureau: fromBureau,
                toBureau: toBureau,
                status: TransferStatus.PENDING,
                personalFund: 0,
                overallFund: 0,
                paidMonths: 0,
                appliedAt: uint64(block.timestamp),
                authorizedAt: 0,
                receivedAt: 0,
                reason: ""
            })
        );

        outboundTransfers[fromBureau].push(id);
        inboundTransfers[toBureau].push(id);
        // 在途转移标记 = 申请 id + 1（0 表示无在途）；走 SafeMathTransfer 与 0.8 溢出检查语义对齐
        _core().setActiveTransfer(employee, id.add(1));

        emit TransferApplied(id, employee, fromCity, toCity, fromBureau, toBureau);
    }

    /// @notice 转出地社保局复核放行。approved = false 即"复核失败"，须给出原因。
    /// @dev 放行要求职工已办理停缴（核心合约会在锁定基金时校验），否则整体回滚。
    function authorizeTransfer(uint256 id, bool approved, string calldata reason) external onlyBureau {
        // 授权状态是外层闸门，先于申请单是否存在做判断，避免向未授权方泄露申请信息
        // 0.8 原语义：revert BureauNotAuthorized(msg.sender)
        require(bureaus[msg.sender].authorized, "BureauNotAuthorized");

        TransferApplication storage app = _transfer(id);
        // 0.8 原语义：revert NotTransferBureau(msg.sender, app.fromBureau)
        require(app.fromBureau == msg.sender, "NotTransferBureau");
        // 0.8 原语义：revert InvalidTransferStatus(id, app.status)
        require(app.status == TransferStatus.PENDING, "InvalidTransferStatus");

        address employee = app.employee;

        if (!approved) {
            app.status = TransferStatus.REJECTED;
            app.reason = reason;
            app.authorizedAt = uint64(block.timestamp);
            _core().clearActiveTransfer(employee);
            emit TransferRejected(id, employee, msg.sender, reason);
            return;
        }

        (uint256 personalFund, uint256 overallFund, uint32 paidMonths) =
            _core().lockFundsForTransfer(employee, app.fromCity);

        app.personalFund = personalFund;
        app.overallFund = overallFund;
        app.paidMonths = paidMonths;
        app.status = TransferStatus.AUTHORIZED;
        app.authorizedAt = uint64(block.timestamp);

        emit TransferAuthorized(id, employee, personalFund, overallFund, paidMonths);
    }

    /// @notice 转入地社保局接收：核心合约建立新城市参保档案并落下托管基金。
    function receiveTransfer(uint256 id) external onlyBureau {
        // 0.8 原语义：revert BureauNotAuthorized(msg.sender)
        require(bureaus[msg.sender].authorized, "BureauNotAuthorized");

        TransferApplication storage app = _transfer(id);
        // 0.8 原语义：revert NotTransferBureau(msg.sender, app.toBureau)
        require(app.toBureau == msg.sender, "NotTransferBureau");
        // 0.8 原语义：revert InvalidTransferStatus(id, app.status)
        require(app.status == TransferStatus.AUTHORIZED, "InvalidTransferStatus");

        (uint256 personalFund, uint256 overallFund, uint32 paidMonths) =
            _core().unlockFundsForTransfer(app.employee, app.toCity);

        // 防御性核对：核心合约实际托管的金额必须与申请单一致（不一致则整体回滚）
        // 0.8 原语义：revert EscrowMismatch(id, app.personalFund, personalFund)
        require(
            personalFund == app.personalFund && overallFund == app.overallFund,
            "EscrowMismatch"
        );

        app.paidMonths = paidMonths;
        app.status = TransferStatus.RECEIVED;
        app.receivedAt = uint64(block.timestamp);
        _core().clearActiveTransfer(app.employee);

        emit TransferReceived(
            id,
            app.employee,
            app.fromBureau,
            app.toBureau,
            app.toCity,
            personalFund,
            overallFund,
            paidMonths
        );
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    function getTransferCount() external view returns (uint256) {
        return transfers.length;
    }

    /// @notice 查询转移申请（本人 / 相关社保局 / ADMIN / REGULATOR 审计）
    function getTransfer(uint256 id) external view returns (TransferApplication memory) {
        // 0.8 原语义：revert UnknownTransfer(id)
        require(id < transfers.length, "UnknownTransfer");
        TransferApplication storage app = transfers[id];
        // 0.8 原语义：revert TransferNotVisible(msg.sender, id)
        require(_canReadTransfer(msg.sender, app), "TransferNotVisible");
        return app;
    }

    /// @notice 转入地待办列表（该社保局 / ADMIN / REGULATOR）
    function getInboundTransfers(address bureau, uint256 offset, uint256 limit)
        external
        view
        returns (uint256[] memory ids, uint256 total, uint256 nextOffset)
    {
        _requireBureauReader(bureau);
        return _slice(inboundTransfers[bureau], offset, limit);
    }

    /// @notice 转出地待办列表（该社保局 / ADMIN / REGULATOR）
    function getOutboundTransfers(address bureau, uint256 offset, uint256 limit)
        external
        view
        returns (uint256[] memory ids, uint256 total, uint256 nextOffset)
    {
        _requireBureauReader(bureau);
        return _slice(outboundTransfers[bureau], offset, limit);
    }

    function getBureau(address bureau)
        external
        view
        returns (string memory name, bool authorized, uint64 registeredAt, string[] memory cities)
    {
        Bureau storage b = bureaus[bureau];
        // 0.8 原语义：revert UnknownBureau(bureau)
        require(b.exists, "UnknownBureau");
        return (b.name, b.authorized, b.registeredAt, bureauCities[bureau]);
    }

    function getBureauCount() external view returns (uint256) {
        return bureauList.length;
    }

    /// @notice 社保局名单（ADMIN / REGULATOR / 任一社保局）
    function getBureauList(uint256 offset, uint256 limit)
        external
        view
        returns (address[] memory list, uint256 total, uint256 nextOffset)
    {
        uint8 role = _roleOf(msg.sender);
        // 0.8 原语义：revert NotAuthorized(msg.sender, ROLE_BUREAU)
        require(role == ROLE_ADMIN || role == ROLE_REGULATOR || role == ROLE_BUREAU, "NotAuthorized");
        total = bureauList.length;
        if (limit == 0 || offset >= total) {
            return (new address[](0), total, total);
        }
        uint256 end = offset + limit;
        if (end > total) end = total;
        list = new address[](end - offset);
        for (uint256 i = offset; i < end; ++i) {
            list[i - offset] = bureauList[i];
        }
        nextOffset = end;
    }

    /// @notice 城市对应的社保局地址（公开信息，0 表示该城市尚未绑定）
    function bureauOfCity(string calldata city) external view returns (address) {
        return cityBureau[city];
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    function _core() private view returns (IChainSIPCore) {
        return IChainSIPCore(coreAddress);
    }

    function _roleOf(address account) private view returns (uint8) {
        return _core().roleOf(account);
    }

    function _canReadTransfer(address caller, TransferApplication storage app) private view returns (bool) {
        if (caller == app.employee || caller == app.fromBureau || caller == app.toBureau) return true;
        uint8 role = _roleOf(caller);
        return role == ROLE_ADMIN || role == ROLE_REGULATOR;
    }

    function _transfer(uint256 id) private view returns (TransferApplication storage) {
        // 0.8 原语义：revert UnknownTransfer(id)
        require(id < transfers.length, "UnknownTransfer");
        return transfers[id];
    }

    function _requireBureauReader(address bureau) private view {
        if (msg.sender == bureau) return;
        uint8 role = _roleOf(msg.sender);
        // 0.8 原语义：revert NotAuthorized(msg.sender, ROLE_BUREAU)
        require(role == ROLE_ADMIN || role == ROLE_REGULATOR, "NotAuthorized");
    }

    function _slice(uint256[] storage all, uint256 offset, uint256 limit)
        private
        view
        returns (uint256[] memory ids, uint256 total, uint256 nextOffset)
    {
        total = all.length;
        if (limit == 0 || offset >= total) {
            return (new uint256[](0), total, total);
        }
        uint256 end = offset + limit;
        if (end > total) end = total;
        ids = new uint256[](end - offset);
        for (uint256 i = offset; i < end; ++i) {
            ids[i - offset] = all[i];
        }
        nextOffset = end;
    }
}
