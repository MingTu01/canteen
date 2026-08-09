<script setup lang="ts">
/**
 * 品牌背景图层(全局共享,避免页面切换闪烁)
 * - 绝对定位铺满父容器
 * - 半透明遮罩保证文字可读
 * - 无背景图时不渲染
 * - 预加载图片:加载完成前保持旧背景显示(不隐藏),加载完成后切换到新图,
 *   避免URL变化时"背景消失再出现"的闪烁
 * - 图片 URL 不变时不会重新加载(浏览器 HTTP 缓存 + 已加载标记)
 */
import { ref, watch } from 'vue'

const props = withDefaults(defineProps<{
  bgUrl?: string
  /** 遮罩透明度 0-1,默认 0.45 */
  overlayOpacity?: number
}>(), {
  overlayOpacity: 0.45,
})

/** 图片是否已加载完成(用于控制淡入) */
const loaded = ref(false)
/** 已加载完成并实际显示的 URL(加载完成后才切换,避免闪烁) */
const displayUrl = ref('')

watch(
  () => props.bgUrl,
  (url) => {
    if (!url) {
      loaded.value = false
      displayUrl.value = ''
      return
    }
    // URL 未变化,不重复加载
    if (url === displayUrl.value && loaded.value) return
    // 关键:不立即清除 loaded,保持旧背景显示直到新图加载完成
    const img = new Image()
    img.onload = () => {
      displayUrl.value = url
      loaded.value = true
    }
    img.onerror = () => {
      // 加载失败:仅当没有任何已加载背景时才隐藏,否则保持旧背景
      if (!displayUrl.value) loaded.value = false
    }
    img.src = url
  },
  { immediate: true },
)
</script>

<template>
  <div
    v-if="bgUrl"
    class="branding-bg"
    :class="{ 'branding-bg--loaded': loaded }"
    :style="{
      backgroundImage: loaded ? `url(${displayUrl})` : 'none',
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
  position: fixed;
  inset: 0;
  pointer-events: none;
  background-size: cover;
  background-position: center;
  z-index: 0;
  /* 加载前透明(页面自身深色背景透出),加载后淡入 */
  opacity: 0;
  transition: opacity 0.3s ease;
}
.branding-bg--loaded {
  opacity: 1;
}
.branding-bg__overlay {
  position: absolute;
  inset: 0;
}
</style>
