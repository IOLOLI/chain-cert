<template>
  <div>
    <h3 class="page-title">政策参数</h3>
    <p class="page-desc">
      缴费比例、缴费基数上下限、最低缴费年限、退休年龄、计发月数、基础养老金计发比例、发放周期——
      这些随政策文件变化的数值不再写死在合约里，而是<b>链下版本化管理</b>：
      每次调整只追加新版本（旧版本自动截止到生效前一日），变更全部留审计；
      核定时把当时的参数快照存进核定单，历史待遇永远可复算。
    </p>

    <el-alert
      v-if="!hasParams"
      type="info"
      :closable="false"
      show-icon
      title="还没有参数字典：确认后端已启动（PolicyDataInitializer 会自动补齐一期参数）"
    />

    <div v-if="chain" class="card-block chain-block">
      <div class="flex-between">
        <div>
          <strong>链上政策版本</strong>
          <span class="text-muted chain-desc">链上业务按已发布参数执行；改完参数会自动发布，失败时可手动重发。</span>
        </div>
        <div class="chain-actions">
          <el-tag v-if="chain.consistent" type="success" size="small">已同步 · 链上 v{{ chain.chainVersion }}</el-tag>
          <el-tag v-else-if="chain.chainReady" type="warning" size="small">未同步 · 链上 v{{ chain.chainVersion ?? '—' }}</el-tag>
          <el-tag v-else type="info" size="small">链网关未就绪</el-tag>
          <el-button size="small" type="primary" plain :loading="publishing" @click="publishToChain">
            发布到链上
          </el-button>
        </div>
      </div>
      <p v-if="chainNote" class="text-muted tip">{{ chainNote }}</p>
    </div>

    <el-row :gutter="16">
      <el-col :span="7">
        <div class="card-block">
          <strong>参数目录</strong>
          <el-collapse v-model="activeCategories" class="mt-8">
            <el-collapse-item v-for="(items, category) in groups" :key="category" :name="category">
              <template #title>
                <span>{{ category }}（{{ items.length }}）</span>
              </template>
              <div
                v-for="item in items"
                :key="item.paramCode"
                class="param-row"
                :class="{ active: item.paramCode === code }"
                @click="select(item.paramCode)"
              >
                <div class="param-name">{{ item.paramName }}</div>
                <div class="param-code">{{ item.paramCode }}</div>
              </div>
            </el-collapse-item>
          </el-collapse>
        </div>
      </el-col>

      <el-col :span="17">
        <div class="card-block" v-loading="detailLoading">
          <template v-if="definition">
            <div class="flex-between">
              <div>
                <strong>{{ definition.paramName }}</strong>
                <span class="text-muted param-code-inline">{{ definition.paramCode }}</span>
              </div>
              <div>
                <el-button size="small" @click="openAudit">变更审计</el-button>
                <el-button size="small" type="primary" @click="openModify">调整参数</el-button>
              </div>
            </div>

            <el-descriptions :column="3" border size="small" class="mt-12">
              <el-descriptions-item label="当前生效值">
                <span class="stat-value">{{ effective ? effective.paramValue : definition.defaultValue }}</span>
                <el-tag v-if="!effective" type="info" size="small" class="ml-8">字典默认（无生效版本）</el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="生效版本">
                <template v-if="effective">v{{ effective.versionNo }}（{{ effective.validFrom }} 起）</template>
                <span v-else class="text-muted">—</span>
              </el-descriptions-item>
              <el-descriptions-item label="类型 / 值域">
                {{ definition.paramType }}
                <span v-if="definition.valueMin !== null || definition.valueMax !== null" class="text-muted">
                  （{{ definition.valueMin ?? '-∞' }} ~ {{ definition.valueMax ?? '+∞' }}）
                </span>
              </el-descriptions-item>
              <el-descriptions-item label="政策依据" :span="2">
                {{ definition.policyDoc || '未填写' }}
              </el-descriptions-item>
              <el-descriptions-item label="说明">
                {{ definition.remark || '—' }}
              </el-descriptions-item>
            </el-descriptions>

            <div class="mt-16">
              <strong>版本时间线</strong>
              <el-table :data="versions" size="small" class="mt-8">
                <el-table-column prop="versionNo" label="版本" width="70" />
                <el-table-column prop="paramValue" label="值" width="140" />
                <el-table-column label="生效区间" min-width="200">
                  <template #default="{ row }">
                    {{ row.validFrom }} ~ {{ row.validTo || '长期' }}
                  </template>
                </el-table-column>
                <el-table-column label="状态" width="100">
                  <template #default="{ row }">
                    <el-tag :type="statusType(row.status)" size="small">{{ statusName(row.status) }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column prop="createdBy" label="操作人" width="120" />
                <el-table-column label="写入时间" min-width="160">
                  <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
                </el-table-column>
              </el-table>
            </div>
            <div class="text-muted tip">
              DRAFT = 已预置、未到生效日（每日 01:00 自动切换为 ACTIVE）；TERMINATED = 已被新版本截止，历史查询仍可命中。
            </div>
          </template>
          <el-empty v-else description="请选择左侧参数" />
        </div>
      </el-col>
    </el-row>

    <!-- 调整参数 -->
    <el-dialog v-model="modifyVisible" title="调整政策参数" width="520px">
      <el-form label-width="90px">
        <el-form-item label="参数">
          <span>{{ definition?.paramName }}（{{ definition?.paramCode }}）</span>
        </el-form-item>
        <el-form-item label="新值" required>
          <el-input v-model="form.paramValue" :placeholder="`当前：${effective?.paramValue ?? definition?.defaultValue ?? ''}`" />
          <div class="text-muted tip">需与当前生效值不同才会产生新版本；与当前一致时提交会被拒绝。</div>
          <div class="text-muted tip">
            类型 {{ definition?.paramType }}
            <span v-if="definition?.valueMin !== null || definition?.valueMax !== null">
              ，范围 {{ definition?.valueMin ?? '-∞' }} ~ {{ definition?.valueMax ?? '+∞' }}
            </span>
          </div>
        </el-form-item>
        <el-form-item label="生效日期">
          <el-date-picker
            v-model="form.validFrom"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="留空 = 今天生效；选未来日期 = 预置"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="变更原因" required>
          <el-input v-model="form.reason" type="textarea" :rows="2" placeholder="审计要求：写清政策依据（如 国发〔2005〕38号）" />
        </el-form-item>
      </el-form>
      <el-alert type="info" :closable="false" show-icon
        title="提交后只追加新版本：当前版本自动截止到生效前一日，历史核定单不受影响" />
      <template #footer>
        <el-button @click="modifyVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">提交</el-button>
      </template>
    </el-dialog>

    <!-- 审计 -->
    <el-drawer v-model="auditVisible" title="参数变更审计" size="60%">
      <el-table :data="auditRows" size="small" v-loading="auditLoading">
        <el-table-column prop="paramCode" label="参数" min-width="180" />
        <el-table-column prop="versionNo" label="版本" width="70" />
        <el-table-column label="变更" min-width="160">
          <template #default="{ row }">
            <span class="text-muted">{{ row.oldValue ?? '（首次配置）' }}</span>
            →
            <strong>{{ row.newValue }}</strong>
          </template>
        </el-table-column>
        <el-table-column prop="effectiveDate" label="生效日" width="110" />
        <el-table-column prop="reason" label="原因" min-width="180" />
        <el-table-column prop="operator" label="操作人" width="110" />
        <el-table-column label="操作时间" min-width="160">
          <template #default="{ row }">{{ formatTime(row.operateAt) }}</template>
        </el-table-column>
      </el-table>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { adminApi } from '../../api'
import { formatTime } from '../../utils/format'

const groups = ref({})
const categories = ref([])
const activeCategories = ref([])
const code = ref('')
const detail = ref(null)
const detailLoading = ref(false)
const modifyVisible = ref(false)
const saving = ref(false)
const auditVisible = ref(false)
const auditLoading = ref(false)
const auditRows = ref([])

/** 链上政策版本对账（仅 ADMIN 可读；无权限时保持 null，整块不渲染） */
const chain = ref(null)
const publishing = ref(false)
const chainNote = ref('')

const form = reactive({ paramValue: '', validFrom: '', reason: '' })

const definition = computed(() => detail.value?.definition || null)
const effective = computed(() => detail.value?.effective || null)
const versions = computed(() => detail.value?.versions || [])
const hasParams = computed(() => Object.keys(groups.value || {}).length > 0)

async function loadDefinitions() {
  try {
    const data = await adminApi.policyDefinitions()
    groups.value = data?.groups || {}
    categories.value = data?.categories || []
    activeCategories.value = categories.value.length ? [categories.value[0]] : []
    const first = categories.value.length ? groups.value[categories.value[0]][0] : null
    if (first) select(first.paramCode)
  } catch (e) {
    groups.value = {}
  }
}

async function select(paramCode) {
  code.value = paramCode
  detailLoading.value = true
  try {
    detail.value = await adminApi.policyParameters(paramCode, 'NATIONAL')
  } catch (e) {
    detail.value = null
  } finally {
    detailLoading.value = false
  }
}

function openModify() {
  if (!definition.value) return
  form.paramValue = effective.value?.paramValue ?? definition.value.defaultValue ?? ''
  form.validFrom = ''
  form.reason = ''
  modifyVisible.value = true
}

async function save() {
  if (!form.paramValue || !form.reason) {
    ElMessage.warning('请填写新值与变更原因')
    return
  }
  saving.value = true
  try {
    await adminApi.savePolicyParameter({
      paramCode: code.value,
      regionCode: 'NATIONAL',
      paramValue: String(form.paramValue).trim(),
      validFrom: form.validFrom || undefined,
      reason: form.reason
    })
    ElMessage.success('已追加新版本')
    modifyVisible.value = false
    await select(code.value)
    await loadChain()
  } catch (e) {
    /* http.js 已统一提示后端 message */
  } finally {
    saving.value = false
  }
}

async function openAudit() {
  auditVisible.value = true
  auditLoading.value = true
  try {
    auditRows.value = (await adminApi.policyAudit(code.value)) || []
  } catch (e) {
    auditRows.value = []
  } finally {
    auditLoading.value = false
  }
}

function statusName(status) {
  return { ACTIVE: '生效中', DRAFT: '待生效', TERMINATED: '已截止' }[status] || status
}

function statusType(status) {
  return { ACTIVE: 'success', DRAFT: 'warning', TERMINATED: 'info' }[status] || 'info'
}

async function loadChain() {
  try {
    chain.value = await adminApi.policyChainStatus()
    chainNote.value = chain.value?.note || ''
  } catch (e) {
    // BUREAU 没有 chain/status 权限（403）时整块隐藏，不影响参数维护
    chain.value = null
    chainNote.value = ''
  }
}

async function publishToChain() {
  publishing.value = true
  try {
    const result = await adminApi.publishPolicyToChain()
    chainNote.value = result?.message || ''
    if (result && result.published) ElMessage.success(result.message || '政策参数已发布到链上')
    else ElMessage.info(result?.message || '链上参数已是最新，无需重复发布')
    await loadChain()
  } catch (e) {
    // http.js 已按后端 message 提示
  } finally {
    publishing.value = false
  }
}

onMounted(() => {
  loadDefinitions()
  loadChain()
})
</script>

<style scoped>
.chain-block {
  margin-bottom: 16px;
}

.chain-desc {
  margin-left: 8px;
  font-size: 12px;
}

.chain-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.param-row {
  padding: 8px 10px;
  border-radius: 6px;
  cursor: pointer;
  border: 1px solid transparent;
}

.param-row:hover {
  background: var(--el-fill-color-light);
}

.param-row.active {
  background: var(--el-color-primary-light-9);
  border-color: var(--el-color-primary-light-7);
}

.param-name {
  font-size: 13px;
}

.param-code {
  font-size: 11px;
  color: var(--el-text-color-secondary);
  font-family: Consolas, monospace;
}

.param-code-inline {
  margin-left: 8px;
  font-family: Consolas, monospace;
  font-size: 12px;
}

.mt-8 {
  margin-top: 8px;
}

.mt-12 {
  margin-top: 12px;
}

.mt-16 {
  margin-top: 16px;
}

.ml-8 {
  margin-left: 8px;
}

.tip {
  font-size: 12px;
  line-height: 1.6;
  margin-top: 4px;
}
</style>
