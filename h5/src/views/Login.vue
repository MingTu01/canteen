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
import { get } from '@/api'

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

/** DEV 模式:显示默认测试账号 */
const isDev = import.meta.env.DEV

/** 版本号 */
const version = __APP_VERSION__

/** 测试刷卡模块:从后端拉取员工列表,点击模拟手机号登录(始终显示,便于部署演示) */
interface TestEmployee {
  id: number
  cardNo: string
  name: string
  phone?: string
  storeId: number
  storeName?: string
}
const testEmployees = ref<TestEmployee[]>([])
const testLoading = ref(false)
const quickLoginId = ref<number | null>(null)

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

/** 拉取测试员工列表(公开接口,免登录) */
const loadTestEmployees = async (): Promise<void> => {
  if (testEmployees.value.length > 0) return
  testLoading.value = true
  try {
    const list = await get<TestEmployee[]>('/test/employees')
    testEmployees.value = Array.isArray(list) ? list : []
  } catch {
    /* 静默,模块显示空列表 */
  } finally {
    testLoading.value = false
  }
}

/** 点击员工模拟登录:用 phone + 默认密码 123456 */
const onQuickLogin = async (emp: TestEmployee): Promise<void> => {
  if (loading.value || quickLoginId.value !== null) return
  if (!emp.phone) {
    showFailToast('该员工未绑定手机号,无法快速登录')
    return
  }
  quickLoginId.value = emp.id
  loading.value = true
  try {
    await authStore.phoneLogin(emp.phone, '123456')
    showSuccessToast('登录成功')
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
    quickLoginId.value = null
  }
}

/** 填入测试账号 */
const fillTestAccount = (): void => {
  phoneForm.phone = '13800000001'
  phoneForm.password = '123456'
}

onMounted(() => {
  // 如果已经登录,跳首页(路由守卫也会处理,这里双保险)
  if (authStore.isLoggedIn) {
    router.replace('/')
    return
  }
  // 加载测试员工列表(用于模拟登录)
  loadTestEmployees()
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

      <!-- DEV 模式:测试账号提示 -->
      <div v-if="isDev" class="login-page__test-tip">
        <div class="login-page__test-title">🧪 测试账号(DEV)</div>
        <div class="login-page__test-row">
          <span>手机号:13800000001 / 密码:123456</span>
          <van-button
            size="mini"
            type="primary"
            plain
            @click="fillTestAccount"
          >
            填入
          </van-button>
        </div>
      </div>

      <!-- 测试刷卡模块:点击员工模拟手机号登录(仅 DEV 显示) -->
      <div v-if="isDev" class="login-page__swipe-test">
        <div class="login-page__swipe-title">
          🧪 测试快速登录
          <span class="login-page__swipe-count">
            {{ testLoading ? '加载中...' : `共 ${testEmployees.length} 人` }}
          </span>
        </div>
        <div class="login-page__swipe-hint">点击员工模拟手机号登录(默认密码 123456)</div>
        <div v-if="!testLoading && testEmployees.length === 0" class="login-page__swipe-empty">
          暂无员工,请先在后台添加
        </div>
        <div v-else class="login-page__swipe-list">
          <button
            v-for="emp in testEmployees"
            :key="emp.id"
            class="login-page__swipe-card"
            :disabled="loading"
            @click="onQuickLogin(emp)"
          >
            <div class="login-page__swipe-name">{{ emp.name }}</div>
            <div class="login-page__swipe-meta">
              <span v-if="emp.phone" class="login-page__swipe-phone">{{ emp.phone }}</span>
              <span v-else class="login-page__swipe-no-phone">未绑定手机号</span>
              <span v-if="emp.storeName" class="login-page__swipe-store">{{ emp.storeName }}</span>
            </div>
            <div v-if="quickLoginId === emp.id" class="login-page__swipe-loading">登录中...</div>
          </button>
        </div>
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

  &__test-tip {
    margin: 8px 16px 16px;
    padding: 12px;
    background: #fff7e6;
    border-radius: 8px;
    border: 1px dashed #ffb320;
    font-size: 12px;
    color: #fa8c16;
  }

  &__test-title {
    font-weight: 600;
    margin-bottom: 8px;
  }

  &__test-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-top: 6px;
    gap: 8px;
  }

  // 测试刷卡模块
  &__swipe-test {
    margin: 8px 16px 16px;
    padding: 12px;
    background: #f0f7ff;
    border-radius: 8px;
    border: 1px dashed #0065fd;
    font-size: 12px;
    color: #0065fd;
  }

  &__swipe-title {
    font-weight: 600;
    margin-bottom: 4px;
    display: flex;
    align-items: center;
    justify-content: space-between;
  }

  &__swipe-count {
    font-size: 11px;
    font-weight: 400;
    color: $text-secondary;
  }

  &__swipe-hint {
    font-size: 11px;
    color: $text-secondary;
    margin-bottom: 8px;
  }

  &__swipe-empty {
    padding: 12px;
    text-align: center;
    font-size: 12px;
    color: $text-placeholder;
  }

  &__swipe-list {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 8px;
    max-height: 240px;
    overflow-y: auto;
  }

  &__swipe-card {
    position: relative;
    padding: 10px 12px;
    border-radius: 8px;
    background: $brand-card;
    border: 1px solid $brand-border;
    text-align: left;
    cursor: pointer;
    font-family: inherit;
    transition: background 0.15s;

    &:active:not(:disabled) {
      background: #e6f0ff;
    }

    &:disabled {
      opacity: 0.6;
      cursor: not-allowed;
    }
  }

  &__swipe-name {
    font-size: 14px;
    font-weight: 600;
    color: $text-primary;
    margin-bottom: 4px;
  }

  &__swipe-meta {
    display: flex;
    flex-direction: column;
    gap: 2px;
  }

  &__swipe-phone {
    font-size: 11px;
    color: $text-secondary;
    font-variant-numeric: tabular-nums;
  }

  &__swipe-no-phone {
    font-size: 11px;
    color: #fa8c16;
  }

  &__swipe-store {
    font-size: 10px;
    color: $text-placeholder;
  }

  &__swipe-loading {
    position: absolute;
    top: 4px;
    right: 8px;
    font-size: 10px;
    color: #0065fd;
    font-weight: 600;
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
