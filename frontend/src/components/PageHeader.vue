<template>
  <div class="page-head">
    <div class="page-head-main">
      <h1 class="page-title">{{ title }}</h1>
      <p v-if="desc" class="page-desc">{{ desc }}</p>
    </div>
    <div v-if="$slots.default" class="page-head-actions">
      <slot />
    </div>
  </div>
</template>

<script setup>
/**
 * 页面头：标题 + 一句话说明 + 右侧动作区。
 *
 * 说明文字的用途是「告诉用户这个页面的数据从哪来」——本项目所有数字都取自合约，
 * 页面头是唯一适合交代口径的地方（例如「链上是权威账本，链下只做索引」）。
 */
defineProps({
  title: { type: String, required: true },
  desc: { type: String, default: '' }
})
</script>

<style scoped>
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 18px;
}

.page-head-main {
  min-width: 0;
}

.page-head-main .page-desc {
  margin-bottom: 0;
}

.page-head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: none;
  padding-top: 2px;
}

@media (max-width: 720px) {
  .page-head {
    flex-direction: column;
    gap: 10px;
  }
}
</style>
