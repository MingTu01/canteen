<script setup lang="ts">
/**
 * 品牌背景图层(对齐 H5 OrderStandby 品牌背景)
 * - 绝对定位铺满父容器
 * - 半透明遮罩保证文字可读
 * - 无背景图时不渲染
 */
withDefaults(defineProps<{
  bgUrl?: string
  /** 遮罩透明度 0-1,默认 0.45 */
  overlayOpacity?: number
}>(), {
  overlayOpacity: 0.45,
})
</script>

<template>
  <div
    v-if="bgUrl"
    class="branding-bg"
    :style="{
      backgroundImage: `url(${bgUrl})`,
    }"
  >
    <div
      class="branding-bg__overlay"
      :style="{ background: `rgba(14, 17, 21, ${overlayOpacity})` }"
    ></div>
  </div>
</template>

<style scoped>
.branding-bg {
  position: absolute;
  inset: 0;
  pointer-events: none;
  background-size: cover;
  background-position: center;
  z-index: 0;
}
.branding-bg__overlay {
  position: absolute;
  inset: 0;
}
</style>
