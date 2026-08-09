<script setup lang="ts">
import { RouterView } from 'vue-router'
import { onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { loadConfig } from '@/api'
import { initLocalCache, destroyLocalCache } from '@/utils/cache'
import { initEmployeeCache, destroyEmployeeCache } from '@/utils/employeeCache'
import AdminEntryZone from '@/components/AdminEntryZone.vue'
import { purgeOldBrandingCache } from '@/store/branding'
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
  } else {
    destroyLocalCache()
    destroyEmployeeCache().catch(() => {})
  }
}

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
  <RouterView />
  <!-- 全局管理入口:右上角 6 次点击触发,任何页面都可用 -->
  <AdminEntryZone />
</template>
