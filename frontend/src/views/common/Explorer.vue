<template>
  <div>
    <h3 class="page-title">区块链浏览器</h3>
    <p class="page-desc">
      直接读 FISCO BCOS 节点账本：出块高度、每个区块里的交易、交易调用的合约方法与回执状态、
      地址的链上角色。与「链上索引」不同，这里包括不是本系统发起的交易（例如控制台直接发的）。
    </p>

    <div class="overview">
      <div class="ov-item">
        <span class="ov-key">最新高度</span>
        <span class="ov-val">{{ overview?.blockNumber ?? '—' }}</span>
      </div>
      <div class="ov-item">
        <span class="ov-key">累计交易</span>
        <span class="ov-val">{{ overview?.txSum ?? '—' }}</span>
      </div>
      <div class="ov-item">
        <span class="ov-key">失败交易</span>
        <span class="ov-val">{{ overview?.failedTxSum ?? '—' }}</span>
      </div>
      <div class="ov-item">
        <span class="ov-key">群组 / 节点版本</span>
        <span class="ov-val small">group {{ overview?.groupId ?? '—' }} · {{ overview?.nodeVersion ?? '—' }}</span>
      </div>
      <div class="ov-item">
        <span class="ov-key">核心合约</span>
        <span class="ov-val small mono">{{ shortAddress(overview?.coreContract) }}</span>
      </div>
      <div class="ov-item">
        <span class="ov-key">转移合约</span>
        <span class="ov-val small mono">{{ shortAddress(overview?.transferContract) }}</span>
      </div>
    </div>

    <div class="search-bar">
      <el-input v-model="searchText" placeholder="输入区块高度 / 0x 交易哈希 / 0x 地址" clearable
        @keyup.enter="doSearch" />
      <el-button type="primary" @click="doSearch">查询</el-button>
      <el-switch v-model="autoRefresh" active-text="自动刷新（5s）" />
      <el-button size="small" @click="refreshAll">立即刷新</el-button>
    </div>

    <el-row :gutter="16" class="mt-16">
      <el-col :span="10">
        <div class="card-block">
          <div class="flex-between">
            <strong>最新区块</strong>
            <span class="text-muted">总数 {{ blocks.length }} · 点击查看详情</span>
          </div>
          <div class="block-list">
            <div v-for="b in blocks" :key="b.hash" class="block-item"
              :class="{ active: selected.type === 'block' && selected.data?.hash === b.hash }"
              @click="openBlock(b.number)">
              <div class="bi-head">
                <span class="bi-num">#{{ b.number }}</span>
                <span class="text-muted">{{ fmtTime(b.timestampMillis) }}</span>
              </div>
              <div class="bi-sub">
                交易 {{ b.txCount }} 笔
                <span class="mono">{{ shortAddress(b.hash) }}</span>
              </div>
            </div>
            <el-empty v-if="!blocks.length" description="暂无区块" :image-size="60" />
          </div>
        </div>
      </el-col>

      <el-col :span="14">
        <div class="card-block detail" v-loading="detailLoading">
          <!-- 区块详情 -->
          <template v-if="selected.type === 'block' && selected.data">
            <div class="flex-between">
              <strong>区块 #{{ selected.data.number }}</strong>
              <el-button size="small" @click="openBlock(selected.data.number)">重新读取</el-button>
            </div>
            <el-descriptions :column="1" border size="small" class="mt-8">
              <el-descriptions-item label="区块哈希"><span class="mono">{{ selected.data.hash }}</span></el-descriptions-item>
              <el-descriptions-item label="父区块哈希"><span class="mono">{{ selected.data.parentHash }}</span></el-descriptions-item>
              <el-descriptions-item label="出块节点"><span class="mono">{{ selected.data.sealer }}</span></el-descriptions-item>
              <el-descriptions-item label="出块时间">{{ fmtTime(selected.data.timestampMillis) }}</el-descriptions-item>
              <el-descriptions-item label="交易数 / Gas">
                {{ selected.data.txCount }} 笔 · {{ selected.data.gasUsed }} / {{ selected.data.gasLimit }}
              </el-descriptions-item>
              <el-descriptions-item label="State Root"><span class="mono">{{ shortAddress(selected.data.stateRoot) }}</span></el-descriptions-item>
            </el-descriptions>

            <strong class="mt-16 block-tx-title">区块内交易</strong>
            <el-table :data="txRows" size="small" empty-text="该区块没有交易" class="mt-8">
              <el-table-column label="交易哈希" min-width="180">
                <template #default="{ row }">
                  <el-link type="primary" :underline="false" @click="openTx(row.hash)">
                    {{ shortAddress(row.hash) }}
                  </el-link>
                </template>
              </el-table-column>
              <el-table-column label="方法" width="150">
                <template #default="{ row }">{{ row.functionName || '—' }}</template>
              </el-table-column>
              <el-table-column label="发送方" min-width="120">
                <template #default="{ row }"><span class="mono">{{ shortAddress(row.from) }}</span></template>
              </el-table-column>
              <el-table-column label="接收方" min-width="120">
                <template #default="{ row }"><span class="mono">{{ shortAddress(row.to) }}</span></template>
              </el-table-column>
              <el-table-column label="状态" width="80">
                <template #default="{ row }">
                  <el-tag v-if="row.success === true" type="success" size="small">成功</el-tag>
                  <el-tag v-else-if="row.success === false" type="danger" size="small">失败</el-tag>
                  <span v-else class="text-muted">—</span>
                </template>
              </el-table-column>
            </el-table>
          </template>

          <!-- 交易详情 -->
          <template v-else-if="selected.type === 'transaction' && selected.data">
            <div class="flex-between">
              <strong>交易详情</strong>
              <el-button size="small" v-if="selected.data.blockNumber" @click="openBlock(selected.data.blockNumber)">
                所属区块 #{{ selected.data.blockNumber }}
              </el-button>
            </div>
            <el-descriptions :column="1" border size="small" class="mt-8">
              <el-descriptions-item label="交易哈希"><span class="mono">{{ selected.data.hash }}</span></el-descriptions-item>
              <el-descriptions-item label="状态">
                <el-tag v-if="selected.data.success === true" type="success" size="small">成功（status {{ selected.data.status }}）</el-tag>
                <el-tag v-else-if="selected.data.success === false" type="danger" size="small">失败（status {{ selected.data.status }}）</el-tag>
                <span v-else class="text-muted">未取到回执</span>
              </el-descriptions-item>
              <el-descriptions-item label="区块 / 序号">
                #{{ selected.data.blockNumber }} · index {{ selected.data.transactionIndex }}
              </el-descriptions-item>
              <el-descriptions-item label="发送方"><span class="mono">{{ selected.data.from }}</span></el-descriptions-item>
              <el-descriptions-item label="接收方"><span class="mono">{{ selected.data.to }}</span></el-descriptions-item>
              <el-descriptions-item label="本系统记录的方法">
                <span v-if="selected.data.localRecord">
                  {{ selected.data.localRecord.contractName }}.{{ selected.data.localRecord.functionName }}
                  <el-tag size="small" :type="selected.data.localRecord.success ? 'success' : 'danger'" class="ml-8">
                    {{ selected.data.localRecord.success ? '成功' : '失败' }}
                  </el-tag>
                </span>
                <span v-else class="text-muted">非本系统发起的交易（无链下记录）</span>
              </el-descriptions-item>
              <el-descriptions-item label="输入数据（前 200 字符）">
                <span class="mono break-all">{{ shortInput }}</span>
              </el-descriptions-item>
              <el-descriptions-item v-if="selected.data.contractAddress" label="创建的合约地址">
                <span class="mono">{{ selected.data.contractAddress }}</span>
              </el-descriptions-item>
            </el-descriptions>

            <template v-if="selected.data.events && selected.data.events.length">
              <strong class="mt-16 block-tx-title">事件（本系统索引）</strong>
              <el-table :data="selected.data.events" size="small" class="mt-8">
                <el-table-column prop="eventName" label="事件" width="180" />
                <el-table-column label="关键金额" width="160">
                  <template #default="{ row }">
                    {{ row.amount ?? row.personalAmount ?? '—' }}
                  </template>
                </el-table-column>
                <el-table-column prop="contractName" label="合约" width="120" />
                <el-table-column label="原始数据" min-width="200">
                  <template #default="{ row }"><span class="mono ellipsis">{{ row.payload }}</span></template>
                </el-table-column>
              </el-table>
            </template>
          </template>

          <!-- 地址详情 -->
          <template v-else-if="selected.type === 'address' && selected.data">
            <strong>地址详情</strong>
            <el-descriptions :column="1" border size="small" class="mt-8">
              <el-descriptions-item label="地址"><span class="mono">{{ selected.data.address }}</span></el-descriptions-item>
              <el-descriptions-item label="链上角色">
                <el-tag v-if="selected.data.onChainRole && selected.data.onChainRole !== 'NONE'" type="success" size="small">
                  {{ selected.data.onChainRole }}
                </el-tag>
                <el-tag v-else-if="selected.data.onChainRole === 'NONE'" type="warning" size="small">未注册</el-tag>
                <span v-else class="text-muted">链未就绪 / 查询失败</span>
              </el-descriptions-item>
              <el-descriptions-item label="机构编码">{{ selected.data.institutionCode || '—' }}</el-descriptions-item>
              <el-descriptions-item label="机构名称">{{ selected.data.institutionName || '—' }}</el-descriptions-item>
              <el-descriptions-item label="机构角色">{{ selected.data.institutionRole || '—' }}</el-descriptions-item>
              <el-descriptions-item label="平台账号">
                {{ selected.data.username ? selected.data.username + '（' + (selected.data.displayName || '') + '）' : '—' }}
              </el-descriptions-item>
            </el-descriptions>
          </template>

          <el-empty v-else description="点左侧区块，或在上方搜索交易哈希 / 地址" :image-size="80" />
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { chainApi } from '../../api'
import { formatTime, shortAddress } from '../../utils/format'

