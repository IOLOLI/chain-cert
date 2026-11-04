import http from './http'

// 认证与账号：登录 / 我的信息 / 修改账号信息 / 修改自己的密码。
// 账号链上地址在创建时由后端自动生成并绑定，随 me()/login 的 chainAddress、primaryAddress、wallets[] 返回；
// 手动地址绑定接口（wallet/message、wallet/bind、auth/wallets）前后端均已删除。
// 后端仅保留联调代签接口 POST /api/auth/demo/sign（本前端不调用）。
export const authApi = {
  login: (data) => http.post('/auth/login', data),
  me: () => http.get('/auth/me'),
  /**
   * 修改自己的账号信息（用户名 / 显示名）——任意角色，需已登录。
   * body `{ newUsername, newDisplayName, password }`：两个 new* 字段留空表示不修改，password 为**当前密码**（必填）；
   * 失败时后端返回 400 + 原文提示（当前密码不正确 / 该用户名已被占用 /
   * 用户名只能包含字母、数字和下划线，长度 3-32 位 / 没有需要修改的内容），由 http.js 统一拦截弹出。
   * 成功返回 `{ username, displayName }`，用它刷新本地资料即可，**不需要重新登录**。
   */
  updateProfile: (data) => http.post('/auth/profile', data),
  /**
   * 修改自己的密码（任意角色，需已登录）。
   * body `{ oldPassword, newPassword }`；失败时后端返回 400 + 原文提示（原密码不正确 /
   * 新密码至少 6 位 / 新密码不能与原密码相同），由 http.js 统一拦截弹出，业务层不要再重复弹。
   */
  changePassword: (data) => http.post('/auth/password', data)
}

/** 链信息与通用调用 */
export const chainApi = {
  /* ---------------- 区块链浏览器（只读，ADMIN / REGULATOR） ---------------- */
  explorerOverview: () => http.get('/chain/explorer/overview'),
  explorerBlocks: (params = {}) => http.get('/chain/explorer/blocks', { params }),
  explorerBlock: (id) => http.get(`/chain/explorer/blocks/${id}`),
  explorerTx: (hash) => http.get(`/chain/explorer/transactions/${hash}`),
  explorerAddress: (address) => http.get(`/chain/explorer/addresses/${address}`),
  explorerSearch: (q) => http.get('/chain/explorer/search', { params: { q } }),

  status: () => http.get('/chain/status'),
  topology: () => http.get('/chain/topology'),
  refresh: () => http.post('/chain/refresh'),
  block: () => http.get('/chain/block'),
  stats: () => http.get('/chain/stats'),
  /**
   * 通用只读调用。
   *
   * @param {object} [options] 透传给 axios 的配置；{ silent: true } 表示失败不弹全局提示
   *   （调用方自己降级，例如首页探测旧合约有没有 totalPensionPaid()）
   */
  call: (contract, fn, params = [], options = {}) =>
    http.post('/chain/call', { contract, function: fn, params }, options),
  send: (contract, fn, params = []) => http.post('/chain/send', { contract, function: fn, params })
}

