<template>
  <div>
    <h3 class="page-title">养老金支出</h3>
    <p class="page-desc">
      这是「缴纳」的对称面：职工累计缴满 <strong>{{ rules.minPaidMonths }} 个月</strong>、达到
      <strong>{{ rules.retireAge }} 周岁</strong>后，由本社保局办理退休，之后按月发放养老金——
      <strong>基础养老金</strong>从参保城市的统筹基金池支付，<strong>个人账户养老金</strong>从职工个人账户支付。
      发放是链上动作（<code>claimPension</code>），余额与基金池在链上同步扣减，链下只做经办与展示。
    </p>

    <el-alert v-if="scopeAlert" type="warning" :closable="false" show-icon class="mb-16" :title="scopeAlert" />
    <el-alert
      v-if="consistency && consistency.consistent === false"
      type="warning"
      :closable="false"
      show-icon
      class="mb-16"
      title="政策参数生效值与合约常量不一致：链上执行仍按合约常量，本页展示 / 试算按参数表，双口径会漂移，请先对齐参数"
    />

    <div class="card-block mb-16 rule-bar">
      <el-tag type="info" size="small">法定退休年龄 {{ rules.retireAge }} 岁</el-tag>
      <el-tag type="info" size="small">累计缴费 ≥ {{ rules.minPaidMonths }} 个月</el-tag>
      <el-tag type="info" size="small">个人账户计发 {{ rules.payoutMonths }} 个月</el-tag>
      <el-tag type="info" size="small">发放周期 {{ rules.cycleDays }} 天</el-tag>
      <el-tag v-if="rules.basicRatePercent" type="success" size="small">
        基础养老金年比例 {{ rules.basicRatePercent }}%
      </el-tag>
      <el-tooltip v-if="rules.retireDelayStarted" :content="rules.retireDelayNote" placement="bottom">
        <el-tag type="warning" size="small">延迟退休参考 {{ rules.legalRetireAgeText }}</el-tag>
      </el-tooltip>
      <div class="rule-text">
        {{ rules.baseFormula }}；{{ rules.personalFormula }}
      </div>
      <div v-if="rules.parameters && rules.parameters.length" class="rule-text">
        当前口径来自政策参数表：
        <el-tooltip
          v-for="p in rules.parameters"
          :key="p.paramCode"
          :content="`${p.paramCode} = ${p.paramValue}${p.versionNo ? '（v' + p.versionNo + '，' + p.validFrom + ' 起）' : '（默认值）'}${p.policyDoc ? '；依据 ' + p.policyDoc : ''}`"
        >
          <span class="param-chip">{{ p.paramName }} {{ p.paramValue }}</span>
        </el-tooltip>
      </div>
    </div>

    <el-row :gutter="16" class="mb-16">
      <el-col :span="6">
        <div class="card-block stat-card">
          <div class="stat-label">辖区退休人数</div>
          <div class="stat-value">{{ overview.retireeCount ?? 0 }}</div>
        </div>
      </el-col>
      <el-col :span="6">
        <div class="card-block stat-card">
          <div class="stat-label">本期待发（人 / 金额）</div>
          <div class="stat-value">
            {{ overview.pendingCount ?? 0 }}
            <span class="stat-sub">/ {{ formatAmount(overview.pendingAmount) }}</span>
          </div>
        </div>
      </el-col>
      <el-col :span="6">
        <div class="card-block stat-card">
          <div class="stat-label">退休人员月应发合计</div>
          <div class="stat-value">{{ formatAmount(overview.monthlyPayable) }}</div>
        </div>
      </el-col>
      <el-col :span="6">
        <div class="card-block stat-card">
          <div class="stat-label">
            累计已发（{{ overview.claimedCount ?? 0 }} 笔）
            <el-tooltip v-if="overview.claimedCovered === false" content="辖区退休人员超过 100 人，仅统计前 100 人">
              <el-icon><WarningFilled /></el-icon>
            </el-tooltip>
          </div>
          <div class="stat-value">{{ formatAmount(overview.claimedAmount) }}</div>
        </div>
      </el-col>
    </el-row>

    <el-tabs v-model="tab">
      <el-tab-pane label="待办退休" name="eligible">
        <div class="card-block">
          <div class="flex-between mb-8">
            <div>
              <strong>辖区未退休职工</strong>
              <span class="text-muted tip-inline">
                （其中 {{ overview.eligibleCount ?? 0 }} 人已达退休条件；未达标的会显示还差多少）
              </span>
            </div>
            <div>
              <el-button size="small" @click="forceDialogVisible = true">特殊审批退休</el-button>
              <el-button size="small" :loading="eligibleLoading" @click="loadEligible">刷新</el-button>
            </div>
          </div>
          <el-table :data="eligibleRows" size="small" v-loading="eligibleLoading">
            <el-table-column label="姓名" width="100">
              <template #default="{ row }">{{ row.name || '—' }}</template>
            </el-table-column>
            <el-table-column label="职工地址" min-width="160">
              <template #default="{ row }">
                <span class="chain-address">{{ shortAddress(row.address) }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="city" label="参保城市" width="90" />
            <el-table-column label="所属单位" min-width="130">
              <template #default="{ row }">{{ row.sponsorName || '—' }}</template>
            </el-table-column>
            <el-table-column label="年龄" width="70" align="right">
              <template #default="{ row }">{{ row.age ?? '—' }}</template>
            </el-table-column>
            <el-table-column label="累计月数" width="90" align="right">
              <template #default="{ row }">{{ row.paidMonths ?? 0 }}</template>
            </el-table-column>
            <el-table-column label="退休条件" width="150">
              <template #default="{ row }">
                <el-tag v-if="row.qualified" type="success" size="small">已达标</el-tag>
                <el-tooltip v-else :content="gapText(row)">
                  <el-tag type="warning" size="small">{{ shortGapText(row) }}</el-tag>
                </el-tooltip>
              </template>
            </el-table-column>
            <el-table-column label="个人账户余额" width="120" align="right">
              <template #default="{ row }">{{ formatAmount(row.personalBalance) }}</template>
            </el-table-column>
            <el-table-column label="预估月发" width="120" align="right">
              <template #default="{ row }">
                <el-tooltip :content="`基础 ${formatAmount(row.baseMonthly)} + 个人 ${formatAmount(row.personalMonthly)}`">
                  <span>{{ formatAmount(row.monthlyTotal) }}</span>
                </el-tooltip>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="130" fixed="right">
              <template #default="{ row }">
                <el-button v-if="row.qualified" link type="primary" size="small"
                  @click="retire(row, false)">办理退休</el-button>
                <el-button v-else link type="warning" size="small"
                  @click="retire(row, true)">特殊审批</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-pagination class="mt-16" layout="prev, pager, next, total" :total="eligibleTotal"
            :current-page="eligiblePage" :page-size="pageSize" @current-change="onEligiblePage" />
          <div class="text-muted tip">
            预估月发 = 基础养老金（缴费基数 × 缴费年数 × 1%）+ 个人账户养老金（个人账户余额 ÷ {{ rules.payoutMonths }}）。
            实际发放以链上计算为准；退休时会把个人账户月发额锁定，之后不再随余额变化。
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="退休发放" name="retirees">
        <div class="card-block">
          <div class="flex-between mb-8">
            <strong>已退休人员</strong>
            <el-button size="small" :loading="retireeLoading" @click="loadRetirees">刷新</el-button>
          </div>
          <el-table :data="retireeRows" size="small" v-loading="retireeLoading">
            <el-table-column label="姓名" width="100">
              <template #default="{ row }">{{ row.name || '—' }}</template>
            </el-table-column>
            <el-table-column label="职工地址" min-width="160">
              <template #default="{ row }">
                <span class="chain-address">{{ shortAddress(row.address) }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="city" label="参保城市" width="90" />
            <el-table-column label="月应发" width="120" align="right">
              <template #default="{ row }">
                <el-tooltip :content="`基础 ${formatAmount(row.baseMonthly)} + 个人 ${formatAmount(row.personalMonthly)}`">
                  <span>{{ formatAmount(row.monthlyTotal) }}</span>
                </el-tooltip>
              </template>
            </el-table-column>
            <el-table-column label="个人账户余额" width="120" align="right">
              <template #default="{ row }">{{ formatAmount(row.personalBalance) }}</template>
            </el-table-column>
            <el-table-column label="已领月数" width="90" align="right">
              <template #default="{ row }">{{ row.pensionClaimedMonths ?? 0 }}</template>
            </el-table-column>
            <el-table-column label="上次发放" min-width="150">
              <template #default="{ row }">
                <span v-if="Number(row.lastPensionAt) > 0">{{ formatTime(row.lastPensionAt) }}</span>
                <span v-else class="text-muted">未发放</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="110">
              <template #default="{ row }">
                <el-tag v-if="row.canClaim" type="success" size="small">可发放</el-tag>
                <el-tooltip v-else :content="`下次可发：${formatTime(row.nextClaimAt)}`">
                  <el-tag type="info" size="small">周期未到</el-tag>
                </el-tooltip>
              </template>
            </el-table-column>
            <el-table-column label="核定单" width="150">
              <template #default="{ row }">
                <template v-if="row.determinationId">
                  <el-button link type="primary" size="small" @click="openDetermination(row)">
                    #{{ row.determinationId }} · {{ row.snapshotHashShort }}
                  </el-button>
                  <el-tag v-if="row.determinationSource === 'LEGACY'" type="info" size="small" class="ml-4">存量</el-tag>
                </template>
                <el-tag v-else type="warning" size="small">未核定（历史）</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="230" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" size="small" :disabled="!row.canClaim"
                  @click="claim(row)">发放</el-button>
                <el-button link type="primary" size="small" @click="openRecords(row)">流水</el-button>
                <el-button link type="warning" size="small" :loading="recomputeLoading"
                  @click="recomputeDetermination(row)">复算</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-pagination class="mt-16" layout="prev, pager, next, total" :total="retireeTotal"
            :current-page="retireePage" :page-size="pageSize" @current-change="onRetireePage" />
          <div class="text-muted tip">
            同一职工 30 天内只能发放一次（链上周期护栏）。「复算」用核定单里存档的政策快照重新计算
            基础养老金与个人账户养老金，与落库值逐项比对：政策后来怎么调，历史核定结果都应保持一致。
            存量退休人员没有核定单的，复算时后端会按链上现状补建一份 LEGACY 核定单（不发链交易）。
          </div>
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- 核定单详情 / 复算结果 -->
    <el-drawer v-model="determinationVisible" :title="determinationTitle" size="60%">
      <div v-loading="determinationLoading">
        <template v-if="determination">
          <el-alert
            v-if="recomputeResult"
            :type="recomputeResult.consistent ? 'success' : 'error'"
            :closable="false"
            show-icon
            :title="recomputeResult.consistent
              ? '复算一致：用存档快照重算的结果与落库金额完全相同'
              : `复算存在偏差（${recomputeResult.differences.length} 项），请检查核定单或快照完整性`"
            class="mb-16"
          />
          <el-descriptions :column="3" border size="small">
            <el-descriptions-item label="核定单号">#{{ determination.id }}</el-descriptions-item>
            <el-descriptions-item label="状态">
              <el-tag size="small">{{ determination.status }}</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="来源">{{ determination.source }}</el-descriptions-item>
            <el-descriptions-item label="退休日">{{ determination.retireDate }}</el-descriptions-item>
            <el-descriptions-item label="退休年龄">{{ determination.retireAge }} 岁</el-descriptions-item>
            <el-descriptions-item label="累计缴费">{{ determination.paidMonths }} 个月</el-descriptions-item>
            <el-descriptions-item label="缴费基数">{{ formatAmount(determination.paymentBase) }}</el-descriptions-item>
            <el-descriptions-item label="个人账户余额">{{ formatAmount(determination.accountBalance) }}</el-descriptions-item>
            <el-descriptions-item label="经办人">{{ determination.operator || '—' }}</el-descriptions-item>
            <el-descriptions-item label="基础养老金">{{ formatAmount(determination.basicMonthly) }}</el-descriptions-item>
            <el-descriptions-item label="个人账户养老金">{{ formatAmount(determination.personalMonthly) }}</el-descriptions-item>
            <el-descriptions-item label="月发合计">{{ formatAmount(determination.monthlyTotal) }}</el-descriptions-item>
            <el-descriptions-item label="快照 SHA-256" :span="3">
              <span class="mono">{{ determination.policySnapshotHash }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="参数版本" :span="2">
              {{ determination.policyVersionRef || '全部使用默认值' }}
            </el-descriptions-item>
            <el-descriptions-item label="链交易">
              <span class="mono">{{ determination.determinationTxHash || '未上链（LEGACY）' }}</span>
            </el-descriptions-item>
          </el-descriptions>

          <div class="mt-16">
            <strong>政策快照（核定时点）</strong>
            <pre class="snapshot">{{ JSON.stringify(determination.policySnapshot, null, 2) }}</pre>
          </div>

          <div v-if="recomputeResult" class="mt-16">
            <strong>复算比对</strong>
            <el-table :data="recomputeRows" size="small" class="mt-8">
              <el-table-column prop="item" label="项目" width="160" />
              <el-table-column prop="stored" label="落库值" width="160" align="right" />
              <el-table-column prop="recomputed" label="复算值" width="160" align="right" />
              <el-table-column prop="delta" label="差额" align="right" />
            </el-table>
          </div>
        </template>
        <el-empty v-else description="核定单读取失败" />
      </div>
    </el-drawer>

    <el-drawer v-model="recordsVisible" :title="recordsTitle" size="55%">
      <div class="flex-between mb-8">
        <strong>养老金发放流水</strong>
        <el-button size="small" :loading="recordsLoading" @click="loadRecords(0)">刷新</el-button>
      </div>
      <el-table :data="recordRows" size="small" v-loading="recordsLoading">
        <el-table-column label="发放时间" min-width="160">
          <template #default="{ row }">{{ formatTime(row.timestamp) }}</template>
        </el-table-column>
        <el-table-column label="发放合计" width="120" align="right">
          <template #default="{ row }">{{ formatAmount(row.totalAmount) }}</template>
        </el-table-column>
        <el-table-column label="个人账户部分" width="130" align="right">
          <template #default="{ row }">{{ formatAmount(row.personalAmount) }}</template>
        </el-table-column>
        <el-table-column label="统筹基金部分" width="130" align="right">
          <template #default="{ row }">{{ formatAmount(row.overallAmount) }}</template>
        </el-table-column>
        <el-table-column label="交易哈希" min-width="180">
          <template #default="{ row }">
            <el-tooltip :content="row.txHash || '—'">
              <span class="mono">{{ shortAddress(row.txHash) }}</span>
            </el-tooltip>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination class="mt-16" layout="prev, pager, next, total" :total="recordTotal"
        :current-page="recordPage" :page-size="recordLimit" @current-change="onRecordPage" />
      <div class="text-muted tip">
        流水来自链上事件 <code>PensionClaimed</code> 的索引；链上余额变动才是最终权威，
        如果直接绕过平台发交易，这里可能看不到那笔记录。
      </div>
    </el-drawer>

    <el-dialog v-model="forceDialogVisible" title="特殊审批退休" width="480px">
      <el-alert type="warning" :closable="false" show-icon class="mb-16"
        title="该操作会跳过「60 周岁 + 缴费满 180 个月」的门槛，仅用于病退 / 提前退休等特殊情形" />
      <el-form label-width="90px">
        <el-form-item label="职工地址" required>
          <el-input v-model="forceEmployee" placeholder="0x 开头的 40 位十六进制地址" />
        </el-form-item>
        <el-form-item label="审批说明">
          <el-input type="textarea" :rows="2" disabled
            value="豁免事实会随链上事件 EmployeeRetired 留痕，监管方可审计" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="forceDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="actionLoading" @click="retireForce">确认办理</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, ref, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { bureauApi, chainApi } from '../../api'
import { formatAmount, formatTime, shortAddress } from '../../utils/format'

const tab = ref('eligible')
const pageSize = 20

/* ---------------- 规则与总览 ---------------- */
const rules = ref({
  retireAge: 60,
  minPaidMonths: 180,
  payoutMonths: 139,
  cycleDays: 30,
  baseFormula: '',
  personalFormula: ''
})
const overview = ref({})

/** 链上合约版本自检结果（后端探针调 totalPensionPaid() 得出；null 表示还没拿到） */
const contractCompat = ref(null)

/** 参数生效值 vs 合约常量的一致性巡检结果（不一致时页面顶部告警） */
const consistency = ref(null)

async function loadContractCompat() {
  try {
    const status = await chainApi.status()
    contractCompat.value = status?.contractCompat || null
    consistency.value = status?.policyConsistency || null
  } catch (e) {
    contractCompat.value = null
    consistency.value = null
  }
}

const scopeAlert = computed(() => {
  // 合约版本不匹配时，这页所有写操作（退休 / 发放）都会回滚，必须最先说清楚
  const compat = contractCompat.value
  if (compat && compat.pensionReady === false) {
    return '链上核心合约仍是升级前的版本（不支持 retireEmployee / claimPension / totalPensionPaid）：'
      + '办理退休与发放都会回滚。请按 README §5.4.6 重新部署核心合约，'
      + '把新地址写进 CHAINSIP_CORE_ADDRESS 后重启后端。'
  }
  const data = overview.value || {}
  if (data.chainReadable === false) {
    return `读取本社保局的链上服务城市失败，养老金数据为空：${data.reason || '链未就绪'}`
  }
  if (data.reason) {
    return data.reason
  }
  if (data.truncated) {
    return '辖区职工超过单次扫描上限（300 人），统计与列表可能不完整，请缩小范围后重试'
  }
  return ''
})

async function loadRules() {
  try {
    const data = await bureauApi.pensionRules()
    if (data) rules.value = data
  } catch (e) {
    /* 规则读不到就用默认值，不阻断页面 */
  }
}

async function loadOverview() {
  try {
    overview.value = (await bureauApi.pensionOverview()) || {}
  } catch (e) {
    overview.value = {}
  }
}

/* ---------------- 待办退休 ---------------- */
const eligibleRows = ref([])
const eligibleTotal = ref(0)
const eligibleLoading = ref(false)
const eligibleOffset = ref(0)
const eligiblePage = computed(() => Math.floor(eligibleOffset.value / pageSize) + 1)

async function loadEligible() {
  eligibleLoading.value = true
  try {
    const data = await bureauApi.pensionEligible(eligibleOffset.value, pageSize)
    eligibleRows.value = (data && data.rows) || []
    eligibleTotal.value = (data && data.total) || 0
  } catch (e) {
    eligibleRows.value = []
    eligibleTotal.value = 0
  } finally {
    eligibleLoading.value = false
  }
}

function onEligiblePage(page) {
  eligibleOffset.value = (page - 1) * pageSize
  loadEligible()
}

/** 未达标职工的差距（tooltip 用，完整表述） */
function gapText(row) {
  const parts = []
  if (Number(row.ageGap) > 0) parts.push(`年龄还差 ${row.ageGap} 岁`)
  if (Number(row.monthsGap) > 0) parts.push(`缴费还差 ${row.monthsGap} 个月`)
  return parts.length ? parts.join('，') : '已满足退休条件'
}

/** 未达标职工的差距（表格内短文案） */
function shortGapText(row) {
  const parts = []
  if (Number(row.ageGap) > 0) parts.push(`${row.ageGap} 岁`)
  if (Number(row.monthsGap) > 0) parts.push(`${row.monthsGap} 月`)
  return parts.length ? `差 ${parts.join(' / ')}` : '—'
}

/* ---------------- 退休发放 ---------------- */
const retireeRows = ref([])
const retireeTotal = ref(0)
const retireeLoading = ref(false)
const retireeOffset = ref(0)
const retireePage = computed(() => Math.floor(retireeOffset.value / pageSize) + 1)

async function loadRetirees() {
  retireeLoading.value = true
  try {
    const data = await bureauApi.pensionRetirees(retireeOffset.value, pageSize)
    retireeRows.value = (data && data.rows) || []
    retireeTotal.value = (data && data.total) || 0
  } catch (e) {
    retireeRows.value = []
    retireeTotal.value = 0
  } finally {
    retireeLoading.value = false
  }
}

function onRetireePage(page) {
  retireeOffset.value = (page - 1) * pageSize
  loadRetirees()
}

/* ---------------- 办理退休 / 发放 ---------------- */
const actionLoading = ref(false)
const forceDialogVisible = ref(false)
const forceEmployee = ref('')

const employeeOf = (row) => row.address || row.employee || ''

async function retire(row, force) {
  const employee = employeeOf(row)
  if (!employee) {
    ElMessage.warning('缺少职工地址')
    return
  }
  try {
    await ElMessageBox.confirm(
      force
        ? `确认为 ${row.name || employee} 办理「特殊审批退休」？将跳过年龄与缴费月数门槛。`
        : `确认为 ${row.name || employee} 办理退休？办理后该职工状态变为「已退休」，不能再缴费，次月起可领取养老金。`,
      force ? '特殊审批退休' : '办理退休',
      { type: force ? 'warning' : 'info', confirmButtonText: '确认办理', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  actionLoading.value = true
  try {
    await bureauApi.pensionRetire(employee, force)
    ElMessage.success('退休办理成功，链上状态已更新')
    forceDialogVisible.value = false
    forceEmployee.value = ''
    await refreshAll()
    tab.value = 'retirees'
  } catch (e) {
    /* http.js 已统一提示后端 message */
  } finally {
    actionLoading.value = false
  }
}

async function retireForce() {
  const employee = forceEmployee.value.trim()
  if (!/^0x[0-9a-fA-F]{40}$/.test(employee)) {
    ElMessage.warning('请输入 0x 开头的 40 位十六进制职工地址')
    return
  }
  await retire({ address: employee, name: '该职工' }, true)
}

async function claim(row) {
  const employee = employeeOf(row)
  if (!employee) return
  try {
    await ElMessageBox.confirm(
      `确认为 ${row.name || employee} 发放本月养老金 ${formatAmount(row.monthlyTotal)}？`
        + `其中统筹基金承担 ${formatAmount(row.baseMonthly)}、个人账户承担 ${formatAmount(row.personalMonthly)}。`,
      '发放养老金',
      { type: 'info', confirmButtonText: '确认发放', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  actionLoading.value = true
  try {
    await bureauApi.pensionClaim(employee)
    ElMessage.success('养老金发放成功')
    await refreshAll()
  } catch (e) {
    /* http.js 已统一提示后端 message */
  } finally {
    actionLoading.value = false
  }
}

/* ---------------- 发放流水 ---------------- */
const recordsVisible = ref(false)
const recordsLoading = ref(false)
const recordRows = ref([])
const recordTotal = ref(0)
const recordOffset = ref(0)
const recordLimit = 10
const recordEmployee = ref('')
const recordName = ref('')

const recordsTitle = computed(() =>
  recordName.value ? `发放流水 · ${recordName.value}` : '发放流水')

const recordPage = computed(() => Math.floor(recordOffset.value / recordLimit) + 1)

function openRecords(row) {
  recordEmployee.value = employeeOf(row)
  recordName.value = row.name || ''
  recordRows.value = []
  recordTotal.value = 0
  recordsVisible.value = true
  loadRecords(0)
}

async function loadRecords(offset) {
  if (!recordEmployee.value) return
  recordOffset.value = offset
  recordsLoading.value = true
  try {
    const data = await bureauApi.pensionRecords(recordEmployee.value, offset, recordLimit)
    recordRows.value = (data && data.records) || []
    recordTotal.value = Number((data && data.total) || 0)
  } catch (e) {
    recordRows.value = []
    recordTotal.value = 0
  } finally {
    recordsLoading.value = false
  }
}

function onRecordPage(page) {
  loadRecords((page - 1) * recordLimit)
}

/* ---------------- 核定单（政策快照 / 复算） ---------------- */
const determinationVisible = ref(false)
const determinationLoading = ref(false)
const determination = ref(null)
const recomputeLoading = ref(false)
const recomputeResult = ref(null)

const determinationTitle = computed(() =>
  determination.value ? `核定单 #${determination.value.id} · ${determination.value.employeeAddress}` : '退休核定单')

/** 复算比对表：落库值 / 复算值 / 差额（差额用 BigInt 算，避免大整数精度丢失） */
const recomputeRows = computed(() => {
  const result = recomputeResult.value
  if (!result) return []
  const stored = result.stored || {}
  const recomputed = result.recomputed || {}
  const pairs = [
    ['基础养老金', 'basicMonthly'],
    ['个人账户养老金', 'personalMonthly'],
    ['月发合计', 'monthlyTotal']
  ]
  return pairs.map(([label, key]) => ({
    item: label,
    stored: stored[key] ?? '-',
    recomputed: recomputed[key] ?? '-',
    delta: bigDelta(recomputed[key], stored[key])
  }))
})

function bigDelta(a, b) {
  try {
    return (BigInt(String(a)) - BigInt(String(b))).toString()
  } catch (e) {
    return '-'
  }
}

/** 打开核定单详情；存量退休人员没有留档时后端会补建 LEGACY 行 */
async function openDetermination(row) {
  const employee = employeeOf(row)
  if (!employee) return
  determinationVisible.value = true
  determinationLoading.value = true
  recomputeResult.value = null
  determination.value = null
  try {
    determination.value = await bureauApi.determinationLatest(employee)
  } catch (e) {
    determination.value = null
  } finally {
    determinationLoading.value = false
  }
}

/** 复算：用存档政策快照重算并比对（政策调整后历史结果应保持不变） */
async function recomputeDetermination(row) {
  const employee = employeeOf(row)
  if (!employee) return
  recomputeLoading.value = true
  try {
    const result = await bureauApi.recomputeDeterminationByEmployee(employee)
    recomputeResult.value = result
    determination.value = await bureauApi.determinationLatest(employee)
    determinationVisible.value = true
    ElMessage.success(result?.consistent
      ? '复算一致：存档快照重算结果与落库值完全相同'
      : '复算完成：存在偏差，请查看明细')
    await loadRetirees()
  } catch (e) {
    /* http.js 已统一提示后端 message */
  } finally {
    recomputeLoading.value = false
  }
}

/* ---------------- 初始化 ---------------- */
async function refreshAll() {
  await Promise.all([loadOverview(), loadEligible(), loadRetirees()])
}

onMounted(() => {
  loadContractCompat()
  loadRules()
  refreshAll()
})
</script>

<style scoped>
.rule-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.rule-text {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.6;
}

.stat-card {
  text-align: center;
}

.stat-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 6px;
}

.stat-value {
  font-size: 20px;
  font-weight: 500;
  word-break: break-all;
}

.stat-sub {
  font-size: 14px;
  color: var(--el-text-color-secondary);
}

.tip-inline {
  font-size: 12px;
}

.tip {
  font-size: 12px;
  line-height: 1.6;
  margin-top: 8px;
}

.mt-16 {
  margin-top: 16px;
}

.mb-16 {
  margin-bottom: 16px;
}

.mb-8 {
  margin-bottom: 8px;
}

.mt-8 {
  margin-top: 8px;
}

.ml-4 {
  margin-left: 4px;
}

/* 规则条里的参数小标签：鼠标悬停看版本号与政策文号 */
.param-chip {
  display: inline-block;
  margin: 0 6px 4px 0;
  padding: 0 6px;
  border-radius: 4px;
  background: var(--el-fill-color-light);
  color: var(--el-text-color-regular);
  font-size: 11px;
  cursor: help;
}

/* 核定单项里的政策快照 JSON */
.snapshot {
  margin-top: 8px;
  padding: 12px;
  max-height: 320px;
  overflow: auto;
  background: var(--el-fill-color-lighter);
  border-radius: 6px;
  font-size: 12px;
  line-height: 1.6;
}

</style>