const overview = ref(null)
const blocks = ref([])
const searchText = ref('')
const autoRefresh = ref(true)
const detailLoading = ref(false)
const selected = reactive({ type: '', data: null })
let timer = null

const txRows = computed(() => {
  const list = (selected.data?.transactions) || []
  return Array.isArray(list) ? list.filter((item) => item && typeof item === 'object') : []
})

const shortInput = computed(() => {
  const input = selected.data?.input || ''
  return input.length > 200 ? input.slice(0, 200) + '…' : input || '—'
})

function fmtTime(millis) {
  return millis ? formatTime(millis) : '—'
}

async function loadOverview() {
  try {
    overview.value = await chainApi.explorerOverview()
  } catch (e) {
    /* 链未就绪时保留旧值，页面照常 */
  }
}

async function loadBlocks() {
  try {
    blocks.value = (await chainApi.explorerBlocks({ limit: 20 })) || []
  } catch (e) {
    /* 同上 */
  }
}

async function openBlock(id) {
  detailLoading.value = true
  try {
    selected.type = 'block'
    selected.data = await chainApi.explorerBlock(id)
  } catch (e) {
    selected.data = null
  } finally {
    detailLoading.value = false
  }
}

async function openTx(hash) {
  detailLoading.value = true
  try {
    selected.type = 'transaction'
    selected.data = await chainApi.explorerTx(hash)
  } catch (e) {
    selected.data = null
  } finally {
    detailLoading.value = false
  }
}

