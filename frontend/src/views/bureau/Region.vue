<template>
  <div>
    <h3 class="page-title">辖区总览</h3>
    <p class="page-desc">
      按<strong>链上服务城市</strong>看本社保局辖区内的全部对象：辖区内的缴费单位、这些单位名下的参保职工，
      以及每个职工的<strong>参保记录</strong>与<strong>缴纳记录</strong>。
      辖区范围来自链上（转移合约里本局绑定的服务城市），不是"本局自己创建的账号"——
      由管理员或其它社保局创建、但城市落在本局辖区内的单位同样会出现在这里。
    </p>

    <el-alert v-if="scopeAlert" type="warning" :closable="false" show-icon class="mb-16"
      :title="scopeAlert" />
    <el-alert type="info" :closable="false" show-icon class="mb-16"
      title="这里的名单以链上归属为准">
      合约没有"按城市列公司 / 列职工"的接口，只能遍历链上单位名单再按城市过滤、再逐条读参保账户，
      因此扫描量有上限（单次最多扫描 300 名职工）；被截断时页面会提示"结果已截断"，可缩小到单个单位再查。
    </el-alert>

    <el-row :gutter="16" class="mb-16">
      <el-col :span="6">
        <div class="card-block stat-card">
          <div class="stat-label">服务城市</div>
          <div class="stat-value">
            <el-tag v-for="city in overview.cities || []" :key="city" size="small" class="mr-4">{{ city }}</el-tag>
            <span v-if="!(overview.cities || []).length" class="text-muted">—</span>
          </div>
        </div>
      </el-col>
      <el-col :span="4">
        <div class="card-block stat-card">
          <div class="stat-label">辖区单位数</div>
          <div class="stat-value">{{ overview.sponsorCount ?? 0 }}</div>
        </div>
      </el-col>
      <el-col :span="4">
        <div class="card-block stat-card">
          <div class="stat-label">参保职工数</div>
          <div class="stat-value">{{ overview.employeeCount ?? 0 }}</div>
        </div>
      </el-col>
      <el-col :span="5">
        <div class="card-block stat-card">
          <div class="stat-label">单位统筹余额合计</div>
          <div class="stat-value">{{ formatAmount(overview.poolBalance) }}</div>
        </div>
      </el-col>
      <el-col :span="5">
        <div class="card-block stat-card">
          <div class="stat-label">累计已缴合计</div>
          <div class="stat-value">{{ formatAmount(overview.paidTotal) }}</div>
        </div>
      </el-col>
    </el-row>

    <div class="card-block mb-16">
      <div class="flex-between mb-8">
        <strong>参保状态分布</strong>
        <el-button size="small" :loading="overviewLoading" @click="loadOverview">刷新概览</el-button>
      </div>
      <div class="dist-row">
        <el-tag v-for="(count, name) in (overview.statusDistribution || {})" :key="name"
          :type="statusTagType(name)" size="small" class="mr-4">
          {{ name }}：{{ count }}
        </el-tag>
        <span v-if="overview.statusTruncated" class="text-muted tip-inline">
          （已扫描 {{ overview.statusScanned ?? 0 }} 条，超出上限的分布未统计）
        </span>
      </div>
    </div>

    <el-tabs v-model="tab">
      <el-tab-pane label="辖区缴费单位" name="sponsors">
        <div class="card-block">
          <div class="flex-between mb-8">
            <strong>辖区内缴费单位</strong>
            <el-button size="small" :loading="sponsorLoading" @click="loadSponsors">刷新</el-button>
          </div>
          <el-table :data="sponsorRows" size="small" v-loading="sponsorLoading">
            <el-table-column label="单位名称" min-width="140">
              <template #default="{ row }">{{ row.name || '—' }}</template>
            </el-table-column>
            <el-table-column label="机构地址" min-width="160">
              <template #default="{ row }">
                <span class="chain-address">{{ shortAddress(row.address) }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="city" label="城市" width="90" />
            <el-table-column label="职工数" width="80" align="right">
              <template #default="{ row }">{{ row.employeeCount ?? 0 }}</template>
            </el-table-column>
            <el-table-column label="统筹余额" width="120" align="right">
              <template #default="{ row }">{{ formatAmount(row.poolBalance) }}</template>
            </el-table-column>
            <el-table-column label="累计已缴" width="120" align="right">
              <template #default="{ row }">{{ formatAmount(row.paidTotal) }}</template>
            </el-table-column>
            <el-table-column label="平台账号" width="140">
              <template #default="{ row }">
                <span v-if="row.username">{{ row.username }}</span>
                <span v-else class="text-muted">无链下账号</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="120" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="filterBySponsor(row)">
                  看本单位职工
                </el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-pagination class="mt-16" layout="prev, pager, next, total" :total="sponsorTotal"
            :current-page="sponsorPage" :page-size="sponsorLimit" @current-change="onSponsorPage" />
        </div>
      </el-tab-pane>

      <el-tab-pane label="辖区参保职工" name="employees">
        <div class="card-block">
          <div class="flex-between mb-8">
            <strong>辖区内参保职工</strong>
            <div>
              <el-select v-model="sponsorFilter" clearable filterable placeholder="全部单位"
                style="width: 260px; margin-right: 8px" @change="onSponsorFilterChange">
                <el-option v-for="item in sponsorOptions" :key="item.address" :label="item.name"
                  :value="item.address" />
              </el-select>
              <el-button size="small" :loading="employeeLoading" @click="loadEmployees">刷新</el-button>
            </div>
          </div>
          <el-table :data="employeeRows" size="small" v-loading="employeeLoading">
            <el-table-column label="姓名" width="100">
              <template #default="{ row }">{{ row.name || '—' }}</template>
            </el-table-column>
            <el-table-column label="身份证号" width="170">
              <template #default="{ row }">
                <span class="mono">{{ row.idNumber || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="参保地址" min-width="150">
              <template #default="{ row }">
                <span class="chain-address">{{ shortAddress(row.address) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="所属单位" min-width="130">
              <template #default="{ row }">{{ row.sponsorName || '—' }}</template>
            </el-table-column>
            <el-table-column prop="city" label="参保城市" width="90" />
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag :type="employeeStatusType(row.status)" size="small">
                  {{ row.statusName || employeeStatusName(row.status) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="个人账户" width="110" align="right">
              <template #default="{ row }">{{ formatAmount(row.personalBalance) }}</template>
            </el-table-column>
            <el-table-column label="统筹账户" width="110" align="right">
              <template #default="{ row }">{{ formatAmount(row.overallBalance) }}</template>
            </el-table-column>
            <el-table-column label="累计月数" width="90" align="right">
              <template #default="{ row }">{{ row.paidMonths ?? 0 }}</template>
            </el-table-column>
            <el-table-column label="操作" width="110" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="openDetail(row)">参保明细</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-pagination class="mt-16" layout="prev, pager, next, total" :total="employeeTotal"
            :current-page="employeePage" :page-size="employeeLimit" @current-change="onEmployeePage" />
          <div v-if="employeeTruncated" class="text-muted tip">
            结果已截断：单次最多扫描 300 名职工，可用上方的单位筛选缩小范围再查。
          </div>
        </div>
      </el-tab-pane>
    </el-tabs>

    <el-drawer v-model="detailVisible" :title="detailTitle" size="60%">
      <template v-if="detail">
        <el-descriptions :column="2" border size="small" title="参保记录">
          <el-descriptions-item label="姓名">{{ detail.name || '—' }}</el-descriptions-item>
          <el-descriptions-item label="身份证号">
            <span class="mono">{{ detail.idNumber || '—' }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="参保地址">
            <span class="chain-address">{{ detail.employee || detail.address }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="参保城市">{{ detail.city || '—' }}</el-descriptions-item>
          <el-descriptions-item label="所属单位">{{ detail.sponsorName || detail.sponsor || '—' }}</el-descriptions-item>
          <el-descriptions-item label="参保状态">
            <el-tag :type="employeeStatusType(detail.status)" size="small">
              {{ detail.statusName || employeeStatusName(detail.status) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="工资">{{ formatAmount(detail.salary) }}</el-descriptions-item>
          <el-descriptions-item label="缴费基数">{{ formatAmount(detail.paymentBase) }}</el-descriptions-item>
          <el-descriptions-item label="个人账户余额">{{ formatAmount(detail.personalBalance) }}</el-descriptions-item>
          <el-descriptions-item label="统筹账户余额">{{ formatAmount(detail.overallBalance) }}</el-descriptions-item>
          <el-descriptions-item label="账户合计">{{ formatAmount(detail.totalBalance) }}</el-descriptions-item>
          <el-descriptions-item label="累计缴费月数">{{ detail.paidMonths ?? 0 }}</el-descriptions-item>
          <el-descriptions-item label="欠缴次数">{{ detail.overdueCount ?? 0 }}</el-descriptions-item>
          <el-descriptions-item label="在途转移编号">
            {{ detail.activeTransferId && Number(detail.activeTransferId) !== 0 ? detail.activeTransferId : '无' }}
          </el-descriptions-item>
          <el-descriptions-item label="建档时间">{{ formatTime(detail.registeredAt) }}</el-descriptions-item>
          <el-descriptions-item label="最近缴费时间">{{ formatTime(detail.lastPaymentAt) }}</el-descriptions-item>
        </el-descriptions>

        <div class="flex-between mt-16 mb-8">
          <strong>缴纳记录</strong>
          <el-button size="small" :loading="paymentsLoading" @click="loadPayments(0)">刷新</el-button>
        </div>
        <el-table :data="paymentRows" size="small" v-loading="paymentsLoading">
          <el-table-column label="缴费时间" min-width="165">
            <template #default="{ row }">{{ formatTime(row.timestamp) }}</template>
          </el-table-column>
          <el-table-column label="缴费基数" width="110" align="right">
            <template #default="{ row }">{{ formatAmount(row.paymentBase) }}</template>
          </el-table-column>
          <el-table-column label="个人账户(8%)" width="120" align="right">
            <template #default="{ row }">{{ formatAmount(row.personalAmount) }}</template>
          </el-table-column>
          <el-table-column label="统筹账户(19%)" width="130" align="right">
            <template #default="{ row }">{{ formatAmount(row.overallAmount) }}</template>
          </el-table-column>
          <el-table-column label="缴费单位" min-width="160">
            <template #default="{ row }">
              <span class="chain-address">{{ shortAddress(row.sponsor) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="是否补缴" width="90">
            <template #default="{ row }">
              <el-tag :type="row.overdue ? 'warning' : 'success'" size="small">
                {{ row.overdue ? '补缴' : '正常' }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination class="mt-16" layout="prev, pager, next, total" :total="paymentTotal"
          :current-page="paymentPage" :page-size="paymentLimit" @current-change="onPaymentPage" />
        <div class="text-muted tip">
          个人 8% 进个人账户、单位 19% 进统筹账户，由所属单位调用链上 makePayment 产生；
          这里的记录就是该职工的完整缴费流水。
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { onMounted, ref, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { bureauApi } from '../../api'
import { formatAmount, formatTime, shortAddress, employeeStatusName, employeeStatusType } from '../../utils/format'

const tab = ref('sponsors')

/* ---------------- 概览 ---------------- */
const overviewLoading = ref(false)
const overview = ref({})

const scopeAlert = computed(() => {
  const data = overview.value || {}
  if (data.chainReadable === false) {
    return `读取本社保局的链上服务城市失败，辖区为空：${data.reason || '链未就绪'}`
  }
  if ((data.cities || []).length === 0 && data.reason) {
    return data.reason
  }
  return ''
})

function statusTagType(name) {
  return { 正常参保: 'success', 已停缴: 'warning', 已转出: 'warning', 已注销: 'danger', 已退休: 'primary' }[name] || 'info'
}

async function loadOverview() {
  overviewLoading.value = true
  try {
    overview.value = (await bureauApi.cityOverview()) || {}
  } catch (e) {
    overview.value = {}
  } finally {
    overviewLoading.value = false
  }
}

/* ---------------- 辖区缴费单位 ---------------- */
const sponsorRows = ref([])
const sponsorTotal = ref(0)
const sponsorLoading = ref(false)
const sponsorLimit = 20
const sponsorOffset = ref(0)

const sponsorPage = computed(() => Math.floor(sponsorOffset.value / sponsorLimit) + 1)

async function loadSponsors() {
  sponsorLoading.value = true
  try {
    const data = await bureauApi.citySponsors(sponsorOffset.value, sponsorLimit)
    sponsorRows.value = (data && data.rows) || []
    sponsorTotal.value = (data && data.total) || 0
  } catch (e) {
    sponsorRows.value = []
    sponsorTotal.value = 0
  } finally {
    sponsorLoading.value = false
  }
}

function onSponsorPage(page) {
  sponsorOffset.value = (page - 1) * sponsorLimit
  loadSponsors()
}

/** 单位下拉（用已加载的单位列表，翻页时只保留当页，够用） */
const sponsorOptions = computed(() =>
  sponsorRows.value.filter((row) => row && row.address).map((row) => ({
    address: row.address,
    name: row.name ? `${row.name}（${row.city || ''}）` : row.address
  }))
)

/* ---------------- 辖区职工 ---------------- */
const employeeRows = ref([])
const employeeTotal = ref(0)
const employeeTruncated = ref(false)
const employeeLoading = ref(false)
const employeeLimit = 20
const employeeOffset = ref(0)
const sponsorFilter = ref('')

const employeePage = computed(() => Math.floor(employeeOffset.value / employeeLimit) + 1)

async function loadEmployees() {
  employeeLoading.value = true
  try {
    const params = { offset: employeeOffset.value, limit: employeeLimit }
    if (sponsorFilter.value) {
      params.sponsor = sponsorFilter.value
    }
    const data = await bureauApi.cityEmployees(params)
    employeeRows.value = (data && data.rows) || []
    employeeTotal.value = (data && data.total) || 0
    employeeTruncated.value = !!(data && data.truncated)
  } catch (e) {
    employeeRows.value = []
    employeeTotal.value = 0
  } finally {
    employeeLoading.value = false
  }
}

function onEmployeePage(page) {
  employeeOffset.value = (page - 1) * employeeLimit
  loadEmployees()
}

function onSponsorFilterChange() {
  employeeOffset.value = 0
  loadEmployees()
}

/** 从单位表跳到职工表：自动带上该单位的筛选 */
function filterBySponsor(row) {
  sponsorFilter.value = row.address
  employeeOffset.value = 0
  tab.value = 'employees'
  loadEmployees()
}

/* ---------------- 职工明细：参保记录 + 缴纳记录 ---------------- */
const detailVisible = ref(false)
const detail = ref(null)
const detailLoading = ref(false)

const paymentRows = ref([])
const paymentTotal = ref(0)
const paymentsLoading = ref(false)
const paymentLimit = 10
const paymentOffset = ref(0)

const paymentPage = computed(() => Math.floor(paymentOffset.value / paymentLimit) + 1)

const detailTitle = computed(() => {
  const data = detail.value
  if (!data) return '参保明细'
  return `参保明细${data.name ? ' · ' + data.name : ''}`
})

async function openDetail(row) {
  detail.value = null
  paymentRows.value = []
  paymentOffset.value = 0
  detailVisible.value = true
  const employee = row.address || row.employee
  detailLoading.value = true
  try {
    // 列表里已经带回了参保账户的全字段，这里再拉一次是为了拿到最新的余额与月数；
    // 拉不到（例如该职工刚被注销）就回退用列表里的快照，不让抽屉开天窗
    const data = await bureauApi.cityInsurance(employee)
    detail.value = data || row
  } catch (e) {
    detail.value = row
  } finally {
    detailLoading.value = false
  }
  loadPayments(0)
}

async function loadPayments(offset) {
  const employee = (detail.value && (detail.value.employee || detail.value.address)) || ''
  if (!employee) return
  paymentOffset.value = offset
  paymentsLoading.value = true
  try {
    const data = await bureauApi.cityPayments(employee, offset, paymentLimit)
    paymentRows.value = (data && data.records) || []
    paymentTotal.value = Number((data && data.total) || 0)
  } catch (e) {
    paymentRows.value = []
    paymentTotal.value = 0
  } finally {
    paymentsLoading.value = false
  }
}

function onPaymentPage(page) {
  loadPayments((page - 1) * paymentLimit)
}

onMounted(() => {
  loadOverview()
  loadSponsors()
  loadEmployees()
})
</script>

<style scoped>
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

.dist-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px;
}

.tip-inline {
  font-size: 12px;
}

.tip {
  font-size: 12px;
  line-height: 1.6;
  margin-top: 8px;
}

.mr-4 {
  margin-right: 4px;
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
</style>
