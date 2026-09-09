<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, onActivated, onDeactivated, watch, type Component } from 'vue'
import { useRouter } from 'vue-router'
import {
  showConfirmDialog,
  showSuccessToast,
  showFailToast,
} from 'vant'
import QRCode from 'qrcode'
import {
  MessageSquare,
  PenSquare,
  Users,
  QrCode as QrCodeIcon,
  Lock,
  Info,
  Receipt,
  ChevronRight,
  UtensilsCrossed,
} from 'lucide-vue-next'
import EmptyState from '@/components/EmptyState.vue'
import { useAuthStore } from '@/stores/auth'
import { useBrandingStore } from '@/stores/branding'
import { useCartStore } from '@/stores/cart'
import { getMyRecharges } from '@/api/recharge'
import * as authApi from '@/api/auth'
import { formatMoney, formatDateTime } from '@/composables/useFormat'
import { getCachedImage } from '@/utils/imageCache'
import type { RechargeRecord, PayCode } from '@/api/types'

defineOptions({ name: 'Profile' })

const router = useRouter()
const authStore = useAuthStore()
const brandingStore = useBrandingStore()
const cartStore = useCartStore()

/** 应用版本号(由 vite define 注入) */
const version = __APP_VERSION__

const employee = computed(() => authStore.employee)
const balance = computed(() => formatMoney(authStore.balance))

// ============ 弹层显隐 ============
const showQrcodePopup = ref(false)
const showPasswordPopup = ref(false)
const showRechargePopup = ref(false)
const showAboutPopup = ref(false)

// ============ 取餐码(一次性支付码)弹层数据 ============
const qrcodeData = ref<PayCode | null>(null)
const qrcodeImg = ref<string>('')
const qrcodeLoading = ref(false)
/** 支付码定时刷新定时器(4 分钟自动刷新,5 分钟有效期前刷新) */
let payCodeRefreshTimer: ReturnType<typeof setTimeout> | null = null
/** 支付码刷新间隔(4 分钟,留 1 分钟余量避免过期) */
const PAY_CODE_REFRESH_INTERVAL = 4 * 60 * 1000
/** 刷新限流:正在刷新时不再重复触发,避免短时间多次核销导致重复请求 */
let isRefreshingFromSse = false

// ============ 修改密码表单 ============
const passwordForm = ref({
  oldPassword: '',
  newPassword: '',
  confirmPassword: '',
})
const passwordSubmitting = ref(false)

// ============ 充值记录 ============
const recharges = ref<RechargeRecord[]>([])
const rechargeLoading = ref(false)
const rechargeRefreshing = ref(false)

// ============ 头像回退 ============
const avatarError = ref(false)
/** 经过本地缓存处理的头像 URL（命中缓存返回 blob URL，否则原 URL） */
const avatarSrc = ref('')
// 头像 URL 变化时重置错误标志,避免新头像被旧失败状态卡在默认占位
watch(
  () => employee.value?.avatar,
  async (raw) => {
    avatarError.value = false
    if (!raw) {
      avatarSrc.value = ''
      return
    }
    avatarSrc.value = await getCachedImage(raw)
  },
  { immediate: true },
)

onMounted(async () => {
  // 后台刷新员工信息(余额等),refreshEmployee 内部会在无 employee 时直接返回
  await authStore.refreshEmployee()
  // 自动加载取餐码(不弹层,供内嵌卡片展示)
  loadQrcode()
  // 监听页面可见性:页面切回前台时恢复 SSE 连接(防止后台被中断后未重连)
  document.addEventListener('visibilitychange', onVisibilityChange)
  // 确保全局 SSE 连接运行(SSE 在 auth store 全局管理,登录时已启动,
  // 此处为页面刷新后的双保险)
  authStore.ensureSseRunning()
})

/**
 * keep-alive 组件激活时(从其他页面切回"我的"):
 * 恢复 SSE 连接(防止长时间切走后 SSE 被中断未重连)。
 * SSE 在 auth store 全局管理,不随页面切换断开,此处仅做恢复保险。
 */
