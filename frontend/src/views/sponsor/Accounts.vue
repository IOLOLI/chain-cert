<template>
  <div>
    <h3 class="page-title">职工账号</h3>
    <p class="page-desc">
      账号创建是<strong>层级</strong>的：平台管理员 → 社保局 → 缴费单位 → 参保职工。
      本页只能创建<strong>参保职工（EMPLOYEE）</strong>账号，且只能创建<strong>本单位名下</strong>的职工账号。
    </p>

    <el-alert type="info" :closable="false" show-icon class="mb-16"
      title="链上地址在创建账号时自动生成并绑定，用户不需要做任何绑定操作。">
      职工账号创建成功后，后端会<strong>自动为该职工生成链上地址并同时写为绑定地址</strong>；单位直接用它为职工建档即可。
      账号地址在创建时自动生成并绑定，界面不再提供绑定页（后端仍保留绑定接口）。职工账号不需要机构编码，也不占用本单位的机构私钥。
    </el-alert>

    <el-row :gutter="16">
      <el-col :span="9">
        <div class="card-block">
          <strong>新建职工账号</strong>
          <el-form ref="formRef" :model="form" :rules="rules" label-width="90px" class="mt-16">
            <el-form-item label="用户名" prop="username">
              <el-input v-model="form.username" placeholder="登录账号，如 employee2" />
            </el-form-item>
            <el-form-item label="密码" prop="password">
              <el-input v-model="form.password" type="password" show-password
                placeholder="至少 6 位；长度不足会被后端拒绝" />
            </el-form-item>
            <el-form-item label="显示名" prop="displayName">
              <el-input v-model="form.displayName" placeholder="建议填职工姓名" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="creating" @click="create">创建职工账号</el-button>
              <el-button :disabled="creating" @click="reset">重置</el-button>
            </el-form-item>
          </el-form>
          <div class="text-muted tip">
            创建成功后，在
            <el-link type="primary" :underline="false" @click="$router.push('/sponsor/employees')">「职工管理」</el-link>
            里选中该职工账号（会自动带出其链上地址）建档，建档之后才能缴费。
          </div>
        </div>
      </el-col>

      <el-col :span="15">
        <div class="card-block">
          <div class="flex-between mb-8">
            <strong>本单位职工账号</strong>
            <el-button size="small" :loading="loading" @click="load">刷新</el-button>
          </div>
          <el-table v-loading="loading" :data="rows" size="small" :empty-text="emptyText">
            <el-table-column prop="username" label="用户名" width="140" />
            <el-table-column prop="displayName" label="显示名" width="140" />
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag :type="row.status === 'ENABLED' ? 'success' : 'info'" size="small">
                  {{ row.status === 'ENABLED' ? '启用' : '禁用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="链上地址" min-width="200">
              <template #default="{ row }">
                <span v-if="addressOf(row)" class="chain-address">{{ addressOf(row) }}</span>
                <span v-else class="text-muted">未返回（请联系管理员）</span>
              </template>
            </el-table-column>
            <el-table-column label="创建时间" width="165">
              <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
            </el-table-column>
          </el-table>
          <div v-if="loadError" class="text-muted tip">
            职工账号列表读取失败，可点右上角「刷新」重试。
          </div>
          <div class="text-muted tip">
            「链上地址」来自后端在创建职工账号时自动生成的地址；老数据回退到职工本人验签绑定的钱包地址。
            创建完成后，在
            <el-link type="primary" :underline="false" @click="$router.push('/sponsor/employees')">「职工管理」</el-link>
            里选中该账号即可自动带出地址并建档。
          </div>
        </div>
      </el-col>
    </el-row>

    <el-dialog v-model="resultVisible" title="职工账号创建成功" width="560px">
      <el-alert type="success" :closable="false" show-icon title="账号已开通，链上地址已自动生成" />
      <el-descriptions :column="1" border size="small" class="mt-16">
        <el-descriptions-item label="用户名">{{ result.account?.username }}</el-descriptions-item>
        <el-descriptions-item label="显示名">{{ result.account?.displayName || '—' }}</el-descriptions-item>
        <el-descriptions-item label="链上地址">
          <span v-if="createdChainAddress" class="chain-address">{{ createdChainAddress }}</span>
          <span v-else class="text-muted">未返回（不影响建档，可在「职工管理」中查看）</span>
        </el-descriptions-item>
      </el-descriptions>
      <el-alert type="info" :closable="false" show-icon class="mt-16" title="下一步：为单位名下职工建档">
        到
        <el-link type="primary" :underline="false" @click="$router.push('/sponsor/employees')">「职工管理」</el-link>
        里选中该职工账号建档（地址会自动带出，无需再手动绑定）。请提醒职工尽快修改初始密码。
        账号地址已在创建时自动生成并绑定，界面不再提供额外的绑定页。
      </el-alert>
      <el-alert v-if="result.hint" type="info" :closable="false" show-icon class="mt-16" :title="result.hint" />
      <template #footer>
        <el-button @click="resultVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { sponsorApi } from '../../api'
import { formatTime } from '../../utils/format'

const loading = ref(false)
const creating = ref(false)
const rows = ref([])
const formRef = ref(null)
const resultVisible = ref(false)
const result = ref({})
/** 列表读取是否失败（失败时清空列表并给出可重试的文案） */
const loadError = ref(false)

const form = reactive({ username: '', password: '', displayName: '' })

/**
 * 表格空态文案：读取中 / 读取失败 / 确实没有账号。
 * 注意不能再写死的"加载中"——空数组时它会一直显示，看起来像卡在加载。
 */
const emptyText = computed(() => {
  if (loading.value) return '正在读取职工账号…'
  if (loadError.value) return '读取失败，请重试'
  return '本单位暂无职工账号，可用左侧表单创建'
})

/**
 * 把接口返回归一化成数组：兼容裸数组，以及被包裹的情况（{list} / {records} / {rows} / {data}）。
 * 拿不到数组时返回空数组，绝不抛错（否则会跳过 loading 复位）。
 */
function toArray(data) {
  if (Array.isArray(data)) return data
  if (data && typeof data === 'object') {
    for (const key of ['list', 'records', 'rows', 'data', 'items']) {
      if (Array.isArray(data[key])) return data[key]
    }
  }
  return []
}

/** 创建响应里后端自动生成的链上地址（兼容放在 data 顶层或 data.account 里） */
const createdChainAddress = computed(
  () => result.value.chainAddress || result.value.account?.chainAddress || ''
)

const rules = {
  username: [{ required: true, message: '请填写用户名', trigger: 'blur' }],
  password: [
    { required: true, message: '请填写密码', trigger: 'blur' },
    { min: 6, message: '密码至少 6 位', trigger: 'blur' }
  ]
}

/** 职工账号的链上地址：优先后端返回的 chainAddress / address，老数据回退到已验签的钱包地址 */
function addressOf(row) {
  if (row?.chainAddress || row?.address) return row.chainAddress || row.address
  const verified = (row?.wallets || []).filter((w) => w && w.verified)
  if (!verified.length) return ''
  return (verified.find((w) => w.primaryWallet) || verified[0]).address || ''
}

async function load() {
  loading.value = true
  loadError.value = false
  try {
    // GET /api/sponsor/accounts（无参数，单位由登录态推断）
    rows.value = toArray(await sponsorApi.accounts())
  } catch (e) {
    // http.js 已按后端 message 弹过提示；这里置失败态并清空，避免旧数据残留
    rows.value = []
    loadError.value = true
  } finally {
    // 成功 / 失败 / 返回非数组都在这里复位，保证不会一直"加载中"
    loading.value = false
  }
}

async function create() {
  if (formRef.value) {
    const ok = await formRef.value.validate().catch(() => false)
    if (!ok) return
  }
  creating.value = true
  try {
    const data = await sponsorApi.createAccount({ ...form })
    result.value = data || {}
    resultVisible.value = true
    ElMessage.success('职工账号已创建')
    formRef.value?.resetFields()
    await load()
  } finally {
    creating.value = false
  }
}

function reset() {
  formRef.value?.resetFields()
}

onMounted(load)
</script>

<style scoped>
.tip {
  font-size: 12px;
  line-height: 1.6;
  margin-top: 8px;
}
</style>
