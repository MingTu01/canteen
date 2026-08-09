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
import { pickupStore, resetPickupFlow, type PickupOrder } from '@/store/pickup'
import { fetchBranding } from '@/store/branding'
import { toDateKey } from '@/utils'
import { getCachedAvatar } from '@/utils/imageCache'
import { useCardReader } from '@/composables/useCardReader'
import { User } from 'lucide-vue-next'

import Modal from '@/components/Modal.vue'

const router = useRouter()
const employee = computed(() => pickupStore.employee)

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
  try {
    const resp = await api.get(`/order/employee/${employee.value.id}`)
    const list: any[] = resp.data?.code === 200 ? (resp.data.data ?? []) : []
    const today = toDateKey(new Date())
    const pending = list
      .filter((o) => o.date === today && o.status === 1)
      .sort((a, b) => Number(a.mealType) - Number(b.mealType))
    if (pending.length === 0) {
      // 无待取餐订单:5 秒后自动消失,不影响下一位刷卡
      showErrorWithAutoClose(
        '暂无待取餐订单',
        '今日暂无待取餐订单,请先在订餐端下单',
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
 * 处理新刷卡(弹窗显示时):识别员工并重新查询订单。
 * 不导航到待机页,直接在当前页处理新卡号,实现"刷卡即切换"。
 */
const handleNewCard = async (cardNo: string) => {
  const trimmed = cardNo.trim()
  if (!trimmed) return
  try {
    const empResp = await api.get(`/terminal/employee/${encodeURIComponent(trimmed)}`)
    if (empResp.data.code === 200 && empResp.data.data) {
      // 识别成功:重置流程,设置新员工,重新查询订单
      resetPickupFlow()
      pickupStore.employee = empResp.data.data
      done = false
      startedAt.value = Date.now()
      fetchAndAdvance()
      return
    }
    // 未识别为员工:提示卡号不存在
    showErrorWithAutoClose('取餐失败', '卡号不存在')
  } catch {
    showErrorWithAutoClose('取餐失败', '卡号不存在')
  }
}

/**
 * 读卡器:弹窗显示时接受刷卡,关闭弹窗并处理新卡号。
 * 弹窗未显示时(验证中/跳转中)不接受刷卡,避免干扰流程。
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
  fetchAndAdvance()
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
  height: 100vh;
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
  backdrop-filter: blur(8px);
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
