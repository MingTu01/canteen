<script setup lang="ts">
import { Search, RotateCcw } from 'lucide-vue-next'
import { ElButton } from 'element-plus'

defineEmits<{
  (e: 'search'): void
  (e: 'reset'): void
}>()
</script>

<template>
  <!--
    单行筛选栏:
    - 始终 flex-row,不分断点竖排
    - flex-wrap 允许小屏换行,但控件本身宽度适中不会轻易换行
    - 搜索/重置按钮通过 actions 插槽允许扩展(如导出按钮)
  -->
  <div class="mb-5 rounded-xl border border-border bg-card p-4 shadow-sm" role="search" aria-label="搜索">
    <div class="flex flex-wrap items-center gap-3">
      <div class="flex flex-1 flex-wrap items-center gap-3 min-w-0">
        <slot />
      </div>
      <div class="flex shrink-0 items-center gap-2">
        <slot name="actions" />
        <ElButton type="primary" :icon="Search" aria-label="搜索" @click="$emit('search')">
          搜索
        </ElButton>
        <ElButton :icon="RotateCcw" aria-label="重置" @click="$emit('reset')">
          重置
        </ElButton>
      </div>
    </div>
  </div>
</template>