/** ADMIN */
export const adminApi = {
  addSponsor: (data) => http.post('/admin/sponsors', data),
  deposit: (data) => http.post('/admin/deposit', data),
  addPerson: (data) => http.post('/admin/persons', data),
  removeEmployee: (address) => http.post('/admin/employees/remove', { address }),
  setTransferContract: (address) => http.post('/admin/transfer-contract', { address }),
  grantBureauRole: (address) => http.post('/admin/bureaus/grant-role', { address }),

  addRegulator: (data) => http.post('/admin/regulators', data),
  removeRegulator: (address) => http.post('/admin/regulators/remove', { address }),

  addBureau: (data) => http.post('/admin/bureaus', data),
  authorizeBureau: (bureau, authorized) => http.post('/admin/bureaus/authorize', { bureau, authorized }),

  sponsorAccount: (address) => http.get(`/admin/sponsors/${address}`),
  systemStats: () => http.get('/admin/system-stats'),

  createAccount: (data) => http.post('/admin/accounts', data),
  accounts: () => http.get('/admin/accounts'),
  /** 链上已注册的全部城市（含所属社保局），给「缴费单位」的城市下拉用；链未就绪时返回空数组 */
  cities: () => http.get('/admin/cities'),
  updateAccountStatus: (id, status) => http.post(`/admin/accounts/${id}/status`, null, { params: { status } }),
  resetPassword: (id, password) => http.post(`/admin/accounts/${id}/password`, null, { params: { password } }),

  institutionKeys: () => http.get('/admin/institution-keys'),
  createInstitutionKey: (data) => http.post('/admin/institution-keys', data),
  setInstitutionKeyEnabled: (code, enabled) =>
    http.post(`/admin/institution-keys/${code}/enabled`, null, { params: { enabled } }),

  /** 待上链注册 / 待授权的机构（管理员补注册用） */
  pendingInstitutions: () => http.get('/admin/institutions/pending'),
  /** 注册单个机构；失败也是 HTTP 200，要看返回的 ok */
  registerInstitution: (code, city) => http.post(`/admin/institutions/${code}/register`, { city }),

  // ---------------- 政策参数（一期：参数版本化 + 审计） ----------------
  /** 参数字典（按分类分组；仅 ADMIN） */
  policyDefinitions: () => http.get('/admin/policy/definitions'),
  /** 参数详情：字典 + 当前生效版本 + 全部版本时间线 */
  policyParameters: (code, region) => http.get('/admin/policy/parameters', { params: { code, region } }),
  /** 追加版本（未来日期即预置 DRAFT）；ADMIN 限 NATIONAL，BUREAU 限本局城市 */
  savePolicyParameter: (data) => http.post('/admin/policy/parameters', data),
  /** 参数变更审计流水（仅 ADMIN） */
  policyAudit: (code) => http.get('/admin/policy/audit', { params: { code } }),
  /** 当前生效参数清单（含版本号 / 文号 / 是否回落默认） */
  policyEffective: (region) => http.get('/admin/policy/effective', { params: { region } })
}

/** SPONSOR（缴费单位） */
export const sponsorApi = {
  addEmployee: (data) => http.post('/sponsor/employees', data),
  suspend: (employee, reason) => http.post('/sponsor/employees/suspend', { employee, reason }),
  setSalary: (employee, salary) => http.post('/sponsor/employees/salary', { employee, salary }),
  pay: (employee) => http.post('/sponsor/payments', { address: employee }),

  employees: (sponsor, offset = 0, limit = 20) =>
    http.get('/sponsor/employees', { params: { sponsor, offset, limit } }),
  /**
   * 本单位参保职工明细（带姓名 / 状态 / 参保城市 / 缴费基数 / 上次缴费时间）。
   * SPONSOR 角色，单位由登录态推断，**不需要传 sponsor**。
   */
  employeeDetails: (offset = 0, limit = 50) =>
    http.get('/sponsor/employees/detail', { params: { offset, limit } }),

  /** 搜索待接续职工（完整身份证号，或 姓名+身份证后4位） */
  searchRehire: (keyword) => http.get('/sponsor/employees/rehire-search', { params: { keyword } }),

  /** 企业发起接续参保（姓名/年龄/身份证/基数取链上档案） */
  rehire: (address) => http.post('/sponsor/employees/rehire', { address }),
  account: (sponsor) => http.get('/sponsor/account', { params: { sponsor } }),
  payments: (employee, offset = 0, limit = 20) =>
    http.get('/sponsor/payments', { params: { employee, offset, limit } }),
  employeeAccount: (employee) => http.get('/sponsor/employees/account', { params: { employee } }),

  /** 本单位的参保职工账号（层级创建：SPONSOR → EMPLOYEE） */
  accounts: () => http.get('/sponsor/accounts'),
  createAccount: (data) => http.post('/sponsor/accounts', data),

  /**
   * 缴费试算（只读）：按政策参数拆出个人 / 统筹金额，返回基数夹取结果与费率版本摘要。
   * 实际扣款仍以链上 makePayment 的合约常量为准（一期双口径，一致性巡检会告警）。
   */
  paymentTrial: (employee) => http.get('/sponsor/payments/trial', { params: { employee } }),

  /** 缴费试算（按链下生效政策参数计算，合约已移除 quotePayment） */
  quote: (salary) => http.get('/sponsor/payments/quote', { params: { salary } })
}

