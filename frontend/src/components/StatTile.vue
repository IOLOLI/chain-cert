<template>
  <el-card shadow="never" class="stat-tile">
    <div class="cap">
      <span>{{ label }}</span>
      <el-icon v-if="icon"><component :is="icon" /></el-icon>
    </div>
    <el-statistic :value="value ?? ''" :formatter="amountText" />
    <div v-if="note" class="note">{{ note }}</div>
  </el-card>
</template>

<script setup>
import { formatAmount } from '../utils/format'

/**
 * 指标卡：一个数字 + 一句口径。
 *
 * <p>所有数字都来自链上，金额可能是超大整数的十进制字符串、首次渲染时是 undefined，
 * 因此统一走 formatAmount（空值渲染成 "-"），不要直接用 el-statistic 的默认格式化。
 */
defineProps({
  label: { type: String, required: true },
  value: { type: [String, Number], default: '' },
  note: { type: String, default: '' },
  icon: { type: [String, Object], default: '' }
})

const amountText = (v) => formatAmount(v)
</script>

<style scoped>
:deep(.el-card__body) {
  padding: 14px 16px 13px;
  display: flex;
  flex-direction: column;
  min-height: 100px;
}

.cap {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  font-size: 12px;
  color: var(--ink-2);
  margin-bottom: 2px;
}

.cap .el-icon {
  font-size: 14px;
  opacity: 0.4;
}

.note {
  font-size: 11.5px;
  color: var(--ink-3);
  margin-top: auto;
  padding-top: 3px;
  line-height: 1.5;
}
</style>
