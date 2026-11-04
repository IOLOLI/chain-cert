<template>
  <el-card shadow="never" class="panel-card">
    <template #header>
      <h2 class="ttl">{{ title }}</h2>
      <div class="meta">
        <slot name="meta" />
      </div>
    </template>
    <slot />
  </el-card>
</template>

<script setup>
/**
 * 内容面板：标题 + 右上角口径说明 + 主体。
 *
 * 表头右侧那行小字（"共 N 条""按创建顺序""判定以链上为准"）本来是每个页面各写一遍的，
 * 收进这里之后各页只负责内容，标题的字号与内边距才不会再漂。
 */
defineProps({
  title: { type: String, required: true }
})
</script>

<style scoped>
.panel-card {
  margin-bottom: 14px;
}

:deep(.el-card__header) {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  padding: 13px 18px;
}

:deep(.el-card__body) {
  padding: 0;
}

.ttl {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--ink);
}

.meta {
  font-size: 11.5px;
  color: var(--ink-3);
  text-align: right;
}
</style>
