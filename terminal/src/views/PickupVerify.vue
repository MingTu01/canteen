<script setup lang="ts">
/**
 * 取餐验证页
 *
 * 刷卡后拉取员工今日待取餐订单,选第一条 status===1 的,
 * 设置 store 后跳转取餐信息页。至少展示 1.2s "验证中" 动画。
 */
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import api, { loadConfig } from '@/api'
import { getEmployeeByCardNo } from '@/utils/employeeCache'
import { pickupStore, resetPickupFlow, type PickupOrder } from '@/store/pickup'
import { fetchBranding } from '@/store/branding'
import { toDateKey, mealTypeLabel } from '@/utils'
import { serverDate } from '@/utils/serverTime'
import { useMealTimeSlots } from '@/composables/useMealTimeSlots'
import { getCachedAvatar } from '@/utils/imageCache'
import { useCardReader } from '@/composables/useCardReader'
import { User } from 'lucide-vue-next'

import Modal from '@/components/Modal.vue'
import BrandingHeader from '@/components/BrandingHeader.vue'

const router = useRouter()
const employee = computed(() => pickupStore.employee)
const { loadMealSlots, getCurrentMealType } = useMealTimeSlots()

/** 头像缓存处理 */
const avatarError = ref(false)
const avatarSrc = ref('')
let avatarObjectUrl = ''

const revokeAvatarUrl = () => {
  if (avatarObjectUrl && avatarObjectUrl.startsWith('blob:')) {
    URL.revokeObjectURL(avatarObjectUrl)
  }
  avatarObjectUrl = ''
}

watch(
  () => employee.value?.avatar,
  async (raw) => {
    avatarError.value = false
    revokeAvatarUrl()
    if (!raw) {
      avatarSrc.value = ''
      return
    }
    const config = loadConfig()
    const baseUrl = config?.serverUrl || ''
    const url = await getCachedAvatar(raw, baseUrl)
    if (url.startsWith('blob:')) avatarObjectUrl = url
    avatarSrc.value = url
  },
  { immediate: true },
)
const startedAt = ref(0)
let done = false
let advanceTimer: ReturnType<typeof setTimeout> | null = null

/* 错误弹窗 */
const showError = ref(false)
const errorMsg = ref('')
const errorVariant = ref<'warning' | 'info'>('warning')
const errorTitle = ref('')
/** 错误弹窗 5 秒自动关闭定时器 */
let errorTimer: ReturnType<typeof setTimeout> | null = null
/** 错误弹窗自动关闭延迟(毫秒) */
const ERROR_AUTO_CLOSE_DELAY = 5000

/** 关闭错误弹窗并清除定时器(不导航) */
const dismissError = () => {
  showError.value = false
  if (errorTimer) {
    clearTimeout(errorTimer)
    errorTimer = null
  }
}

/**
 * 显示错误弹窗并启动 5 秒自动关闭。
 * 自动关闭后返回取餐待机页,不影响下一位员工刷卡。
 */
const showErrorWithAutoClose = (
  title: string,
  msg: string,
  variant: 'warning' | 'info' = 'warning',
) => {
  errorTitle.value = title
  errorMsg.value = msg
  errorVariant.value = variant
  showError.value = true
  if (errorTimer) clearTimeout(errorTimer)
  errorTimer = setTimeout(() => {
    dismissError()
    router.replace('/pickup')
  }, ERROR_AUTO_CLOSE_DELAY)
}

