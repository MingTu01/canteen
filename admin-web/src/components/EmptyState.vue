<script setup lang="ts">
import type { Component } from 'vue'
import { Inbox } from 'lucide-vue-next'

interface Props {
  description?: string
  icon?: Component
}

withDefaults(defineProps<Props>(), {
  description: '暂无数据',
})
</script>

<template>
  <div
    class="flex flex-col items-center justify-center px-4 py-16 text-center"
    role="status"
    aria-live="polite"
  >
    <div class="flex h-16 w-16 items-center justify-center rounded-2xl bg-bg-tertiary">
      <component :is="icon || Inbox" class="h-8 w-8 text-text-muted" />
    </div>
    <p class="mt-4 text-sm text-text-secondary">{{ description }}</p>
    <div v-if="$slots.default" class="mt-5">
      <slot />
    </div>
  </div>
</template>
