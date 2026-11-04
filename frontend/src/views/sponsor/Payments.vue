<template>
  <div>
    <h3 class="page-title">缴费管理</h3>
    <p class="page-desc">
      单位按月为职工缴费：个人 8% 进个人账户（实账），单位 19% 进<strong>参保城市的统筹基金池</strong>，
      两笔都从单位统筹账户余额扣减（共 27%）。同一职工 30 天内只能缴一次（防重复缴费），
      逾期不影响补缴，但会链上留痕（overdueCount / PaymentMade.overdue）。
    </p>

    <el-row :gutter="16">
      <el-col :span="9">
        <div class="card-block">
          <strong>本公司统筹账户</strong>
          <el-descriptions v-if="account" :column="1" border size="small" class="mt-8">
            <el-descriptions-item label="名称">{{ account.name }}</el-descriptions-item>
            <el-descriptions-item label="城市">{{ account.city }}</el-descriptions-item>
            <el-descriptions-item label="可用余额">
              <span class="stat-value">{{ formatAmount(account.poolBalance) }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="累计已缴">{{ formatAmount(account.paidTotal) }}</el-descriptions-item>
            <el-descriptions-item label="职工数">{{ account.employeeCount }}</el-descriptions-item>
          </el-descriptions>
          <el-empty v-else description="尚未绑定单位地址或查询失败" />
        </div>

        <div class="card-block">
          <strong>试算（quotePayment）</strong>
          <el-form label-width="90px" class="mt-8">
            <el-form-item label="月工资">
              <el-input-number v-model="salary" :min="1" :step="500" />
            </el-form-item>
            <el-form-item>
              <el-button size="small" @click="quote">试算</el-button>
            </el-form-item>
          </el-form>
          <el-descriptions v-if="quoteResult" :column="1" border size="small">
            <el-descriptions-item label="缴费基数">{{ quoteResult[0] }}</el-descriptions-item>
            <el-descriptions-item label="个人账户 8%">{{ quoteResult[1] }}</el-descriptions-item>
            <el-descriptions-item label="统筹账户 19%">{{ quoteResult[2] }}</el-descriptions-item>
            <el-descriptions-item label="单位合计 27%">{{ quoteResult[3] }}</el-descriptions-item>
          </el-descriptions>
        </div>
      </el-col>

      <el-col :span="15">
        <div class="card-block">
          <strong>为职工缴费（makePayment）</strong>
          <el-form inline class="mt-8">
            <el-form-item label="选择已建档职工（用于缴费 / 查记录）">
              <el-select
                v-model="employee"
                filterable
                clearable
                :loading="employeesLoading"
                placeholder="按姓名搜索已建档职工"
                style="width: 300px"
                @change="onEmployeeChange"
              >
                <el-option
                  v-for="item in employeeOptions"
                  :key="item.address"
                  :label="employeeOptionLabel(item)"
                  :value="item.address"
                />
              </el-select>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="busy" @click="pay">缴费</el-button>
              <el-button :loading="loading" @click="loadRecords">查缴费记录</el-button>
            </el-form-item>
          </el-form>

          <div v-if="employeesLoading" class="text-muted mt-8">正在读取本单位职工…</div>

          <template v-else-if="employeesError">
            <div class="text-muted mt-8">职工名单读取失败，请检查网络或后端服务后重试。</div>
            <el-button size="small" class="mt-8" :loading="employeesLoading" @click="loadEmployees">
              重新读取
            </el-button>
          </template>

          <div v-else-if="!employeeOptions.length" class="text-muted mt-8">
            本单位暂无职工，请先到「职工管理」建档
            <el-button link type="primary" size="small" @click="$router.push('/sponsor/employees')">
              去「职工管理」建档
            </el-button>
          </div>

          <el-alert
            v-if="suspendedSelected"
            class="mt-8"
            type="warning"
            :closable="false"
            show-icon
            title="该职工已停缴，缴费可能被合约回滚"
          />

          <el-descriptions
            v-if="selectedEmployee"
            class="mt-8"
            :column="3"
            border
            size="small"
            title="职工信息"
          >
            <el-descriptions-item label="姓名">
              {{ selectedEmployee.name || '未命名' }}
            </el-descriptions-item>
            <el-descriptions-item label="状态">
              <el-tag :type="employeeStatusType(selectedEmployee.status)" size="small">
                {{ employeeStatusName(selectedEmployee.status) }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="参保城市">{{ selectedEmployee.city || '-' }}</el-descriptions-item>
            <el-descriptions-item label="缴费基数">
              {{ formatAmount(selectedEmployee.paymentBase) }}
            </el-descriptions-item>
            <el-descriptions-item label="上次缴费时间">
              {{ formatTime(selectedEmployee.lastPaidAt) }}
            </el-descriptions-item>
            <el-descriptions-item label="链上地址">
              <span class="chain-address">{{ shortAddress(selectedEmployee.address, 10, 8) }}</span>
            </el-descriptions-item>
          </el-descriptions>

          <el-descriptions
            v-if="trial"
            class="mt-8"
            :column="3"
            border
            size="small"
            title="本次缴费试算（按当前政策参数）"
          >
            <el-descriptions-item label="缴费基数">
              {{ formatAmount(trial.effectiveBase) }}
              <el-tag v-if="trial.baseClamped" type="warning" size="small" class="ml-4">已按上下限夹取</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="个人账户">
              {{ formatAmount(trial.personalAmount) }}
              <span class="text-muted">（{{ (trial.employeeContribRate * 100).toFixed(0) }}%）</span>
            </el-descriptions-item>
            <el-descriptions-item label="统筹账户">
              {{ formatAmount(trial.overallAmount) }}
              <span class="text-muted">（{{ (trial.employerContribRate * 100).toFixed(0) }}%）</span>
            </el-descriptions-item>
            <el-descriptions-item label="单位合计">
              {{ formatAmount(trial.totalAmount) }}
            </el-descriptions-item>
            <el-descriptions-item label="基数范围">
              {{ formatAmount(trial.baseFloorValue) }} ~ {{ formatAmount(trial.baseCeilingValue) }}
            </el-descriptions-item>
            <el-descriptions-item label="费率版本">
              <span class="text-muted mono">{{ trial.policyVersionRef || '默认值' }}</span>
            </el-descriptions-item>
          </el-descriptions>
          <div v-if="trial" class="text-muted mt-8">{{ trial.note }}</div>
        </div>

        <div class="card-block">
          <strong>缴费记录</strong>
          <div class="text-muted mt-8">
            选中职工即自动读取缴费记录；也可以随时点上方「查缴费记录」手动刷新（读取失败后可重试）。
          </div>
          <el-table :data="records" size="small" class="mt-8" :empty-text="recordsEmptyText">
            <el-table-column label="时间" width="160">
              <template #default="{ row }">{{ formatTime(row.timestamp) }}</template>
            </el-table-column>
            <el-table-column label="缴费基数" width="100">
              <template #default="{ row }">{{ formatAmount(row.paymentBase) }}</template>
            </el-table-column>
            <el-table-column label="个人 8%" width="90">
              <template #default="{ row }">{{ formatAmount(row.personalAmount) }}</template>
            </el-table-column>
            <el-table-column label="统筹 19%" width="90">
              <template #default="{ row }">{{ formatAmount(row.overallAmount) }}</template>
            </el-table-column>
            <el-table-column label="是否逾期" width="90">
              <template #default="{ row }">
                <el-tag :type="row.overdue ? 'danger' : 'success'" size="small">
                  {{ row.overdue ? '逾期缴纳' : '按期' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="缴费单位" min-width="160">
              <template #default="{ row }">
                <!-- 后端若在记录里带上 sponsorName 就优先显示名称（hover 看地址），缺字段时保持原样 -->
                <template v-if="row.sponsorName">
                  <span>{{ row.sponsorName }}</span>
                  <el-tooltip v-if="row.sponsor" :content="row.sponsor" placement="top">
                    <span class="text-muted mono" style="margin-left: 6px">
                      {{ shortAddress(row.sponsor) }}
                    </span>
                  </el-tooltip>
                </template>
                <el-tooltip v-else-if="row.sponsor" :content="row.sponsor" placement="top">
                  <span class="chain-address">{{ shortAddress(row.sponsor) }}</span>
                </el-tooltip>
                <span v-else class="text-muted">-</span>
              </template>
            </el-table-column>
          </el-table>
          <div class="text-muted mt-8">共 {{ recordTotal }} 条</div>
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { sponsorApi } from '../../api'
import { STRUCT_FIELDS, mapStruct, mapPagedList } from '../../utils/contract'
import {
  employeeStatusName,
  employeeStatusType,
  formatAmount,
  formatTime,
  shortAddress
} from '../../utils/format'
import { useUserStore } from '../../stores/user'

const user = useUserStore()
/** 职工下拉的选中值 = 职工链上地址（POST /sponsor/payments 仍按地址缴费） */
const employee = ref('')
/** 本单位职工明细（GET /sponsor/employees/detail，姓名索引） */
const employeeOptions = ref([])
const employeesLoading = ref(false)
const employeesError = ref(false)
const salary = ref(25000)
const quoteResult = ref(null)
const account = ref(null)
const records = ref([])
const recordTotal = ref(0)
/** 是否已经成功查询过一次（用于区分"还没查"和"查过但确实没有记录"） */
const recordsLoaded = ref(false)
/** 最近一次查询是否失败（失败要清空旧数据并给出可重试的文案） */
const recordsError = ref(false)
const busy = ref(false)
const loading = ref(false)
/** 本次缴费试算结果（GET /sponsor/payments/trial，按当前政策参数计算，只读） */
const trial = ref(null)

/** 本单位的链上地址：机构账号创建时已自动生成，钱包绑定只作兜底 */
const sponsorAddress = computed(() => user.chainAddress)

/** 选中职工对应的明细行 */
const selectedEmployee = computed(
  () => employeeOptions.value.find((item) => item.address === employee.value) || null
)

/** 停缴判定：优先看后端 suspended 标记，否则回退到状态码（2 已停缴 / 4 已注销） */
function isSuspended(item) {
  if (!item) return false
  if (item.suspended) return true
  const status = Number(item.status)
  return status === 2 || status === 4
}

/** 停缴 / 非正常参保职工的额外中文标注，未命中返回空串 */
function employeeStatusNote(item) {
  if (!item) return ''
  if (item.suspended) {
    const status = Number(item.status)
    if (status !== 1 && status !== 2) return employeeStatusName(status)
    return '已停缴'
  }
  switch (Number(item.status)) {
    case 1:
      return ''
    case 2:
      return '已停缴'
    case 4:
      return '已注销'
    default:
      return item.statusName || employeeStatusName(item.status)
  }
}

/** 下拉项：张三（0x1ee9fa…）；无姓名时用未命名（0x…）；停缴职工加中文标注 */
function employeeOptionLabel(item) {
  const name = item.name || '未命名'
  const note = employeeStatusNote(item)
  const suffix = note ? `（${note}）` : ''
  return `${name}${suffix}（${shortAddress(item.address, 8, 4)}）`
}

const suspendedSelected = computed(() => isSuspended(selectedEmployee.value))

/** 读取本单位职工明细；失败时置错误标记，页面给出「重新读取」按钮 */
async function loadEmployees() {
  employeesLoading.value = true
  employeesError.value = false
  try {
    const data = await sponsorApi.employeeDetails(0, 50)
    // 正常返回 data 数组；个别网关会多包一层 data，这里做一次兜底
    employeeOptions.value = Array.isArray(data) ? data : Array.isArray(data?.data) ? data.data : []
  } catch (e) {
    employeeOptions.value = []
    employeesError.value = true
  } finally {
    employeesLoading.value = false
  }
}

/** 换职工时先清空上一位职工的缴费记录，再自动读取新职工的数据（避免串数据） */
function onEmployeeChange() {
  records.value = []
  recordTotal.value = 0
  recordsLoaded.value = false
  recordsError.value = false
  trial.value = null
  if (employee.value) {
    // 选中即出数据；未选（清空下拉）时保持"请先选择职工"的空态
    loadTrial()
    loadRecords()
  }
}

/**
 * 读取缴费试算：按当前政策参数拆出个人 / 统筹金额。
 * 失败不阻断缴费流程（试算只是预览，实际扣款以链上为准）。
 */
async function loadTrial() {
  if (!employee.value) {
    trial.value = null
    return
  }
  try {
    trial.value = await sponsorApi.paymentTrial(employee.value.trim())
  } catch (e) {
    trial.value = null
  }
}

/** 表格空态文案：没选职工 / 读取中 / 读取失败 / 查过但确实没有记录 */
const recordsEmptyText = computed(() => {
  if (!employee.value) return '请先选择已建档职工'
  if (loading.value) return '正在读取缴费记录…'
  if (recordsError.value) return '读取失败，请重试'
  return recordsLoaded.value ? '该职工暂无缴费记录' : '点上方「查缴费记录」读取'
})

async function loadAccount() {
  if (!sponsorAddress.value) return
  try {
    account.value = await sponsorApi.account(sponsorAddress.value)
  } catch (e) {
    account.value = null
  }
}

async function quote() {
  const result = await chainApi.call('ChainSIP', 'quotePayment', [salary.value])
  quoteResult.value = result.values
}

async function pay() {
  if (!employee.value) {
    ElMessage.warning('请先选择职工')
    return
  }
  if (trial.value) {
    const name = selectedEmployee.value?.name || employee.value
    try {
      await ElMessageBox.confirm(
        `确认为「${name}」缴纳本月费用？\n`
          + `缴费基数 ${formatAmount(trial.value.effectiveBase)}，个人账户 ${formatAmount(trial.value.personalAmount)}，`
          + `统筹账户 ${formatAmount(trial.value.overallAmount)}，合计 ${formatAmount(trial.value.totalAmount)}。`,
        '缴费确认',
        { type: 'info', confirmButtonText: '确认缴费', cancelButtonText: '取消' }
      )
    } catch (e) {
      return
    }
  }
  busy.value = true
  try {
    await sponsorApi.pay(employee.value.trim())
    ElMessage.success('缴费成功')
    await loadAccount()
    // 刷新职工明细：更新该职工的 lastPaidAt / 缴费基数
    await loadEmployees()
    // 缴费成功后同步刷新缴费记录与试算（未选职工时跳过，避免弹"请先选择职工"的警告）
    if (employee.value) {
      await loadTrial()
      await loadRecords()
    }
  } finally {
    busy.value = false
  }
}

async function loadRecords() {
  if (!employee.value) {
    ElMessage.warning('请先选择职工')
    return
  }
  loading.value = true
  recordsError.value = false
  try {
    const data = await sponsorApi.payments(employee.value.trim(), 0, 20)
    records.value = (data.records || []).map((r) => r)
    recordTotal.value = data.total || 0
    recordsLoaded.value = true
  } catch (e) {
    // http.js 拦截器已按后端 message 弹过提示；这里把状态收敛为"读取失败"，
    // 并清空上一次的数据，避免旧记录被误当成最新结果
    records.value = []
    recordTotal.value = 0
    recordsLoaded.value = false
    recordsError.value = true
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadAccount()
  loadEmployees()
})
</script>