/** 拉取员工今日待取餐订单,选第一条 status===1 的,设置 store 后跳转取餐信息页 */
const fetchAndAdvance = async () => {
  if (!employee.value) {
    router.replace('/pickup')
    return
  }
  // 时段校验:空档期(不在任何餐次的 [startTime,endTime] 内)拒绝取餐
  const curMealType = getCurrentMealType()
  if (curMealType === null) {
    showErrorWithAutoClose('未到用餐时间', '未到用餐时间,请在就餐时段内取餐', 'warning')
    return
  }
  try {
    const resp = await api.get(`/order/employee/${employee.value.id}`)
    const list: any[] = resp.data?.code === 200 ? (resp.data.data ?? []) : []
    const today = toDateKey(serverDate())
    // 关键:只保留当前时段餐次的订单,绝对避免"午餐时段核销早餐订单"的错配
    const pending = list
      .filter((o) => o.date === today && o.status === 1 && Number(o.mealType) === curMealType)
      .sort((a, b) => Number(a.mealType) - Number(b.mealType))
    if (pending.length === 0) {
      // 当前时段无待取餐订单(可能订单已被标记为未就餐,或没订该餐次)
      showErrorWithAutoClose(
        '暂无待取餐订单',
        `${employee.value.name || '该员工'}${mealTypeLabel(curMealType)}暂无待取餐订单`,
        'info',
      )
      return
    }
    const o = pending[0]
    const order: PickupOrder = {
      id: Number(o.id),
      mealType: Number(o.mealType),
      date: String(o.date),
      totalAmount: Number(o.totalAmount ?? 0),
      // 订单来源:0-正常订餐,1-未订餐用餐(用于取餐页标识)
      orderSource: Number(o.orderSource ?? 0),
      // 兼容后端 items 字段(批量查询填充),映射菜品图片用于取餐页大图展示
      orderItems: (o.items ?? []).map((it: any) => ({
        dishName: String(it.dishName || ''),
        price: Number(it.price ?? 0),
        quantity: Number(it.quantity ?? 1),
        dishImage: String(it.dishImage || it.dish_image || ''),
        // 辣度: 0-3,后端字段 spiceLevel / spice_level 任一存在即可
        spiceLevel: Number(it.spiceLevel ?? it.spice_level ?? 0),
      })),
    }
    pickupStore.order = order
    // 至少展示 1.2s 的"验证中"动画
    const elapsed = Date.now() - startedAt.value
    const wait = Math.max(0, 1200 - elapsed)
    advanceTimer = setTimeout(() => {
      if (done) return
      done = true
      router.replace('/pickup/info')
    }, wait)
  } catch (e: any) {
    showErrorWithAutoClose(
      '查询失败',
      e?.response?.data?.message ?? '查询订餐信息失败,请重试',
    )
  }
}

/**
 * 处理新刷卡/扫码(弹窗显示时):识别员工并重新查询订单。
 * 不导航到待机页,直接在当前页处理新输入,实现"刷卡/扫码即切换"。
 * 识别顺序与待机页 handleInput 一致:身份二维码 → 一次性支付码 → 卡号。
 */
const handleNewCard = async (code: string) => {
  const trimmed = code.trim()
  if (!trimmed) return
  /** 识别成功:重置流程,设置新员工,重新查询订单 */
  const startNewFlow = (emp: NonNullable<typeof pickupStore.employee>) => {
    resetPickupFlow()
    pickupStore.employee = emp
    done = false
    startedAt.value = Date.now()
    fetchAndAdvance()
  }
  try {
    // 1. 旧版身份二维码:内容为 JSON 对象(以 { 开头,含 sign 签名)→ 兼容
    if (trimmed.startsWith('{')) {
      try {
        const qr = JSON.parse(trimmed)
        if (qr.sign && qr.cardNo && qr.storeId && qr.employeeId && qr.expire) {
          const resp = await api.post('/terminal/verify-qrcode', qr)
          if (resp.data.code === 200 && resp.data.data) {
            startNewFlow(resp.data.data)
            return
          }
        }
      } catch {
        /* 非合法二维码 JSON,继续按其他方式处理 */
      }
    }

    // 2. 一次性支付码:32 位 hex(小写)→ /terminal/verify-paycode
    //    大小写归一化:部分 HID 扫码枪出厂输出大写,后端按小写 key 核销
    const payCode = trimmed.toLowerCase()
    if (/^[0-9a-f]{32}$/.test(payCode)) {
      try {
        const resp = await api.post('/terminal/verify-paycode', { code: payCode })
        if (resp.data.code === 200 && resp.data.data) {
          startNewFlow(resp.data.data)
          return
        }
      } catch {
        /* 支付码无效或已使用,继续尝试卡号识别 */
      }
    }

    // 3. 作为卡号识别员工(读卡器/扫码枪,优先查本地缓存,毫秒级)
    try {
      const emp = await getEmployeeByCardNo(trimmed)
      if (emp) {
        startNewFlow(emp)
        return
      }
    } catch { /* 非员工卡 */ }

    // 所有方式均未匹配
    showErrorWithAutoClose('取餐失败', '卡号不存在')
  } catch {
    showErrorWithAutoClose('取餐失败', '卡号不存在')
  }
}

