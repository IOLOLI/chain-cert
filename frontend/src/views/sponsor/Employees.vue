<template>
  <div>
    <h3 class="page-title">职工管理</h3>
    <p class="page-desc">
      单位可为自己名下职工<strong>建档</strong>（addEmployee）、<strong>减员</strong>（suspendEmployee，停缴是转移放行的前置条件）、
      <strong>调薪</strong>（setSalary，会重算缴费基数）。这些交易都用本单位的机构私钥签名，
      合约会校验 {@code emp.sponsor == msg.sender}，所以只能操作自己的职工。
    </p>

    <div class="card-block">
      <div class="flex-between">
        <strong>待接续参保（按信息搜索）</strong>
        <el-button size="small" :loading="rehireLoading" @click="searchRehire">搜索</el-button>
      </div>
      <p class="text-muted tip">
        转移接收后职工处于「已转出待参保」，需要由用人单位发起接续。出于隐私保护，这里<strong>不列出全部人员</strong>：
        请输入<strong>完整 18 位身份证号</strong>，或 <strong>姓名 + 身份证后 4 位</strong>（如 李四2345）后再搜索。
      </p>
      <div class="toolbar mt-8">
        <el-input v-model="rehireKeyword" style="width: 360px" clearable
          placeholder="身份证号，或 姓名+身份证后4位（如 李四2345）" @keyup.enter="searchRehire" />
        <el-button type="primary" size="small" :loading="rehireLoading" @click="searchRehire">搜索</el-button>
      </div>
      <el-table v-if="rehireResults.length" :data="rehireResults" size="small" class="mt-8">
        <el-table-column label="姓名" width="120">
          <template #default="{ row }">{{ row.name || '未命名' }}</template>
        </el-table-column>
        <el-table-column label="身份证号（掩码）" min-width="180">
          <template #default="{ row }">{{ row.idNumber }}</template>
        </el-table-column>
        <el-table-column label="转入来源" width="150">
          <template #default="{ row }">{{ row.fromCity }} → {{ row.toCity }}</template>
        </el-table-column>
        <el-table-column label="接收时间" width="170">
          <template #default="{ row }">{{ formatTime(row.receivedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="130" align="right">
          <template #default="{ row }">
            <el-button type="primary" size="small" :loading="rehireBusy === row.address" @click="rehire(row)">
              发起接续
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-else-if="rehireSearched && !rehireLoading"
        description="没有找到匹配的待接续职工：请确认身份证号，或 姓名+后四位 是否正确" :image-size="56" />
    </div>

    <div class="card-block">
      <div class="flex-between">
        <div>
          <strong>本公司职工</strong>
          <span class="text-muted" style="margin-left: 8px">
            单位地址：{{ user.chainAddress || '（当前账号没有链上地址，请联系管理员）' }}
          </span>
        </div>
        <div>
          <el-button size="small" :loading="detailsLoading" @click="loadDetails">刷新</el-button>
          <el-button size="small" @click="$router.push('/sponsor/accounts')">职工账号</el-button>
          <el-button size="small" type="primary" @click="openDialog">为职工建档</el-button>
        </div>
      </div>

      <div class="toolbar mt-16">
        <span class="text-muted">选择已建档职工（用于减员 / 调薪）：</span>
        <el-select
          v-model="selectedAddress"
          filterable
          clearable
          :loading="detailsLoading"
          placeholder="按姓名搜索已建档职工"
          style="width: 300px"
        >
          <el-option
            v-for="item in employeeDetails"
            :key="item.address"
            :label="employeeOptionLabel(item)"
            :value="item.address"
          />
        </el-select>
        <el-button size="small" :disabled="!selectedEmployee" @click="openSuspendDialog">减员</el-button>
        <el-button size="small" type="warning" :disabled="!selectedEmployee" @click="openSalaryDialog">
          调薪
        </el-button>
        <el-button size="small" :loading="detailsLoading" @click="loadDetails">重新读取职工</el-button>
      </div>
      <div class="text-muted tip">
        这里只列<strong>链上已建档</strong>的职工（来源 GET /api/sponsor/employees/detail）；
        刚创建的<strong>职工账号</strong>要先「为职工建档」才会出现在这里。
      </div>

      <div v-if="detailsLoading" class="text-muted tip">正在读取本单位职工…</div>
      <div v-else-if="detailsError" class="text-muted tip">
        职工名单读取失败，请检查网络或后端服务后重试。
      </div>
      <div v-else-if="!employeeDetails.length" class="text-muted tip">
        本单位还没有已建档职工。请点「为职工建档」，从“职工账号”下拉里选择刚创建的职工账号（地址会自动带出）后提交。
        <el-button link type="primary" size="small" @click="openDialog">立即为职工建档</el-button>
        <el-button link type="primary" size="small" @click="$router.push('/sponsor/accounts')">
          还没有职工账号？去创建
        </el-button>
      </div>

      <el-alert
        v-if="suspendedSelected"
        class="mt-8"
        type="warning"
        :closable="false"
        show-icon
        title="该职工已停缴"
      />

      <el-descriptions v-if="selectedEmployee" class="mt-8" :column="3" border size="small" title="职工信息">
        <el-descriptions-item label="姓名">{{ selectedEmployee.name || '未命名' }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="employeeStatusType(selectedEmployee.status)" size="small">
            {{ employeeStatusText(selectedEmployee) }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="参保城市">{{ selectedEmployee.city || '-' }}</el-descriptions-item>
        <el-descriptions-item label="缴费基数">
          {{ formatAmount(selectedEmployee.paymentBase) }}
        </el-descriptions-item>
        <el-descriptions-item label="上次缴费时间">
          {{ formatTime(lastPaidOf(selectedEmployee)) }}
        </el-descriptions-item>
        <el-descriptions-item label="链上地址">
          <span class="chain-address">{{ shortAddress(selectedEmployee.address, 10, 8) }}</span>
        </el-descriptions-item>
      </el-descriptions>

      <el-table v-loading="detailsLoading" :data="employeeDetails" size="small" class="mt-16"
        empty-text="本单位还没有已建档职工，点上方「为职工建档」">
        <el-table-column label="职工地址" min-width="190">
          <template #default="{ row }">
            <span class="chain-address">{{ row.address }}</span>
          </template>
        </el-table-column>
        <el-table-column label="姓名" width="110">
          <template #default="{ row }">{{ employeeName(row) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="employeeStatusType(row.status)" size="small">
              {{ employeeStatusText(row) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="缴费基数" width="110">
          <template #default="{ row }">{{ formatAmount(row.paymentBase) }}</template>
        </el-table-column>
        <el-table-column label="个人账户" width="110">
          <template #default="{ row }">{{ formatAmount(row.personalBalance) }}</template>
        </el-table-column>
        <el-table-column label="统筹权益" width="110">
          <template #default="{ row }">{{ formatAmount(row.overallBalance) }}</template>
        </el-table-column>
        <el-table-column label="累计月数" width="90">
          <template #default="{ row }">{{ row.paidMonths ?? '-' }}</template>
        </el-table-column>
        <el-table-column label="操作" min-width="230">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="suspend(row)">减员</el-button>
            <el-button link type="warning" size="small" @click="changeSalary(row)">调薪</el-button>
            <el-button link type="success" size="small" @click="$router.push('/sponsor/payments')">
              去缴费
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="dialogVisible" title="为职工建档" width="560px">
      <el-form :model="form" label-width="140px">
        <el-form-item label="选择职工账号（用于建档）">
          <el-select
            v-model="selectedAccount"
            filterable
            clearable
            :loading="accountsLoading"
            placeholder="选择本单位职工账号（含刚创建、未建档的）"
            style="width: 100%"
            @change="onAccountChange"
          >
            <el-option
              v-for="item in accountOptions"
              :key="accountKey(item)"
              :label="accountLabel(item)"
              :value="accountKey(item)"
              :disabled="accountFiled(item)"
            />
          </el-select>
          <div class="text-muted tip">
            这里列的是本单位的<strong>职工账号</strong>（GET /api/sponsor/accounts，含刚创建但还没建档的）；
            <strong>已建档</strong>的账号会标注出来并不可选（重复建档会被合约 AlreadyInsured 回滚）。
            选中后自动填入该账号的链上地址，也可以直接在下面手动填写（兼容老账号）。
          </div>
          <div v-if="!accountsLoading && !accountOptions.length" class="text-muted tip">
            本单位还没有职工账号，请先到「职工账号」页创建职工账号。
            <el-button link type="primary" size="small" @click="goAccounts">去创建职工账号</el-button>
          </div>
        </el-form-item>
        <el-form-item label="职工地址">
          <el-input v-model="form.employee" placeholder="0x..." />
          <div v-if="selectedMissingAddress" class="text-muted tip">
            该职工账号暂无链上地址，请手动填写或联系管理员。
          </div>
        </el-form-item>
        <el-form-item label="姓名">
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="年龄">
          <el-input-number v-model="form.age" :min="16" :max="100" />
        </el-form-item>
        <el-form-item label="身份证号">
          <el-input v-model="form.idNumber" />
        </el-form-item>
        <el-form-item label="用工单位名称">
          <el-input v-model="form.employer" placeholder="默认用本公司名" />
        </el-form-item>
        <el-form-item label="入职日期">
          <el-date-picker v-model="startDate" type="date" style="width: 100%" />
        </el-form-item>
        <el-form-item label="月工资（元）">
          <el-input-number v-model="form.salary" :min="1" :step="500" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="submit">提交上链</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { sponsorApi } from '../../api'
import {
  employeeStatusName,
  employeeStatusType,
  formatAmount,
  formatTime,
  shortAddress
} from '../../utils/format'
import { useUserStore } from '../../stores/user'

const user = useUserStore()
const router = useRouter()
const busy = ref(false)
const dialogVisible = ref(false)
const startDate = ref(new Date())
/** 建档表单：本单位职工账号下拉 */
const accountOptions = ref([])
const accountsLoading = ref(false)
const selectedAccount = ref('')
/**
 * 本单位职工明细（GET /sponsor/employees/detail）：**下拉与列表共用这一份数据**，
 * 不再走只返回地址的 /sponsor/employees，也省掉了每行一次 getInsuranceAccount 的 N+1 查询。
 */
const employeeDetails = ref([])
const detailsLoading = ref(false)
const detailsError = ref(false)
const selectedAddress = ref('')
/** 待接续参保：只按信息搜索，不列全量（方案 A） */
const rehireKeyword = ref('')
const rehireResults = ref([])
const rehireLoading = ref(false)
const rehireSearched = ref(false)
const rehireBusy = ref('')

const form = reactive({
  employee: '',
  name: '',
  age: 30,
  idNumber: '',
  employer: '',
  startDate: 0,
  salary: 10000
})

/** 下拉选中的职工明细行 */
const selectedEmployee = computed(
  () => employeeDetails.value.find((item) => item.address === selectedAddress.value) || null
)

/** 职工展示名：优先姓名；后端未返回姓名时退化为地址缩写（不报错） */
function employeeName(row) {
  return row?.name || shortAddress(row?.address || '', 8, 4)
}

/**
 * 把接口返回归一化成数组：兼容裸数组与 {list}/{records}/{rows}/{data} 包裹；
 * 拿不到数组时返回空数组，绝不抛错（否则会跳过 loading 复位）。
 */
function normalizeList(data) {
  if (Array.isArray(data)) return data
  if (data && typeof data === 'object') {
    for (const key of ['list', 'records', 'rows', 'data', 'items']) {
      if (Array.isArray(data[key])) return data[key]
    }
  }
  return []
}

/** 状态文案：优先后端 statusName，缺字段时回退到本地状态码映射 */
function employeeStatusText(row) {
  return row?.statusName || employeeStatusName(row?.status)
}

/** 上次缴费时间：兼容后端新增的 lastPaymentAt 与既有的 lastPaidAt */
function lastPaidOf(row) {
  return row?.lastPaymentAt ?? row?.lastPaidAt
}

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

/** 读取本单位职工明细（下拉 + 列表都吃这一份）；失败时置错误标记，页面给出「重新读取职工」按钮 */
async function loadDetails() {
  detailsLoading.value = true
  detailsError.value = false
  try {
    const data = await sponsorApi.employeeDetails(0, 50)
    // 正常返回 data 数组；个别网关会多包一层 data，这里做一次兜底
    employeeDetails.value = normalizeList(data)
  } catch (e) {
    employeeDetails.value = []
    detailsError.value = true
  } finally {
    detailsLoading.value = false
  }
}

/** 顶部工具栏 / 操作按钮用：选中职工那一行（直接复用明细数据） */
function selectedRow() {
  if (!selectedAddress.value) return null
  return (
    employeeDetails.value.find((item) => item.address === selectedAddress.value) || {
      address: selectedAddress.value
    }
  )
}

function openSuspendDialog() {
  const row = selectedRow()
  if (!row) {
    ElMessage.warning('请先选择职工')
    return
  }
  suspend(row)
}

function openSalaryDialog() {
  const row = selectedRow()
  if (!row) {
    ElMessage.warning('请先选择职工')
    return
  }
  changeSalary(row)
}

/**
 * 已建档职工的地址集合（来自 employees/detail = 链上已建档口径）。
 * 用来给建档对话框里的「职工账号」下拉标注 未建档 / 已建档。
 */
const filedAddressSet = computed(() => {
  const set = new Set()
  for (const item of employeeDetails.value) {
    const address = (item?.address || '').toLowerCase()
    if (address) set.add(address)
  }
  return set
})

/** 该职工账号是否已经建档（地址命中已建档职工列表） */
function accountFiled(row) {
  const address = (row && (row.chainAddress || row.address)) || ''
  return !!address && filedAddressSet.value.has(String(address).toLowerCase())
}

/** 下拉项的稳定 key：优先用后端 id，老接口退化用用户名 */
function accountKey(row) {
  return String((row && (row.id ?? row.username)) ?? '')
}

/** 下拉项展示：用户名（显示名）（未建档/已建档）— 链上地址；地址为空只显示账号，退化为手动输入 */
function accountLabel(row) {
  const name = (row && row.username) || ''
  const display = row && row.displayName ? `（${row.displayName}）` : ''
  const tag = accountFiled(row) ? '已建档' : '未建档'
  const address = (row && (row.chainAddress || row.address)) || ''
  const tail = address ? ` — ${address}` : ''
  return `${name}${display}（${tag}）${tail}`
}

const selectedMissingAddress = computed(() => {
  if (!selectedAccount.value) return false
  const target = accountOptions.value.find((item) => accountKey(item) === selectedAccount.value)
  return !!target && !((target.chainAddress || target.address) || '')
})

/** 拉取本单位职工账号（账号口径，含刚创建未建档的）；接口未就绪时静默退化为手动输入，不报错 */
async function loadAccounts() {
  accountsLoading.value = true
  try {
    accountOptions.value = normalizeList(await sponsorApi.accounts())
  } catch (e) {
    accountOptions.value = []
  } finally {
    accountsLoading.value = false
  }
}

function openDialog() {
  selectedAccount.value = ''
  dialogVisible.value = true
  loadAccounts()
}

/** 没有职工账号时，从建档对话框直接跳到「职工账号」页去创建 */
function goAccounts() {
  dialogVisible.value = false
  router.push('/sponsor/accounts')
}

/** 选中职工账号后，自动把该职工自己的链上地址填进地址栏 */
function onAccountChange(key) {
  const target = accountOptions.value.find((item) => accountKey(item) === key)
  const address = target ? target.chainAddress || target.address || '' : ''
  if (address) form.employee = address
}

async function submit() {
  if (!form.employee || !form.name || !form.idNumber) {
    ElMessage.warning('请填写职工地址、姓名与身份证号')
    return
  }
  busy.value = true
  try {
    await sponsorApi.addEmployee({
      ...form,
      startDate: startDate.value ? Math.floor(new Date(startDate.value).getTime() / 1000) : 0
    })
    ElMessage.success('建档成功')
    dialogVisible.value = false
    selectedAccount.value = ''
    await loadDetails()
  } finally {
    busy.value = false
  }
}

async function suspend(row) {
  let value
  try {
    ;({ value } = await ElMessageBox.prompt('请输入减员原因', `减员 ${employeeName(row)}`, {
      inputPlaceholder: '离职 / 转出'
    }))
  } catch (e) {
    return
  }
  await sponsorApi.suspend(row.address, value || '离职')
  ElMessage.success('已减员（停缴）')
  await loadDetails()
}

async function changeSalary(row) {
  let value
  try {
    ;({ value } = await ElMessageBox.prompt('请输入新的月工资', `调薪 ${employeeName(row)}`, {
      inputValue: String(row.salary ?? row.paymentBase ?? 10000)
    }))
  } catch (e) {
    return
  }
  await sponsorApi.setSalary(row.address, value)
  ElMessage.success('已调薪并重算缴费基数')
  await loadDetails()
}

async function searchRehire() {
  const keyword = rehireKeyword.value.trim()
  if (!keyword) {
    ElMessage.warning('请输入完整身份证号，或 姓名+身份证后4位')
    return
  }
  rehireLoading.value = true
  rehireSearched.value = false
  try {
    rehireResults.value = normalizeList(await sponsorApi.searchRehire(keyword))
  } catch (e) {
    rehireResults.value = []
  } finally {
    rehireLoading.value = false
    rehireSearched.value = true
  }
}

/** 发起接续：后端用链上档案代填参数，企业私钥签名 addEmployee */
async function rehire(row) {
  try {
    await ElMessageBox.confirm(
      `确认为「${row.name || shortAddress(row.address)}」发起接续参保？缴费基数沿用原基数。`,
      '发起接续参保',
      { type: 'info', confirmButtonText: '接续', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  rehireBusy.value = row.address
  try {
    await sponsorApi.rehire(row.address)
    ElMessage.success('已接续参保，可以正常缴费了')
    rehireResults.value = []
    rehireKeyword.value = ''
    rehireSearched.value = false
    await loadDetails()
  } catch (e) {
    /* http.js 已提示 */
  } finally {
    rehireBusy.value = ''
  }
}

onMounted(loadDetails)
</script>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.tip {
  font-size: 12px;
  line-height: 1.6;
  margin-top: 4px;
}
</style>