/** EMPLOYEE（职工） */
export const employeeApi = {
  account: (employee) => http.get('/employee/account', { params: { employee } }),
  payments: (employee, offset = 0, limit = 20) =>
    http.get('/employee/payments', { params: { employee, offset, limit } }),
  transferState: (employee) => http.get('/employee/transfer-state', { params: { employee } }),
  escrow: (employee) => http.get('/employee/escrow', { params: { employee } }),
  applyTransfer: (toCity) => http.post('/employee/transfer/apply', { toCity }),

  /** 当前账号的全部转移申请（直接读链上，倒序；无需输入申请编号） */
  myTransfers: (offset = 0, limit = 20) =>
    http.get('/employee/transfers', { params: { offset, limit } }),
  transfer: (id) => http.get(`/employee/transfer/${id}`),
  bureauOfCity: (city) => http.get('/employee/bureau-of-city', { params: { city } }),
  /** 领取本月养老金（职工本人私钥签名；需已退休，且距上次发放满 30 天） */
  claimPension: () => http.post('/employee/pension/claim')
}

/** BUREAU（社保局） */
export const bureauApi = {
  authorize: (id, approved, reason = '') =>
    http.post('/bureau/transfers/authorize', { id, approved, reason }),
  receive: (id) => http.post(`/bureau/transfers/${id}/receive`),

  inbound: (bureau, offset = 0, limit = 20) =>
    http.get('/bureau/transfers/inbound', { params: { bureau, offset, limit } }),
  outbound: (bureau, offset = 0, limit = 20) =>
    http.get('/bureau/transfers/outbound', { params: { bureau, offset, limit } }),

  transfer: (id) => http.get(`/bureau/transfers/${id}`),
  info: (bureau) => http.get('/bureau/info', { params: { bureau } }),
  list: (offset = 0, limit = 20) => http.get('/bureau/list', { params: { offset, limit } }),
  cityBureau: (city) => http.get('/bureau/city-bureau', { params: { city } }),

  /** 本局下辖的缴费单位账号（层级创建：BUREAU → SPONSOR） */
  accounts: () => http.get('/bureau/accounts'),
  createAccount: (data) => http.post('/bureau/accounts', data),
  /** 本社保局自己的服务城市数组，如 ["A市"]；未注册 / 链未就绪时为空数组 */
  cities: () => http.get('/bureau/cities'),
  /**
   * 给**本局所属**的缴费单位注资（链上 depositSponsor，给单位统筹账户"增加"余额、累计注资），
   * 后端用 ADMIN 私钥代签；`amount` 传字符串。
   * 失败：403 该缴费单位不属于本社保局 / 400 单位未注册或金额非法 / 401 未登录，
   * 由 http.js 统一弹后端 message 原文，业务层不要重复弹。
   */
  depositToSponsor: ({ sponsor, amount }) =>
    http.post('/bureau/sponsors/deposit', { sponsor, amount }),

  /**
   * 辖区总览：**本局服务城市内**的全部单位 / 职工 / 参保记录 / 缴纳记录。
   * 辖区判据取自链上（getBureau(本局).cities），与「缴费单位账号」页的"本局创建的账号"不是一回事：
   * 别人创建、但城市落在本局辖区内的单位也会出现在这里。
   * 后端统一用 ADMIN 身份代读（合约这些 getter 不对 BUREAU 开放），链未就绪时返回空结果 + reason。
   */
  cityOverview: () => http.get('/bureau/city/overview'),
  citySponsors: (offset = 0, limit = 20) =>
    http.get('/bureau/city/sponsors', { params: { offset, limit } }),
  /** sponsor 可选：传单位地址则只看该单位名下的职工 */
  cityEmployees: (params = {}) => http.get('/bureau/city/employees', { params }),
  /** 某职工的参保记录；职工不在本辖区时后端返回 403，由 http.js 统一弹提示 */
  cityInsurance: (employee) => http.get('/bureau/city/insurance', { params: { employee } }),
  cityPayments: (employee, offset = 0, limit = 20) =>
    http.get('/bureau/city/payments', { params: { employee, offset, limit } }),

  /**
   * 养老金支出（缴纳的对称面）：退休办理 + 按月发放 + 发放流水。
   *
   * 链上 retireEmployee 是 onlyAdmin，后端用 ADMIN 私钥代签；辖区边界仍由后端按本局服务城市守，
   * 越界会 403。发放受 30 天周期限制（同一职工 30 天内只能发一次），由合约兜底。
   */
  pensionRules: () => http.get('/bureau/pension/rules'),
  pensionOverview: () => http.get('/bureau/pension/overview'),
  /** 可办理退休的职工（已达 60 岁且累计缴费满 180 个月） */
  pensionEligible: (offset = 0, limit = 20) =>
    http.get('/bureau/pension/eligible', { params: { offset, limit } }),
  /** 已退休人员（每月应发 / 已领月数 / 下次可领） */
  pensionRetirees: (offset = 0, limit = 20) =>
    http.get('/bureau/pension/retirees', { params: { offset, limit } }),
  /**
   * 办理退休。force=true 为「特殊审批」（病退 / 提前退休），跳过年龄与缴费月数门槛，
   * 豁免事实随链上事件 EmployeeRetired 留痕。
   */
  pensionRetire: (employee, force = false) =>
    http.post('/bureau/pension/retire', { employee, force }),
  /** 发放一个月养老金（基础养老金扣城市统筹池、个人账户养老金扣职工个人账户） */
  pensionClaim: (employee) => http.post('/bureau/pension/claim', { employee }),
  pensionRecords: (employee, offset = 0, limit = 10) =>
    http.get('/bureau/pension/records', { params: { employee, offset, limit } }),

  // ---------------- 退休核定单（政策快照 / 复算） ----------------
  /** 核定单分页列表（含快照哈希前 8 位、状态、交易哈希） */
  determinations: (offset = 0, limit = 20) =>
    http.get('/bureau/pension/determinations', { params: { offset, limit } }),
  /** 核定单详情（含政策快照 JSON 展开） */
  determination: (id) => http.get(`/bureau/pension/determinations/${id}`),
  /** 某职工的最新核定单；存量退休人员没有留档时后端补建 LEGACY 行 */
  determinationLatest: (employee) =>
    http.get('/bureau/pension/determinations/latest', { params: { employee } }),
  /** 按核定单 id 复算 */
  recomputeDetermination: (id) => http.post(`/bureau/pension/determinations/${id}/recompute`),
  /** 按职工复算（自动取最新核定单，必要时先补建 LEGACY） */
  recomputeDeterminationByEmployee: (employee) =>
    http.post('/bureau/pension/determinations/recompute', null, { params: { employee } })
}

/** REGULATOR（监管，只读） */
export const regulatorApi = {
  dashboard: () => http.get('/regulator/dashboard'),
  systemStats: () => http.get('/regulator/system-stats'),
  cityPool: (city) => http.get('/regulator/city-pool', { params: { city } }),
  transfer: (id) => http.get(`/regulator/transfers/${id}`),
  transferCount: () => http.get('/regulator/transfers/count'),
  bureauCount: () => http.get('/regulator/bureaus/count'),
  accounts: (offset = 0, limit = 20) => http.get('/regulator/accounts', { params: { offset, limit } }),
  sponsors: (offset = 0, limit = 20) => http.get('/regulator/sponsors', { params: { offset, limit } })
}

/** 链下索引（事件 / 上链日志） */
export const indexApi = {
  events: (params = {}) => http.get('/index/events', { params }),
  txLogs: (params = {}) => http.get('/index/tx-logs', { params })
}
