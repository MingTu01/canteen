<script setup lang="ts">
import { ref, watch } from 'vue'
import { showSuccessToast, showFailToast } from 'vant'
import { useAuthStore } from '@/stores/auth'
import * as authApi from '@/api/auth'

/**
 * 首次登录「建议修改密码」弹窗(全局挂载)。
 * 当 authStore.needChangePassword 为 true 时弹出,但不再强制:
 * - 建议弹窗:可「暂不修改」关闭(本会话内不再弹)/「去修改密码」进入改密表单
 * - 改密表单:仅新密码+确认密码(免原密码,身份已通过登录验证)
 * 真正改密成功后才能清除 mustChangePassword 标志。
 */
const authStore = useAuthStore()

/** 弹窗是否显示 */
const show = ref(false)
/** 弹窗模式:suggest=建议卡片;change=输入新密码 */
const mode = ref<'suggest' | 'change'>('suggest')
/** 本会话内已「暂不修改」,避免反复弹出 */
const dismissed = ref(false)

const submitting = ref(false)
const form = ref({
  newPassword: '',
  confirmPassword: '',
})

const onSubmit = async () => {
  const err = validate()
  if (err) {
    showFailToast(err)
    return
  }
  submitting.value = true
  try {
    // 首次登录改密免原密码:不传 oldPassword,后端按 mustChangePassword=1 跳过校验
    await authApi.changePassword(form.value.newPassword)
    showSuccessToast('密码修改成功')
    dismissed.value = false
    show.value = false
    // 刷新员工信息(后端会清除 mustChangePassword 标志)
    await authStore.refreshEmployee()
  } catch {
    // 拦截器已 toast 提示
  } finally {
    submitting.value = false
  }
}

/** 去修改密码 */
const goChange = (): void => {
  mode.value = 'change'
  form.value = { newPassword: '', confirmPassword: '' }
}

/** 暂不修改:关闭弹窗,本会话内不再自动弹出 */
const onCancel = (): void => {
  dismissed.value = true
  show.value = false
}

watch(
  () => authStore.needChangePassword,
  (val) => {
    if (val) {
      // 仅在本次会话未「暂不修改」时自动弹出
      if (!dismissed.value) {
        mode.value = 'suggest'
        show.value = true
      }
    } else {
      show.value = false
    }
  },
  { immediate: true },
)

const validate = (): string | null => {
  if (!form.value.newPassword) return '请输入新密码'
  if (form.value.newPassword.length < 8) return '新密码至少 8 位'
  if (form.value.newPassword !== form.value.confirmPassword) return '两次输入的密码不一致'
  return null
}
</script>

<template>
  <van-popup
    v-model:show="show"
    position="center"
    round
    :close-on-click-overlay="false"
    :close-on-popstate="false"
  >
    <!-- 建议卡片 -->
    <div v-if="mode === 'suggest'" class="sugg">
      <div class="sugg__icon">
        <van-icon name="warning-o" size="48" color="#ff9800" />
      </div>
      <div class="sugg__title">建议修改密码</div>
      <div class="sugg__desc">
        您的账号正在使用初始密码,建议设置专属新密码以保障账号安全。可稍后在「我的」中继续修改。
      </div>
      <div class="sugg__footer">
        <div class="sugg__footer-inner">
          <van-button plain round @click="onCancel">暂不修改</van-button>
          <van-button type="primary" round @click="goChange">去修改密码</van-button>
        </div>
      </div>
    </div>

    <!-- 改密表单(免原密码) -->
    <div v-else class="pwd">
      <div class="pwd__title">设置新密码</div>
      <div class="pwd__desc">新密码至少 8 位,设置后用于手机号等账号登录。</div>
      <van-cell-group inset>
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
      <div class="pwd__footer">
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

/* 建议卡片 */
.sugg {
  width: 300px;
  padding: 24px 20px 20px;
  text-align: center;

  &__icon {
    margin-bottom: 10px;
  }

  &__title {
    font-size: 17px;
    font-weight: 600;
    color: $text-primary;
    margin-bottom: 8px;
  }

  &__desc {
    font-size: 13px;
    color: $text-secondary;
    line-height: 1.6;
  }

  &__footer {
    margin-top: 20px;

    &-inner {
      display: flex;
      gap: 12px;
      justify-content: center;

      .van-button {
        flex: 1;
      }
    }
  }
}

/* 改密表单 */
.pwd {
  width: 300px;
  padding: 24px 0 calc(env(safe-area-inset-bottom) + 16px);

  &__title {
    padding: 0 20px;
    font-size: 17px;
    font-weight: 600;
    color: $text-primary;
  }

  &__desc {
    padding: 8px 20px 16px;
    font-size: 13px;
    color: $text-secondary;
    line-height: 1.5;
  }

  &__footer {
    margin: 20px 16px 0;
  }
}
</style>