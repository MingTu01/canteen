<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { loadConfig } from '@/api'

const router = useRouter()

onMounted(() => {
  const cfg = loadConfig()
  if (!cfg) {
    router.replace('/settings')
    return
  }
  router.replace(cfg.mode === 'pickup' ? '/pickup' : '/order')
})
</script>

<template>
  <div class="terminal-container" />
</template>
