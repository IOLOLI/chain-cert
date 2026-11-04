<template>
  <div>
    <h3 class="page-title">职工建档 / 注销</h3>
    <p class="page-desc">
      ADMIN 可以全量录入职工档案，也可以由缴费单位自助建档。
      参保城市由<strong>缴费单位注册的城市</strong>推导（保证转移时转出地可判定），因此这里不需要填城市。
      一个职工同一时间只能被一家单位参保（"一人一缴"），换单位必须先由原单位减员。
    </p>

    <el-row :gutter="16">
      <el-col :span="13">
        <div class="card-block">
          <strong>全量建档（addPerson）</strong>
          <el-form :model="form" label-width="100px" class="mt-8">
            <el-form-item label="职工地址">
              <el-input v-model="form.employee" placeholder="0x..." />
            </el-form-item>
            <el-form-item label="姓名">
              <el-input v-model="form.name" placeholder="张三" />
            </el-form-item>
            <el-form-item label="年龄">
              <el-input-number v-model="form.age" :min="16" :max="100" />
            </el-form-item>
            <el-form-item label="身份证号">
              <el-input v-model="form.idNumber" placeholder="110101199001011234（链上唯一，防重复参保）" />
            </el-form-item>
            <el-form-item label="用工单位名称">
              <el-input v-model="form.employer" placeholder="甲公司（劳动部门档案）" />
            </el-form-item>
            <el-form-item label="入职日期">
              <el-date-picker v-model="startDate" type="date" placeholder="选择日期" style="width: 100%" />
            </el-form-item>
            <el-form-item label="月工资（元）">
              <el-input-number v-model="form.salary" :min="1" :step="500" style="width: 200px" />
              <span class="text-muted" style="margin-left: 10px">
                缴费基数按 [3200, 24000] 夹取，例如 25000 → 24000
              </span>
            </el-form-item>
            <el-form-item label="缴费单位">
              <el-input v-model="form.sponsor" placeholder="公司地址 0x..." />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="busy" @click="submit">录入档案</el-button>
              <el-button @click="reset">重置</el-button>
            </el-form-item>
          </el-form>
        </div>
      </el-col>

      <el-col :span="11">
        <div class="card-block">
          <strong>注销职工账户（removeEmployee）</strong>
          <p class="text-muted mt-8">
            注销要求<strong>账户余额为 0 且无在途转移</strong>：资金只能通过转移流程结清，避免链上直接销毁职工权益。
          </p>
          <el-form label-width="90px">
            <el-form-item label="职工地址">
              <el-input v-model="removeAddress" placeholder="0x..." />
            </el-form-item>
            <el-form-item>
              <el-button type="danger" :loading="busy" @click="remove">注销账户</el-button>
            </el-form-item>
          </el-form>
        </div>

        <div class="card-block">
          <strong>查询参保账户</strong>
          <el-form label-width="90px" class="mt-8">
            <el-form-item label="职工地址">
              <el-input v-model="queryAddress" placeholder="0x...">
                <template #append>
                  <el-button @click="query">查询</el-button>
                </template>
              </el-input>
            </el-form-item>
          </el-form>
          <el-descriptions v-if="account" :column="1" border size="small">
            <el-descriptions-item label="姓名">{{ account.name }}</el-descriptions-item>
            <el-descriptions-item label="参保城市">{{ account.city }}</el-descriptions-item>
            <el-descriptions-item label="状态">
              <el-tag :type="employeeStatusType(account.status)" size="small">
                {{ employeeStatusName(account.status) }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="缴费基数">{{ formatAmount(account.paymentBase) }}</el-descriptions-item>
            <el-descriptions-item label="个人账户 / 统筹权益">
              {{ formatAmount(account.personalBalance) }} / {{ formatAmount(account.overallBalance) }}
            </el-descriptions-item>
            <el-descriptions-item label="累计缴费月数">{{ account.paidMonths }}</el-descriptions-item>
            <el-descriptions-item label="年龄">{{ account.age ?? '—' }}</el-descriptions-item>
          </el-descriptions>
          <p v-else-if="queried" class="text-muted query-empty">
            该地址没有参保档案（未建档 / 地址不存在）。
          </p>
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi, chainApi } from '../../api'
import { STRUCT_FIELDS, mapStruct } from '../../utils/contract'
import { employeeStatusName, employeeStatusType, formatAmount, isAddress } from '../../utils/format'

