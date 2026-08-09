<script setup lang="ts">
import { RouterView } from 'vue-router'
import { onMounted, watch, computed } from 'vue'
import { useRoute } from 'vue-router'
import { loadConfig } from '@/api'
import { initLocalCache, destroyLocalCache } from '@/utils/cache'
import { initEmployeeCache, destroyEmployeeCache } from '@/utils/employeeCache'
import AdminEntryZone from '@/components/AdminEntryZone.vue'
import BrandingBg from '@/components/BrandingBg.vue'
import { brandingState, purgeOldBrandingCache, fetchBranding } from '@/store/branding'
import { loadRuntimeConfig } from '@/store/terminalSettings'

const route = useRoute()

/** 根据终端绑定状态初始化本地缓存(SSE + IndexedDB + 员工/头像预加载) */
function syncCache() {
  const config = loadConfig()
  if (config?.storeId && config?.token) {
    initLocalCache(config.storeId).catch(() => {
      /* 静默失败,不影响主流程 */
    })
    // 后台预加载员工列表 + 头像(不阻塞启动,店铺隔离)
    initEmployeeCache(config.storeId).catch(() => {})
    // 加载品牌信息(含背景图),全局 BrandingBg 依赖此数据
    fetchBranding({ background: true }).catch(() => {})
  } else {
    destroyLocalCache()
    destroyEmployeeCache().catch(() => {})
  }
}

/**
 * 全局背景遮罩透明度:取餐验证页需更暗(0.5)让弹窗清晰,其余页面 0.15
 */
const bgOverlayOpacity = computed(() => {
  return route.path === '/pickup/verify' ? 0.5 : 0.15
})

onMounted(() => {
  // 清理旧版本 branding 缓存(含相对路径 URL 的数据)
  purgeOldBrandingCache()
  syncCache()
  // 加载 Python 侧运行时配置(window_mode/card_interval/idle_timeout)
  // 浏览器环境静默跳过(保留默认值)
  loadRuntimeConfig().catch(() => {})
})

// 路由变化时检查(终端绑定/解绑后自动同步)
watch(() => route.path, () => {
  syncCache()
})
</script>

<template>
  <div class="app-root">
    <!-- 全局品牌背景:所有页面共享,避免页面切换时背景卸载/重载导致闪烁黑屏 -->
    <BrandingBg
      :bg-url="brandingState.data?.terminalBackgroundUrl"
      :overlay-opacity="bgOverlayOpacity"
    />
    <!-- 页面内容层:z-index 1 确保在品牌背景之上 -->
    <div class="app-content">
      <RouterView />
    </div>
    <!-- 全局管理入口:右上角 6 次点击触发,任何页面都可用 -->
    <AdminEntryZone />
  </div>
</template>

<style>
.app-root {
  position: relative;
  min-height: 100vh;
  /* 全局深色底色:无品牌图时显示,有品牌图时作为预加载前的兜底(不黑屏) */
  background: #0e1115;
}
.app-content {
  position: relative;
  z-index: 1;
}
</style>
