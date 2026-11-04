<template>
  <div>
    <h3 class="page-title">养老保险关系转移</h3>
    <p class="page-desc">
      流程：职工提交申请（PENDING）→ 转出地社保局复核放行（AUTHORIZED，个人账户资金与统筹基金<strong>托管锁定</strong>）
      → 转入地社保局接收（RECEIVED，基金落到新城市池与职工名下）。
      放行的硬前置是<strong>原单位已办理停缴（减员）</strong>，否则复核失败。
    </p>

    <el-row :gutter="16">
      <el-col :span="10">
        <div class="card-block">
          <strong>我的参保状态</strong>
          <el-form inline class="mt-8">
            <el-form-item label="职工地址">
              <el-input v-model="employee" style="width: 260px" readonly />
            </el-form-item>
            <el-form-item>
              <el-button size="small" :loading="loading" @click="loadState">刷新</el-button>
            </el-form-item>
          </el-form>
          <el-descriptions v-if="state" :column="1" border size="small">
            <el-descriptions-item label="参保状态">
              <el-tag :type="employeeStatusType(state.status)" size="small">
                {{ employeeStatusName(state.status) }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="参保城市">{{ state.city }}</el-descriptions-item>
            <el-descriptions-item label="在途转移申请">
              <span v-if="state.activeTransferRef" class="mono">
                编号 {{ Number(state.activeTransferRef) - 1 }}
              </span>
              <span v-else class="text-muted">无</span>
            </el-descriptions-item>
          </el-descriptions>
          <el-descriptions v-if="escrow" :column="1" border size="small" class="mt-8" title="在途托管金额">
            <el-descriptions-item label="个人账户资金">
              {{ formatAmount(escrow.personalFund) }}
            </el-descriptions-item>
            <el-descriptions-item label="统筹基金">
              {{ formatAmount(escrow.overallFund) }}
            </el-descriptions-item>
          </el-descriptions>
        </div>

        <div class="card-block">
          <strong>发起转移申请</strong>
          <el-form label-width="90px" class="mt-8">
            <el-form-item label="目标城市">
              <el-input v-model="toCity" placeholder="如 B市（需已绑定社保局并授权）" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="busy" @click="apply">提交申请</el-button>
              <el-button @click="queryCityBureau">查目标城市社保局</el-button>
            </el-form-item>
          </el-form>
          <div v-if="targetBureau" class="text-muted chain-address">目标城市社保局：{{ targetBureau }}</div>
        </div>
      </el-col>

      <el-col :span="14">
        <div class="card-block">
          <div class="flex-between">
            <strong>我的转移申请</strong>
            <el-button size="small" :loading="loading" @click="loadTransfers">刷新</el-button>
          </div>
          <p class="text-muted tip">直接读链上：当前账号的全部转移申请（按编号倒序），无需输入申请编号。</p>
          <el-empty v-if="!transfers.length" description="暂无转移申请" :image-size="60" />
          <el-collapse v-else v-model="openIds" class="mt-8">
            <el-collapse-item v-for="item in transfers" :key="item.id" :name="String(item.id)">
              <template #title>
                <span class="tf-title">
                  <span class="mono">#{{ item.id }}</span>
                  <el-tag :type="transferStatusType(item.status)" size="small">
                    {{ transferStatusName(item.status) }}
                  </el-tag>
                  <span class="tf-route">{{ item.fromCity }} → {{ item.toCity }}</span>
                  <span class="text-muted">{{ formatTime(item.appliedAt) }}</span>
                </span>
              </template>
              <el-descriptions :column="2" border size="small">
                <el-descriptions-item label="状态">
                  <el-tag :type="transferStatusType(item.status)" size="small">
                    {{ transferStatusName(item.status) }}
                  </el-tag>
                </el-descriptions-item>
                <el-descriptions-item label="申请人">
                  <span class="chain-address">{{ shortAddress(item.employee) }}</span>
                </el-descriptions-item>
                <el-descriptions-item label="转出城市">{{ item.fromCity }}</el-descriptions-item>
                <el-descriptions-item label="转入城市">{{ item.toCity }}</el-descriptions-item>
                <el-descriptions-item label="转出地社保局">
                  <span class="chain-address">{{ shortAddress(item.fromBureau) }}</span>
                </el-descriptions-item>
                <el-descriptions-item label="转入地社保局">
                  <span class="chain-address">{{ shortAddress(item.toBureau) }}</span>
                </el-descriptions-item>
                <el-descriptions-item label="个人账户基金">{{ formatAmount(item.personalFund) }}</el-descriptions-item>
                <el-descriptions-item label="统筹基金">{{ formatAmount(item.overallFund) }}</el-descriptions-item>
                <el-descriptions-item label="累计缴费月数">{{ item.paidMonths }}</el-descriptions-item>
                <el-descriptions-item label="申请时间">{{ formatTime(item.appliedAt) }}</el-descriptions-item>
                <el-descriptions-item label="放行时间">{{ formatTime(item.authorizedAt) }}</el-descriptions-item>
                <el-descriptions-item label="接收时间">{{ formatTime(item.receivedAt) }}</el-descriptions-item>
                <el-descriptions-item label="驳回原因" :span="2">{{ item.reason || '—' }}</el-descriptions-item>
              </el-descriptions>
            </el-collapse-item>
          </el-collapse>
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { employeeApi } from '../../api'
import { STRUCT_FIELDS, mapStruct } from '../../utils/contract'
import {
  employeeStatusName,
  employeeStatusType,
  formatAmount,
  formatTime,
  shortAddress,
  transferStatusName,
  transferStatusType
} from '../../utils/format'
import { useUserStore } from '../../stores/user'

const user = useUserStore()
const employee = ref('')
const toCity = ref('')
const targetBureau = ref('')
const state = ref(null)
const escrow = ref(null)
const transfers = ref([])
const openIds = ref([])
const busy = ref(false)
const loading = ref(false)

async function loadState() {
  if (!employee.value) return
  loading.value = true
  try {
    state.value = await employeeApi.transferState(employee.value.trim())
    try {
      escrow.value = await employeeApi.escrow(employee.value.trim())
    } catch (e) {
      escrow.value = null
    }
  } finally {
    loading.value = false
  }
}

async function apply() {
  if (!employee.value) {
    employee.value = await user.ensureChainAddress()
  }
  if (!toCity.value) {
    ElMessage.warning('请填写目标城市')
    return
  }
  busy.value = true
  try {
    await employeeApi.applyTransfer(toCity.value.trim())
    ElMessage.success('转移申请已提交，等待转出地社保局放行')
    await loadState()
    await loadTransfers()
  } finally {
    busy.value = false
  }
}

async function queryCityBureau() {
  if (!toCity.value) return
  targetBureau.value = await employeeApi.bureauOfCity(toCity.value.trim())
}

async function loadTransfers() {
  loading.value = true
  try {
    const data = await employeeApi.myTransfers(0, 50)
    transfers.value = data?.list || []
    if (transfers.value.length && !openIds.value.length) {
      openIds.value = [String(transfers.value[0].id)]
    }
  } catch (e) {
    transfers.value = []
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  // 职工账号创建时已自动生成链上地址，钱包绑定只作兜底
  employee.value = user.chainAddress || (await user.ensureChainAddress()) || ''
  if (employee.value) {
    loadState()
    loadTransfers()
  }
})
</script>

<style scoped>
.tip {
  font-size: 12px;
  line-height: 1.6;
  margin: 6px 0 0;
}

.tf-title {
  display: flex;
  align-items: center;
  gap: 10px;
}

.tf-route {
  color: var(--ink-2);
}
</style>
