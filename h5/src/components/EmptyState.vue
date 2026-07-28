<script setup lang="ts">
/**
 * 空状态占位组件(基于 Vant Empty)
 */
withDefaults(
  defineProps<{
    /** 描述文字 */
    text?: string
    /** 图片类型(default/error/network/search)或自定义图片 URL */
    icon?: string
  }>(),
  {
    text: '暂无数据',
    icon: 'default',
  },
)
</script>

<template>
  <van-empty :description="text" :image="icon">
    <slot />
  </van-empty>
</template>

<style scoped lang="scss">
:deep(.van-empty) {
  padding: 32px 0;
}
</style>
