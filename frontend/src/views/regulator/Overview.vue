<template>
  <div class="overview" v-loading="loading">
    <PageHeader title="监管大屏"
      desc="监管是纯只读角色：可以查全平台账户、流水、资金与转移记录，任何写操作都会被合约按 msg.sender 拒绝。本页所有数字来自合约 view 调用，链下不参与计算。">
      <el-button :icon="Refresh" :loading="loading" @click="loadAll">刷新</el-button>
    </PageHeader>

    <el-row :gutter="14" class="kpi-row">
      <el-col :xs="12" :sm="12" :md="6" :lg="6">
        <el-card shadow="never" class="kpi-card">
          <div class="kpi-cap"><span>参保单位</span><el-icon><OfficeBuilding /></el-icon></div>
          <el-statistic :value="board.sponsorCount ?? ''" :formatter="amountText" />
          <div class="kpi-note">已链上注册的缴费单位</div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="12" :md="6" :lg="6">
        <el-card shadow="never" class="kpi-card">
          <div class="kpi-cap"><span>社保局</span><el-icon><DataLine /></el-icon></div>
          <el-statistic :value="board.bureauCount ?? ''" :formatter="amountText" />
          <div class="kpi-note">按城市提供经办服务</div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="12" :md="6" :lg="6">
        <el-card shadow="never" class="kpi-card">
          <div class="kpi-cap"><span>监管账户</span><el-icon><DataAnalysis /></el-icon></div>
          <el-statistic :value="board.regulatorCount ?? ''" :formatter="amountText" />
          <div class="kpi-note">只读审计身份</div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="12" :md="6" :lg="6">
        <el-card shadow="never" class="kpi-card">
          <div class="kpi-cap"><span>转移申请</span><el-icon><Switch /></el-icon></div>
          <el-statistic :value="board.transferCount ?? ''" :formatter="amountText" />
          <div class="kpi-note">累计提交的跨城市转移</div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="14">
      <el-col :xs="24" :sm="24" :md="16" :lg="16">
        <el-card shadow="never" class="panel">
          <template #header>
            <h2 class="section-title">资金对账</h2>
            <el-tag :type="allBalanced ? 'success' : 'danger'" effect="light" size="small">
              {{ allBalanced ? '三段恒等式全部成立' : '存在不平的恒等式' }}
            </el-tag>
          </template>

          <div v-for="item in identities" :key="item.label" class="id-row">
            <div class="id-body">
              <p class="id-label">{{ item.label }}</p>
              <p class="id-math">
                <template v-for="(token, i) in item.tokens" :key="i">
                  <span v-if="token.type === 'num'" class="num">{{ token.value }}</span>
                  <span v-else class="id-op">{{ token.value }}</span>
                </template>
              </p>
            </div>
            <el-tag class="id-seal" :type="item.ok ? 'success' : 'danger'" effect="light">
              {{ item.ok ? '平' : '不平' }}
            </el-tag>
          </div>

          <p class="panel-note">
            恒等式来自 <span class="mono">getSystemStats</span> 的八个字段现场相减，以及 <span class="mono">totalPensionPaid</span>
            （其中已发养老金的统筹部分为推导值）：
            左边与右边不等，说明账本被绕过合约改动过——这是审计时唯一需要盯的信号。
          </p>
        </el-card>

        <el-card shadow="never" class="panel">
          <template #header>
            <h2 class="section-title">余额科目</h2>
            <span class="panel-meta">对账口径 · 八个字段</span>
          </template>
          <el-descriptions class="balance-desc" :column="2" border size="small">
            <el-descriptions-item v-for="row in balances" :key="row.label" :label="row.label">
              <span class="num">{{ formatAmount(row.value) }}</span>
            </el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-col>

      <el-col :xs="24" :sm="24" :md="8" :lg="8">
        <el-card shadow="never" class="panel">
          <template #header>
            <h2 class="section-title">合约与系统</h2>
            <span class="panel-meta">只读</span>
          </template>
          <el-descriptions class="kv" :column="1" size="small">
            <el-descriptions-item label="系统名称">{{ board.systemName || '—' }}</el-descriptions-item>
            <el-descriptions-item label="合约版本">{{ board.version || '—' }}</el-descriptions-item>
            <el-descriptions-item label="管理员">
              <span class="num">{{ formatAmount(board.adminCount) }}</span> 个
            </el-descriptions-item>
            <el-descriptions-item label="已发养老金">
              <span class="num">{{ formatAmount(pensionPaid) }}</span>
            </el-descriptions-item>
          </el-descriptions>
          <p class="panel-note">管理员数量来自链上 adminCount；已发养老金单独取自 totalPensionPaid()，旧合约没有该函数时按 0 显示。</p>
        </el-card>

        <el-card shadow="never" class="panel">
          <template #header>
            <h2 class="section-title">城市统筹基金池</h2>
            <span class="panel-meta">统筹基金属于公共基金，公开可查</span>
          </template>
          <el-input v-model="cityQuery" placeholder="输入城市，例如 A市" clearable @keyup.enter="queryCityPool">
            <template #append>
              <el-button :loading="cityLoading" @click="queryCityPool">查询</el-button>
            </template>
          </el-input>
          <div v-if="cityResult !== null" class="pool-result">
            <span class="pool-city">{{ cityQueried }}</span>
            <span class="pool-amount num">{{ formatAmount(cityResult) }}</span>
          </div>
          <p v-else class="panel-note">输入城市名查询该城市统筹基金池余额。</p>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { chainApi, regulatorApi } from '../../api'
