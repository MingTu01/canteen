<script setup lang="ts">
import { ref, watch } from 'vue'
import { showSuccessToast, showFailToast } from 'vant'
import { useAuthStore } from '@/stores/auth'
import * as authApi from '@/api/auth'

/**
 * 强制修改密码组件(全局挂载)。
 * 当 authStore.needChangePassword 为 true 时弹出不可关闭的密码修改弹层。
 * 首次登录使用默认密码 12345678 的员工必须修改密码后才能使用系统。
 */
const authStore = useAuthStore()

const show = ref(false)
const submitting = ref(false)
const form = ref({
  oldPassword: '',
  newPassword: '',
  confirmPassword: '',
})

// 监听 needChangePassword 状态,自动弹窗
watch(
  () => authStore.needChangePassword,
  (val) => {
    show.value = val
    if (val) {
      form.value = { oldPassword: '', newPassword: '', confirmPassword: '' }
    }
  },
  { immediate: true },
)

const validate = (): string | null => {
  if (!form.value.oldPassword) return '请输入当前密码'
  if (!form.value.newPassword) return '请输入新密码'
  if (form.value.newPassword.length < 8) return '新密码至少 8 位'
  if (form.value.newPassword !== form.value.confirmPassword) return '两次输入的密码不一致'
  if (form.value.newPassword === form.value.oldPassword) return '新密码不能与当前密码相同'
  return null
}

const onSubmit = async () => {
  const err = validate()
  if (err) {
    showFailToast(err)
    return
  }
  submitting.value = true
  try {
    await authApi.changePassword(form.value.oldPassword, form.value.newPassword)
    showSuccessToast('密码修改成功')
    // 刷新员工信息(后端会清除 mustChangePassword 标志)
    await authStore.refreshEmployee()
  } catch {
    // 拦截器已 toast 提示
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <van-popup
    v-model:show="show"
    position="bottom"
    round
    :close-on-click-overlay="false"
    :close-on-popstate="false"
    :style="{ maxHeight: '85%' }"
  >
    <div class="force-pwd">
      <div class="force-pwd__icon">
        <van-icon name="warning-o" size="48" color="#ff9800" />
      </div>
      <div class="force-pwd__title">首次登录请修改密码</div>
      <div class="force-pwd__desc">
        您的账号正在使用默认密码,为了账号安全请设置新密码后继续使用。
      </div>
      <van-cell-group inset>
        <van-field
          v-model="form.oldPassword"
          type="password"
          label="当前密码"
          placeholder="请输入当前密码(默认 12345678)"
          :maxlength="32"
        />
        <van-field
          v-model="form.newPassword"
          type="password"
          label="新密码"
          placeholder="至少 8 位"
          :maxlength="32"
        />
        <van-field
          v-model="form.confirmPassword"
          type="password"
          label="确认密码"
          placeholder="请再次输入新密码"
          :maxlength="32"
        />
      </van-cell-group>
      <div class="force-pwd__footer">
        <van-button
          block
          round
          type="primary"
          :loading="submitting"
          @click="onSubmit"
        >
          确认修改
        </van-button>
      </div>
    </div>
  </van-popup>
</template>

<style scoped lang="scss">
@use '@/styles/variables' as *;

.force-pwd {
  padding: 24px 0 calc(env(safe-area-inset-bottom) + 16px);

  &__icon {
    text-align: center;
    margin-bottom: 12px;
  }

  &__title {
    text-align: center;
    font-size: 18px;
    font-weight: 600;
    color: $text-primary;
    margin-bottom: 8px;
  }

  &__desc {
    text-align: center;
    font-size: 13px;
    color: $text-secondary;
    padding: 0 24px;
    margin-bottom: 20px;
    line-height: 1.5;
  }

  &__footer {
    padding: 20px 16px 0;
  }
}
</style>
