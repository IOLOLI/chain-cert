/** 链上数据格式化工具 */

/** 链上金额（uint256 的十进制字符串/数字）→ 带千分位的整数字符串 */
export function formatAmount(value) {
  if (value === null || value === undefined || value === '') return '-'
  const text = String(value)
  if (!/^-?\d+$/.test(text)) return text
  return text.replace(/\B(?=(\d{3})+(?!\d))/g, ',')
}

/** 秒级或毫秒级时间戳 / ISO 字符串 → yyyy-MM-dd HH:mm:ss */
export function formatTime(value) {
  if (value === null || value === undefined || value === '' || value === '0') return '-'
  let ms
  if (typeof value === 'number') {
    ms = value > 1e12 ? value : value * 1000
  } else if (/^0x[0-9a-fA-F]+$/.test(String(value))) {
    // FISCO BCOS 的区块时间戳是十六进制数：超过 1e12 已经是毫秒，否则按秒换算
    const n = parseInt(String(value), 16)
    ms = n > 1e12 ? n : n * 1000
  } else if (/^\d+$/.test(String(value))) {
    const n = Number(value)
    ms = n > 1e12 ? n : n * 1000
  } else {
    ms = Date.parse(value)
  }
  if (!ms || Number.isNaN(ms)) return String(value)
  const d = new Date(ms)
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(
    d.getMinutes()
  )}:${pad(d.getSeconds())}`
}

/** 地址缩写：0x1234…abcd */
export function shortAddress(address, head = 8, tail = 6) {
  if (!address) return '-'
  const text = String(address)
  if (text.length <= head + tail) return text
  return `${text.slice(0, head)}…${text.slice(-tail)}`
}

export function isAddress(value) {
  return typeof value === 'string' && /^0x[0-9a-fA-F]{40}$/.test(value)
}

/** 合约 EmployeeStatus：0 NONE 1 ACTIVE 2 SUSPENDED 3 TRANSFERRED 4 REMOVED 5 RETIRED */
const EMPLOYEE_STATUS = ['未建档', '正常参保', '已停缴', '已转出待参保', '已注销', '已退休']
export function employeeStatusName(value) {
  if (value === null || value === undefined) return '-'
  return EMPLOYEE_STATUS[Number(value)] || `未知(${value})`
}
export function employeeStatusType(value) {
  return ['info', 'success', 'warning', 'warning', 'danger', 'primary'][Number(value)] || 'info'
}

/** 合约 TransferStatus：0 NONE 1 PENDING 2 AUTHORIZED 3 RECEIVED 4 REJECTED */
const TRANSFER_STATUS = ['无', '待放行', '已放行(资金托管中)', '已接收', '已驳回']
export function transferStatusName(value) {
  if (value === null || value === undefined) return '-'
  return TRANSFER_STATUS[Number(value)] || `未知(${value})`
}
export function transferStatusType(value) {
  return ['info', 'warning', 'primary', 'success', 'danger'][Number(value)] || 'info'
}

/** 角色展示 */
const ROLE_NAMES = {
  ADMIN: '平台管理员',
  SPONSOR: '缴费单位',
  BUREAU: '社保局',
  REGULATOR: '监管方',
  EMPLOYEE: '参保职工'
}
export function roleName(role) {
  if (!role) return '-'
  return ROLE_NAMES[role] || `未知(${role})`
}
export function roleTagType(role) {
  return { ADMIN: 'danger', SPONSOR: 'primary', BUREAU: 'success', REGULATOR: 'warning', EMPLOYEE: 'info' }[
    role
  ] || 'info'
}

/**
 * 复制文本到剪贴板。
 * 优先用异步 Clipboard API（要求 HTTPS 或 localhost）；
 * 非安全上下文（如用局域网 IP 访问演示环境）下退回到 textarea + execCommand。
 * 返回是否复制成功。
 */
export async function copyText(text) {
  const value = text === null || text === undefined ? '' : String(text)
  if (!value) return false
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(value)
      return true
    }
  } catch (e) {
    /* 继续走下面的兜底方案 */
  }
  try {
    const area = document.createElement('textarea')
    area.value = value
    area.style.position = 'fixed'
    area.style.top = '-1000px'
    area.style.opacity = '0'
    document.body.appendChild(area)
    area.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(area)
    return ok
  } catch (e) {
    return false
  }
}

/** 把链上返回的地址数组（可能是字符串或嵌套数组）拍平 */
export function toAddressList(value) {
  if (!value) return []
  if (Array.isArray(value)) return value.flat().filter((v) => typeof v === 'string')
  return [String(value)]
}