import { formatAmount } from '../../utils/format'
import PageHeader from '../../components/PageHeader.vue'

const loading = ref(false)
const board = ref({})
const stats = ref({})
const pensionPaid = ref(0)

const cityQuery = ref('')
const cityQueried = ref('')
const cityResult = ref(null)
const cityLoading = ref(false)

const amountText = (value) => formatAmount(value)

function big (value) {
  try {
    return BigInt(value === undefined || value === null || value === '' ? 0 : value)
  } catch (e) {
    return 0n
  }
}

function tokens (left, parts) {
  const list = [{ type: 'num', value: formatAmount(String(left)) }, { type: 'op', value: '=' }]
  parts.forEach((part, index) => {
    if (index > 0) list.push({ type: 'op', value: '+' })
    list.push({ type: 'num', value: formatAmount(String(part)) })
  })
  return list
}

/** 三段对账：与首页同源，都是 SystemStats 的八个字段 + totalPensionPaid */
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
  const pension = big(pensionPaid.value)

  // 已发养老金的统筹部分（推导口径与首页一致）：权益记账是历史累计、不因发放减少，
  // 城市池则被发放支取，差额 = 历次发放的基础养老金合计
  const personalPaid = paid - rights - escrowO - personal - escrowP
  const pensionOverall = pension - personalPaid

  return [
    {
      label: '累计注资 = 单位统筹余额 + 累计已缴',
      tokens: tokens(deposited, [pool, paid]),
      ok: deposited === pool + paid
    },
    {
      label: '累计已缴 = 个人账户 + 城市基金池 + 在途托管 + 已发养老金',
      tokens: tokens(paid, [personal, cityPool, escrowP, escrowO, pension]),
      ok: paid === personal + cityPool + escrowP + escrowO + pension
    },
    {
      label: '城市基金池 + 在途统筹 + 已发养老金（统筹） = 职工统筹权益',
      tokens: tokens(cityPool + escrowO + pensionOverall, [rights]),
      ok: cityPool + escrowO + pensionOverall === rights
    }
  ]
})

const allBalanced = computed(() => identities.value.every((item) => item.ok))

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

async function loadAll () {
  loading.value = true
  try {
    const data = await regulatorApi.dashboard().catch(() => ({}))
    board.value = data || {}
    stats.value = (data && data.stats) || {}
    // 已发养老金是独立 getter，旧合约没有该函数属于预期内，silent 掉不弹提示
    try {
      const raw = await chainApi.call('ChainSIP', 'totalPensionPaid', [], { silent: true })
      const values = raw && raw.values
      pensionPaid.value = Array.isArray(values) && values.length ? values[0] : 0
    } catch (e) {
      pensionPaid.value = 0
    }
  } finally {
    loading.value = false
  }
}

async function queryCityPool () {
  const city = (cityQuery.value || '').trim()
  if (!city) return
  cityLoading.value = true
  try {
    cityResult.value = await regulatorApi.cityPool(city)
    cityQueried.value = city
  } catch (e) {
    cityResult.value = null
  } finally {
    cityLoading.value = false
  }
}

onMounted(loadAll)
</script>

<style scoped>
.kpi-row {
  margin-bottom: 14px;
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

.kpi-note {
  font-size: 11.5px;
  color: var(--ink-3);
  margin-top: auto;
  padding-top: 3px;
  line-height: 1.5;
}

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
  padding: 8px 18px 16px;
}

.section-title {
  margin: 0;
}

.panel-meta {
  font-size: 11.5px;
  color: var(--ink-3);
}

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
  line-height: 1.4;
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
  padding: 0 10px;
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

:deep(.kv .el-descriptions__cell) {
  padding: 8px 0 !important;
  border-bottom: 1px solid var(--hair-2);
}

:deep(.kv .el-descriptions__label) {
  width: 92px;
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

.num {
  font-variant-numeric: tabular-nums;
  font-weight: 600;
}

.mono {
  font-family: var(--font-mono);
  font-size: 11.5px;
  color: var(--ink-2);
}

.pool-result {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  margin-top: 12px;
  padding: 12px 14px;
  background: var(--tone);
  border-radius: var(--radius-sm);
  border: 1px solid var(--hair-2);
}

.pool-city {
  font-size: 12.5px;
  color: var(--ink-2);
}

.pool-amount {
  font-size: 20px;
  font-weight: 600;
  letter-spacing: -0.01em;
}
</style>
