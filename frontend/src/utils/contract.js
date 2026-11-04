/**
 * 合约返回值映射。
 *
 * 合约里的 struct 经过 ABI 解码后是"按字段顺序的数组"，没有字段名。
 * 这里集中维护字段顺序（必须与 contracts/ 下的 struct 定义一致），
 * 页面用 mapStruct() 拿到带名字的对象；将来合约调字段，只改这里一处。
 */

export const STRUCT_FIELDS = {
  // PensionAccount.InsuranceAccountView
  insuranceAccount: [
    'employee', 'name', 'idNumber', 'employer', 'city', 'sponsor', 'status', 'salary',
    'paymentBase', 'personalBalance', 'overallBalance', 'totalBalance', 'paidMonths',
    'overdueCount', 'registeredAt', 'lastPaymentAt', 'activeTransferId',
    // 养老金（支出侧）：未退休时均为 0（合约 struct 的后 5 个字段，漏了会让前 17 个之外的字段错位）
    'retiredAt', 'lastPensionAt', 'pensionPersonalMonthly', 'pensionClaimedMonths', 'age'
  ],
  // PensionBank.SystemStats
  systemStats: [
    'deposited', 'sponsorPoolTotal', 'paid', 'personalFund', 'overallRights',
    'cityPoolTotal', 'escrowedPersonal', 'escrowedOverall'
  ],
  // getSponsorAccount
  sponsorAccount: ['name', 'city', 'registeredAt', 'poolBalance', 'paidTotal', 'employeeCount'],
  // PensionTransfer.TransferApplication
  transferApplication: [
    'id', 'employee', 'fromCity', 'toCity', 'fromBureau', 'toBureau', 'status',
    'personalFund', 'overallFund', 'paidMonths', 'appliedAt', 'authorizedAt', 'receivedAt', 'reason'
  ],
  // getTransferState
  transferState: ['status', 'activeTransferRef', 'city'],
  // getEscrow
  escrow: ['personalFund', 'overallFund', 'paidMonths'],
  // PensionBank.PaymentRecord
  paymentRecord: ['timestamp', 'paymentBase', 'personalAmount', 'overallAmount', 'sponsor', 'overdue'],
  // PensionTransfer.Bureau
  bureau: ['name', 'authorized', 'registeredAt', 'cities'],
  // PensionRegulator.Regulator
  regulator: ['name', 'agency', 'registeredAt'],
  // ChainSIP.getRegulatorDashboard 前 7 个返回值
  dashboardHead: [
    'systemName', 'version', 'adminCount', 'sponsorCount', 'bureauCount', 'regulatorCount', 'transferCount'
  ]
}

/**
 * 把 CallResult（{ values, rawValues }）映射成命名对象。
 *
 * 合约里 struct 是「一个返回值」，后端拿到的 values 形如 `[[f0, f1, ...]]`；
 * 直接按 `values[index]` 取会整体错位（姓名 / 城市 / 余额全部读不到，表现为一片空白）。
 * 因此单元素且该元素是数组时先拍平；已经扁平的返回值（如 getSponsorAccount）原样使用。
 */
export function mapStruct(callResult, fields) {
  const result = {}
  let values = (callResult && callResult.values) || []
  if (values.length === 1 && Array.isArray(values[0])) {
    values = values[0]
  }
  fields.forEach((field, index) => {
    result[field] = values[index]
  })
  return result
}

/** 分页列表型返回值：合约统一返回 (数组, total, nextOffset) */
export function mapPagedList(callResult, itemFields) {
  const values = (callResult && callResult.values) || []
  const raw = values[0] || []
  const list = Array.isArray(raw)
    ? raw.map((item) => {
        if (!itemFields || !Array.isArray(item)) return item
        const obj = {}
        itemFields.forEach((field, index) => {
          obj[field] = item[index]
        })
        return obj
      })
    : []
  return { list, total: values[1] ?? 0, nextOffset: values[2] ?? 0, raw: callResult }
}
