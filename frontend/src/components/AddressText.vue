<template>
  <el-tooltip v-if="address" :content="address" placement="bottom" :show-after="260">
    <button class="addr" type="button" @click="copy">
      {{ shortAddress(address, head, tail) }}
    </button>
  </el-tooltip>
  <span v-else class="addr-empty">{{ emptyText }}</span>
</template>

<script setup>
import { ElMessage } from 'element-plus'
import { copyText, shortAddress } from '../utils/format'

/**
 * 链上地址：缩写显示 + 悬浮看全文 + 点一下复制。
 *
 * 42 个字符会把表格撑破，所以一律缩写；但复制必须是完整地址，
 * 否则用户拿到的是一段没法用的文本 —— 这两件事都收在这个组件里，页面不用各写一遍。
 */
const props = defineProps({
  address: { type: String, default: '' },
  head: { type: Number, default: 10 },
  tail: { type: Number, default: 8 },
  emptyText: { type: String, default: '—' }
})

async function copy () {
  const ok = await copyText(props.address)
  if (ok) ElMessage.success('地址已复制')
  else ElMessage.warning('复制失败，请手动选中复制')
}
</script>

<style scoped>
.addr {
  border: 0;
  background: transparent;
  padding: 0;
  font: inherit;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  letter-spacing: 0.01em;
  color: var(--ink-2);
  cursor: pointer;
  transition: color 0.16s ease;
}

.addr:hover {
  color: var(--accent);
  text-decoration: underline;
  text-underline-offset: 2px;
}

.addr-empty {
  font-size: 12px;
  color: var(--ink-3);
}
</style>
