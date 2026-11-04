<template>
  <div>
    <h3 class="page-title">缴费单位账号</h3>
    <p class="page-desc">
      账号创建是<strong>层级</strong>的：平台管理员 → 社保局 → 缴费单位 → 参保职工。
      本页只能创建<strong>缴费单位（SPONSOR）</strong>账号，创建时会为该单位生成一把机构链私钥（机构地址随账号一起下发）。
      社保局还可以给自己所属的缴费单位<strong>注资</strong>（仅限本局服务城市内的单位）；平台管理员也能在「机构管理 → 缴费单位」注资。
    </p>

    <el-alert type="info" :closable="false" show-icon class="mb-16"
      title="创建后自动完成链上注册（由平台代为上链），创建成功即可为职工建档缴费">
      创建时后端会用 ADMIN 私钥代签，把该单位注册到链上（SPONSOR）；若自动注册失败（例如所选城市未注册），
      该机构会出现在管理员的「机构管理 → 待上链注册」里，由管理员补一次。
    </el-alert>

    <el-row :gutter="16">
      <el-col :span="9">
        <div class="card-block">
          <strong>新建缴费单位账号</strong>
          <el-form ref="formRef" :model="form" :rules="rules" label-width="100px" class="mt-16">
            <el-form-item label="用户名" prop="username">
              <el-input v-model="form.username" placeholder="登录账号，如 sponsor2" />
            </el-form-item>
            <el-form-item label="密码" prop="password">
              <el-input v-model="form.password" type="password" show-password
                placeholder="至少 6 位；长度不足会被后端拒绝" />
            </el-form-item>
            <el-form-item label="显示名" prop="displayName">
              <el-input v-model="form.displayName" placeholder="界面上展示的名称" />
            </el-form-item>
            <el-form-item label="单位名称" prop="name">
              <el-input v-model="form.name" placeholder="如 乙公司（链上机构名）" />
            </el-form-item>
            <el-form-item label="所在城市" prop="city">
              <el-select v-model="form.city" :disabled="!citySelectable" :loading="cityLoading"
                :placeholder="cityPlaceholder" style="width: 100%">
                <el-option v-for="item in cities" :key="item" :label="item" :value="item" />
              </el-select>
              <div v-if="cityMode === 'inherited'" class="text-muted tip">
                继承本社保局服务城市，无需填写。
              </div>
              <div v-else-if="cityMode === 'none'" class="text-muted tip">
                本社保局尚未在链上注册服务城市，请联系平台管理员。
                <el-button link type="primary" size="small" :loading="cityLoading" @click="loadCities">重新读取</el-button>
              </div>
              <div v-else-if="cityMode === 'choose'" class="text-muted tip">
                本社保局有多个服务城市，请选择该缴费单位所属的城市。
                <el-button link type="primary" size="small" :loading="cityLoading" @click="loadCities">重新读取</el-button>
              </div>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="creating" @click="create">创建缴费单位账号</el-button>
              <el-button :disabled="creating" @click="reset">重置</el-button>
            </el-form-item>
          </el-form>
          <div class="text-muted tip">
            机构编码由后端按规则生成，创建成功后会在结果里给出；
            账号通过机构编码绑定到一把机构私钥，单位发交易时由后端用该私钥签名。
            <strong>所在城市继承本社保局的服务城市</strong>（接口 GET /api/bureau/cities），不可手填。
          </div>
        </div>
      </el-col>

      <el-col :span="15">
        <div class="card-block">
          <div class="flex-between mb-8">
            <strong>本局下辖的缴费单位账号</strong>
            <el-button size="small" :loading="loading" @click="load">刷新</el-button>
          </div>
          <el-table :data="rows" size="small" empty-text="加载中">
            <el-table-column prop="username" label="用户名" width="120" />
            <el-table-column prop="displayName" label="显示名" width="120" />
            <el-table-column label="机构编码" width="140">
              <template #default="{ row }">
                <span class="mono">{{ row.institutionCode || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="机构链上地址" min-width="200">
              <template #default="{ row }">
                <span v-if="addressOf(row)" class="chain-address">{{ addressOf(row) }}</span>
                <span v-else class="text-muted">—</span>
              </template>
            </el-table-column>
            <el-table-column label="链上角色" width="100">
              <template #default="{ row }">
                <el-tag v-if="row.onChainRole && row.onChainRole !== 'NONE'" type="success" size="small">
                  {{ row.onChainRole }}
                </el-tag>
                <el-tag v-else-if="row.onChainRole === 'NONE'" type="warning" size="small">未注册</el-tag>
                <el-tooltip v-else content="链网关未就绪，或该单位还没有链上角色" placement="top">
                  <span class="text-muted">—</span>
                </el-tooltip>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag :type="row.status === 'ENABLED' ? 'success' : 'info'" size="small">
                  {{ row.status === 'ENABLED' ? '启用' : '禁用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="创建时间" width="165">
              <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="90" fixed="right">
              <template #default="{ row }">
                <el-tooltip :disabled="!!addressOf(row)" content="该单位没有链上地址，无法注资"
                  placement="top">
                  <span>
                    <el-button link type="primary" size="small" :disabled="!addressOf(row)"
                      @click="openDeposit(row)">注资</el-button>
                  </span>
                </el-tooltip>
              </template>
            </el-table-column>
          </el-table>
          <div class="text-muted tip">
            「链上角色」为空表示该单位的机构地址还没有完成链上注册：正常情况下创建时会自动注册，
            可以点「刷新」再看；仍未注册（自动注册失败）请到「机构管理 → 待上链注册」交给平台管理员补一次。
            「注资」只在链上注册完成后才有意义，机构地址为空的异常行不能注资。
          </div>
        </div>
      </el-col>
    </el-row>

    <el-dialog v-model="depositVisible" title="给缴费单位注资" width="560px" @closed="resetDepositForm">
      <el-descriptions :column="1" border size="small">
        <el-descriptions-item label="用户名">{{ depositTarget?.username || '—' }}</el-descriptions-item>
        <el-descriptions-item label="显示名">{{ depositTarget?.displayName || '—' }}</el-descriptions-item>
        <el-descriptions-item label="单位名称">{{ depositTarget?.name || '—' }}</el-descriptions-item>
        <el-descriptions-item label="机构编码">
          <span class="mono">{{ depositTarget?.institutionCode || '—' }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="机构链上地址">
          <span v-if="depositTargetAddress" class="chain-address">{{ depositTargetAddress }}</span>
          <span v-else class="text-muted">未返回（该单位没有链上地址，无法注资）</span>
        </el-descriptions-item>
      </el-descriptions>

      <el-form ref="depositFormRef" :model="depositForm" :rules="depositRules" label-width="90px"
        class="mt-16">
        <el-form-item label="注资金额" prop="amount">
          <el-input v-model="depositForm.amount" placeholder="如 100000" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="depositing" :disabled="!depositTargetAddress"
            @click="submitDeposit">确认注资</el-button>
          <el-button :disabled="depositing" @click="depositVisible = false">取消</el-button>
        </el-form-item>
      </el-form>

      <el-alert type="info" :closable="false" show-icon title="这是给该单位的统筹账户「增加」余额">
        链上调用 depositSponsor，会累加到该单位的<strong>累计注资</strong>，不能把它改成任意数值；
        金额单位为元，只接受正整数。单位可用余额可在该单位登录后的「缴费管理」里看到。
      </el-alert>
    </el-dialog>

    <el-dialog v-model="resultVisible" title="缴费单位账号创建成功" width="620px">
      <el-alert type="success" :closable="false" show-icon title="账号已开通" />
      <el-descriptions :column="1" border size="small" class="mt-16">
        <el-descriptions-item label="用户名">{{ result.account?.username }}</el-descriptions-item>
        <el-descriptions-item label="显示名">{{ result.account?.displayName || '—' }}</el-descriptions-item>
        <el-descriptions-item label="单位名称">{{ result.account?.name || '—' }}</el-descriptions-item>
        <el-descriptions-item label="机构编码">
          <span class="mono">{{ result.institutionCode || result.account?.institutionCode || '—' }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="机构链上地址">
          <div v-if="resultAddress" class="copy-row">
            <span class="chain-address">{{ resultAddress }}</span>
            <el-button link type="primary" size="small" @click="copy(resultAddress)">复制</el-button>
          </div>
          <span v-else class="text-muted">—（后端未返回地址）</span>
        </el-descriptions-item>
      </el-descriptions>
      <el-alert v-if="onChainRegistered === true" type="success" :closable="false" show-icon class="mt-16"
        title="已在链上注册并授权（由平台代为上链）">
        该单位的机构地址已注册为链上 SPONSOR，创建成功即可为职工建档（addEmployee）并缴费。
      </el-alert>
      <el-alert v-else-if="onChainRegistered === false" type="warning" :closable="false" show-icon class="mt-16"
        :title="onChainMessage || '链上自动注册失败'">
        可到「机构管理 → 待上链注册」补注册（等价于 addSponsorAccount）；补注册后该单位才能为职工建档并缴费。
        在此之前，该单位登录后发起的链上交易会被合约回滚。
      </el-alert>
      <el-alert v-else type="info" :closable="false" show-icon class="mt-16"
        title="未返回链上注册结果">
        正常情况下后端会在创建时自动完成链上注册；若该单位在链上还没有 SPONSOR 角色，
        请到「机构管理 → 待上链注册」查看并补注册。
      </el-alert>
      <el-alert v-if="result.hint" type="info" :closable="false" show-icon class="mt-16" :title="result.hint" />
      <template #footer>
        <el-button @click="resultVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, ref, reactive, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { bureauApi } from '../../api'
import { copyText, formatTime } from '../../utils/format'

const loading = ref(false)
const creating = ref(false)
const rows = ref([])
const formRef = ref(null)
const resultVisible = ref(false)
const result = ref({})

/** 本社保局的服务城市（GET /api/bureau/cities），缴费单位的城市必须从里面来 */
const cities = ref([])
const cityLoading = ref(false)
const cityLoaded = ref(false)

const form = reactive({
  username: '',
  password: '',
  displayName: '',
  name: '',
  city: ''
})

/**
 * 城市的三种形态：
 *  inherited 本局只有一个服务城市 → 自动选中并置灰（"继承本社保局服务城市"）
 *  choose    多个服务城市 → 必选
 *  none      没有服务城市（未注册 / 链未就绪）→ 禁用下拉、给提示，但不阻止提交（后端会给准确报错）
 */
const cityMode = computed(() => {
  if (!cityLoaded.value) return 'loading'
  if (cities.value.length === 0) return 'none'
  if (cities.value.length === 1) return 'inherited'
  return 'choose'
})
const citySelectable = computed(() => cityMode.value === 'choose')
const cityPlaceholder = computed(() => {
  if (cityMode.value === 'loading') return '正在读取本社保局服务城市…'
  if (cityMode.value === 'none') return '暂无可用城市'
  if (cityMode.value === 'inherited') return '继承本社保局服务城市'
  return '请选择服务城市'
})

const rules = computed(() => ({
  username: [{ required: true, message: '请填写用户名', trigger: 'blur' }],
  password: [
    { required: true, message: '请填写密码', trigger: 'blur' },
    { min: 6, message: '密码至少 6 位', trigger: 'blur' }
  ],
  city:
    cityMode.value === 'choose'
      ? [{ required: true, message: '请选择所在城市', trigger: 'change' }]
      : []
}))

/** 只有一个服务城市时自动选中；没有城市时清空（不给手填） */
function applyCityDefaults() {
  if (cities.value.length === 1) {
    form.city = cities.value[0]
  } else if (cities.value.length === 0) {
    form.city = ''
  } else if (!cities.value.includes(form.city)) {
    form.city = ''
  }
}

async function loadCities() {
  cityLoading.value = true
  try {
    const list = await bureauApi.cities()
    cities.value = Array.isArray(list) ? list.filter((item) => !!item) : []
  } catch (e) {
    // 链未就绪 / 网络异常：按"没有服务城市"处理，不阻止提交，后端会给出准确报错
    cities.value = []
  } finally {
    cityLoaded.value = true
    cityLoading.value = false
    applyCityDefaults()
  }
}

/** 后端可能把机构地址放在 chainAddress / address 上，这里都兼容 */
function addressOf(row) {
  return row?.chainAddress || row?.address || ''
}

/* ---------------- 给缴费单位注资（BUREAU → SPONSOR） ---------------- */
const depositVisible = ref(false)
const depositing = ref(false)
const depositTarget = ref(null)
const depositFormRef = ref(null)
const depositForm = reactive({ amount: '' })

const depositTargetAddress = computed(() => addressOf(depositTarget.value))

/** 金额：必填、纯数字（元）、且必须大于 0 */
function validateAmount(rule, value, callback) {
  const text = value === null || value === undefined ? '' : String(value).trim()
  if (!text) {
    callback(new Error('请输入注资金额'))
  } else if (!/^\d+$/.test(text)) {
    callback(new Error('金额必须是整数（元），不能带小数、符号或空格'))
  } else if (/^0+$/.test(text)) {
    callback(new Error('金额必须大于 0'))
  } else {
    callback()
  }
}

const depositRules = {
  amount: [{ validator: validateAmount, trigger: 'blur' }]
}

/** 去掉前导 0，交给后端的始终是干净的十进制字符串 */
function normalizeAmount(value) {
  return String(value === null || value === undefined ? '' : value).trim().replace(/^0+(?=\d)/, '')
}

function openDeposit(row) {
  if (!addressOf(row)) {
    ElMessage.warning('该单位没有链上地址，无法注资')
    return
  }
  depositTarget.value = row
  depositForm.amount = ''
  depositFormRef.value?.clearValidate()
  depositVisible.value = true
}

/** 弹窗关闭时清空金额与校验状态 */
function resetDepositForm() {
  depositForm.amount = ''
  depositFormRef.value?.clearValidate()
  depositTarget.value = null
}

async function submitDeposit() {
  const ok = await depositFormRef.value?.validate().catch(() => false)
  if (!ok) return
  const sponsor = depositTargetAddress.value
  if (!sponsor) {
    ElMessage.warning('该单位没有链上地址，无法注资')
    return
  }
  depositing.value = true
  try {
    await bureauApi.depositToSponsor({ sponsor, amount: normalizeAmount(depositForm.amount) })
    ElMessage.success('注资成功')
    depositVisible.value = false
    await load()
  } catch (e) {
    // http.js 拦截器已按后端 message 原样弹过提示（403 该缴费单位不属于本社保局 /
    // 400 单位未注册、金额非法），这里不再重复弹，避免两次 toast
  } finally {
    depositing.value = false
  }
}

const resultAddress = computed(() => result.value.chainAddress || result.value.account?.chainAddress || '')

/** 创建响应里的链上注册结果：true 已注册 / false 注册失败 / null（缺省）未返回，与 admin/Accounts.vue 同一套三态 */
const onChainRegistered = computed(() => {
  const value = result.value ? result.value.onChainRegistered : null
  return typeof value === 'undefined' ? null : value
})
const onChainMessage = computed(() => (result.value && result.value.onChainMessage) || '')

async function load() {
  loading.value = true
  try {
    rows.value = (await bureauApi.accounts()) || []
  } finally {
    loading.value = false
  }
}

/** 按后端返回的 onChainRegistered 给出不同提示（与 admin/Accounts.vue 同一套三态） */
function notifyCreateResult(data) {
  const registered = data ? data.onChainRegistered : null
  if (registered === true) {
    ElMessage.success('账号已创建，并已在链上注册')
  } else if (registered === false) {
    ElMessage.warning({
      message: `${(data && data.onChainMessage) || '链上自动注册失败'}；可到「机构管理 → 待上链注册」补注册`,
      duration: 6000
    })
  } else {
    ElMessage.success('账号已创建')
  }
}

async function create() {
  if (formRef.value) {
    const ok = await formRef.value.validate().catch(() => false)
    if (!ok) return
  }
  creating.value = true
  try {
    const data = await bureauApi.createAccount({ ...form })
    result.value = data || {}
    resultVisible.value = true
    notifyCreateResult(data)
    formRef.value?.resetFields()
    applyCityDefaults()
    await load()
  } finally {
    creating.value = false
  }
}

function reset() {
  formRef.value?.resetFields()
  applyCityDefaults()
}

async function copy(text) {
  const ok = await copyText(text)
  if (ok) {
    ElMessage.success('地址已复制')
  } else {
    ElMessage.warning('复制失败，请手动选中复制')
  }
}

onMounted(() => {
  load()
  loadCities()
})
</script>

<style scoped>
.tip {
  font-size: 12px;
  line-height: 1.6;
  margin-top: 8px;
}

.copy-row {
  display: flex;
  align-items: center;
  gap: 8px;
  word-break: break-all;
}
</style>
