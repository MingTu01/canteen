<script setup lang="ts">
import { ref, watch } from 'vue'
import { showSuccessToast, showFailToast, showLoadingToast, closeToast } from 'vant'
import { useAuthStore } from '@/stores/auth'
import * as authApi from '@/api/auth'

/**
 * 强制修改密码组件(全局挂载)。
 * 当 authStore.needChangePassword 为 true 时弹出不可关闭的密码修改弹层。
 * 首次登录使用默认密码 12345678 的员工必须修改密码后才能使用系统。
 *
 * 改密成功后(仅微信内)弹出居中「开通微信通知」弹窗,仅同意/拒绝两个按钮:
 * 同意 → 跳转微信一次性订阅授权页(scene=1000 通知/公告),点「允许」即完成,
 * 后续可在「我的 → 微信提醒订阅」再次订阅。
 */
const authStore = useAuthStore()

const show = ref(false)
const submitting = ref(false)
const form = ref({
  oldPassword: '',
  newPassword: '',
  confirmPassword: '',
})

// ============ 开通微信通知弹窗(改密成功后) ============
const showSubscribeDialog = ref(false)
const subscribing = ref(false)

/** 是否在微信内置浏览器中(订阅授权页仅微信内可用) */
const isWechatBrowser = (): boolean => /MicroMessenger/i.test(navigator.userAgent)

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
    // 首次登录流程收尾:微信内弹窗引导开通通知(仅同意/拒绝)
    if (isWechatBrowser()) {
      showSubscribeDialog.value = true
    }
  } catch {
    // 拦截器已 toast 提示
  } finally {
    submitting.value = false
  }
}

/**
 * 同意开通 → 获取订阅授权链接并跳转(scene=1000 通知/公告)。
 * 微信授权页点「允许」后回跳 /profile?subscribed=1 并 toast 结果。
 */
const onSubscribeConfirm = async (): Promise<void> => {
  if (subscribing.value) return
  subscribing.value = true
  showLoadingToast({ message: '正在跳转...', forbidClick: true, duration: 0 })
  try {
    const res = await authApi.getWechatSubscribeUrl(1000)
    if (res?.url) {
      window.location.href = res.url
      return // 页面跳转中,无需关弹层
    }
    closeToast()
    showFailToast('获取订阅链接失败,可稍后在「我的 → 微信提醒订阅」中开通')
  } catch {
    /* 拦截器已提示(如公众号未配置) */
  } finally {
    subscribing.value = false
  }
}

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

  <!-- ============ 开通微信通知弹窗(首次登录改密成功后,居中) ============ -->
  <van-dialog
    v-model:show="showSubscribeDialog"
    title="开通微信通知"
    message="食堂公告、活动和订餐成功提醒将通过微信推送给你,重要消息不错过。"
    confirm-button-text="同意"
    cancel-button-text="拒绝"
    show-cancel-button
    round
    :close-on-click-overlay="false"
    @confirm="onSubscribeConfirm"
  />
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