onActivated(() => {
  authStore.ensureSseRunning()
})

/**
 * keep-alive 组件停用时(切到其他页面):
 * SSE 连接在 auth store 全局管理,不随页面切换断开,此处无需操作。
 */
onDeactivated(() => {
  /* SSE 全局管理,无需暂停 */
})

/**
 * 监听 auth store 的 payCodeUsedAt:终端核销支付码后 SSE 推送事件,
 * store 更新此时间戳,Profile.vue 检测到变化后立即刷新二维码。
 * (SSE 在 store 全局管理,不随 Profile.vue 卸载断开)
 */
watch(
  () => authStore.payCodeUsedAt,
  () => {
    // 初始值 0 不触发刷新(仅登录后第一次 watch 不刷)
    if (authStore.payCodeUsedAt === 0) return
    // 限流:正在刷新时跳过,避免短时间多次核销触发重复请求
    if (isRefreshingFromSse) return
    isRefreshingFromSse = true
    refreshPayCode().finally(() => {
      isRefreshingFromSse = false
    })
  },
)

onUnmounted(() => {
  // 清理支付码定时刷新定时器
  if (payCodeRefreshTimer) {
    clearTimeout(payCodeRefreshTimer)
    payCodeRefreshTimer = null
  }
  // 移除可见性监听
  document.removeEventListener('visibilitychange', onVisibilityChange)
  // 注意:SSE 连接在 auth store 全局管理,不随 Profile.vue 卸载关闭
})

/**
 * 页面可见性变化处理:
 * - 不可见(hidden):无需操作(SSE 全局管理,保持连接接收事件)
 * - 可见(visible):恢复 SSE 连接(防止后台被中断后未重连)
 *
 * SSE 是支付码核销实时刷新的唯一机制:
 * 终端核销后后端通过 SSE 推送 paycode_used 事件,store 更新时间戳,
 * Profile.vue watch 时间戳变化后刷新二维码。
 */
const onVisibilityChange = (): void => {
  if (document.visibilityState === 'hidden') return
  // 页面切回前台:恢复 SSE 连接(可能后台被中断)
  authStore.ensureSseRunning()
}

/** 重新生成支付码(静默,不显示 loading) */
const refreshPayCode = async (): Promise<void> => {
  try {
    qrcodeData.value = await authApi.generatePayCode()
    qrcodeImg.value = await QRCode.toDataURL(qrcodeData.value.code, {
      width: 240,
      margin: 1,
      color: { dark: '#1a1a1a', light: '#ffffff' },
    })
    // 重启定时刷新(4 分钟后再刷新)
    startPayCodeRefreshTimer()
  } catch {
    /* 刷新失败,保留旧码,下次可见时再试 */
  }
}

// ============ 跳转 ============
const goFeedback = (): void => {
  router.push('/feedback/list')
}

const goCreateFeedback = (): void => {
  router.push('/feedback/create')
}

const goGroupOrder = (): void => {
  router.push('/group-order')
}

const goUnsolicitedOrder = (): void => {
  router.push('/unsolicited-order')
}

// ============ 取餐码(一次性支付码) ============
/** 支付码是否已过期(expire 为毫秒时间戳) */
const isQrcodeExpired = (): boolean => {
  if (!qrcodeData.value) return true
  return Date.now() > qrcodeData.value.expire
}

/** 启动支付码定时刷新(4 分钟后自动刷新,避免 5 分钟过期) */
const startPayCodeRefreshTimer = (): void => {
  if (payCodeRefreshTimer) clearTimeout(payCodeRefreshTimer)
  payCodeRefreshTimer = setTimeout(async () => {
    // 刷新支付码(不显示 loading,静默刷新)
    try {
      qrcodeData.value = await authApi.generatePayCode()
      qrcodeImg.value = await QRCode.toDataURL(qrcodeData.value.code, {
        width: 240,
        margin: 1,
        color: { dark: '#1a1a1a', light: '#ffffff' },
      })
    } catch {
      /* 刷新失败,下次打开会重新加载 */
    }
    // 递归启动下一次刷新
    startPayCodeRefreshTimer()
  }, PAY_CODE_REFRESH_INTERVAL)
}