/**
 * 读卡器:弹窗显示时接受刷卡/扫码,关闭弹窗并处理新输入。
 * 弹窗未显示时(验证中/跳转中)不接受输入,避免干扰流程。
 */
useCardReader((cardNo) => {
  if (!showError.value) return
  dismissError()
  handleNewCard(cardNo)
})

/** 错误弹窗确认后返回待机 */
const onErrorConfirm = () => {
  dismissError()
  router.replace('/pickup')
}

onMounted(() => {
  startedAt.value = Date.now()
  fetchBranding({ background: true })
  // 加载就餐时段配置(admin-web 时间管理),用于时段校验
  loadMealSlots().then(() => fetchAndAdvance())
})
onUnmounted(() => {
  done = true
  if (advanceTimer) clearTimeout(advanceTimer)
  if (errorTimer) clearTimeout(errorTimer)
  revokeAvatarUrl()
})
</script>

<template>
  <main class="verify">
    <BrandingHeader />
    <div class="verify__inner">
      <!-- Spinner -->
      <div class="verify__spinner spinner"></div>

      <!-- 验证中 -->
      <div class="verify__title">验证中...</div>

      <!-- 用户信息卡片 -->
      <div v-if="employee" class="verify__card">
        <div class="verify__avatar">
          <img
            v-if="avatarSrc && !avatarError"
            :src="avatarSrc"
            :alt="employee.name"
            class="verify__avatar-img"
            @error="avatarError = true"
          />
          <User v-else :size="32" />
        </div>
        <div class="verify__user">
          {{ employee.name }} · {{ employee.departmentName || '未分配部门' }} · 工号 {{ employee.cardNo }}
        </div>
        <div class="verify__hint">正在查询您的订餐信息...</div>
      </div>
    </div>

    <!-- 错误弹窗:无待取餐订单 / 查询失败(5 秒自动消失,下一位刷卡也会关闭) -->
    <Modal
      v-model="showError"
      :title="errorTitle"
      :message="errorMsg"
      :variant="errorVariant"
      :cancel-text="''"
      :close-on-overlay="false"
      confirm-text="知道了"
      @confirm="onErrorConfirm"
      @cancel="onErrorConfirm"
    />
  </main>
</template>

<style scoped>
.verify {
  position: relative;
  height: 100vh;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  /* 背景由 App.vue 全局提供(深色底色 + 品牌图),此处透明避免遮挡 */
  background: transparent;
}
.verify__inner {
  position: relative;
  z-index: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  flex: 1;
  gap: 32px;
  padding: 40px 24px;
}
.verify__spinner {
  width: 56px;
  height: 56px;
  border: 4px solid rgba(255, 255, 255, 0.2);
  border-top-color: var(--doubao-primary);
  border-radius: 50%;
}
.verify__title {
  font-size: var(--fs-xl);
  font-weight: 700;
  color: #ffffff;
}
.verify__card {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
  padding: 32px 48px;
  background: rgba(255, 255, 255, 0.08);
  border: 1px solid rgba(255, 255, 255, 0.15);
  border-radius: var(--doubao-radius);
  min-width: 360px;
}
.verify__avatar {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 64px;
  height: 64px;
  border-radius: 50%;
  background: var(--doubao-accent);
  color: var(--doubao-primary);
  overflow: hidden;
}
.verify__avatar-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.verify__user {
  font-size: var(--fs-lg);
  font-weight: 700;
  color: #ffffff;
  text-align: center;
}
.verify__hint {
  font-size: var(--fs-base);
  color: rgba(255, 255, 255, 0.7);
  margin-top: 4px;
}
</style>
