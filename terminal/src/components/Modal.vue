<script setup lang="ts">
/**
 * 通用模态弹窗(替换原生 alert/confirm)
 *
 * - 遮罩层 + 居中卡片 + 入场动画
 * - 三种 variant:info(主色) / success(绿) / danger(红) / warning(橙)
 * - 双按钮:确认 + 取消(可隐藏取消按钮做 alert 用)
 * - 触摸目标 ≥56px,圆角阴影,对齐终端设计规范
 *
 * 用法:
 *   <Modal v-model="show" title="标题" :message="['行1','行2']" variant="danger"
 *          confirm-text="确认取消" cancel-text="再想想"
 *          @confirm="onOk" @cancel="onClose" />
 */
import { computed } from 'vue'
import { CheckCircle2, XCircle, AlertCircle, Info, X } from 'lucide-vue-next'

interface Props {
  /** 双向绑定:是否显示 */
  modelValue: boolean
  /** 标题 */
  title?: string
  /** 内容(字符串或字符串数组,数组每项一行) */
  message?: string | string[]
  /** 视觉风格 */
  variant?: 'info' | 'success' | 'danger' | 'warning'
  /** 确认按钮文字 */
  confirmText?: string
  /** 取消按钮文字(为空则不显示取消按钮) */
  cancelText?: string
  /** 确认按钮是否 loading */
  loading?: boolean
  /** 是否允许点击遮罩关闭 */
  closeOnOverlay?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  title: '',
  message: '',
  variant: 'info',
  confirmText: '确认',
  cancelText: '取消',
  loading: false,
  closeOnOverlay: true,
})

const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'confirm'): void
  (e: 'cancel'): void
}>()

/** 图标映射 */
const iconMap = {
  info: Info,
  success: CheckCircle2,
  danger: XCircle,
  warning: AlertCircle,
} as const

const iconComp = computed(() => iconMap[props.variant])

/** 消息行数组 */
const messageLines = computed<string[]>(() => {
  if (!props.message) return []
  return Array.isArray(props.message) ? props.message : [props.message]
})

const close = () => {
  if (props.loading) return
  emit('update:modelValue', false)
  emit('cancel')
}

const onConfirm = () => {
  if (props.loading) return
  emit('confirm')
}

const onOverlayClick = () => {
  if (props.closeOnOverlay && !props.loading) close()
}
</script>

<template>
  <Teleport to="body">
    <Transition name="modal">
      <div v-if="modelValue" class="modal__overlay" @click.self="onOverlayClick">
        <div :class="['modal__panel', `modal__panel--${variant}`]">
          <!-- 关闭按钮(右上角) -->
          <button
            v-if="!loading"
            class="modal__close btn-press"
            aria-label="关闭"
            @click="close"
          >
            <X :size="18" />
          </button>

          <!-- 图标 -->
          <div :class="['modal__icon', `modal__icon--${variant}`]">
            <component :is="iconComp" :size="40" stroke-width="2" />
          </div>

          <!-- 标题 -->
          <h2 v-if="title" class="modal__title">{{ title }}</h2>

          <!-- 内容 -->
          <div v-if="messageLines.length" class="modal__body">
            <p v-for="(line, i) in messageLines" :key="i" class="modal__line">
              {{ line }}
            </p>
          </div>

          <!-- 自定义内容插槽(覆盖 message) -->
          <slot v-else />

          <!-- 按钮组 -->
          <div class="modal__actions">
            <button
              v-if="cancelText"
              class="modal__btn modal__btn--cancel btn-press"
              :disabled="loading"
              @click="close"
            >
              {{ cancelText }}
            </button>
            <button
              class="modal__btn modal__btn--confirm btn-press"
              :class="`modal__btn--${variant}`"
              :disabled="loading"
              @click="onConfirm"
            >
              <span v-if="loading" class="modal__spinner spinner" />
              {{ loading ? '处理中...' : confirmText }}
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
/* 遮罩 */
.modal__overlay {
  position: fixed;
  inset: 0;
  z-index: 200;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 32px;
  background: rgba(14, 17, 21, 0.55);
  backdrop-filter: blur(6px);
}