/** 仅加载取餐码数据(不弹层),供内嵌卡片与放大弹层共用 */
const loadQrcode = async (): Promise<void> => {
  // 无缓存或已过期则重新拉取
  if (!qrcodeData.value || isQrcodeExpired()) {
    qrcodeLoading.value = true
    try {
      qrcodeData.value = await authApi.generatePayCode()
      // 生成二维码:内容仅含 32 位 hex 支付码(不含个人信息)
      qrcodeImg.value = await QRCode.toDataURL(qrcodeData.value.code, {
        width: 240,
        margin: 1,
        color: { dark: '#1a1a1a', light: '#ffffff' },
      })
      // 启动定时刷新(4 分钟后自动刷新)
      startPayCodeRefreshTimer()
    } catch {
      /* 拦截器已提示 */
    } finally {
      qrcodeLoading.value = false
    }
  }
}

/** 点击内嵌二维码或"我的取餐码"菜单项:打开放大弹层 */
const openQrcodeZoom = async (): Promise<void> => {
  showQrcodePopup.value = true
  // 若未加载或已过期,先加载
  if (!qrcodeData.value || isQrcodeExpired()) {
    await loadQrcode()
  }
}

// ============ 修改密码 ============
const openPasswordPopup = (): void => {
  showPasswordPopup.value = true
}

const validatePassword = (): string | null => {
  const { oldPassword, newPassword, confirmPassword } = passwordForm.value
  if (!oldPassword) return '请输入旧密码'
  if (!newPassword) return '请输入新密码'
  if (newPassword.length < 8) return '新密码至少 8 位'
  if (newPassword !== confirmPassword) return '两次输入的密码不一致'
  return null
}

const onSubmitPassword = async (): Promise<void> => {
  const err = validatePassword()
  if (err) {
    showFailToast(err)
    return
  }
  passwordSubmitting.value = true
  try {
    await authApi.changePassword(
      passwordForm.value.oldPassword,
      passwordForm.value.newPassword,
    )
    showSuccessToast('密码修改成功')
    showPasswordPopup.value = false
    passwordForm.value = {
      oldPassword: '',
      newPassword: '',
      confirmPassword: '',
    }
  } catch {
    /* 拦截器已提示 */
  } finally {
    passwordSubmitting.value = false
  }
}

// ============ 充值记录 ============
const loadRecharges = async (): Promise<void> => {
  if (!authStore.employeeId) return
  rechargeLoading.value = true
  try {
    recharges.value = (await getMyRecharges(authStore.employeeId)) ?? []
  } catch {
    /* 拦截器已提示 */
  } finally {
    rechargeLoading.value = false
  }
}

const openRechargePopup = async (): Promise<void> => {
  showRechargePopup.value = true
  await loadRecharges()
}

const onRechargeRefresh = async (): Promise<void> => {
  try {
    await loadRecharges()
  } finally {
    rechargeRefreshing.value = false
  }
}

// ============ 关于弹层 ============
const openAboutPopup = (): void => {
  showAboutPopup.value = true
}

// ============ 退出登录 ============
const onLogout = (): void => {
  showConfirmDialog({
    title: '提示',
    message: '确定要退出登录吗?',
    confirmButtonText: '退出',
    cancelButtonText: '取消',
  })
    .then(async () => {
      await authStore.logout()
      brandingStore.clearBranding()
      // 清空购物车,避免跨用户泄漏
      cartStore.clearAll()
      // 清空取餐码缓存和定时器,避免下一用户看到上一用户的支付码
      // (SSE 连接由 authStore.logout 内部关闭)
      if (payCodeRefreshTimer) {
        clearTimeout(payCodeRefreshTimer)
        payCodeRefreshTimer = null
      }
      qrcodeData.value = null
      qrcodeImg.value = ''
      showSuccessToast('已退出登录')
      router.replace('/login')
    })
    .catch(() => {
      /* 取消 */
    })
}

