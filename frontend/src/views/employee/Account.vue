<template>
  <div>
    <h3 class="page-title">我的社保账户</h3>
    <p class="page-desc">
      余额与缴费记录都直接读链上（链上是唯一真相）。可见性由合约判定：本人 / 所属单位 / ADMIN / REGULATOR，
      查别人的地址会被链上拒绝。
    </p>

    <div class="card-block">
      <el-form inline>
        <el-form-item label="职工地址">
          <el-input v-model="employee" style="width: 340px" readonly placeholder="默认使用你已绑定并验签的地址" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="load">查询</el-button>
          <el-button @click="useMyAddress">用我的地址</el-button>
        </el-form-item>
      </el-form>
    </div>

    <el-row :gutter="16">
      <el-col :span="10">
        <div class="card-block">
          <strong>参保账户</strong>
          <el-descriptions v-if="account" :column="1" border size="small" class="mt-8">
            <el-descriptions-item label="姓名">{{ account.name }}</el-descriptions-item>
            <el-descriptions-item label="身份证号">{{ account.idNumber }}</el-descriptions-item>
            <el-descriptions-item label="参保城市">{{ account.city }}</el-descriptions-item>
            <el-descriptions-item label="所属单位">
              <!-- 优先显示后端新增的 sponsorName；缺字段时回退到链上地址（缩写 + hover 看完整地址） -->
              <template v-if="account.sponsorName">
                <span>{{ account.sponsorName }}</span>
                <el-tooltip v-if="account.sponsor" :content="account.sponsor" placement="top">
                  <span class="text-muted mono" style="margin-left: 6px">
                    {{ shortAddress(account.sponsor) }}
                  </span>
                </el-tooltip>
              </template>
              <el-tooltip v-else-if="account.sponsor" :content="account.sponsor" placement="top">
                <span class="chain-address">{{ shortAddress(account.sponsor) }}</span>
              </el-tooltip>
              <span v-else class="text-muted">（未参保/已转出）</span>
            </el-descriptions-item>
            <el-descriptions-item label="状态">
              <el-tag :type="employeeStatusType(account.status)" size="small">
                {{ employeeStatusName(account.status) }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="月工资 / 缴费基数">
              {{ formatAmount(account.salary) }} / {{ formatAmount(account.paymentBase) }}
            </el-descriptions-item>
            <el-descriptions-item label="个人账户余额">
              <span class="stat-value">{{ formatAmount(account.personalBalance) }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="统筹账户权益">
              {{ formatAmount(account.overallBalance) }}
            </el-descriptions-item>
            <el-descriptions-item label="累计缴费月数">{{ account.paidMonths }}</el-descriptions-item>
            <el-descriptions-item label="逾期缴费次数">{{ account.overdueCount }}</el-descriptions-item>
            <el-descriptions-item label="上次缴费时间">{{ formatTime(account.lastPaymentAt) }}</el-descriptions-item>
            <el-descriptions-item label="在途转移">
              <span v-if="account.activeTransferId" class="text-muted">
                申请编号 {{ Number(account.activeTransferId) - 1 }}（在途）
              </span>
              <span v-else class="text-muted">无</span>
            </el-descriptions-item>
          </el-descriptions>
          <el-empty v-else description="填写地址后查询" />
        </div>

        <div v-if="isRetired" class="card-block mt-16">
          <div class="flex-between mb-8">
            <strong>养老金</strong>
            <el-tag type="success" size="small">已退休</el-tag>
          </div>
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="每月应发">
              <span class="stat-value">{{ formatAmount(monthlyTotal) }}</span>
              <div class="text-muted tip-inline">
                基础 {{ formatAmount(baseMonthly) }}＋个人账户 {{ formatAmount(account.pensionPersonalMonthly) }}
              </div>
            </el-descriptions-item>
            <el-descriptions-item label="已领取月数">{{ account.pensionClaimedMonths ?? 0 }}</el-descriptions-item>
            <el-descriptions-item label="上次领取">
              <span v-if="Number(account.lastPensionAt) > 0">{{ formatTime(account.lastPensionAt) }}</span>
              <span v-else class="text-muted">尚未领取</span>
            </el-descriptions-item>
            <el-descriptions-item label="下次可领">
              <span v-if="canClaimNow">现在可领</span>
              <span v-else class="text-muted">{{ formatTime(nextClaimAt) }}</span>
            </el-descriptions-item>
          </el-descriptions>
          <el-button type="primary" class="mt-16" :disabled="!canClaimNow" :loading="claiming"
            style="width: 100%" @click="claimPension">
            领取本月养老金
          </el-button>
          <div class="text-muted tip-inline mt-8">
            同一职工 30 天内只能领一次。基础养老金由参保城市统筹基金支付，
            个人账户养老金从你的个人账户余额中扣减，两笔都会在链上留下发放记录。
          </div>
        </div>
      </el-col>

      <el-col :span="14">
        <div class="card-block">
          <strong>缴费记录</strong>
          <el-table :data="records" size="small" class="mt-8" empty-text="暂无记录">
            <el-table-column label="时间" width="150">
              <template #default="{ row }">{{ formatTime(row.timestamp) }}</template>
            </el-table-column>
            <el-table-column label="基数" width="84">
              <template #default="{ row }">{{ formatAmount(row.paymentBase) }}</template>
            </el-table-column>
            <el-table-column label="个人 8%" width="84">
              <template #default="{ row }">{{ formatAmount(row.personalAmount) }}</template>
            </el-table-column>
            <el-table-column label="统筹 19%" width="84">
              <template #default="{ row }">{{ formatAmount(row.overallAmount) }}</template>
            </el-table-column>
            <el-table-column label="缴费单位" min-width="136">
              <template #default="{ row }">
                <!-- 有单位名就用单位名（hover 显示地址），否则回退到地址缩写 -->
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
            <el-table-column label="逾期" width="64">
              <template #default="{ row }">
                <el-tag :type="row.overdue ? 'danger' : 'success'" size="small">
                  {{ row.overdue ? '是' : '否' }}
                </el-tag>
              </template>
            </el-table-column>
          </el-table>
          <div class="text-muted mt-8">共 {{ total }} 条</div>
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { employeeApi } from '../../api'
import { employeeStatusName, employeeStatusType, formatAmount, formatTime, shortAddress } from '../../utils/format'
import { useUserStore } from '../../stores/user'

const user = useUserStore()
const employee = ref('')
const account = ref(null)
const records = ref([])
const total = ref(0)
const loading = ref(false)
const claiming = ref(false)

/** 合约 EmployeeStatus：5 = RETIRED */
const isRetired = computed(() => Number(account.value?.status) === 5)

/** 基础养老金 = 缴费基数 × 缴费月数 / 1200（与合约 _basePension 同一口径） */
const baseMonthly = computed(() => {
  const data = account.value
  if (!data) return 0
  const base = Number(data.paymentBase || 0)
  const months = Number(data.paidMonths || 0)
  return Math.floor((base * months) / 1200)
})

const monthlyTotal = computed(() => baseMonthly.value + Number(account.value?.pensionPersonalMonthly || 0))

/** 距上次发放满 30 天才可再领（与合约 PAYMENT_CYCLE 一致） */
const nextClaimAt = computed(() => {
  const last = Number(account.value?.lastPensionAt || 0)
  return last > 0 ? last + 30 * 24 * 3600 : 0
})

const canClaimNow = computed(() => {
  if (!isRetired.value) return false
  const next = nextClaimAt.value
  return next === 0 || Date.now() / 1000 >= next
})

async function claimPension() {
  try {
    await ElMessageBox.confirm(
      `确认领取本月养老金 ${formatAmount(monthlyTotal.value)}？`,
      '领取养老金',
      { type: 'info', confirmButtonText: '确认领取', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  claiming.value = true
  try {
    await employeeApi.claimPension()
    ElMessage.success('领取成功，链上余额已更新')
    await load()
  } catch (e) {
    /* http.js 已统一提示后端 message */
  } finally {
    claiming.value = false
  }
}

async function useMyAddress() {
  // 职工账号创建时已自动生成链上地址，钱包绑定只作兜底
  if (!user.chainAddress) {
    await user.ensureChainAddress()
  }
  if (!user.chainAddress) {
    ElMessage.warning('当前账号没有链上地址，请联系管理员')
    return
  }
  employee.value = user.chainAddress
}

async function load() {
  if (!employee.value) {
    ElMessage.warning('请填写职工地址')
    return
  }
  loading.value = true
  try {
    account.value = await employeeApi.account(employee.value.trim())
    const data = await employeeApi.payments(employee.value.trim(), 0, 20)
    records.value = data.records || []
    total.value = data.total || 0
  } catch (e) {
    account.value = null
    records.value = []
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await useMyAddress()
  if (employee.value) load()
})
</script>
