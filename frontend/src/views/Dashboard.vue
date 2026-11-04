<template>
  <div class="dash" v-loading="loading">
    <div class="page-head">
      <h1 class="page-title">首页概览</h1>
      <p class="page-desc">
        链上是权威账本（角色、账户、余额、基金池），链下是认证与索引。以下数字全部实时取自合约状态，链下不参与任何计算。
      </p>
    </div>

    <el-alert v-if="chainStatus && !chainStatus.ready" type="warning" show-icon :closable="false" class="mb-16"
      title="链未就绪">
      <div class="chain-address">{{ chainStatus.message }}</div>
      <div class="text-muted mt-8">
        核心合约：{{ chainStatus.coreContract || '未配置' }} ｜ 转移合约：{{ chainStatus.transferContract || '未配置' }}
        ｜ SDK 配置：{{ chainStatus.sdkConfigFile }}
      </div>
      <el-button size="small" type="primary" class="mt-8" :loading="loading" @click="refreshChain">
        重新初始化链网关
      </el-button>
    </el-alert>

    <!-- ---------------------------------- 账本摘要：el-statistic ---------------------------------- -->
    <el-row :gutter="14" class="kpi-row">
      <el-col :xs="12" :sm="12" :md="6" :lg="6">
        <el-card shadow="never" class="kpi-card">
          <div class="kpi-cap">
            <span>当前区块高度</span>
            <el-icon><Box /></el-icon>
          </div>
          <el-statistic :value="block?.blockNumber ?? ''" :formatter="amountText" />
          <div class="kpi-note">
            {{ block?.transactionCount ?? 0 }} 笔交易 · {{ formatTime(block?.blockTimestamp) }}
          </div>
        </el-card>
      </el-col>

      <el-col :xs="12" :sm="12" :md="6" :lg="6">
        <el-card shadow="never" class="kpi-card">
          <div class="kpi-cap">
            <span>累计注资</span>
            <el-icon><Money /></el-icon>
          </div>
          <el-statistic :value="stats.deposited ?? ''" :formatter="amountText" />
          <div class="kpi-note">单位统筹账户</div>
        </el-card>
      </el-col>

      <el-col :xs="12" :sm="12" :md="6" :lg="6">
        <el-card shadow="never" class="kpi-card">
          <div class="kpi-cap">
            <span>累计已缴</span>
            <el-icon><TrendCharts /></el-icon>
          </div>
          <el-statistic :value="stats.paid ?? ''" :formatter="amountText" />
          <div class="kpi-note">{{ stats.paid > 0 ? '按缴费事件累计' : '尚无缴费' }}</div>
        </el-card>
      </el-col>

      <el-col :xs="12" :sm="12" :md="6" :lg="6">
        <el-card shadow="never" class="kpi-card is-state">
          <div class="kpi-cap">
            <span>链网关</span>
            <el-icon><Connection /></el-icon>
          </div>
          <div class="kpi-state" :class="{ 'is-ok': chainStatus?.ready }">
            <span class="dot"></span>
            {{ chainStatus?.ready ? '已连接' : '未连接' }}
          </div>
          <div class="kpi-note">
            group {{ chainStatus?.groupId ?? '-' }} ·
            {{ topology ? `${topology.sealerCount} 共识 + ${topology.observerCount} 观察` : `${(chainStatus?.peers || []).length} 个网关` }}
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="14">
      <el-col :xs="24" :sm="24" :md="16" :lg="16">
        <!-- ---------------------------------- 对账：本项目的主证据 ---------------------------------- -->
        <el-card shadow="never" class="panel">
          <template #header>
            <h2 class="section-title">资金对账</h2>
            <el-tag :type="allBalanced ? 'success' : 'danger'" effect="light" size="small">
              {{ allBalanced ? '三段恒等式全部成立' : '存在不平的恒等式' }}
            </el-tag>
          </template>

          <div v-for="(item, index) in identities" :key="item.label" class="id-row">
            <div class="id-body">
              <p class="id-label">{{ item.label }}</p>
              <p class="id-math">
                <template v-for="(token, i) in item.tokens" :key="i">
                  <span v-if="token.type === 'num'" class="num">{{ token.value }}</span>
                  <span v-else class="id-op">{{ token.value }}</span>
                </template>
              </p>
            </div>
            <el-tag class="id-seal" :type="item.ok ? 'success' : 'danger'" effect="light"
              :style="{ '--d': 120 + index * 90 + 'ms' }" data-stamp>
              {{ item.ok ? '平' : '不平' }}
            </el-tag>
          </div>

          <p class="panel-note">
            恒等式由 <span class="mono">SystemStats</span> 的八个字段在现场相减得出（已发养老金的统筹部分由
            <span class="mono">totalPensionPaid</span> 与记账口径推导）：左边与右边不等，说明账本被绕过合约改动过。
          </p>
        </el-card>

        <!-- ---------------------------------- 资金构成：环形图 ---------------------------------- -->
        <el-card shadow="never" class="panel">
          <template #header>
            <h2 class="section-title">已缴资金的去向</h2>
            <span class="panel-meta">累计已缴 {{ formatAmount(stats.paid) }}（平台全量）</span>
          </template>

          <div class="split" role="group" :aria-label="splitAria">
            <div class="split-chart">
              <svg class="donut" viewBox="0 0 140 140" role="img" :aria-label="splitAria">
                <circle class="donut-track" cx="70" cy="70" r="54" fill="none" stroke-width="20" />
                <circle v-for="seg in donutSegments" :key="seg.key" class="donut-seg" cx="70" cy="70" r="54"
                  fill="none" stroke-width="20" :stroke="seg.color" :stroke-dasharray="seg.dash"
                  :transform="seg.transform" />
                <text class="donut-center" x="70" y="66" text-anchor="middle">累计已缴</text>
                <text class="donut-center-num" x="70" y="83" text-anchor="middle">{{ formatAmount(stats.paid) }}</text>
              </svg>
            </div>

            <div class="split-legend">
              <div v-for="piece in split" :key="piece.key" class="split-row">
                <span class="legend-dot" :style="{ background: piece.color }"></span>
                <span class="split-label">{{ piece.label }}</span>
                <span class="split-value num">{{ formatAmount(piece.value) }}</span>
                <span class="split-percent num">{{ piece.percentText }}</span>
              </div>
            </div>
          </div>

          <el-descriptions class="balance-desc" :column="2" border size="small">
            <el-descriptions-item v-for="row in balances" :key="row.label" :label="row.label">
              <span class="num">{{ formatAmount(row.value) }}</span>
            </el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-col>

      <!-- ---------------------------------- 侧栏：身份与链 ---------------------------------- -->
      <el-col :xs="24" :sm="24" :md="8" :lg="8">
        <el-card shadow="never" class="panel">
          <template #header>
            <h2 class="section-title">当前账号</h2>
            <el-button link type="primary" size="small" @click="loadAll">刷新</el-button>
          </template>

          <el-descriptions class="kv" :column="1" size="small">
            <el-descriptions-item label="账号">{{ user.profile?.username }}</el-descriptions-item>
            <el-descriptions-item label="链下角色">{{ roleName(user.role) }}</el-descriptions-item>
            <el-descriptions-item label="链上角色">
              <el-tag v-if="user.onChainRole === 'NONE'" :type="user.role === 'EMPLOYEE' ? 'info' : 'warning'"
                effect="light" size="small">
                {{ user.role === 'EMPLOYEE' ? '无需链上角色' : '未注册' }}
              </el-tag>
              <el-tag v-else-if="user.onChainRole" type="success" effect="light" size="small">{{ user.onChainRole }}</el-tag>
              <el-tooltip v-else content="链网关未就绪，或该地址还没有链上角色" placement="top">
                <span class="text-muted">—</span>
              </el-tooltip>
            </el-descriptions-item>
            <el-descriptions-item label="机构账户编码">
              <span class="mono">{{ user.institutionCode || '—' }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="链上地址">
              <el-tooltip v-if="user.chainAddress" :content="user.chainAddress" placement="bottom" :show-after="300">
                <button class="addr-btn" type="button" @click="copy(user.chainAddress)">
                  {{ shortAddress(user.chainAddress, 10, 8) }}
                </button>
              </el-tooltip>
              <span v-else class="text-muted">未返回</span>
            </el-descriptions-item>
          </el-descriptions>

          <p class="panel-note">链下角色只决定界面可见性；能不能动账，由合约按交易签名地址判定。</p>
        </el-card>

        <el-card shadow="never" class="panel">
          <template #header>
            <h2 class="section-title">链上合约</h2>
            <span class="panel-meta">group {{ chainStatus?.groupId ?? '-' }}</span>
          </template>

          <el-descriptions class="kv" :column="1" size="small">
            <el-descriptions-item label="核心合约">
              <el-tooltip :content="chainStatus?.coreContract || '未配置'" placement="bottom" :show-after="300">
                <button class="addr-btn" type="button" @click="copy(chainStatus?.coreContract)">
                  {{ shortAddress(chainStatus?.coreContract, 10, 8) || '未配置' }}
                </button>
              </el-tooltip>
            </el-descriptions-item>
            <el-descriptions-item label="转移合约">
              <el-tooltip :content="chainStatus?.transferContract || '未配置'" placement="bottom" :show-after="300">
                <button class="addr-btn" type="button" @click="copy(chainStatus?.transferContract)">
                  {{ shortAddress(chainStatus?.transferContract, 10, 8) || '未配置' }}
                </button>
              </el-tooltip>
            </el-descriptions-item>
            <el-descriptions-item label="后端签名地址">
              <el-tooltip :content="chainStatus?.adminAddress || '未返回'" placement="bottom" :show-after="300">
                <button class="addr-btn" type="button" @click="copy(chainStatus?.adminAddress)">
                  {{ shortAddress(chainStatus?.adminAddress, 10, 8) || '未返回' }}
                </button>
              </el-tooltip>
            </el-descriptions-item>
            <el-descriptions-item label="地址一致性">
              <el-tag :type="adminMatches ? 'success' : 'warning'" effect="light" size="small">
                {{ adminMatches ? '一致' : '待核对' }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="合约版本">
              <el-tooltip :content="contractCompat?.detail || ''" placement="bottom" :show-after="300">
                <el-tag :type="compatBad ? 'warning' : 'success'" effect="light" size="small">
                  {{ compatText }}
                </el-tag>
              </el-tooltip>
            </el-descriptions-item>
          </el-descriptions>

          <p class="panel-note">
            {{ adminMatches
              ? '后端签名地址已在链上注册为 ADMIN，注册机构、注资等管理交易可用。'
              : '后端签名地址必须与链上注册的 ADMIN 是同一个，否则管理交易会被合约回滚。' }}
          </p>
        </el-card>

        <el-card shadow="never" class="panel">
          <template #header>
            <h2 class="section-title">最新区块</h2>
          </template>

          <el-descriptions class="kv" :column="1" size="small">
            <el-descriptions-item label="区块号">
              <span class="num">{{ block?.blockNumber ?? '—' }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="区块哈希">
              <el-tooltip :content="block?.blockHash || '—'" placement="bottom" :show-after="300">
                <button class="addr-btn" type="button" @click="copy(block?.blockHash)">
                  {{ shortAddress(block?.blockHash, 12, 10) || '—' }}
                </button>
              </el-tooltip>
            </el-descriptions-item>
            <el-descriptions-item label="最新交易">
              <el-tooltip :content="block?.latestTransactionHash || '本区块无交易'" placement="bottom" :show-after="300">
                <button class="addr-btn" type="button" @click="copy(block?.latestTransactionHash)">
                  {{ shortAddress(block?.latestTransactionHash, 12, 10) || '本区块无交易' }}
                </button>
              </el-tooltip>
            </el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { bureauApi, chainApi } from '../api'
import { useUserStore } from '../stores/user'
import { copyText, formatAmount, formatTime, roleName, shortAddress } from '../utils/format'

const user = useUserStore()
const loading = ref(false)

/** 金额/区块号的展示格式（el-statistic 的 formatter） */
const amountText = (value) => formatAmount(value)

/** 已缴资金各去向的分段色：走 CSS 变量，浅 / 深主题各一套，环形图与图例共用 */
const SEG_VAR = {
  personal: 'var(--seg-personal)',
  city: 'var(--seg-city)',
  escrow: 'var(--seg-escrow)',
  pension: 'var(--seg-pension)'
}

const chainStatus = ref(null)
/** 共识拓扑：sealer 出块，observer（监管节点）只同步复核、不投票 */
const topology = ref(null)
const block = ref(null)
const stats = ref({})

/**
 * 累计已发放养老金（合约 totalPensionPaid，单独一个 getter）。
 * 它是"缴费去向"的第四个出口：没有它，缴费总额与三个余额科目之和会差一个"已发放"的缺口。
 * 旧合约（未升级）没有这个函数，调用失败时按 0 处理，页面照常显示。
 */
const pensionPaid = ref(0)

/** 后端 ADMIN 签名地址与链上 ADMIN 是否对得上（这俩不一致是链上操作回滚最常见的原因） */
const adminMatches = computed(() => {
  const role = chainStatus.value?.adminOnChainRole
  return !!role && String(role).toUpperCase().includes('ADMIN')
})

/**
 * 链上合约版本自检（后端探针调 totalPensionPaid() 得出）。
 * 这是"改了合约忘了重新部署"的现场证据：pensionReady=false 说明链上还是旧字节码，
 * 页面上一切养老金操作都会以 Revert 回滚——与其让用户去猜 code=2002，不如直接把结论摆出来。
 * null 表示链未就绪、暂时判断不了，此时不要显示成"待重新部署"。
 */
const contractCompat = computed(() => chainStatus.value?.contractCompat || null)

const compatText = computed(() => {
  const compat = contractCompat.value
  if (!compat || compat.pensionReady === null || compat.pensionReady === undefined) return '待检测'
  return compat.pensionReady ? '已升级（含养老金）' : '待重新部署'
})

const compatBad = computed(() => contractCompat.value?.pensionReady === false)

async function copy(text) {
  if (!text) return
  const ok = await copyText(text)
  if (ok) {
    ElMessage.success('地址已复制')
  } else {
    ElMessage.warning('复制失败，请手动选中复制')
  }
}

/**
 * /api/chain/stats 返回的是结构体的按序取值，这里按合约 SystemStats 的字段顺序映射：
 * deposited, sponsorPoolTotal, paid, personalFund, overallRights, cityPoolTotal,
 * escrowedPersonal, escrowedOverall
 */
const STAT_FIELDS = [
  'deposited',
  'sponsorPoolTotal',
  'paid',
  'personalFund',
  'overallRights',
  'cityPoolTotal',
  'escrowedPersonal',
  'escrowedOverall'
]

/**
 * 取出 stats 的按序数值数组。
 * 后端的 values 可能被**单 tuple 多包一层**（`[[100000, 97300, ...]]`），
 * 也可能是字符串形式的 JSON；这里统一解包成一维数组，其它情况保持原样。
 */
function unwrapStats(raw) {
  const parse = (value) => {
    if (typeof value === 'string') {
      try {
        return JSON.parse(value)
      } catch (e) {
        return null
      }
    }
    return value
  }
  let values = parse(raw && raw.values)
  if (!Array.isArray(values) || !values.length) {
    // values 缺失 / 为空时退回 rawValues（同样可能是字符串）
    const fallback = parse(raw && raw.rawValues)
    values = Array.isArray(fallback) ? fallback : []
  }
  // 单 tuple 包了一层：[[...]] → [...]
  if (values.length === 1 && Array.isArray(values[0])) {
    return values[0]
  }
  return values
}

function big(value) {
  try {
    return BigInt(value === undefined || value === null || value === '' ? 0 : value)
  } catch (e) {
    return 0n
  }
}

/** 三段恒等式：左边、运算符、右边，按账页的写法摊平成一串记号 */
const identities = computed(() => {
  const s = stats.value
  const deposited = big(s.deposited)
  const pool = big(s.sponsorPoolTotal)
  const paid = big(s.paid)
  const personal = big(s.personalFund)
  const cityPool = big(s.cityPoolTotal)
  const escrowP = big(s.escrowedPersonal)
  const escrowO = big(s.escrowedOverall)
  const rights = big(s.overallRights)
  const pensionOut = big(pensionPaid.value)

  // 已发养老金的统筹（基础养老金）部分。链上只有发放合计 totalPensionPaid，
  // 但权益记账（overallRights）是历史累计口径、不因发放减少，而城市池会被发放支取，
  // 因此"城市池 + 在途统筹"天然小于权益，差额就是历次发放里的统筹部分。它可由链上字段推导：
  //   累计统筹缴费（含在途）= rights + escrowO
  //   累计个人缴费（含在途）= paid − 累计统筹缴费
  //   已发个人部分 = 累计个人缴费 − personalFund − escrowP
  //   已发统筹部分 = totalPensionPaid − 已发个人部分
  const personalPaid = paid - rights - escrowO - personal - escrowP
  const pensionOverall = pensionOut - personalPaid

  const tokens = (left, rightParts) => {
    const list = [{ type: 'num', value: formatAmount(left.toString()) }]
    list.push({ type: 'op', value: '=' })
    rightParts.forEach((part, index) => {
      if (index > 0) list.push({ type: 'op', value: '+' })
      list.push({ type: 'num', value: formatAmount(part.value.toString()) })
    })
    return list
  }

  return [
    {
      label: '累计注资 = 单位统筹余额 + 累计已缴',
      tokens: tokens(deposited, [{ value: pool }, { value: paid }]),
      ok: deposited === pool + paid
    },
    {
      label: '累计已缴 = 个人账户 + 城市基金池 + 在途托管 + 已发养老金',
      tokens: tokens(paid, [
        { value: personal },
        { value: cityPool },
        { value: escrowP },
        { value: escrowO },
        { value: pensionOut }
      ]),
      ok: paid === personal + cityPool + escrowP + escrowO + pensionOut
    },
    {
      label: '城市基金池 + 在途统筹 + 已发养老金（统筹） = 职工统筹权益',
      tokens: tokens(cityPool + escrowO + pensionOverall, [{ value: rights }]),
      ok: cityPool + escrowO + pensionOverall === rights
    }
  ]
})

const allBalanced = computed(() => identities.value.every((item) => item.ok))

/** 已缴资金的三条去向（个人 / 城市池 / 在途），按金额占比画一条带 */
const split = computed(() => {
  const s = stats.value
  const pieces = [
    { key: 'personal', label: '职工个人账户', value: Number(big(s.personalFund)) },
    { key: 'city', label: '城市统筹基金池', value: Number(big(s.cityPoolTotal)) },
    { key: 'escrow', label: '转移在途托管', value: Number(big(s.escrowedPersonal) + big(s.escrowedOverall)) },
    { key: 'pension', label: '已发放养老金', value: Number(big(pensionPaid.value)) }
  ]
  const total = pieces.reduce((sum, item) => sum + item.value, 0)
  return pieces.map((item) => {
    const ratio = total > 0 ? item.value / total : 0
    return {
      ...item,
      color: SEG_VAR[item.key],
      percent: total > 0 ? Math.max(ratio * 100, item.value > 0 ? 1.5 : 0) : 0,
      percentText: total > 0 ? Math.round(ratio * 100) + '%' : '—'
    }
  })
})

/** 环形图几何：把各去向占比折算成 SVG 的 stroke-dasharray 与旋转角（顶起、顺时针） */
const donutSegments = computed(() => {
  const R = 54
  const C = 2 * Math.PI * R
  const GAP = 3
  const total = split.value.reduce((sum, item) => sum + item.value, 0)
  let acc = 0
  return split.value.map((piece) => {
    const frac = total > 0 ? piece.value / total : 0
    const len = frac * C
    const startAngle = acc * 360
    acc += frac
    return {
      ...piece,
      dash: `${Math.max(len - GAP, 0)} ${C}`,
      transform: `rotate(${startAngle - 90} 70 70)`
    }
  })
})

const splitAria = computed(() =>
  split.value.map((item) => `${item.label} ${formatAmount(item.value)}`).join('，')
)

/** 六个余额科目：对账用的完整口径 */
const balances = computed(() => {
  const s = stats.value
  return [
    { label: '单位统筹账户余额合计', value: s.sponsorPoolTotal },
    { label: '职工个人账户资金合计', value: s.personalFund },
    { label: '城市统筹基金池合计', value: s.cityPoolTotal },
    { label: '职工统筹权益合计', value: s.overallRights },
    { label: '在途托管（个人）', value: s.escrowedPersonal },
    { label: '在途托管（统筹）', value: s.escrowedOverall }
  ]
})

async function loadAll() {
  loading.value = true
  try {
    chainStatus.value = await chainApi.status()
    try {
      topology.value = await chainApi.topology()
    } catch (e) {
      topology.value = null
    }
    try {
      block.value = await chainApi.block()
    } catch (e) {
      block.value = null
    }
    try {
      const raw = await chainApi.stats()
      const values = unwrapStats(raw)
      const mapped = {}
      STAT_FIELDS.forEach((field, index) => {
        // 缺失的字段补 0：页面上按"0"展示，而不是 '-' 或对象
        mapped[field] = values[index] ?? 0
      })
      stats.value = mapped
    } catch (e) {
      stats.value = {}
    }
    try {
      // silent：旧合约没有这个 getter 是预期内的，由下面的 catch 按 0 处理，不要再弹全局错误
      const raw = await chainApi.call('ChainSIP', 'totalPensionPaid', [], { silent: true })
      const values = unwrapStats(raw)
      pensionPaid.value = values.length ? values[0] : 0
    } catch (e) {
      // 旧合约没有这个 getter：按 0 处理，不打断首页
      pensionPaid.value = 0
    }
  } finally {
    loading.value = false
  }
}

async function refreshChain() {
  loading.value = true
  try {
    await chainApi.refresh()
    await loadAll()
  } finally {
    loading.value = false
  }
}

onMounted(loadAll)
</script>

<style scoped>
.dash {
  padding-bottom: 8px;
}

/* ------------------------------------------------ KPI */

.kpi-row {
  margin-bottom: 14px;
}

.kpi-card {
  background: var(--surface);
}

:deep(.kpi-card .el-card__body) {
  padding: 14px 16px 13px;
  display: flex;
  flex-direction: column;
  min-height: 106px;
}

.kpi-cap {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  font-size: 12px;
  color: var(--ink-2);
  margin-bottom: 2px;
}

.kpi-cap .el-icon {
  font-size: 14px;
  opacity: 0.4;
}

:deep(.kpi-card .el-statistic) {
  margin: 0;
}

.kpi-note {
  font-size: 11.5px;
  color: var(--ink-3);
  margin-top: auto;
  padding-top: 3px;
  line-height: 1.5;
}

.kpi-state {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 20px;
  font-weight: 600;
  line-height: 1.35;
  color: var(--alert);
  letter-spacing: -0.01em;
}

.kpi-state.is-ok {
  color: var(--ok);
}

.kpi-state .dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: currentColor;
  box-shadow: 0 0 0 3px rgba(179, 38, 30, 0.12);
}

.kpi-state.is-ok .dot {
  box-shadow: 0 0 0 3px rgba(14, 122, 88, 0.14);
}

/* ------------------------------------------------ 面板 */

.panel {
  margin-bottom: 14px;
}

:deep(.panel > .el-card__header) {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 13px 18px;
}

:deep(.panel > .el-card__body) {
  padding: 6px 18px 16px;
}

.section-title {
  margin: 0;
}

.panel-meta {
  font-size: 11.5px;
  color: var(--ink-3);
}

/* 恒等式 */

.id-row {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  padding: 13px 0;
  border-bottom: 1px solid var(--hair-2);
}

.id-row:last-of-type {
  border-bottom: 0;
}

.id-body {
  flex: 1;
  min-width: 0;
}

.id-label {
  margin: 0 0 5px;
  font-size: 11.5px;
  color: var(--ink-2);
}

.id-math {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  letter-spacing: -0.01em;
  font-variant-numeric: tabular-nums;
  color: var(--ink);
  line-height: 1.4;
  /* 长数字令牌在窄容器里允许换行，避免公式被卡片裁切 */
  overflow-wrap: anywhere;
}

.id-op {
  margin: 0 7px;
  font-weight: 400;
  color: var(--ink-3);
}

:deep(.id-seal) {
  flex: none;
  margin-bottom: 2px;
  height: 30px;
  line-height: 28px;
  border-radius: 8px;
  font-size: 12.5px;
  font-weight: 700;
  letter-spacing: 0.02em;
  padding: 0 10px;
}

/* 资金去向：环形图 + 图例 */

.split {
  display: flex;
  align-items: center;
  gap: 22px;
  padding: 4px 0 8px;
}

.split-chart {
  flex: none;
  width: 150px;
}

.donut {
  display: block;
  width: 150px;
  height: 150px;
}

.donut-track {
  stroke: var(--hair-2);
}

.donut-seg {
  transition: stroke-dasharray 0.4s ease, transform 0.4s ease, stroke 0.2s ease;
}

.donut-center {
  fill: var(--ink-3);
  font-size: 11px;
  letter-spacing: 0.02em;
}

.donut-center-num {
  fill: var(--ink);
  font-size: 13px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.split-legend {
  flex: 1;
  min-width: 0;
}

.split-row {
  display: grid;
  grid-template-columns: 10px minmax(96px, 1fr) auto 44px;
  gap: 10px;
  align-items: center;
  padding: 7px 0;
  font-size: 12.5px;
}

.legend-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.split-label {
  color: var(--ink-2);
  white-space: nowrap;
}

.split-value {
  text-align: right;
  font-weight: 600;
  color: var(--ink);
}

.split-percent {
  text-align: right;
  font-size: 11.5px;
  color: var(--ink-3);
}

.balance-desc {
  margin-top: 14px;
}

:deep(.balance-desc .el-descriptions__label) {
  width: 46%;
  background: var(--tone) !important;
  color: var(--ink-2) !important;
  font-weight: 500 !important;
  font-size: 12px !important;
}

:deep(.balance-desc .el-descriptions__content) {
  font-weight: 600;
  font-size: 13px !important;
}

/* 键值表 */

:deep(.kv .el-descriptions__cell) {
  padding: 8px 0 !important;
  border-bottom: 1px solid var(--hair-2);
}

:deep(.kv .el-descriptions__label) {
  width: 104px;
  color: var(--ink-2) !important;
  font-weight: 400 !important;
  font-size: 12.5px !important;
}

:deep(.kv .el-descriptions__content) {
  text-align: right;
  font-size: 12.5px !important;
}

:deep(.kv .el-descriptions__body) {
  background: transparent;
}

.addr-btn {
  border: 0;
  background: transparent;
  padding: 0;
  font: inherit;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  color: var(--ink-2);
  cursor: pointer;
  transition: color 0.16s ease;
}

.addr-btn:hover {
  color: var(--accent);
  text-decoration: underline;
  text-underline-offset: 2px;
}

/* 小屏 */

@media (max-width: 900px) {
  .split {
    flex-direction: column;
    align-items: stretch;
    gap: 6px;
  }

  .split-chart {
    width: 150px;
    margin: 0 auto;
  }

  .split-row {
    grid-template-columns: 10px minmax(0, 1fr) auto 44px;
  }

  /* 移动端资金对账公式：降字号并让运算符收窄，配合 overflow-wrap 完整展示三段恒等式 */
  .id-math {
    font-size: 15px;
    line-height: 1.5;
  }

  .id-op {
    margin: 0 4px;
  }
}
</style>
