<template>
  <div>
    <h3 class="page-title">合约调试台</h3>
    <p class="page-desc">
      骨架阶段的联调利器：直接调用合约的任意函数。发交易时会用<strong>当前账号所属机构的私钥</strong>签名，
      所以这不是"越权后门"—— 合约会按签名地址再判一次权限，角色不匹配照样回滚。
    </p>

    <el-row :gutter="16">
      <el-col :span="12">
        <div class="card-block">
          <strong>只读调用（view 函数）</strong>
          <el-form :model="callForm" label-width="90px" class="mt-8">
            <el-form-item label="合约">
              <el-select v-model="callForm.contract" style="width: 100%">
                <el-option label="ChainSIP（核心：账户/资金/监管）" value="ChainSIP" />
                <el-option label="ChainSIPTransfer（转移：社保局/转移）" value="ChainSIPTransfer" />
              </el-select>
            </el-form-item>
            <el-form-item label="函数名">
              <el-input v-model="callForm.function" placeholder="如 getSystemStats、roleOf" />
            </el-form-item>
            <el-form-item label="参数(JSON)">
              <el-input v-model="callForm.params" type="textarea" :rows="3" placeholder='["0x..."] 或 [0, 20]' />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="busy" @click="doCall">调用</el-button>
              <el-button @click="callForm.function = 'getSystemStats'; callForm.params = '[]'">
                填充示例
              </el-button>
            </el-form-item>
          </el-form>
          <div v-if="callResult" class="result">
            <div class="flex-between">
              <strong>返回</strong>
              <el-button link type="primary" size="small" @click="copy(callResult.rawValues)">复制原始 JSON</el-button>
            </div>
            <pre class="payload">{{ callResult.rawValues || JSON.stringify(callResult.values, null, 2) }}</pre>
          </div>
        </div>
      </el-col>

      <el-col :span="12">
        <div class="card-block">
          <strong>发交易（send）</strong>
          <el-form :model="sendForm" label-width="90px" class="mt-8">
            <el-form-item label="机构编码">
              <el-input :model-value="user.institutionCode || '（当前账号未关联机构私钥）'" disabled />
            </el-form-item>
            <el-form-item label="合约">
              <el-select v-model="sendForm.contract" style="width: 100%">
                <el-option label="ChainSIP（核心）" value="ChainSIP" />
                <el-option label="ChainSIPTransfer（转移）" value="ChainSIPTransfer" />
              </el-select>
            </el-form-item>
            <el-form-item label="函数名">
              <el-input v-model="sendForm.function" placeholder="如 depositSponsor、makePayment" />
            </el-form-item>
            <el-form-item label="参数(JSON)">
              <el-input v-model="sendForm.params" type="textarea" :rows="3" placeholder='["0x...", "100000"]' />
            </el-form-item>
            <el-form-item>
              <el-button type="success" :loading="busy" @click="doSend">签名并发送</el-button>
            </el-form-item>
          </el-form>
          <div v-if="sendResult" class="result">
            <el-descriptions :column="1" border size="small">
              <el-descriptions-item label="结果">
                <el-tag :type="sendResult.success ? 'success' : 'danger'" size="small">
                  {{ sendResult.success ? '成功' : '已上链但回滚（status=' + sendResult.status + '）' }}
                </el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="交易哈希">
                <span class="chain-address">{{ sendResult.transactionHash || '-' }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="区块号">{{ sendResult.blockNumber ?? '-' }}</el-descriptions-item>
              <el-descriptions-item label="信息">{{ sendResult.message }}</el-descriptions-item>
            </el-descriptions>
          </div>
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { chainApi } from '../../api'
import { useUserStore } from '../../stores/user'

const user = useUserStore()
const busy = ref(false)
const callResult = ref(null)
const sendResult = ref(null)

const callForm = reactive({ contract: 'ChainSIP', function: 'getSystemStats', params: '[]' })
const sendForm = reactive({ contract: 'ChainSIP', function: '', params: '[]' })

function parseParams(text) {
  if (!text || !text.trim()) return []
  const value = JSON.parse(text)
  if (!Array.isArray(value)) {
    throw new Error('参数必须是 JSON 数组，例如 ["0x...", "100000"]')
  }
  return value
}

async function doCall() {
  busy.value = true
  try {
    callResult.value = await chainApi.call(callForm.contract, callForm.function.trim(), parseParams(callForm.params))
  } catch (e) {
    ElMessage.error(e.message || '调用失败')
  } finally {
    busy.value = false
  }
}

async function doSend() {
  if (!sendForm.function) {
    ElMessage.warning('请填写函数名')
    return
  }
  busy.value = true
  try {
    sendResult.value = await chainApi.send(
      sendForm.contract,
      sendForm.function.trim(),
      parseParams(sendForm.params)
    )
    ElMessage.success('交易已上链')
  } catch (e) {
    sendResult.value = null
  } finally {
    busy.value = false
  }
}

async function copy(text) {
  if (!text) return
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制')
  } catch (e) {
    ElMessage.warning('浏览器拒绝了剪贴板访问')
  }
}
</script>

<style scoped>
.payload {
  max-height: 360px;
  overflow: auto;
  background: #f5f7fa;
  padding: 12px;
  border-radius: 6px;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
  margin: 8px 0 0;
}

.result {
  margin-top: 12px;
}
</style>
