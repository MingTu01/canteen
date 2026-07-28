<script setup lang="ts">
import EmptyState from '@/components/EmptyState.vue'

defineOptions({ name: 'NotFound' })
</script>

<template>
  <div class="not-found">
    <EmptyState text="页面未找到" icon="search" />
    <div class="not-found__action">
      <van-button round type="primary" to="/">返回首页</van-button>
    </div>
  </div>
</template>

<style scoped lang="scss">
.not-found {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 32px;

  &__action {
    margin-top: 16px;
  }
}
</style>
