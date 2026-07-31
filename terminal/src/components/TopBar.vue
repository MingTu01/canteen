<script setup lang="ts">
/**
 * 通用顶栏
 * - 左侧返回按钮(图标 + "返回"文字)
 * - 居中标题(可用 #title slot 覆盖,如嵌入日期选择器)
 * - 右侧默认渲染时钟(可通过 showClock=false 关闭)
 * - 右侧可通过 right slot 覆盖默认时钟
 *
 * 用于订餐/取餐流程的二级页面。
 */
import { ArrowLeft } from 'lucide-vue-next'
import ClockWidget from './ClockWidget.vue'

withDefaults(defineProps<{
  title?: string
  showBack?: boolean
  /** 是否显示右上角时钟,默认 true */
  showClock?: boolean
}>(), {
  title: '',
  showBack: true,
  showClock: true,
})

const emit = defineEmits<{ (e: 'back'): void }>()
</script>

<template>
  <header class="top-bar">
    <button
      v-if="showBack"
      class="top-bar__back btn-press"
      :aria-label="`返回${title}`"
      @click="emit('back')"
    >
      <ArrowLeft :size="24" />
      <span class="top-bar__back-text">返回</span>
    </button>
    <div v-else class="top-bar__placeholder" />

    <h1 class="top-bar__title">
      <slot name="title">{{ title }}</slot>
    </h1>

    <div class="top-bar__right">
      <slot name="right">
        <ClockWidget v-if="showClock" />
      </slot>
    </div>
  </header>
</template>

<style scoped>
.top-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 24px;
  border-bottom: 1px solid var(--doubao-border);
  background: var(--doubao-card);
}
.top-bar__back {
  display: flex;
  align-items: center;
  gap: 4px;
  height: 44px;
  padding: 0 14px 0 10px;
  border-radius: 999px;
  border: none;
  background: transparent;
  color: var(--doubao-foreground);
  cursor: pointer;
  font-family: inherit;
  font-size: var(--fs-base);
  font-weight: 400;
  transition: background 0.15s ease;
}
.top-bar__back:hover {
  background: var(--doubao-muted);
}
.top-bar__back-text {
  line-height: 1;
}
.top-bar__placeholder {
  width: 80px;
}
.top-bar__title {
  margin: 0;
  font-size: var(--fs-xl);
  font-weight: 700;
  color: var(--doubao-foreground);
  text-align: center;
  flex: 1;
}
.top-bar__right {
  min-width: 80px;
  display: flex;
  align-items: center;
  justify-content: flex-end;
}
</style>