const busy = ref(false)
const startDate = ref(new Date())
const removeAddress = ref('')
const queryAddress = ref('')
const account = ref(null)
/** 是否已经查过一次：用来区分"还没查"与"查了但没有档案" */
const queried = ref(false)

const form = reactive({
  employee: '',
  name: '',
  age: 30,
  idNumber: '',
  employer: '',
  startDate: 0,
  salary: 10000,
  sponsor: ''
})

function reset() {
  Object.assign(form, {
    employee: '', name: '', age: 30, idNumber: '', employer: '', startDate: 0, salary: 10000, sponsor: ''
  })
}

async function submit() {
  if (!form.employee || !form.name || !form.idNumber || !form.sponsor) {
    ElMessage.warning('请填写职工地址、姓名、身份证号与缴费单位')
    return
  }
  if (!isAddress(form.employee.trim()) || !isAddress(form.sponsor.trim())) {
    ElMessage.warning('职工地址与缴费单位地址都必须是 0x + 40 位十六进制')
    return
  }
  busy.value = true
  try {
    await adminApi.addPerson({
      ...form,
      employee: form.employee.trim(),
      sponsor: form.sponsor.trim(),
      startDate: startDate.value ? Math.floor(new Date(startDate.value).getTime() / 1000) : 0
    })
    ElMessage.success('档案已录入链上')
  } catch (e) {
    // http.js 已按后端 message 弹过提示（例如"该职工已在其他单位参保"），这里不再重复弹
  } finally {
    busy.value = false
  }
}

async function remove() {
  if (!removeAddress.value) {
    ElMessage.warning('请填写职工地址')
    return
  }
  if (!isAddress(removeAddress.value.trim())) {
    ElMessage.warning('职工地址必须是 0x + 40 位十六进制')
    return
  }
  try {
    await ElMessageBox.confirm('确定注销该职工账户吗？注销后档案留痕、身份证号不可复用。', '提示', {
      type: 'warning'
    })
  } catch (e) {
    // 用户取消：ElMessageBox 以 reject 结束，这里吞掉，避免未处理的 Promise 拒绝
    return
  }
  busy.value = true
  try {
    await adminApi.removeEmployee(removeAddress.value.trim())
    ElMessage.success('已注销')
  } catch (e) {
    // 同上：余额不为 0 / 有在途转移等业务错误已由 http.js 提示
  } finally {
    busy.value = false
  }
}

async function query() {
  if (!queryAddress.value) {
    ElMessage.warning('请填写职工地址')
    return
  }
  if (!isAddress(queryAddress.value.trim())) {
    ElMessage.warning('职工地址必须是 0x + 40 位十六进制')
    return
  }
  account.value = null
  queried.value = false
  try {
    const mapped = mapStruct(
      await chainApi.call('ChainSIP', 'getInsuranceAccount', [queryAddress.value.trim()]),
      STRUCT_FIELDS.insuranceAccount
    )
    // 未建档的地址合约会回滚（走 catch）；这里兜住"返回了但字段全空"的情况
    account.value = mapped && (mapped.employee || mapped.name) ? mapped : null
  } catch (e) {
    // http.js 已提示（例如"该职工尚未在链上建档"），清掉上一次的查询结果即可
    account.value = null
  } finally {
    queried.value = true
  }
}
</script>

<style scoped>
.query-empty {
  margin: 10px 0 0;
  font-size: 12.5px;
}
</style>