async function openAddress(address) {
  detailLoading.value = true
  try {
    selected.type = 'address'
    selected.data = await chainApi.explorerAddress(address)
  } catch (e) {
    selected.data = null
  } finally {
    detailLoading.value = false
  }
}

async function doSearch() {
  const query = searchText.value.trim()
  if (!query) return
  try {
    const result = await chainApi.explorerSearch(query)
    if (result.type === 'block') await openBlock(result.target)
    else if (result.type === 'transaction') await openTx(result.target)
    else if (result.type === 'address') await openAddress(result.target)
  } catch (e) {
    selected.type = ''
    selected.data = null
  }
}

function refreshAll() {
  loadOverview()
  loadBlocks()
}

onMounted(() => {
  refreshAll()
  timer = window.setInterval(() => {
    if (autoRefresh.value && document.visibilityState !== 'hidden') {
      refreshAll()
    }
  }, 5000)
})

onBeforeUnmount(() => {
  if (timer) window.clearInterval(timer)
})
</script>

<style scoped>
.overview {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 26px;
  padding: 14px 16px;
  margin-bottom: 14px;
  background: var(--surface);
  border: 1px solid var(--hair);
  border-radius: var(--radius);
}

.ov-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.ov-key {
  font-size: 11.5px;
  color: var(--ink-3);
}

.ov-val {
  font-size: 18px;
  font-weight: 600;
  color: var(--ink);
  font-variant-numeric: tabular-nums;
}

.ov-val.small {
  font-size: 12.5px;
  font-weight: 500;
  color: var(--ink-2);
}

.search-bar {
  display: flex;
  align-items: center;
  gap: 10px;
}

.search-bar .el-input {
  max-width: 460px;
}

.block-list {
  margin-top: 8px;
  max-height: 620px;
  overflow-y: auto;
}

.block-item {
  padding: 8px 10px;
  border-radius: 7px;
  cursor: pointer;
  border: 1px solid transparent;
}

.block-item:hover {
  background: var(--tone);
}

.block-item.active {
  background: var(--accent-soft);
  border-color: var(--accent-line);
}

.bi-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
}

.bi-num {
  font-weight: 600;
  font-size: 13.5px;
  color: var(--ink);
  font-variant-numeric: tabular-nums;
}

.bi-sub {
  margin-top: 2px;
  font-size: 11.5px;
  color: var(--ink-3);
  display: flex;
  justify-content: space-between;
  gap: 8px;
}

.detail {
  min-height: 320px;
}

.mt-8 {
  margin-top: 8px;
}

.mt-16 {
  margin-top: 16px;
}

.block-tx-title {
  display: block;
  margin-top: 16px;
}

.ml-8 {
  margin-left: 8px;
}

.mono {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--ink-2);
}

.break-all {
  word-break: break-all;
}

.ellipsis {
  display: inline-block;
  max-width: 320px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: bottom;
}
</style>
