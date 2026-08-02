<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import {
  showSuccessToast,
  showFailToast,
  type FieldTextAlign,
} from 'vant'
import { useAuthStore } from '@/stores/auth'
import { useBrandingStore } from '@/stores/branding'
import { useWechat } from '@/composables/useWechat'

defineOptions({ name: 'Login' })

const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()
const brandingStore = useBrandingStore()
const { isWechat, wechatLogin } = useWechat()

/** 是否显示密码 */
const showPhonePassword = ref(false)

/** 登录中 */
const loading = ref(false)

const fieldAlign: FieldTextAlign = 'left'

/** 手机号登录表单 */
const phoneForm = reactive({
  phone: '',
  password: '',
})

/** 品牌信息(从 branding store 获取) */
const branding = computed(() => brandingStore.branding)

/** 版本号 */
const version = __APP_VERSION__

/** 校验手机号格式(中国大陆:第二位 3-9) */
const validatePhone = (val: string): boolean => /^1[3-9]\d{9}$/.test(val)

/** 校验 redirect 参数,防止开放重定向(仅允许站内相对路径) */
const safeRedirect = (raw: unknown): string => {
  if (typeof raw === 'string' && raw.startsWith('/') && !raw.startsWith('//')) {
    return raw
  }
  return '/'
}

/** 手机号登录 */
const onPhoneSubmit = async (): Promise<void> => {
  if (!phoneForm.phone) {
    showFailToast('请输入手机号')
    return
  }
  if (!validatePhone(phoneForm.phone)) {
    showFailToast('手机号格式不正确')
    return
  }
  if (!phoneForm.password) {
    showFailToast('请输入密码')
    return
  }
  if (phoneForm.password.length < 8) {
    showFailToast('密码至少 8 位')
    return
  }

  loading.value = true
  try {
    const emp = await authStore.phoneLogin(phoneForm.phone, phoneForm.password)
    showSuccessToast('登录成功')
    // 拉取门店品牌信息(秒开)
    if (emp.storeId) {
      brandingStore.fetchBranding(emp.storeId).catch(() => {
        /* 忽略品牌信息拉取失败 */
      })
    }
    const redirect = safeRedirect(route.query.redirect)
    router.replace(redirect)
  } catch (e: unknown) {
    // 拦截器已 toast 提示错误消息
  } finally {
    loading.value = false
  }
}

/** 点击微信登录 */
const onWechatLogin = (): void => {
  wechatLogin()
}

onMounted(() => {
  // 如果已经登录,跳首页(路由守卫也会处理,这里双保险)
  if (authStore.isLoggedIn) {
    router.replace('/')
  }
})
</script>

<template>
  <div class="login-page">
    <!-- 顶部品牌区 -->
    <div class="login-page__header pt-safe">
      <div class="login-page__logo">
        <van-image
          v-if="branding?.logoUrl"
          round
          width="80"
          height="80"
          fit="cover"
          :src="branding.logoUrl"
        />
        <van-icon v-else name="shop-o" size="40" color="#0065fd" />
      </div>
      <h1 class="login-page__title">
        {{ branding?.name || '企业食堂订餐' }}
      </h1>
      <p class="login-page__subtitle">
        {{ branding?.description || '员工在线点餐 · 订单查询 · 余额管理' }}
      </p>
    </div>

    <!-- 表单卡片 -->
    <div class="login-page__form-card">
      <!-- 手机号登录 -->
      <div class="login-page__tab-title">手机号登录</div>
      <van-form @submit="onPhoneSubmit">
        <van-cell-group inset>
          <van-field
            v-model="phoneForm.phone"
            name="phone"
            label="手机号"
            placeholder="请输入手机号"
            type="tel"
            maxlength="11"
            clearable
            :label-align="fieldAlign"
          />
          <van-field
            v-model="phoneForm.password"
            name="password"
            label="密码"
            placeholder="请输入密码(至少 8 位)"
            :type="showPhonePassword ? 'text' : 'password'"
            maxlength="20"
            :label-align="fieldAlign"
            :right-icon="showPhonePassword ? 'eye-o' : 'closed-eye'"
            @click-right-icon="showPhonePassword = !showPhonePassword"
          />
        </van-cell-group>

        <div class="login-page__submit">
          <van-button
            block
            round
            type="primary"
            native-type="submit"
            :loading="loading"
          >
            登录
          </van-button>
        </div>
      </van-form>

      <!-- 微信登录入口(仅微信浏览器内显示) -->
      <div v-if="isWechat" class="login-page__wechat">
        <van-divider>其他登录方式</van-divider>
        <van-button plain block icon="wechat" color="#07c160" @click="onWechatLogin">
          微信登录
        </van-button>
      </div>
    </div>

    <!-- 底部版本号与版权 -->
    <div class="login-page__footer">
      <div class="login-page__version">v{{ version }}</div>
      <div class="login-page__copyright">© {{ new Date().getFullYear() }} 企业食堂订餐系统</div>
    </div>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables' as *;

.login-page {
  min-height: 100vh;
  background: $brand-card;
  display: flex;
  flex-direction: column;

  // 顶部品牌区:浅色渐变背景
  &__header {
    text-align: center;
    padding: 48px 24px 40px;
    background: linear-gradient(180deg, #eaf2ff 0%, rgba(234, 242, 255, 0) 100%);
  }

  &__logo {
    width: 80px;
    height: 80px;
    margin: 0 auto 16px;
    background: $brand-card;
    border-radius: 20px;
    display: flex;
    align-items: center;
    justify-content: center;
    overflow: hidden;
    border: 1px solid $brand-border;
  }

  &__title {
    margin: 0;
    font-size: 22px;
    font-weight: 600;
    color: $text-primary;
  }

  &__subtitle {
    margin: 8px 0 0;
    font-size: 13px;
    color: $text-secondary;
  }

  // 表单卡片(对齐模板:border 替代 box-shadow,圆角 16px)
  &__form-card {
    margin: 0 16px;
    background: $brand-card;
    border-radius: 16px;
    border: 1px solid $brand-border;
    overflow: hidden;
  }

  &__tab-title {
    padding: 16px 16px 0;
    font-size: 15px;
    font-weight: 600;
    color: $text-primary;
  }

  &__submit {
    margin: 16px 16px 8px;
  }

  &__wechat {
    padding: 0 16px 16px;

    :deep(.van-divider) {
      margin: 8px 0 16px;
      color: $text-secondary;
      border-color: $border-color;
    }
  }

  &__footer {
    margin-top: auto;
    padding: 24px 0 calc(env(safe-area-inset-bottom) + 16px);
    text-align: center;
  }

  &__version {
    font-size: 12px;
    color: $text-placeholder;
  }

  &__copyright {
    margin-top: 4px;
    font-size: 11px;
    color: $text-placeholder;
  }
}
</style>