// ============ 菜单配置 ============
interface MenuItem {
  label: string
  icon: Component
  handler: () => void
}

const menuItems: MenuItem[] = [
  { label: '未订餐用餐', icon: UtensilsCrossed, handler: goUnsolicitedOrder },
  { label: '提交反馈', icon: PenSquare, handler: goCreateFeedback },
  { label: '我的反馈', icon: MessageSquare, handler: goFeedback },
  { label: '团体订餐', icon: Users, handler: goGroupOrder },
  { label: '修改密码', icon: Lock, handler: openPasswordPopup },
  { label: '充值记录', icon: Receipt, handler: openRechargePopup },
  { label: '关于系统', icon: Info, handler: openAboutPopup },
]
</script>

<template>
  <div class="profile">
    <!-- 1. 用户卡(头像 + 信息 + 余额 + 二维码合并为一张卡) -->
    <section class="profile__user-card">
      <!-- 上半部分:头像 + 信息 + 余额(横向排列) -->
      <div class="profile__user-top">
        <div class="profile__avatar">
          <img
            v-if="avatarSrc && !avatarError"
            :src="avatarSrc"
            :alt="employee?.name"
            class="profile__avatar-img"
            @error="avatarError = true"
          />
          <span v-else class="profile__avatar-fallback">
            {{ employee?.name?.charAt(0) || '?' }}
          </span>
        </div>
        <div class="profile__user-meta">
          <h2 class="profile__user-name">{{ employee?.name || '未登录' }}</h2>
          <div class="profile__user-sub">
            <span v-if="employee?.cardNo">工号 {{ employee.cardNo }}</span>
            <span v-if="employee?.departmentName">{{ employee.departmentName }}</span>
          </div>
        </div>
        <div class="profile__balance">
          <p class="profile__balance-label">余额</p>
          <p class="profile__balance-num">¥{{ balance }}</p>
        </div>
      </div>

      <!-- 分隔线 -->
      <div class="profile__divider"></div>

      <!-- 下半部分:二维码(居中展示,可扫描识别;点击放大为兜底) -->
      <div class="profile__qrcode-row" @click="openQrcodeZoom">
        <div class="profile__qrcode-meta">
          <span class="profile__qrcode-title">我的取餐码</span>
          <span class="profile__qrcode-tip">点击可放大 · 取餐终端扫码使用</span>
        </div>
        <div class="profile__qrcode-thumb">
          <van-loading v-if="qrcodeLoading" size="32px">加载中...</van-loading>
          <img
            v-else-if="qrcodeImg"
            :src="qrcodeImg"
            alt="取餐码"
            class="profile__qrcode-img"
          />
          <QrCodeIcon v-else class="profile__qrcode-placeholder" :size="80" />
        </div>
      </div>
    </section>

    <!-- 2. 菜单列表(7项) -->
    <section class="profile__menu">
      <button
        v-for="item in menuItems"
        :key="item.label"
        type="button"
        class="profile__menu-item"
        @click="item.handler"
      >
        <component :is="item.icon" class="profile__menu-icon" :size="20" />
        <span class="profile__menu-label">{{ item.label }}</span>
        <ChevronRight class="profile__menu-chevron" :size="16" />
      </button>
    </section>

    <!-- 5. 退出登录 -->
    <button type="button" class="profile__logout-card" @click="onLogout">
      退出登录
    </button>

    <!-- ============ 取餐码放大弹层(居中放大) ============ -->
    <van-popup
      v-model:show="showQrcodePopup"
      position="center"
      round
      closeable
      :style="{ width: '80%', maxWidth: '320px' }"
    >
      <div class="qrcode-center-popup">
        <div class="qrcode-center-popup__title">我的取餐码</div>
        <div v-if="qrcodeLoading" class="qrcode-center-popup__loading">
          <van-loading size="32px">加载中...</van-loading>
        </div>
        <template v-else-if="qrcodeData">
          <div class="qrcode-center-popup__img-wrap">
            <img
              v-if="qrcodeImg"
              :src="qrcodeImg"
              alt="取餐码"
              class="qrcode-center-popup__img"
            />
          </div>
          <div class="qrcode-center-popup__name">{{ employee?.name }}</div>
          <div class="qrcode-center-popup__tip">5分钟内有效,核销后自动失效,可在取餐终端扫码使用</div>
        </template>
        <div class="qrcode-center-popup__close" @click="showQrcodePopup = false">
          关闭
        </div>
      </div>
    </van-popup>

    <!-- ============ 修改密码弹层 ============ -->
    <van-popup
      v-model:show="showPasswordPopup"
      position="bottom"
      round
      closeable
      :style="{ maxHeight: '80%' }"
    >
      <div class="popup password-popup">
        <div class="popup__title">修改密码</div>
        <van-cell-group inset>
          <van-field
            v-model="passwordForm.oldPassword"
            type="password"
            label="旧密码"
            placeholder="请输入旧密码"
            :maxlength="32"
          />
          <van-field
            v-model="passwordForm.newPassword"
            type="password"
            label="新密码"
            placeholder="至少8位"
            :maxlength="32"
          />
          <van-field
            v-model="passwordForm.confirmPassword"
            type="password"
            label="确认密码"
            placeholder="请再次输入新密码"
            :maxlength="32"
          />
        </van-cell-group>
        <div class="popup__footer">
          <van-button
            block
            round
            type="primary"
            :loading="passwordSubmitting"
            @click="onSubmitPassword"
          >
            确认修改
          </van-button>
        </div>
      </div>
    </van-popup>

    <!-- ============ 充值记录弹层(半屏) ============ -->
    <van-popup
      v-model:show="showRechargePopup"
      position="bottom"
      round
      closeable
      :style="{ height: '60%' }"
    >
      <div class="popup recharge-popup">
        <div class="popup__title">充值记录</div>
        <van-pull-refresh v-model="rechargeRefreshing" @refresh="onRechargeRefresh">
          <div v-if="rechargeLoading" class="recharge-popup__loading">
            <van-loading size="24px">加载中...</van-loading>
          </div>
          <EmptyState v-else-if="recharges.length === 0" text="暂无充值记录" />
          <div v-else class="recharge-popup__list">
            <div
              v-for="r in recharges"
              :key="r.id"
              class="recharge-popup__item"
            >
              <div class="recharge-popup__item-left">
                <div class="recharge-popup__item-amount">
                  +¥{{ formatMoney(r.amount) }}
                </div>
                <div class="recharge-popup__item-meta">
                  <span v-if="r.balanceBefore != null && r.balanceAfter != null">
                    余额 {{ formatMoney(r.balanceBefore) }} → {{ formatMoney(r.balanceAfter) }}
                  </span>
                </div>
                <div class="recharge-popup__item-time">
                  {{ formatDateTime(r.createdAt) }}
                </div>
              </div>
              <div class="recharge-popup__item-operator">
                {{ r.operator || '系统' }}
              </div>
            </div>
          </div>
        </van-pull-refresh>
      </div>
    </van-popup>

    <!-- ============ 关于弹层 ============ -->
    <van-popup
      v-model:show="showAboutPopup"
      position="bottom"
      round
      closeable
      :style="{ maxHeight: '60%' }"
    >
      <div class="popup about-popup">
        <div class="popup__title">关于系统</div>
        <div class="about-popup__content">
          <van-icon name="shop-o" size="48" color="#0065fd" />
          <div class="about-popup__name">企业食堂订餐系统</div>
          <div class="about-popup__version">版本 v{{ version }}</div>
          <div class="about-popup__desc">
            为企业提供便捷的食堂订餐、取餐、充值与反馈服务,支持在线点餐、团体订餐、扫码取餐等功能。
          </div>
        </div>
        <div class="popup__footer">
          <van-button block round @click="showAboutPopup = false">关闭</van-button>
        </div>
      </div>
    </van-popup>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables' as *;

