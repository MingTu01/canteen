<script setup lang="ts">
import { RouterView } from 'vue-router'
import { onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { loadConfig } from '@/api'
import { initLocalCache, destroyLocalCache } from '@/utils/cache'

const route = useRoute()

/** 根据终端绑定状态初始化本地缓存(SSE + IndexedDB) */
function syncCache() {
  const config = loadConfig()
  if (config?.storeId && config?.token) {
    initLocalCache(config.storeId).catch(() => {
      /* 静默失败,不影响主流程 */
    })
  } else {
    destroyLocalCache()
  }
}

onMounted(() => {
  syncCache()
})

// 路由变化时检查(终端绑定/解绑后自动同步)
watch(() => route.path, () => {
  syncCache()
})
</script>

<template>
  <RouterView />
</template>
