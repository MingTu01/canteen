<script setup lang="ts">
import type { Component } from 'vue'
import { computed } from 'vue'
import { TrendingUp, TrendingDown } from 'lucide-vue-next'

type StatColor = 'primary' | 'success' | 'warning' | 'danger' | 'accent'

interface Props {
  title: string
  value: string | number
  icon?: Component
  trend?: number | string
  trendLabel?: string
  color?: StatColor
}

const props = withDefaults(defineProps<Props>(), {
  color: 'primary',
})

const gradientMap: Record<StatColor, string> = {
  primary: 'linear-gradient(135deg, #1a73fe 0%, #0052ca 100%)',
  success: 'linear-gradient(135deg, #10b981 0%, #059669 100%)',
  warning: 'linear-gradient(135deg, #f59e0b 0%, #d97706 100%)',
  danger: 'linear-gradient(135deg, #ef4444 0%, #dc2626 100%)',
  accent: 'linear-gradient(135deg, #9333ea 0%, #7c3aed 100%)',
}

const gradient = computed(() => gradientMap[props.color])

const isPositiveTrend = computed(() => {
  if (typeof props.trend === 'number') return props.trend >= 0
  return true
})

const trendText = computed(() => {
  if (props.trend === undefined) return ''
  if (typeof props.trend === 'number') {
    return `${props.trend >= 0 ? '+' : ''}${props.trend}%`
  }
  return props.trend
})
</script>

<template>
  <div
    class="relative overflow-hidden rounded-2xl p-5 shadow-lg transition-transform duration-300 hover:-translate-y-1"
    :style="{ background: gradient }"
    role="region"
    :aria-label="title"
  >
    <div class="absolute -right-6 -top-6 h-24 w-24 rounded-full bg-white/10" />
    <div class="absolute -bottom-8 -right-2 h-20 w-20 rounded-full bg-white/5" />

    <div class="relative flex items-start justify-between">
      <div class="min-w-0">
        <p class="text-sm font-medium text-white/80">{{ title }}</p>
        <p class="mt-2 text-3xl font-bold tabular-nums text-white" aria-live="polite">{{ value }}</p>
        <div
          v-if="trend !== undefined"
          class="mt-2 flex items-center gap-1 text-xs text-white/90"
        >
          <component :is="isPositiveTrend ? TrendingUp : TrendingDown" class="h-3.5 w-3.5" />
          <span>{{ trendLabel || trendText }}</span>
        </div>
      </div>
      <div
        v-if="icon"
        class="flex h-12 w-12 shrink-0 items-center justify-center rounded-xl bg-white/20"
      >
        <component :is="icon" class="h-6 w-6 text-white" />
      </div>
    </div>
  </div>
</template>
