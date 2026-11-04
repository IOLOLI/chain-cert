<template>
  <div>
    <h3 class="page-title">链上索引</h3>
    <p class="page-desc">
      合约刻意没有"遍历所有公司/职工"的接口（避免链上循环与 gas 爆炸），名单、报表、模糊查询都走链下索引：
      发交易后后端解析回执事件写入索引表。链上余额与状态始终是唯一真相，索引表可随时重放事件重建。
    </p>

    <el-tabs v-model="tab" class="card-block">
      <el-tab-pane label="链上事件" name="events">
        <el-form inline>
          <el-form-item label="职工地址">
            <el-input v-model="eventQuery.employee" style="width: 300px" clearable />
          </el-form-item>
          <el-form-item label="单位地址">
            <el-input v-model="eventQuery.sponsor" style="width: 300px" clearable />
          </el-form-item>
          <el-form-item label="事件名">
            <el-input v-model="eventQuery.eventName" placeholder="如 PaymentMade" clearable />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="loading" @click="loadEvents">查询</el-button>
          </el-form-item>
        </el-form>

        <el-table :data="events" size="small" class="mt-8" empty-text="暂无事件（发一笔缴费试试）">
          <el-table-column label="事件" width="180">
            <template #default="{ row }">
              <el-tag size="small">{{ row.eventName }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="contractName" label="合约" width="150" />
          <el-table-column prop="functionName" label="触发函数" width="140" />
          <el-table-column label="金额" width="120">
            <template #default="{ row }">{{ formatAmount(row.amount) }}</template>
          </el-table-column>
          <el-table-column label="交易哈希" min-width="200">
            <template #default="{ row }">
              <span class="chain-address">{{ shortAddress(row.txHash, 12, 10) }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="blockNumber" label="区块" width="90" />
          <el-table-column label="时间" width="160">
            <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="原始数据" width="100">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="showPayload(row.payload)">查看</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination class="mt-16" layout="total, prev, pager, next" :total="eventTotal" :page-size="pageSize"
          :current-page="eventPage" @current-change="onEventPage" />
      </el-tab-pane>

      <el-tab-pane label="上链操作日志" name="txlogs">
        <el-form inline>
          <el-form-item label="机构编码">
            <el-input v-model="logQuery.institutionCode" style="width: 200px" clearable />
          </el-form-item>
          <el-form-item label="函数名">
            <el-input v-model="logQuery.functionName" style="width: 200px" clearable />
          </el-form-item>
          <el-form-item label="结果">
            <el-select v-model="logQuery.success" clearable style="width: 140px">
              <el-option label="成功" :value="true" />
              <el-option label="失败" :value="false" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="loading" @click="loadLogs">查询</el-button>
          </el-form-item>
        </el-form>

        <el-table :data="logs" size="small" class="mt-8" empty-text="暂无上链记录">
          <el-table-column prop="institutionCode" label="机构" width="120" />
          <el-table-column prop="contractName" label="合约" width="150" />
          <el-table-column prop="functionName" label="函数" width="150" />
          <el-table-column label="结果" width="90">
            <template #default="{ row }">
              <el-tag :type="row.success ? 'success' : 'danger'" size="small">
                {{ row.success ? '成功' : '失败' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="交易哈希" min-width="200">
            <template #default="{ row }">
              <span class="chain-address">{{ shortAddress(row.txHash, 12, 10) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="时间" width="160">
            <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="信息" min-width="200">
            <template #default="{ row }">
              <span :class="{ 'text-muted': row.success }">{{ row.message }}</span>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination class="mt-16" layout="total, prev, pager, next" :total="logTotal" :page-size="pageSize"
          :current-page="logPage" @current-change="onLogPage" />
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="payloadVisible" title="事件原始数据" width="640px">
      <pre class="payload">{{ payload }}</pre>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { indexApi } from '../../api'
import { formatAmount, formatTime, shortAddress } from '../../utils/format'

const tab = ref('events')
const loading = ref(false)
const pageSize = 10

const events = ref([])
const eventTotal = ref(0)
const eventPage = ref(1)
const eventQuery = reactive({ employee: '', sponsor: '', eventName: '' })

const logs = ref([])
const logTotal = ref(0)
const logPage = ref(1)
const logQuery = reactive({ institutionCode: '', functionName: '', success: undefined })

const payload = ref('')
const payloadVisible = ref(false)

async function loadEvents() {
  loading.value = true
  try {
    const data = await indexApi.events({ ...eventQuery, page: eventPage.value, size: pageSize })
    events.value = data.records || []
    eventTotal.value = data.total || 0
  } finally {
    loading.value = false
  }
}

async function loadLogs() {
  loading.value = true
  try {
    const data = await indexApi.txLogs({ ...logQuery, page: logPage.value, size: pageSize })
    logs.value = data.records || []
    logTotal.value = data.total || 0
  } finally {
    loading.value = false
  }
}

function onEventPage(page) {
  eventPage.value = page
  loadEvents()
}

function onLogPage(page) {
  logPage.value = page
  loadLogs()
}

function showPayload(text) {
  payload.value = text || '（无）'
  payloadVisible.value = true
}

onMounted(() => {
  loadEvents()
  loadLogs()
})
</script>

<style scoped>
.payload {
  max-height: 420px;
  overflow: auto;
  background: #f5f7fa;
  padding: 12px;
  border-radius: 6px;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
