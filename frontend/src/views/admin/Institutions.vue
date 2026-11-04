<template>
  <div class="institutions">
    <PageHeader title="机构与账号"
      desc="平台账号的链下角色只决定界面可见性；能不能发起上链交易，取决于该账号机构密钥地址在链上有没有角色。链上未注册的机构会落在下方「待上链注册」里。">
      <el-button type="primary" :icon="Plus" @click="openCreate">开通机构账号</el-button>
      <el-button :icon="Refresh" :loading="loading" @click="loadAll">刷新</el-button>
    </PageHeader>

    <div class="summary">
      <span class="sum-item">账号 <b>{{ rows.length }}</b></span>
      <span class="sum-div"></span>
      <span class="sum-item" title="取自合约 roleOf：链网关未就绪时该列为「—」，不影响账号列表本身">
        链上有角色 <b class="is-ok">{{ readyCount }}</b>
      </span>
      <span class="sum-div"></span>
      <span class="sum-item">待上链 / 待授权 <b :class="{ 'is-warn': pending.length > 0 }">{{ pending.length }}</b></span>
      <span class="sum-div"></span>
      <span class="sum-item">已禁用 <b :class="{ 'is-off': disabledCount > 0 }">{{ disabledCount }}</b></span>
      <span v-if="chainRolesUnavailable" class="sum-note">链网关未就绪：链上角色暂不可用（列表不受影响）</span>
    </div>

    <el-alert v-if="pending.length" type="warning" show-icon class="mb-18">
      <template #title>
        有 {{ pending.length }} 个机构的链上身份未完成（未注册 / 未授权）
      </template>
      <div class="alert-body">
        <span>这些账号在链下已经建好、也能登录，但链上还没有对应角色，任何上链操作都会被合约回滚。</span>
        <el-button link type="primary" size="small" @click="scrollToPending">去处理</el-button>
      </div>
    </el-alert>

    <el-card shadow="never" class="panel">
      <template #header>
        <h2 class="section-title">平台账号</h2>
        <span class="panel-meta">共 {{ rows.length }} 个 · 按角色分类 · 每页 {{ accountPageSize }} 条</span>
      </template>

      <div class="filter-bar">
        <el-radio-group v-model="accountRole" size="small" @change="onRoleFilterChange">
          <el-radio-button value="ALL">全部</el-radio-button>
          <el-radio-button value="ADMIN">管理员</el-radio-button>
          <el-radio-button value="BUREAU">社保局</el-radio-button>
          <el-radio-button value="SPONSOR">缴费单位</el-radio-button>
          <el-radio-button value="REGULATOR">监管方</el-radio-button>
          <el-radio-button value="EMPLOYEE">职工</el-radio-button>
        </el-radio-group>
      </div>

      <el-table :data="pagedRows" v-loading="loading" style="width: 100%" row-key="id"
        :row-class-name="rowClassName"
        :header-cell-style="{ background: 'var(--tone)' }">
        <el-table-column label="账号" min-width="180">
          <template #default="{ row }">
            <div class="cell-name">{{ row.displayName || row.username }}</div>
            <div class="cell-sub">{{ row.username }}</div>
          </template>
        </el-table-column>

        <el-table-column label="链下角色" width="120" align="left">
          <template #default="{ row }">
            <el-tag :type="roleTagType(row.role)" effect="light" size="small">{{ roleName(row.role) }}</el-tag>
          </template>
        </el-table-column>

        <el-table-column label="机构链账户" min-width="200">
          <template #default="{ row }">
            <AddressText :address="row.chainAddress" />
            <div v-if="row.institutionCode" class="cell-sub">{{ row.institutionCode }}</div>
          </template>
        </el-table-column>

        <el-table-column label="链上角色" width="150" align="left">
          <template #default="{ row }">
            <!-- 空值 = 没查到（链未就绪 / 该地址还没有角色），不能写成"链未就绪" -->
            <el-tag v-if="row.onChainRole === 'NONE'" :type="row.role === 'EMPLOYEE' ? 'info' : 'warning'"
              effect="light" size="small">
              {{ row.role === 'EMPLOYEE' ? '无需链上角色' : '未注册' }}
            </el-tag>
            <el-tag v-else-if="row.onChainRole" type="success" effect="light" size="small">
              {{ row.onChainRole }}
            </el-tag>
            <el-tooltip v-else content="链网关未就绪，或该地址还没有链上角色" placement="top">
              <span class="cell-sub">—</span>
            </el-tooltip>
          </template>
        </el-table-column>

        <el-table-column label="账号状态" width="100" align="left">
          <template #default="{ row }">
            <span class="dot" :class="row.status === 'ENABLED' ? 'is-ok' : 'is-off'"></span>
            {{ row.status === 'ENABLED' ? '正常' : '已禁用' }}
          </template>
        </el-table-column>

        <el-table-column label="操作" width="150" align="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="resetPassword(row)">重置密码</el-button>
            <el-button link :type="row.status === 'ENABLED' ? 'danger' : 'primary'" size="small"
              @click="toggleStatus(row)">
              {{ row.status === 'ENABLED' ? '禁用' : '启用' }}
            </el-button>
          </template>
        </el-table-column>

        <template #empty>
          <el-empty description="还没有开通任何账号" :image-size="72" />
        </template>
      </el-table>
      <el-pagination
        v-if="filteredRows.length > accountPageSize"
        class="pager"
        layout="prev, pager, next, total"
        :total="filteredRows.length"
        :page-size="accountPageSize"
        :current-page="accountPage"
        @current-change="onAccountPage" />
    </el-card>

    <!-- ------------------------------ 机构链账户（私钥） ------------------------------ -->
    <el-card shadow="never" class="panel">
      <template #header>
        <h2 class="section-title">机构链账户</h2>
        <span class="panel-meta">共 {{ keys.length }} 个 · 按角色分类 · 每页 {{ keyPageSize }} 条 · 私钥 AES-256-GCM 密文落库，接口不返回明文</span>
      </template>

      <div class="ops-grid">
        <div class="op-block">
          <el-form :model="keyForm" label-width="86px">
            <el-form-item label="机构编码">
              <el-input v-model="keyForm.code" placeholder="如 SPONSOR_S2" />
            </el-form-item>
            <el-form-item label="机构名称">
              <el-input v-model="keyForm.name" placeholder="乙公司" />
            </el-form-item>
            <el-form-item label="角色">
              <el-select v-model="keyForm.role" style="width: 100%">
                <el-option label="ADMIN" value="ADMIN" />
                <el-option label="SPONSOR" value="SPONSOR" />
                <el-option label="BUREAU" value="BUREAU" />
                <el-option label="REGULATOR" value="REGULATOR" />
              </el-select>
            </el-form-item>
            <el-form-item label="导入私钥">
              <el-input v-model="keyForm.privateKeyHex" placeholder="留空则随机生成一把新私钥" />
            </el-form-item>
            <el-form-item label="备注">
              <el-input v-model="keyForm.remark" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="keyBusy" @click="createKey">登记机构链账户</el-button>
              <span class="op-tip">生成后把地址拿去链上注册（公司→addSponsorAccount，社保局→addBureaus，监管→addRegulatorAccount）</span>
            </el-form-item>
          </el-form>
        </div>
      </div>

      <div class="filter-bar">
        <el-radio-group v-model="keyRole" size="small" @change="onKeyRoleFilterChange">
          <el-radio-button value="ALL">全部</el-radio-button>
          <el-radio-button value="ADMIN">管理员</el-radio-button>
          <el-radio-button value="BUREAU">社保局</el-radio-button>
          <el-radio-button value="SPONSOR">缴费单位</el-radio-button>
          <el-radio-button value="REGULATOR">监管方</el-radio-button>
          <el-radio-button value="EMPLOYEE">职工</el-radio-button>
        </el-radio-group>
      </div>

      <el-table :data="pagedKeys" v-loading="loading" style="width: 100%">
        <el-table-column prop="code" label="机构编码" min-width="130">
          <template #default="{ row }"><span class="mono">{{ row.code }}</span></template>
        </el-table-column>
        <el-table-column prop="name" label="名称" min-width="120" />
        <el-table-column label="角色" width="100">
          <template #default="{ row }">
            <el-tag :type="roleTagType(row.role)" effect="light" size="small">{{ roleName(row.role) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="链上地址" min-width="200">
          <template #default="{ row }"><AddressText :address="row.address" /></template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="100" align="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="toggleKey(row)">
              {{ row.enabled ? '停用' : '启用' }}
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="还没有登记机构链账户" :image-size="64" />
        </template>
      </el-table>
      <el-pagination
        v-if="filteredKeys.length > keyPageSize"
        class="pager"
        layout="prev, pager, next, total"
        :total="filteredKeys.length"
        :page-size="keyPageSize"
        :current-page="keyPage"
        @current-change="onKeyPage" />
    </el-card>

    <el-card ref="pendingCard" shadow="never" class="panel">
      <template #header>
        <h2 class="section-title">待上链注册</h2>
        <span class="panel-meta">判定以链上 roleOf / getBureau 为准，链未就绪时不显示</span>
      </template>

      <el-table :data="pending" v-loading="loading" style="width: 100%" row-key="code">
        <el-table-column label="机构编码" min-width="150">
          <template #default="{ row }"><span class="mono">{{ row.code }}</span></template>
        </el-table-column>
        <el-table-column label="角色" width="110">
          <template #default="{ row }">
            <el-tag :type="roleTagType(row.role)" effect="light" size="small">{{ roleName(row.role) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="name" label="机构名称" min-width="140" show-overflow-tooltip />
        <el-table-column label="链上地址" min-width="180">
          <template #default="{ row }"><AddressText :address="row.address" /></template>
        </el-table-column>
        <el-table-column label="链上现状" width="120">
          <template #default="{ row }">
            <el-tag type="warning" effect="light" size="small">{{ row.missing || '待处理' }}</el-tag>
            <div class="cell-sub">当前 {{ row.onChainRole || 'NONE' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="110" align="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" :loading="registering === row.code"
              @click="registerOne(row)">注册上链</el-button>
          </template>
        </el-table-column>

        <template #empty>
          <el-empty description="全部机构的链上身份都已就绪" :image-size="64" />
        </template>
      </el-table>
    </el-card>

    <!-- ------------------------------ 资金与合约接线 ------------------------------ -->
    <el-card shadow="never" class="panel">
      <template #header>
        <h2 class="section-title">资金与合约接线</h2>
        <span class="panel-meta">注资（depositSponsor）是累加，不能改成任意数值；接线只在部署新转移合约时改一次</span>
      </template>

      <div class="ops-grid">
        <div class="op-block">
          <el-form label-width="86px">
            <el-form-item label="缴费单位">
              <el-select v-model="depositForm.sponsor" filterable style="width: 100%"
                placeholder="选择已注册的缴费单位">
                <el-option v-for="item in sponsorOptions" :key="item.chainAddress"
                  :label="`${item.displayName || item.username}（${item.institutionCode || item.chainAddress}）`"
                  :value="item.chainAddress" />
              </el-select>
            </el-form-item>
            <el-form-item label="注资金额">
              <el-input v-model="depositForm.amount" placeholder="如 100000" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="depositing" @click="submitDeposit">注资</el-button>
              <span class="op-tip">给该单位统筹账户「增加」余额，只接受正整数</span>
            </el-form-item>
          </el-form>
        </div>

        <div class="op-block">
          <el-form label-width="86px">
            <el-form-item label="当前接线">
              <span class="mono">{{ transferContract || '未读取到（链网关未就绪）' }}</span>
            </el-form-item>
            <el-form-item label="新地址">
              <el-input v-model="transferForm.address" placeholder="0x + 40 位十六进制" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="wiring" @click="submitTransferContract">保存接线</el-button>
              <span class="op-tip">重复设置同一地址会以 TransferContractUnchanged 回滚（正常）</span>
            </el-form-item>
          </el-form>
        </div>
      </div>
    </el-card>

    <!-- ------------------------------ 开通账号 ------------------------------ -->
    <el-dialog v-model="createVisible" title="开通机构账号" width="560px" @closed="resetForm">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="94px">
        <el-form-item label="角色" prop="role">
          <el-select v-model="form.role" style="width: 100%" @change="onRoleChange">
            <el-option v-for="opt in roleOptions" :key="opt.value" :label="opt.label" :value="opt.value">
              <span>{{ opt.label }}</span>
              <span class="opt-hint">{{ opt.hint }}</span>
            </el-option>
          </el-select>
        </el-form-item>

        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="用户名" prop="username">
              <el-input v-model="form.username" placeholder="3-32 位字母/数字/下划线"
                autocomplete="off" name="chainsip-new-username" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="初始密码" prop="password">
              <el-input v-model="form.password" type="password" show-password placeholder="至少 6 位"
                autocomplete="new-password" name="chainsip-new-password" />
            </el-form-item>
          </el-col>
        </el-row>

        <el-form-item label="显示名" prop="displayName">
          <el-input v-model="form.displayName" placeholder="留空则与用户名相同" />
        </el-form-item>

        <template v-if="needsChainRegistration">
          <el-form-item label="机构名称" prop="name">
            <el-input v-model="form.name" :placeholder="roleHint" />
          </el-form-item>
          <el-form-item v-if="needsCity" label="所在城市" prop="city">
            <!-- 缴费单位：城市必须来自链上已注册城市；社保局：城市是新占用的，只能手填（且不能撞已有城市） -->
            <el-select v-if="form.role === 'SPONSOR'" v-model="form.city" filterable style="width: 100%"
              placeholder="选择链上已注册城市">
              <el-option v-for="c in cities" :key="c.city" :label="c.city" :value="c.city">
                <span>{{ c.city }}</span>
                <span class="opt-hint">{{ c.bureauName || '未命名社保局' }}</span>
              </el-option>
            </el-select>
            <el-input v-else v-model="form.city" placeholder="如 A市（须是链上尚未被占用的城市）" />
            <div class="city-hint">{{ cityHint }}</div>
          </el-form-item>
        </template>

        <el-alert type="info" :closable="false" show-icon class="form-note"
          :title="form.role === 'EMPLOYEE' ? '职工链上地址会自动生成（EMP_ 开头），单位用它在链上建档即可' : '账号创建后会同时生成一把机构链上密钥，私钥加密落库、接口不返回明文'" />
      </el-form>

      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submit">创建账号</el-button>
      </template>
    </el-dialog>

    <!-- ------------------------------ 开通结果（常驻，可定位新账号） ------------------------------ -->
    <el-dialog v-model="resultVisible" title="账号已开通" width="560px" @closed="createdResult = null">
      <template v-if="createdResult">
        <el-alert :type="resultAlertType" :title="resultTitle" :closable="false" show-icon class="result-alert" />
        <el-descriptions :column="1" border size="small" class="result-desc">
          <el-descriptions-item label="账号">{{ createdResult.username }}</el-descriptions-item>
          <el-descriptions-item label="显示名">{{ createdResult.displayName || '—' }}</el-descriptions-item>
          <el-descriptions-item label="链下角色">{{ roleName(createdResult.role) }}</el-descriptions-item>
          <el-descriptions-item label="机构链账户编码">{{ createdResult.institutionCode || '—' }}</el-descriptions-item>
          <el-descriptions-item label="链上地址">
            <span class="mono">{{ createdResult.chainAddress || '—' }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="链上注册结果">{{ createdResult.onChainMessage || '—' }}</el-descriptions-item>
        </el-descriptions>
        <el-alert v-if="createdResult.onChainRegistered === false" type="info" :closable="false" show-icon
          class="result-tip"
          title="账号已建好、可以正常登录；链上身份就绪前它发起的上链交易会被合约回滚，请到「待上链注册」补一次。" />
        <p v-else-if="createdResult.onChainRegistered === null" class="result-tip-text">
          该角色不需要链上注册，账号创建完成即可使用。
        </p>
      </template>

      <template #footer>
        <el-button @click="resultVisible = false">关闭</el-button>
        <el-button v-if="createdResult && createdResult.onChainRegistered === false"
          @click="goPendingFromResult">去待上链注册</el-button>
        <el-button type="primary" :disabled="!createdResult || !createdResult.id" @click="locateCreated">
          在列表中定位
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import { adminApi, chainApi } from '../../api'
import { isAddress, roleName, roleTagType, shortAddress } from '../../utils/format'
import PageHeader from '../../components/PageHeader.vue'
import AddressText from '../../components/AddressText.vue'

/** 链下角色 → 列表展示。允许创建的目标角色与 AccountHierarchyService.assertCanCreate 的矩阵一致 */
const roleOptions = [
  { value: 'BUREAU', label: '社保局', hint: '需绑定服务城市，创建即上链注册 + 授权' },
  { value: 'SPONSOR', label: '缴费单位', hint: '需所在城市，创建即上链注册' },
  { value: 'REGULATOR', label: '监管方', hint: '只读审计身份，无需城市' },
  { value: 'EMPLOYEE', label: '参保职工', hint: '由单位维护，链上地址自动生成' },
  { value: 'ADMIN', label: '平台管理员', hint: '可发起注资、注册机构等管理交易' }
]

const loading = ref(false)
const creating = ref(false)
const registering = ref('')
const pendingCard = ref(null)
const rows = ref([])
const pending = ref([])
const cities = ref([])
const keys = ref([])
const keyBusy = ref(false)
const keyForm = reactive({ code: '', name: '', role: 'SPONSOR', privateKeyHex: '', remark: '' })

/** 平台账号列表：按角色筛选 + 每页 10 条分页 */
const accountRole = ref('ALL')
const accountPage = ref(1)
const accountPageSize = 10

/** 机构链账户列表：按角色筛选 + 每页 10 条分页 */
const keyRole = ref('ALL')
const keyPage = ref(1)
const keyPageSize = 10

/** 新开账号的结果面板：常驻展示，而不是只闪一条 toast */
const resultVisible = ref(false)
const createdResult = ref(null)
/** 「在列表中定位」高亮的行 id（3 秒后自动熄灭） */
const highlightId = ref(null)
let highlightTimer = null

const readyCount = computed(() => rows.value.filter((r) => r.onChainRole && r.onChainRole !== 'NONE').length)
const disabledCount = computed(() => rows.value.filter((r) => r.status !== 'ENABLED').length)

/** 按角色筛选后的账号列表（ALL = 全部） */
const filteredRows = computed(() => {
  const list = rows.value || []
  if (accountRole.value === 'ALL') return list
  return list.filter((a) => a.role === accountRole.value)
})

/** 当前页切片（每页 10 条） */
const pagedRows = computed(() => {
  const start = (accountPage.value - 1) * accountPageSize
  return filteredRows.value.slice(start, start + accountPageSize)
})

function onRoleFilterChange() {
  accountPage.value = 1
}

function onAccountPage(page) {
  accountPage.value = page
}

/** 按角色筛选后的机构链账户列表（ALL = 全部） */
const filteredKeys = computed(() => {
  const list = keys.value || []
  if (keyRole.value === 'ALL') return list
  return list.filter((k) => k.role === keyRole.value)
})

/** 当前页切片（每页 10 条） */
const pagedKeys = computed(() => {
  const start = (keyPage.value - 1) * keyPageSize
  return filteredKeys.value.slice(start, start + keyPageSize)
})

function onKeyRoleFilterChange() {
  keyPage.value = 1
}

function onKeyPage(page) {
  keyPage.value = page
}

/** 所有账号都查不到链上角色 → 基本可以判定是链网关没就绪（而不是"全部未注册"） */
const chainRolesUnavailable = computed(
  () => rows.value.length > 0 && rows.value.every((r) => !r.onChainRole)
)

/** 链上已被占用的城市（社保局与城市一对一，开社保局前要先避开） */
const occupiedCities = computed(() => (cities.value || []).filter((c) => c && c.city))

const cityHint = computed(() => {
  if (form.role === 'SPONSOR') {
    return occupiedCities.value.length
      ? '缴费单位的城市从链上已注册城市里选，决定统筹基金进入哪个城市池。'
      : '尚未读到链上已注册城市，请先确认社保局已在链上注册并授权。'
  }
  if (!occupiedCities.value.length) {
    return '社保局与城市一对一：请填一个链上尚未被占用的城市（当前没读到已占用城市，链网关可能未就绪）。'
  }
  const list = occupiedCities.value
    .map((c) => `${c.city}（${c.bureauName || shortAddress(c.bureau)}）`)
    .join('、')
  return `社保局与城市一对一，已被占用的城市：${list}。请填一个未被占用的城市。`
})

const resultTitle = computed(() => {
  const r = createdResult.value
  if (!r) return ''
  if (r.onChainRegistered === true) return '账号已开通，并已完成链上注册'
  if (r.onChainRegistered === false) return '账号已开通，但链上身份未就绪'
  return '账号已开通（该角色无需链上注册）'
})

const resultAlertType = computed(() => {
  const r = createdResult.value
  if (!r) return 'info'
  return r.onChainRegistered === false ? 'warning' : 'success'
})

async function loadAll () {
  loading.value = true
  try {
    // 三份数据各自容错：任何一个接口失败都不该让整页白屏
    const [accounts, pendings, cityList, keyList] = await Promise.all([
      adminApi.accounts().catch(() => []),
      adminApi.pendingInstitutions().catch(() => []),
      adminApi.cities().catch(() => []),
      adminApi.institutionKeys().catch(() => [])
    ])
    rows.value = accounts || []
    pending.value = pendings || []
    cities.value = cityList || []
    keys.value = keyList || []
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadAll()
  loadChainInfo()
})

/* ---------------------------------- 资金与合约接线 ---------------------------------- */

const sponsorOptions = computed(() => rows.value.filter((r) => r.role === 'SPONSOR' && r.chainAddress))

const depositForm = reactive({ sponsor: '', amount: '' })
const depositing = ref(false)
const transferForm = reactive({ address: '' })
const wiring = ref(false)
const transferContract = ref('')

async function loadChainInfo () {
  try {
    const status = await chainApi.status()
    transferContract.value = (status && status.transferContract) || ''
  } catch (e) {
    transferContract.value = ''
  }
}

async function submitDeposit () {
  const sponsor = depositForm.sponsor
  const amount = String(depositForm.amount || '').trim()
  if (!sponsor) {
    ElMessage.warning('请选择缴费单位')
    return
  }
  if (!/^\d+$/.test(amount) || Number(amount) <= 0) {
    ElMessage.warning('注资金额必须是正整数')
    return
  }
  const target = sponsorOptions.value.find((r) => r.chainAddress === sponsor)
  try {
    await ElMessageBox.confirm(
      `确认给「${(target && (target.displayName || target.username)) || sponsor}」注资 ${amount}？`
        + '注资会累加到「累计注资」，不能撤销。',
      '确认注资', { type: 'warning', confirmButtonText: '注资', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  depositing.value = true
  try {
    await adminApi.deposit({ sponsor, amount })
    ElMessage.success('注资成功')
    depositForm.amount = ''
    await loadAll()
  } catch (e) {
    // http.js 已提示（单位未注册 / 金额非法 / NotAnAdmin 等）
  } finally {
    depositing.value = false
  }
}

async function submitTransferContract () {
  const address = String(transferForm.address || '').trim()
  if (!isAddress(address)) {
    ElMessage.warning('请输入 0x + 40 位十六进制合约地址')
    return
  }
  if (transferContract.value && address.toLowerCase() === transferContract.value.toLowerCase()) {
    ElMessage.warning('与当前接线地址相同，无需重复设置')
    return
  }
  try {
    await ElMessageBox.confirm(
      `确认把转移合约接线改为 ${address}？改错会让整个转移流程不可用。`,
      '确认修改接线', { type: 'warning', confirmButtonText: '保存', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  wiring.value = true
  try {
    await adminApi.setTransferContract(address)
    ElMessage.success('转移合约接线已更新')
    transferForm.address = ''
    await loadChainInfo()
  } catch (e) {
    // http.js 已提示（重复设置同一地址时的 TransferContractUnchanged 回滚也是这里的正常结果）
  } finally {
    wiring.value = false
  }
}

/* ---------------------------------- 账号状态 / 密码 ---------------------------------- */

async function toggleStatus (row) {
  const next = row.status === 'ENABLED' ? 'DISABLED' : 'ENABLED'
  const verb = next === 'ENABLED' ? '启用' : '禁用'
  try {
    await ElMessageBox.confirm(
      next === 'ENABLED'
        ? `启用后「${row.username}」可以重新登录。`
        : `禁用后「${row.username}」将无法登录；已签发的登录凭证在有效期内仍然可用，建议同时重置密码。`,
      `${verb}账号`, { type: 'warning', confirmButtonText: verb, cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  try {
    await adminApi.updateAccountStatus(row.id, next)
    ElMessage.success(`已${verb}`)
    await loadAll()
  } catch (e) {
    // http.js 已提示
  }
}

async function resetPassword (row) {
  let value
  try {
    const res = await ElMessageBox.prompt(`为「${row.username}」设置新密码`, '重置密码', {
      inputType: 'password',
      inputPlaceholder: '至少 6 位',
      inputValidator: (v) => (v && v.length >= 6 ? true : '至少 6 位'),
      confirmButtonText: '重置',
      cancelButtonText: '取消'
    })
    value = res.value
  } catch (e) {
    return
  }
  await adminApi.resetPassword(row.id, value)
  ElMessage.success('密码已重置')
}

/* ---------------------------------- 机构链账户 ---------------------------------- */

async function createKey () {
  if (!keyForm.code || !keyForm.name) {
    ElMessage.warning('请填写机构编码与名称')
    return
  }
  keyBusy.value = true
  try {
    await adminApi.createInstitutionKey({ ...keyForm })
    ElMessage.success('机构链账户已登记')
    await loadAll()
  } catch (e) {
    // http.js 已提示
  } finally {
    keyBusy.value = false
  }
}

async function toggleKey (row) {
  try {
    await adminApi.setInstitutionKeyEnabled(row.code, !row.enabled)
    ElMessage.success('已更新')
    await loadAll()
  } catch (e) {
    // http.js 已提示
  }
}

/* ---------------------------------- 待上链注册 ---------------------------------- */

/** 告警条里的「去处理」：滚到待上链卡片，省得用户自己找 */
function scrollToPending () {
  const el = pendingCard.value && pendingCard.value.$el
  if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

async function registerOne (row) {
  const needsCity = row.role === 'BUREAU' || row.role === 'SPONSOR'
  let city = ''
  if (needsCity) {
    try {
      const res = await ElMessageBox.prompt(
        `为「${row.name || row.code}」填写所在城市（链上 addSponsorAccount / addBureaus 需要）`,
        '注册上链：填写城市',
        { inputValue: cities.value[0]?.city || '', inputPlaceholder: '例如 A市', confirmButtonText: '注册', cancelButtonText: '取消' }
      )
      city = res.value || ''
    } catch (e) {
      return
    }
  }

  registering.value = row.code
  try {
    // 约定：失败也是 HTTP 200，成败看 ok 字段，便于逐条处理而不中断整批
    const result = await adminApi.registerInstitution(row.code, city)
    if (result && result.ok) ElMessage.success(result.message || '注册成功')
    else ElMessage.error((result && result.message) || '注册失败')
    await loadAll()
  } catch (e) {
    // http.js 已按后端 message 提示；registerInstitution 失败本身也是 HTTP 200 + ok:false
  } finally {
    registering.value = ''
  }
}

/* ---------------------------------- 开通账号 ---------------------------------- */

const createVisible = ref(false)
const formRef = ref(null)
const form = reactive({
  role: 'BUREAU',
  username: '',
  password: '',
  displayName: '',
  name: '',
  city: ''
})

const needsChainRegistration = computed(() => ['BUREAU', 'SPONSOR', 'REGULATOR'].includes(form.role))
const needsCity = computed(() => ['BUREAU', 'SPONSOR'].includes(form.role))
const roleHint = computed(() => ({
  BUREAU: '链上注册的社保局名称',
  SPONSOR: '链上注册的缴费单位名称',
  REGULATOR: '监管机构名称（agency）'
}[form.role] || ''))

const rules = {
  role: [{ required: true, message: '请选择角色', trigger: 'change' }],
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { pattern: /^[A-Za-z0-9_]{3,32}$/, message: '3-32 位字母、数字或下划线', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入初始密码', trigger: 'blur' },
    { min: 6, message: '至少 6 位', trigger: 'blur' }
  ],
  name: [{ required: true, message: '请填写链上注册用的机构名称', trigger: 'blur' }],
  city: [
    { required: true, message: '请选择或输入城市', trigger: ['blur', 'change'] },
    { validator: validateCity, trigger: ['blur', 'change'] }
  ]
}

/**
 * 社保局的"城市"是新区占用的，不能撞已有城市（合约 cityBureau 一对一，冲突会在链上回滚）。
 * 这里用 /api/admin/cities 的结果做前端预检；后端还有一次开号前预检做兜底。
 */
function validateCity (rule, value, callback) {
  if (!value || form.role !== 'BUREAU') {
    callback()
    return
  }
  const city = String(value).trim()
  const occupied = occupiedCities.value.find((c) => c.city === city)
  if (occupied) {
    callback(new Error(`城市「${city}」已被${occupied.bureauName || shortAddress(occupied.bureau)}占用，请换一个城市`))
    return
  }
  callback()
}

function onRoleChange () {
  form.name = ''
  form.city = ''
}

function openCreate () {
  resetForm()
  createVisible.value = true
}

function resetForm () {
  form.role = 'BUREAU'
  form.username = ''
  form.password = ''
  form.displayName = ''
  form.name = ''
  form.city = ''
  formRef.value?.clearValidate()
}

async function submit () {
  const ok = await formRef.value?.validate().catch(() => false)
  if (!ok) return

  creating.value = true
  try {
    const result = await adminApi.createAccount({
      username: form.username,
      password: form.password,
      role: form.role,
      displayName: form.displayName || '',
      name: needsChainRegistration.value ? form.name : '',
      city: needsCity.value ? form.city : ''
    })

    // 结果放到常驻面板里：toast 会消失，这个面板还能看清账号 / 地址 / 注册结论，并能定位到表格行
    createdResult.value = {
      id: (result && result.account && result.account.id) || null,
      username: form.username,
      displayName: (result && result.account && result.account.displayName) || form.displayName || '',
      role: (result && result.account && result.account.role) || form.role,
      institutionCode: (result && (result.institutionCode || (result.account && result.account.institutionCode))) || '',
      chainAddress: (result && (result.chainAddress || (result.account && result.account.chainAddress))) || '',
      // onChainRegistered 三态：true 已上链 / false 未上链（缺字段或链上失败）/ null 该角色无需上链
      onChainRegistered: result ? result.onChainRegistered : null,
      onChainMessage: (result && result.onChainMessage) || ''
    }
    createVisible.value = false
    resultVisible.value = true
    await loadAll()
  } finally {
    creating.value = false
  }
}

/** 表格行高亮：定位新开的账号 */
function rowClassName ({ row }) {
  return row && row.id === highlightId.value ? 'row-new' : ''
}

/** 关闭结果面板 → 滚到并高亮刚开的账号行 */
function locateCreated () {
  const id = createdResult.value && createdResult.value.id
  resultVisible.value = false
  highlightId.value = id
  if (highlightTimer) window.clearTimeout(highlightTimer)
  window.setTimeout(() => {
    const row = document.querySelector('tr.row-new')
    if (row) row.scrollIntoView({ behavior: 'smooth', block: 'center' })
  }, 220)
  highlightTimer = window.setTimeout(() => {
    highlightId.value = null
  }, 3600)
}

function goPendingFromResult () {
  resultVisible.value = false
  scrollToPending()
}
</script>

<style scoped>
.summary {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 14px;
  font-size: 12.5px;
  color: var(--ink-2);
}

.sum-item b {
  font-size: 15px;
  font-weight: 600;
  color: var(--ink);
  margin-left: 4px;
  font-variant-numeric: tabular-nums;
}

.sum-item b.is-ok { color: var(--ok); }
.sum-item b.is-warn { color: var(--warn); }
.sum-item b.is-off { color: var(--alert); }

.sum-div {
  width: 1px;
  height: 12px;
  background: var(--hair);
}

.sum-note {
  font-size: 11.5px;
  color: var(--warn);
}

.city-hint {
  margin-top: 4px;
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--ink-3);
}

.result-alert {
  margin-bottom: 12px;
}

.result-desc {
  margin-bottom: 10px;
}

.result-tip {
  margin-top: 10px;
}

.result-tip-text {
  margin: 10px 0 0;
  font-size: 12px;
  color: var(--ink-3);
}

/* 资金与合约接线 */
.ops-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: 18px;
  padding: 16px 18px 18px;
}

.op-block {
  min-width: 0;
}

.op-tip {
  margin-left: 10px;
  font-size: 11.5px;
  line-height: 1.5;
  color: var(--ink-3);
}

/* 「在列表中定位」：新开的账号行短暂高亮 */
:deep(.row-new > td) {
  background: var(--accent-soft) !important;
  transition: background-color 0.3s ease;
}

.panel {
  margin-bottom: 14px;
}

.mb-18 {
  margin-bottom: 18px;
}

.alert-body {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

:deep(.panel > .el-card__header) {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  padding: 13px 18px;
}

:deep(.panel > .el-card__body) {
  padding: 0;
}

.section-title {
  margin: 0;
}

.panel-meta {
  font-size: 11.5px;
  color: var(--ink-3);
}

.filter-bar {
  padding: 10px 16px;
  border-bottom: 1px solid var(--hair);
  background: var(--tone);
}

.pager {
  padding: 12px 16px;
}

.cell-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink);
  line-height: 1.4;
}

.cell-sub {
  font-size: 11.5px;
  color: var(--ink-3);
  line-height: 1.5;
  font-variant-numeric: tabular-nums;
}

.mono {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--ink-2);
}

.dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  margin-right: 5px;
  vertical-align: middle;
  background: var(--ink-3);
}

.dot.is-ok { background: var(--ok); }
.dot.is-off { background: var(--alert); }

.opt-hint {
  float: right;
  font-size: 11.5px;
  color: var(--ink-3);
}

.form-note {
  margin-top: 4px;
}
</style>
