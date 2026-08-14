<script setup lang="ts">
/**
 * 大尺寸按钮(终端触摸屏专用)
 * - 主按钮(默认)/描边按钮/危险按钮三种 variant
 * - 大尺寸触摸目标(min-height 56px)
 * - 支持 icon 插槽与 loading 状态
 */
import { Loader2 } from 'lucide-vue-next'

withDefaults(defineProps<{
  variant?: 'primary' | 'outline' | 'danger'
  size?: 'md' | 'lg' | 'xl'
  loading?: boolean
  disabled?: boolean
  block?: boolean
}>(), {
  variant: 'primary',
  size: 'lg',
  loading: false,
  disabled: false,
  block: false,
})

const sizeClass = (s: string) => ({
  md: 'big-btn--md',
  lg: 'big-btn--lg',
  xl: 'big-btn--xl',
}[s] || 'big-btn--lg')
</script>

<template>
  <button
    :class="['big-btn', `big-btn--${variant}`, sizeClass(size), { 'big-btn--block': block, 'big-btn--disabled': disabled || loading }]"
    :disabled="disabled || loading"
  >
    <Loader2 v-if="loading" class="spinner" :size="20" />
    <slot v-else name="icon" />
    <slot />
  </button>
</template>

<style scoped>
.big-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  font-weight: 700;
  border: none;
  cursor: pointer;
  transition: opacity 0.15s ease, background 0.15s ease;
  font-family: inherit;
  border-radius: var(--doubao-radius);
  white-space: nowrap;
}
.big-btn:active { transform: scale(0.97); opacity: 0.85; }
.big-btn--disabled { opacity: 0.5; cursor: not-allowed; }
.big-btn--block { width: 100%; }

.big-btn--md { min-height: var(--touch-md); padding: 0 20px; font-size: var(--fs-base); }
.big-btn--lg { min-height: var(--touch-lg); padding: 0 28px; font-size: var(--fs-lg); }
.big-btn--xl { min-height: 88px; padding: 0 40px; font-size: var(--fs-xl); }

.big-btn--primary {
  background: var(--doubao-primary);
  color: var(--doubao-primary-foreground);
}
.big-btn--outline {
  background: transparent;
  color: var(--doubao-primary);
  border: 2px solid var(--doubao-primary);
}
.big-btn--danger {
  background: transparent;
  color: var(--doubao-destructive);
  border: 2px solid var(--doubao-destructive);
  border-radius: 999px;
}
</style>
