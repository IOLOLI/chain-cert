<template>
  <div class="inbox">
    <PageHeader title="转移办理"
      desc="跨城市转移是「转出地放行 → 资金进托管 → 转入地接收」三步。放行与接收都只能用本局自己的机构私钥签名，合约按 msg.sender 判定，所以列表里的社保局地址就是本局链上地址。">
      <el-button :icon="Refresh" :loading="loading" @click="loadAll">刷新</el-button>
    </PageHeader>

    <el-alert v-if="!bureauAddress" type="warning" show-icon :closable="false" class="mb-16"
      title="当前账号没有链上地址，无法查询待办">
      <div>合约的待办查询要求 msg.sender 就是该社保局本身。请确认本局已创建机构链账户并完成链上注册。</div>
    </el-alert>

    <div class="summary">
      <span class="sum-item">本局地址 <AddressText :address="bureauAddress" :empty-text="'未配置'" /></span>
      <span class="sum-div"></span>
      <span class="sum-item">转出待放行 <b :class="{ 'is-warn': outboundRows.length > 0 }">{{ outboundRows.length }}</b></span>
      <span class="sum-div"></span>
      <span class="sum-item">转入待接收 <b :class="{ 'is-warn': inboundRows.length > 0 }">{{ inboundRows.length }}</b></span>
      <span class="sum-div"></span>
      <span class="sum-item">全部记录 <b>{{ allRows.length }}</b></span>
    </div>

    <el-tabs v-model="activeTab">
      <el-tab-pane label="待办" name="todo">
        <el-row :gutter="14">
      <el-col :xs="24" :sm="24" :md="12" :lg="12">
        <el-card shadow="never" class="panel">
          <template #header>
            <h2 class="section-title">转出地待放行</h2>
            <span class="panel-meta">复核通过后资金从本局基金池划入托管</span>
          </template>

          <el-table :data="outboundRows" v-loading="loading" style="width: 100%">
            <el-table-column prop="id" label="申请" width="70">
              <template #default="{ row }"><span class="num">#{{ row.id }}</span></template>
            </el-table-column>
            <el-table-column label="职工链上地址" min-width="130">
              <template #default="{ row }">
                <AddressText :address="row.employee" :head="8" :tail="6" />
              </template>
            </el-table-column>
            <el-table-column label="迁移方向" min-width="120">
              <template #default="{ row }">
                <span class="city">{{ row.fromCity }}</span>
                <span class="arrow">→</span>
                <span class="city">{{ row.toCity }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="90" align="right">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="openDetail(row)">办理</el-button>
              </template>
            </el-table-column>
            <template #empty>
              <el-empty description="没有待放行的申请" :image-size="64" />
            </template>
          </el-table>
        </el-card>
      </el-col>

      <el-col :xs="24" :sm="24" :md="12" :lg="12">
        <el-card shadow="never" class="panel">
          <template #header>
            <h2 class="section-title">转入地待接收</h2>
            <span class="panel-meta">接收后在新城市建立参保档案并落下托管基金</span>
          </template>

          <el-table :data="inboundRows" v-loading="loading" style="width: 100%">
            <el-table-column prop="id" label="申请" width="70">
              <template #default="{ row }"><span class="num">#{{ row.id }}</span></template>
            </el-table-column>
            <el-table-column label="职工链上地址" min-width="130">
              <template #default="{ row }">
                <AddressText :address="row.employee" :head="8" :tail="6" />
              </template>
            </el-table-column>
            <el-table-column label="迁移方向" min-width="120">
              <template #default="{ row }">
                <span class="city">{{ row.fromCity }}</span>
                <span class="arrow">→</span>
                <span class="city">{{ row.toCity }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="90" align="right">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="openDetail(row)">办理</el-button>
              </template>
            </el-table-column>
            <template #empty>
              <el-empty description="没有待接收的申请" :image-size="64" />
            </template>
          </el-table>
        </el-card>
      </el-col>
        </el-row>
      </el-tab-pane>

      <el-tab-pane label="全部记录" name="all">
        <el-card shadow="never" class="panel">
          <template #header>
            <h2 class="section-title">转移记录</h2>
            <span class="panel-meta">本局作为转出地或转入地的全部申请，含已放行 / 已接收 / 已驳回</span>
          </template>

          <el-table :data="allRows" v-loading="loading" style="width: 100%">
            <el-table-column prop="id" label="申请" width="70">
              <template #default="{ row }"><span class="num">#{{ row.id }}</span></template>
            </el-table-column>
            <el-table-column label="迁移方向" min-width="130">
              <template #default="{ row }">
                <span class="city">{{ row.fromCity }}</span>
                <span class="arrow">→</span>
                <span class="city">{{ row.toCity }}</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="150">
              <template #default="{ row }">
                <el-tag :type="transferStatusType(row.status)" effect="light" size="small">
                  {{ transferStatusName(row.status) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="申请时间" min-width="165">
              <template #default="{ row }">{{ formatTime(row.appliedAt) }}</template>
            </el-table-column>
            <el-table-column label="放行时间" min-width="165">
              <template #default="{ row }">{{ formatTime(row.authorizedAt) }}</template>
            </el-table-column>
            <el-table-column label="接收时间" min-width="165">
              <template #default="{ row }">{{ formatTime(row.receivedAt) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="90" align="right">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="openDetail(row)">查看</el-button>
              </template>
            </el-table-column>
            <template #empty>
              <el-empty description="暂无转移记录" :image-size="64" />
            </template>
          </el-table>
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <!-- ------------------------------ 办理抽屉 ------------------------------ -->
    <el-drawer v-model="drawerVisible" :title="`转移申请 #${detail?.id ?? ''}`" size="520px" @closed="detail = null">
      <div v-loading="detailLoading">
        <template v-if="detail">
          <el-steps :active="stepActive" :process-status="stepStatus" finish-status="success" align-center class="steps">
            <el-step title="提交申请" :description="formatTime(detail.appliedAt)" />
            <el-step title="转出地放行" :description="detail.authorizedAt ? formatTime(detail.authorizedAt) : '待放行'" />
            <el-step title="转入地接收" :description="detail.receivedAt ? formatTime(detail.receivedAt) : '待接收'" />
          </el-steps>

          <el-alert v-if="Number(detail.status) === 4" type="error" show-icon :closable="false" class="mb-16"
            title="该申请已驳回">
            <div>{{ detail.reason || '未填写原因' }}</div>
          </el-alert>

          <el-descriptions :column="1" border size="small" class="desc">
            <el-descriptions-item label="状态">
              <el-tag :type="transferStatusType(detail.status)" effect="light" size="small">
                {{ transferStatusName(detail.status) }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="职工地址">
              <AddressText :address="detail.employee" :head="12" :tail="10" />
            </el-descriptions-item>
            <el-descriptions-item label="迁移方向">{{ detail.fromCity }} → {{ detail.toCity }}</el-descriptions-item>
            <el-descriptions-item label="转出社保局">
              <AddressText :address="detail.fromBureau" :head="10" :tail="8" />
            </el-descriptions-item>
            <el-descriptions-item label="转入社保局">
              <AddressText :address="detail.toBureau" :head="10" :tail="8" />
            </el-descriptions-item>
            <el-descriptions-item label="托管个人账户">
              <span class="num">{{ formatAmount(detail.personalFund) }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="托管统筹权益">
              <span class="num">{{ formatAmount(detail.overallFund) }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="累计缴费月数">
              <span class="num">{{ detail.paidMonths ?? 0 }} 个月</span>
            </el-descriptions-item>
            <el-descriptions-item label="申请时间">{{ formatTime(detail.appliedAt) }}</el-descriptions-item>
          </el-descriptions>

          <p class="panel-note">
            放行时合约会核对职工个人账户与统筹权益的托管金额；接收时若与托管金额不一致，整笔交易回滚（EscrowMismatch）。
          </p>
        </template>
        <el-empty v-else-if="!detailLoading" description="没有读取到申请详情" :image-size="64" />
      </div>

      <template #footer>
        <template v-if="detail">
          <template v-if="Number(detail.status) === 1">
            <el-button @click="reject">驳回</el-button>
            <el-button type="primary" :loading="acting" @click="approve">复核放行</el-button>
          </template>
          <template v-else-if="Number(detail.status) === 2">
            <el-button type="primary" :loading="acting" @click="receive">接收并建档</el-button>
          </template>
          <template v-else>
            <el-button @click="drawerVisible = false">关闭</el-button>
          </template>
        </template>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { bureauApi } from '../../api'
import { useUserStore } from '../../stores/user'
import {
  formatAmount,
  formatTime,
  transferStatusName,
  transferStatusType
} from '../../utils/format'
import PageHeader from '../../components/PageHeader.vue'
import AddressText from '../../components/AddressText.vue'

const user = useUserStore()

/** 待办查询用本局自己的链上地址：合约的 getInboundTransfers / getOutboundTransfers 要求 msg.sender == bureau */
const bureauAddress = computed(() => user.chainAddress || '')

const loading = ref(false)
const outboundRows = ref([])
const inboundRows = ref([])
const allRows = ref([])
const activeTab = ref('todo')

const drawerVisible = ref(false)
const detailLoading = ref(false)
const detail = ref(null)
const acting = ref(false)

/** 列表只有申请编号，逐条补详情才能显示职工与城市；数量按页上限 20 控制 */
async function fetchDetails (ids) {
  const list = Array.isArray(ids) ? ids : []
  if (!list.length) return []
  const rows = await Promise.all(
    list.map((id) => bureauApi.transfer(String(id)).catch(() => null))
  )
  return rows.filter(Boolean)
}

async function loadAll () {
  if (!bureauAddress.value) return
  loading.value = true
  try {
    const [out, inbound] = await Promise.all([
      bureauApi.outbound(bureauAddress.value, 0, 20).catch(() => ({ ids: [] })),
      bureauApi.inbound(bureauAddress.value, 0, 20).catch(() => ({ ids: [] }))
    ])
    const [outRows, inRows] = await Promise.all([
      fetchDetails(out && out.ids),
      fetchDetails(inbound && inbound.ids)
    ])
    // 合约按「转出地/转入地」返回全部申请，不区分状态；这里按办理状态过滤：
    // 转出地只展示待放行（PENDING=1），转入地只展示待接收（AUTHORIZED=2），
    // 避免已放行/已接收/已驳回的申请一直挂在「待放行」「待接收」列表里。
    outboundRows.value = outRows.filter((r) => Number(r.status) === 1)
    inboundRows.value = inRows.filter((r) => Number(r.status) === 2)
    // 全部记录：转出 + 转入合并，按申请号去重后倒序，展示完整流转（含已放行 / 已接收 / 已驳回）
    const seen = new Set()
    allRows.value = [...outRows, ...inRows]
      .filter((r) => {
        const key = String(r.id)
        if (seen.has(key)) return false
        seen.add(key)
        return true
      })
      .sort((a, b) => Number(b.id) - Number(a.id))
  } finally {
    loading.value = false
  }
}

onMounted(loadAll)

/* ---------------------------------- 详情与动作 ---------------------------------- */

async function openDetail (row) {
  drawerVisible.value = true
  detailLoading.value = true
  try {
    detail.value = await bureauApi.transfer(String(row.id))
  } catch (e) {
    detail.value = null
  } finally {
    detailLoading.value = false
  }
}

async function approve () {
  acting.value = true
  try {
    await bureauApi.authorize(detail.value.id, true, '')
    ElMessage.success('已放行，资金进入托管')
    drawerVisible.value = false
    await loadAll()
  } finally {
    acting.value = false
  }
}

async function reject () {
  let reason
  try {
    const res = await ElMessageBox.prompt('驳回原因会写入链上申请单，作为留痕', '驳回转移申请', {
      inputPlaceholder: '例如：参保信息与实际不符',
      inputValidator: (v) => (v && v.trim() ? true : '请填写驳回原因'),
      confirmButtonText: '确认驳回',
      cancelButtonText: '取消',
      type: 'warning'
    })
    reason = res.value
  } catch (e) {
    return
  }
  acting.value = true
  try {
    await bureauApi.authorize(detail.value.id, false, reason)
    ElMessage.success('已驳回')
    drawerVisible.value = false
    await loadAll()
  } finally {
    acting.value = false
  }
}

async function receive () {
  try {
    await ElMessageBox.confirm(
      '接收后将在新城市建立参保档案，并把托管资金落回该职工账户。此操作不可撤销。',
      '确认接收', { type: 'warning', confirmButtonText: '接收', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  acting.value = true
  try {
    await bureauApi.receive(detail.value.id)
    ElMessage.success('已接收，参保关系已落到新城市')
    drawerVisible.value = false
    await loadAll()
  } finally {
    acting.value = false
  }
}

/* ---------------------------------- 展示映射 ---------------------------------- */

const stepActive = computed(() => {
  const s = Number(detail.value?.status)
  if (s === 1) return 1
  if (s === 2) return 2
  if (s === 3) return 3
  return 1
})

const stepStatus = computed(() => (Number(detail.value?.status) === 4 ? 'error' : 'process'))
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

.sum-item b.is-warn { color: var(--warn); }
.sum-item b.is-ok { color: var(--ok); }

.sum-div {
  width: 1px;
  height: 12px;
  background: var(--hair);
}

.panel {
  margin-bottom: 14px;
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

.city {
  font-size: 12.5px;
  color: var(--ink);
}

.arrow {
  margin: 0 6px;
  color: var(--ink-3);
}

.num {
  font-variant-numeric: tabular-nums;
}

.steps {
  margin: 4px 0 20px;
}

:deep(.steps .el-step__title) {
  font-size: 12.5px;
  line-height: 1.6;
}

:deep(.steps .el-step__description) {
  font-size: 11px;
}

:deep(.desc .el-descriptions__label) {
  width: 104px;
  background: var(--tone) !important;
  color: var(--ink-2) !important;
  font-weight: 500 !important;
  font-size: 12px !important;
}

:deep(.desc .el-descriptions__content) {
  font-size: 12.5px !important;
}

.panel-note {
  margin-top: 14px;
}
</style>
