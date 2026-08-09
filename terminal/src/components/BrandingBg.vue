<script setup lang="ts">
/**
 * 品牌背景图层(全局共享,避免页面切换闪烁)
 * - 绝对定位铺满父容器
 * - 半透明遮罩保证文字可读
 * - 无背景图时不渲染
 * - 预加载图片:加载完成前不渲染背景(页面自身深色背景透出,不黑屏),
 *   加载完成后淡入显示(transition opacity),避免突兀闪烁
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
/** 当前已加载的 URL(避免相同 URL 重复加载) */
let loadedUrl = ''

watch(
  () => props.bgUrl,
  (url) => {
    if (!url) {
      loaded.value = false
      loadedUrl = ''
      return
    }
    // URL 未变化,不重复加载
    if (url === loadedUrl && loaded.value) return
    loaded.value = false
    const img = new Image()
    img.onload = () => {
      loaded.value = true
      loadedUrl = url
    }
    img.onerror = () => {
      loaded.value = false
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
      backgroundImage: loaded ? `url(${bgUrl})` : 'none',
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
