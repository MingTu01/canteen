<script setup lang="ts">
/**
 * 自绘下拉选择器(X86 终端专用)
 *
 * 为什么不用原生 <select>:
 *   原生 select 弹出的选项列表是 Chromium 内部的"原生弹出窗口控件",
 *   独立于页面 DOM,走另一条窗口创建 + 合成渲染路径。在 X86 老机
 *   (软件渲染/低端 GPU)上这条路径极慢,点击后直接卡死界面
 *   (实测取餐端订单查询的餐别/状态两个下拉必卡)。
 *   本组件与 DatePicker 一样是纯 DOM 元素,走普通页面渲染路径,
 *   再复杂的弹层(如月历)在该类设备上也从不卡顿。
 *
 * 交互:
 *   - 点击触发按钮展开/收起选项列表(纯 DOM 弹层)
 *   - 点击选项立即生效并收起
 *   - 点击组件外部自动收起
 */
import { ref, onBeforeUnmount } from 'vue'
import { ChevronDown, Check } from 'lucide-vue-next'

interface Option<T> {
  value: T
  label: string
}

const props = defineProps<{
  modelValue: string | number
  options: Array<Option<string | number>>
  /** 触发按钮宽度提示(如 '110px'),默认自适应最长选项 */
  width?: string
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', v: string | number): void
  (e: 'change', v: string | number): void
}>()

const open = ref(false)
const rootRef = ref<HTMLElement | null>(null)

const currentLabel = () =>
  props.options.find(o => o.value === props.modelValue)?.label ?? ''

const toggle = () => {
  open.value = !open.value
  if (open.value) {
    // 下一帧再挂全局监听,避免本次点击立即触发关闭
    requestAnimationFrame(() => document.addEventListener('pointerdown', onOutside))
  }
}

const close = () => {
  open.value = false
  document.removeEventListener('pointerdown', onOutside)
}

const onOutside = (ev: PointerEvent) => {
  if (rootRef.value && !rootRef.value.contains(ev.target as Node)) close()
}

const pick = (v: string | number) => {
  if (v !== props.modelValue) {
    emit('update:modelValue', v)
    emit('change', v)
  }
  close()
}

onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', onOutside)
})
</script>

<template>
  <div ref="rootRef" class="fdd" :style="width ? { width } : undefined">
    <!-- 触发按钮 -->
    <button type="button" class="fdd__trigger btn-press" @click="toggle">
      <span class="fdd__value">{{ currentLabel() }}</span>
      <ChevronDown :size="16" class="fdd__chevron" :class="{ 'fdd__chevron--open': open }" />
    </button>

    <!-- 选项弹层(纯 DOM,绝对定位) -->
    <div v-if="open" class="fdd__menu">
      <button
        v-for="opt in options"
        :key="String(opt.value)"
        type="button"
        class="fdd__option btn-press"
        :class="{ 'fdd__option--active': opt.value === modelValue }"
        @click="pick(opt.value)"
      >
        <span>{{ opt.label }}</span>
        <Check v-if="opt.value === modelValue" :size="15" class="fdd__check" />
      </button>
    </div>
  </div>
</template>

<style scoped>
.fdd {
  position: relative;
  min-width: 96px;
}

.fdd__trigger {
  width: 100%;
  height: 40px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
  padding: 0 12px;
  border: 1.5px solid var(--doubao-border);
  border-radius: var(--doubao-radius-sm);
  background: var(--doubao-background);
  color: var(--doubao-foreground);
  font-family: inherit;
  font-size: var(--fs-base);
  cursor: pointer;
  /* 与文本输入框一致的边框过渡,不做 box-shadow 过渡(低端机闪烁) */
  transition: border-color 0.15s ease;
}
.fdd__trigger:focus-within,
.fdd__trigger:active {
  border-color: var(--doubao-primary);
}

.fdd__value {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.fdd__chevron {
  flex-shrink: 0;
  color: var(--doubao-muted-foreground);
  transition: transform 0.15s ease;
}
.fdd__chevron--open {
  transform: rotate(180deg);
}

/* 选项弹层 */
.fdd__menu {
  position: absolute;
  top: calc(100% + 6px);
  left: 0;
  min-width: 100%;
  z-index: 1100;
  padding: 4px;
  background: var(--doubao-card);
  border: 1px solid var(--doubao-border);
  border-radius: var(--doubao-radius-sm);
  /* 无模糊阴影:backdrop-filter/大阴影在低端机重绘代价高,用简单投影 */
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.18);
}

.fdd__option {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-height: 44px; /* 触屏最小点击区域 */
  padding: 0 12px;
  border: none;
  border-radius: var(--doubao-radius-xs);
  background: transparent;
  color: var(--doubao-foreground);
  font-family: inherit;
  font-size: var(--fs-base);
  text-align: left;
  cursor: pointer;
  white-space: nowrap;
}
.fdd__option--active {
  color: var(--doubao-primary);
  font-weight: 700;
}
/* 触屏优化:hover 仅鼠标设备生效 */
@media (hover: hover) and (pointer: fine) {
  .fdd__option:hover {
    background: var(--doubao-muted);
  }
}
.fdd__option:active {
  background: var(--doubao-accent);
}

.fdd__check {
  color: var(--doubao-primary);
  flex-shrink: 0;
}
</style>
