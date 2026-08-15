<script setup lang="ts">
import { nextTick, ref } from 'vue'
import { ElButton, ElDialog, ElInput } from 'element-plus'
import { ShieldAlert } from 'lucide-vue-next'

/**
 * 敏感操作二次验证弹窗。
 *
 * 用于删除食堂、恢复备份等破坏性操作前的身份确认:
 * 输入当前登录管理员的密码,随操作请求提交,由后端强制校验(BCrypt 比对 + 限流防爆破)。
 *
 * 防浏览器记住密码:
 * - autocomplete="new-password"(现代浏览器对"新密码"字段既不自动填充也不弹保存提示)
 * - 不包裹 <form>,无原生提交行为,Chrome/Edge 不会触发"保存密码"气泡
 * - name 使用非登录语义的随机化名称,避开登录页密码管理器的自动匹配
 *
 * 交互约定(与项目弹窗规范一致):
 * - 点击遮罩不关闭(仅通过按钮关闭)
 * - onConfirm 抛错(如密码错误)时弹窗保持打开并内联展示错误,方便直接重试
 */
const visible = ref(false)
const title = ref('敏感操作验证')
const message = ref('')
const confirmText = ref('确认执行')
const password = ref('')
const error = ref('')
const submitting = ref(false)
const inputRef = ref<InstanceType<typeof ElInput>>()

/** 执行体:收到明文密码执行真正的破坏性操作;抛错则弹窗保持打开 */
let onConfirmFn: ((password: string) => Promise<void>) | null = null

/**
 * 打开弹窗。
 * @param opts.message 红色警示文案(说明操作后果)
 * @param opts.title 弹窗标题
 * @param opts.confirmText 确认按钮文案
 * @param opts.onConfirm 异步执行体;成功后弹窗自动关闭
 */
const open = (opts: {
  message: string
  title?: string
  confirmText?: string
  onConfirm: (password: string) => Promise<void>
}) => {
  title.value = opts.title ?? '敏感操作验证'
  message.value = opts.message
  confirmText.value = opts.confirmText ?? '确认执行'
  password.value = ''
  error.value = ''
  onConfirmFn = opts.onConfirm
  visible.value = true
  nextTick(() => inputRef.value?.focus())
}

defineExpose({ open })

const handleConfirm = async () => {
  if (!password.value) {
    error.value = '请输入管理员密码'
    inputRef.value?.focus()
    return
  }
  submitting.value = true
  error.value = ''
  try {
    await onConfirmFn?.(password.value)
    visible.value = false // 仅成功时关闭,失败保持打开供重试
  } catch (e: unknown) {
    // 拦截器已 toast,这里内联展示错误(密码错误/尝试次数过多等)并保持弹窗打开
    error.value = (e as { message?: string })?.message || '操作失败,请重试'
    password.value = ''
    nextTick(() => inputRef.value?.focus())
  } finally {
    submitting.value = false
  }
}

const handleClose = () => {
  if (submitting.value) return
  visible.value = false
}
</script>

<template>
  <ElDialog
    v-model="visible"
    :title="title"
    width="420px"
    :close-on-click-modal="false"
    :close-on-press-escape="!submitting"
    :show-close="!submitting"
    append-to-body
    destroy-on-close
  >
    <div class="pw-confirm">
      <div class="pw-confirm__alert" role="alert">
        <ShieldAlert :size="18" class="pw-confirm__alert-icon" />
        <div class="pw-confirm__alert-text">{{ message }}</div>
      </div>

      <div class="pw-confirm__field">
        <label class="pw-confirm__label" for="sensitive-op-verify">请输入管理员密码完成验证</label>
        <ElInput
          id="sensitive-op-verify"
          ref="inputRef"
          v-model="password"
          type="password"
          show-password
          placeholder="请输入当前登录管理员密码"
          autocomplete="new-password"
          name="sensitive-op-verify-field"
          :disabled="submitting"
          maxlength="64"
          @keyup.enter="handleConfirm"
        />
        <div v-if="error" class="pw-confirm__error">{{ error }}</div>
        <div class="pw-confirm__tip">密码仅用于本次操作验证,不会被浏览器保存</div>
      </div>
    </div>

    <template #footer>
      <ElButton :disabled="submitting" @click="handleClose">取消</ElButton>
      <ElButton type="danger" :loading="submitting" @click="handleConfirm">
        {{ confirmText }}
      </ElButton>
    </template>
  </ElDialog>
</template>

<style scoped>
.pw-confirm__alert {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 10px 12px;
  border-radius: 8px;
  background: #fef0f0;
  border: 1px solid #fde2e2;
}

.pw-confirm__alert-icon {
  flex-shrink: 0;
  color: #f56c6c;
  margin-top: 1px;
}

.pw-confirm__alert-text {
  font-size: 13px;
  line-height: 1.6;
  color: #c45656;
  word-break: break-all;
}

.pw-confirm__field {
  margin-top: 16px;
}

.pw-confirm__label {
  display: block;
  font-size: 13px;
  color: #606266;
  margin-bottom: 8px;
}

.pw-confirm__error {
  margin-top: 6px;
  font-size: 12px;
  color: #f56c6c;
}

.pw-confirm__tip {
  margin-top: 6px;
  font-size: 12px;
  color: #909399;
}
</style>