.profile {
  min-height: 100vh;
  padding: 12px 16px 24px;
  // 顶部留出状态栏安全区
  padding-top: calc(12px + env(safe-area-inset-top));
  background: $brand-secondary;
  box-sizing: border-box;
}

// ============ 1. 用户信息卡(头像 + 信息 + 余额 + 二维码合并) ============
.profile__user-card {
  padding: 0;
  margin-bottom: 12px;
  background: $brand-card;
  border: 1px solid $brand-border;
  border-radius: 16px;
  overflow: hidden;
}

// 上半部分:头像 + 信息 + 余额横向排列
.profile__user-top {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 18px 16px;
  background: $brand-primary;
}

.profile__avatar {
  width: 72px;
  height: 72px;
  border-radius: 50%;
  flex-shrink: 0;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(255, 255, 255, 0.2);
  border: 3px solid rgba(255, 255, 255, 0.5);
  box-sizing: border-box;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
}

.profile__avatar-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.profile__avatar-fallback {
  color: $brand-primary-fg;
  font-size: 28px;
  font-weight: 700;
  line-height: 1;
}

.profile__user-meta {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.profile__user-name {
  margin: 0;
  font-size: 18px;
  font-weight: 700;
  color: $brand-primary-fg;
  line-height: 1.3;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.profile__user-sub {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 12px;
  color: rgba(255, 255, 255, 0.75);
  flex-wrap: wrap;
}

// 余额区(右侧,在 primary 背景上)
.profile__balance {
  flex-shrink: 0;
  text-align: right;
  padding-left: 12px;
}

.profile__balance-label {
  margin: 0;
  font-size: 11px;
  color: rgba(255, 255, 255, 0.7);
}

.profile__balance-num {
  margin: 2px 0 0;
  font-size: 20px;
  font-weight: 700;
  color: $brand-primary-fg;
  line-height: 1.2;
}

// 分隔线
.profile__divider {
  height: 1px;
  background: $brand-border;
}

// 下半部分:二维码行(纵向居中布局,二维码尺寸保证可直接扫码识别)
.profile__qrcode-row {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 18px 16px;
  cursor: pointer;
  transition: background 0.15s;

  &:active {
    background: $brand-muted;
  }
}

.profile__qrcode-meta {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
}

.profile__qrcode-title {
  font-size: 14px;
  font-weight: 600;
  color: $brand-foreground;
}

.profile__qrcode-tip {
  font-size: 12px;
  color: $brand-muted-foreground;
}

.profile__qrcode-thumb {
  width: 200px;
  height: 200px;
  padding: 8px;
  box-sizing: border-box;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #fff;
  border: 1px solid $brand-border;
  border-radius: 12px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
}

.profile__qrcode-img {
  width: 100%;
  height: 100%;
  object-fit: contain;
}

.profile__qrcode-placeholder {
  color: $brand-muted-foreground;
}

// ============ 2. 菜单列表(7项) ============
.profile__menu {
  margin-bottom: 12px;
  background: $brand-card;
  border: 1px solid $brand-border;
  border-radius: 16px;
  overflow: hidden;
}

.profile__menu-item {
  display: flex;
  align-items: center;
  width: 100%;
  height: 52px;
  padding: 14px 16px;
  background: transparent;
  border: none;
  border-bottom: 1px solid $brand-border;
  cursor: pointer;
  box-sizing: border-box;
  transition: background 0.15s;

  &:last-child {
    border-bottom: none;
  }

  &:hover,
  &:active {
    background: $brand-muted;
  }
}

.profile__menu-icon {
  flex-shrink: 0;
  margin-right: 12px;
  color: $brand-muted-foreground;
}

.profile__menu-label {
  flex: 1;
  text-align: left;
  font-size: 14px;
  color: $brand-card-foreground;
}

.profile__menu-chevron {
  flex-shrink: 0;
  color: $brand-muted-foreground;
}

// ============ 5. 退出登录 ============
.profile__logout-card {
  display: block;
  width: 100%;
  padding: 14px 16px;
  background: $brand-card;
  border: 1px solid $brand-border;
  border-radius: 16px;
  font-size: 14px;
  font-weight: 500;
  color: $brand-destructive;
  text-align: center;
  cursor: pointer;
  box-sizing: border-box;
  transition: background 0.15s;

  &:hover,
  &:active {
    background: $brand-muted;
  }
}

// ============ 通用弹层样式 ============
.popup {
  padding: 16px;
  display: flex;
  flex-direction: column;
  min-height: 200px;

  &__title {
    font-size: 16px;
    font-weight: 600;
    color: $brand-foreground;
    text-align: center;
    margin-bottom: 16px;
  }

  &__footer {
    margin-top: 16px;
    padding-bottom: env(safe-area-inset-bottom);
  }
}

// 取餐码居中放大弹层(取代原底部上拉弹层)
.qrcode-center-popup {
  padding: 20px 16px 16px;
  display: flex;
  flex-direction: column;
  align-items: center;

  &__title {
    font-size: 16px;
    font-weight: 600;
    color: $brand-foreground;
    text-align: center;
    margin-bottom: 16px;
  }

  &__loading {
    padding: 60px 0;
    display: flex;
    justify-content: center;
  }

  &__img-wrap {
    display: flex;
    justify-content: center;
    align-items: center;
    padding: 12px;
    background: #fff;
    border-radius: 12px;
    width: 240px;
    box-sizing: border-box;
    box-shadow: 0 4px 16px rgba(0, 0, 0, 0.08);
  }

  &__img {
    width: 216px;
    height: 216px;
    object-fit: contain;
  }

  &__name {
    text-align: center;
    font-size: 16px;
    font-weight: 600;
    margin-top: 16px;
    color: $brand-foreground;
  }

  &__code {
    text-align: center;
    font-size: 13px;
    color: $brand-muted-foreground;
    margin-top: 4px;
  }

  &__tip {
    text-align: center;
    font-size: 12px;
    color: $brand-muted-foreground;
    margin-top: 8px;
    padding: 0 8px;
    line-height: 1.5;
  }

  &__close {
    margin-top: 20px;
    padding: 8px 32px;
    font-size: 14px;
    font-weight: 500;
    color: $brand-primary;
    background: rgba(0, 101, 253, 0.08);
    border-radius: 999px;
    cursor: pointer;
    transition: background 0.15s;

    &:active {
      background: rgba(0, 101, 253, 0.16);
    }
  }
}

// 修改密码弹层
.password-popup {
  :deep(.van-cell-group--inset) {
    margin: 0;
  }
}

// 充值记录弹层
.recharge-popup {
  height: 100%;
  overflow: hidden;

  &__loading {
    padding: 48px 0;
    display: flex;
    justify-content: center;
  }

  &__list {
    padding-bottom: 12px;
  }

  &__item {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    padding: 12px 0;
    border-bottom: 1px solid $brand-border;

    &:last-child {
      border-bottom: none;
    }
  }

  &__item-left {
    flex: 1;
    min-width: 0;
  }

  &__item-amount {
    font-size: 16px;
    font-weight: 700;
    color: $brand-success;
  }

  &__item-meta {
    font-size: 12px;
    color: $brand-muted-foreground;
    margin-top: 4px;
  }

  &__item-time {
    font-size: 12px;
    color: $text-placeholder;
    margin-top: 2px;
  }

  &__item-operator {
    font-size: 12px;
    color: $brand-muted-foreground;
    flex-shrink: 0;
    padding-top: 2px;
  }
}

// 关于弹层
.about-popup {
  &__content {
    display: flex;
    flex-direction: column;
    align-items: center;
    padding: 16px 0;
  }

  &__name {
    font-size: 18px;
    font-weight: 600;
    color: $brand-foreground;
    margin-top: 12px;
  }

  &__version {
    font-size: 13px;
    color: $brand-muted-foreground;
    margin-top: 4px;
  }

  &__desc {
    font-size: 13px;
    color: $brand-muted-foreground;
    line-height: 1.6;
    margin-top: 16px;
    padding: 0 24px;
    text-align: center;
  }
}
</style>