/* 弹窗面板 */
.modal__panel {
  position: relative;
  width: 100%;
  max-width: 480px;
  padding: 32px 28px 24px;
  background: var(--doubao-card);
  border-radius: var(--doubao-radius);
  border: 1px solid var(--doubao-border);
  box-shadow: 0 24px 64px rgba(0, 0, 0, 0.28);
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
}

/* 关闭按钮 */
.modal__close {
  position: absolute;
  top: 12px;
  right: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border-radius: 50%;
  border: none;
  background: transparent;
  color: var(--doubao-muted-foreground);
  cursor: pointer;
  transition: background 0.15s ease, color 0.15s ease;
}
.modal__close:hover {
  background: var(--doubao-muted);
  color: var(--doubao-foreground);
}

/* 图标 */
.modal__icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 72px;
  height: 72px;
  border-radius: 50%;
  margin-bottom: 16px;
}
.modal__icon--info {
  background: rgba(0, 101, 253, 0.1);
  color: var(--doubao-primary);
}
.modal__icon--success {
  background: rgba(7, 193, 96, 0.1);
  color: var(--doubao-success);
}
.modal__icon--danger {
  background: rgba(239, 68, 68, 0.1);
  color: var(--doubao-destructive);
}
.modal__icon--warning {
  background: rgba(255, 151, 106, 0.12);
  color: var(--doubao-warning);
}

/* 标题 */
.modal__title {
  margin: 0 0 12px;
  font-size: var(--fs-xl);
  font-weight: 700;
  color: var(--doubao-foreground);
  line-height: 1.3;
}

/* 内容 */
.modal__body {
  width: 100%;
  margin-bottom: 24px;
}
.modal__line {
  margin: 0 0 6px;
  font-size: var(--fs-base);
  color: var(--doubao-secondary-foreground);
  line-height: 1.6;
  word-break: break-word;
}
.modal__line:last-child {
  margin-bottom: 0;
}

/* 按钮组 */
.modal__actions {
  display: flex;
  gap: 12px;
  width: 100%;
}
.modal__btn {
  flex: 1;
  min-height: var(--touch-md);
  padding: 0 20px;
  border-radius: var(--doubao-radius-sm);
  border: none;
  font-size: var(--fs-base);
  font-weight: 600;
  font-family: inherit;
  cursor: pointer;
  transition: transform 0.12s ease, opacity 0.15s ease, background 0.15s ease;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
}
.modal__btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.modal__btn--cancel {
  background: var(--doubao-muted);
  color: var(--doubao-secondary-foreground);
}
.modal__btn--cancel:hover {
  background: var(--doubao-border);
}
.modal__btn--confirm.modal__btn--info {
  background: var(--doubao-primary);
  color: var(--doubao-primary-foreground);
}
.modal__btn--confirm.modal__btn--success {
  background: var(--doubao-success);
  color: #ffffff;
}
.modal__btn--confirm.modal__btn--danger {
  background: var(--doubao-destructive);
  color: #ffffff;
}
.modal__btn--confirm.modal__btn--warning {
  background: var(--doubao-warning);
  color: #ffffff;
}

/* loading spinner */
.modal__spinner {
  width: 18px;
  height: 18px;
  border: 2px solid rgba(255, 255, 255, 0.4);
  border-top-color: #fff;
  border-radius: 50%;
}

/* 入场动画 */
.modal-enter-active {
  transition: opacity 0.2s ease;
}
.modal-enter-active .modal__panel {
  transition: transform 0.25s cubic-bezier(0.34, 1.4, 0.64, 1), opacity 0.2s ease;
}
.modal-enter-from {
  opacity: 0;
}
.modal-enter-from .modal__panel {
  transform: scale(0.92) translateY(8px);
  opacity: 0;
}
.modal-leave-active {
  transition: opacity 0.15s ease;
}
.modal-leave-to {
  opacity: 0;
}

/* 竖屏/小屏适配 */
@media (max-width: 1280px) {
  .modal__panel {
    max-width: 420px;
    padding: 24px 20px 20px;
  }
  .modal__icon {
    width: 60px;
    height: 60px;
  }
  .modal__title {
    font-size: var(--fs-lg);
  }
}
</style>
