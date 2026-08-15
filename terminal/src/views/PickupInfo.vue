<script setup lang="ts">
/**
 * 取餐信息页
 *
 * 展示员工信息 + 待取餐订单详情,员工确认后调用 complete 接口。
 * - 顶栏(右侧倒计时 + 暂停按钮)
 * - 员工头像 + 姓名 + 部门
 * - 餐别信息卡片(对齐 useMealConfig 餐别配色)+ 菜品横条列表(辣度角标+名称+数量单价)
 * - 取餐完成按钮(缩小,一般不点)
 * - 30 秒倒计时,到时自动返回待机
 * - 刷卡切换:展示当前员工菜品时,新员工刷卡直接切换显示,无需返回待机
 */
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import api, { loadConfig } from '@/api'
import { pickupStore, resetPickupFlow, type PickupOrder } from '@/store/pickup'
import { useMealConfig } from '@/composables/useMealConfig'
import { useMealTimeSlots } from '@/composables/useMealTimeSlots'
import { mealTypeLabel, mealTypeTime, toDateKey } from '@/utils'
import { getCachedAvatar } from '@/utils/imageCache'
import { Pause, Play } from 'lucide-vue-next'
import BrandingHeader from '@/components/BrandingHeader.vue'
import Modal from '@/components/Modal.vue'
import ChiliIcon from '@/components/ChiliIcon.vue'

import { fetchBranding } from '@/store/branding'

const router = useRouter()
const employee = computed(() => pickupStore.employee)
const order = computed(() => pickupStore.order)

const COUNTDOWN_TOTAL = 30
const remaining = ref(COUNTDOWN_TOTAL)
const paused = ref(false)
const completing = ref(false)
let timer = 0

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

const { mealBadgeStyle, mealIconMap, mealIconColor } = useMealConfig()
const { slots: mealSlots, loadMealSlots, getCurrentMealType } = useMealTimeSlots()

/* 错误弹窗 */
const showError = ref(false)
const errorMsg = ref('')

/* 切换中提示(新员工刷卡后正在加载订单) */
const switching = ref(false)

const countdownText = computed(() =>
  paused.value ? '已暂停' : `${remaining.value}秒后自动返回`,
)

const goBack = () => {
  resetPickupFlow()
  router.replace('/pickup')
}

const tick = () => {
  // 错误弹窗显示时暂停倒计时,避免用户来不及阅读
  if (paused.value || completing.value || switching.value || showError.value) return
  remaining.value -= 1
  if (remaining.value <= 0) goBack()
}

const togglePause = () => {
  paused.value = !paused.value
}

/** 重置倒计时(切换员工后重新计时 30 秒) */
const resetCountdown = () => {
  remaining.value = COUNTDOWN_TOTAL
  paused.value = false
}

/** 取餐完成:调用 complete 接口,成功后直接返回待机主页 */
const completePickup = async () => {
  if (completing.value || !order.value) return
  completing.value = true
  try {
    await api.put(`/order/${order.value.id}/complete`)
    goBack()
  } catch (e: any) {
    errorMsg.value = e?.response?.data?.message ?? '取餐完成失败,请重试'
    showError.value = true
  } finally {
    completing.value = false
  }
}

/* ============ 刷卡切换:USB 读卡器作为键盘输入,缓冲按键识别卡号 ============ */
let cardBuffer = ''
let cardBufferTimer: ReturnType<typeof setTimeout> | null = null
const CARD_INPUT_TIMEOUT = 80 // 读卡器单字符间隔通常 < 50ms,80ms 兜底

const onKeyPress = (e: KeyboardEvent) => {
  // 模态弹窗显示时忽略刷卡,并清空缓冲(避免弹窗前残留拼接)
  if (showError.value || completing.value) {
    cardBuffer = ''
    return
  }
  // Enter 键:卡号输入结束,触发切换
  if (e.key === 'Enter') {
    if (cardBuffer.length > 0) {
      const cardNo = cardBuffer
      cardBuffer = ''
      if (cardBufferTimer) {
        clearTimeout(cardBufferTimer)
        cardBufferTimer = null
      }
      switchEmployee(cardNo)
    }
    return
  }
  // 累积可打印字符
  if (e.key.length === 1) {
    cardBuffer += e.key
    if (cardBufferTimer) clearTimeout(cardBufferTimer)
    cardBufferTimer = setTimeout(() => {
      cardBuffer = ''
      cardBufferTimer = null
    }, CARD_INPUT_TIMEOUT)
  }
}

/**
 * 切换员工:识别卡号 → 拉取订单 → 更新 store → 重置倒计时
 * 不返回待机页,直接在当前页面刷新显示新员工的菜品。
 * 失败时保留原员工状态,避免显示"新员工姓名 + 旧员工订单"的数据错配。
 */
const switchEmployee = async (cardNo: string) => {
  if (switching.value) return
  switching.value = true
  // 暂存原 employee,订单拉取失败时恢复,避免 store 数据不一致导致错拿餐品
  const oldEmployee = pickupStore.employee
  const oldOrder = pickupStore.order
  try {
    // 时段校验:空档期(不在任何餐次的 [startTime,endTime] 内)拒绝取餐
    const curMealType = getCurrentMealType()
    if (curMealType === null) {
      errorMsg.value = '未到用餐时间,请在就餐时段内取餐'
      showError.value = true
      return
    }
    const resp = await api.get(`/terminal/employee/${encodeURIComponent(cardNo)}`)
    if (resp.data.code !== 200 || !resp.data.data) {
      // 卡号无效:保留原状态,仅弹错误提示
      errorMsg.value = resp.data.message || '刷卡失败,请重试'
      showError.value = true
      return
    }
    const newEmp = resp.data.data
    // 拉取今日待取餐订单(先拉订单,成功后再更新 employee,避免中间态错配)
    const listResp = await api.get(`/order/employee/${newEmp.id}`)
    const list: any[] = listResp.data?.code === 200 ? (listResp.data.data ?? []) : []
    const today = toDateKey(new Date())
    // 关键:只保留当前时段餐次的订单,绝对避免"午餐时段核销早餐订单"的错配
    const pending = list
      .filter((o) => o.date === today && o.status === 1 && Number(o.mealType) === curMealType)
      .sort((a, b) => Number(a.mealType) - Number(b.mealType))
    if (pending.length === 0) {
      // 当前时段无待取餐订单(可能订单已被标记为未就餐,或没订该餐次)
      errorMsg.value = `${newEmp.name || '该员工'}${mealTypeLabel(curMealType)}暂无待取餐订单`
      showError.value = true
      return
    }
    const o = pending[0]
    const newOrder: PickupOrder = {
      id: Number(o.id),
      mealType: Number(o.mealType),
      date: String(o.date),
      totalAmount: Number(o.totalAmount ?? 0),
      // 订单来源:0-正常订餐,1-未订餐用餐(用于取餐页标识)
      orderSource: Number(o.orderSource ?? 0),
      orderItems: (o.items ?? []).map((it: any) => ({
        dishName: String(it.dishName || ''),
        price: Number(it.price ?? 0),
        quantity: Number(it.quantity ?? 1),
        dishImage: String(it.dishImage || it.dish_image || ''),
        // 辣度: 0-3,后端字段 spiceLevel / spice_level 任一存在即可
        spiceLevel: Number(it.spiceLevel ?? it.spice_level ?? 0),
      })),
    }
    // 订单拉取成功后,原子更新 employee + order
    pickupStore.employee = newEmp
    pickupStore.order = newOrder
    resetCountdown()
  } catch (e: any) {
    // 异常时恢复原状态,避免 store 数据不一致
    pickupStore.employee = oldEmployee
    pickupStore.order = oldOrder
    errorMsg.value = e?.response?.data?.message || '切换失败,请重试'
    showError.value = true
  } finally {
    switching.value = false
  }
}

onMounted(() => {
  if (!employee.value) {
    router.replace('/pickup')
    return
  }
  fetchBranding()
  // 加载就餐时段配置(admin-web 时间管理),用于时段校验和真实时段展示
  loadMealSlots()
  timer = window.setInterval(tick, 1000)
  window.addEventListener('keydown', onKeyPress)
})
onUnmounted(() => {
  if (timer) clearInterval(timer)
  window.removeEventListener('keydown', onKeyPress)
  if (cardBufferTimer) clearTimeout(cardBufferTimer)
  // 释放头像 ObjectURL,避免内存泄漏
  revokeAvatarUrl()
})
</script>

<template>
  <main v-if="employee" class="pickup-info">
    <BrandingHeader>
      <template #right>
        <div class="pickup-info__countdown">
          <span class="pickup-info__countdown-text">{{ countdownText }}</span>
          <button
            class="pickup-info__pause btn-press"
            :aria-label="paused ? '继续倒计时' : '暂停倒计时'"
            @click="togglePause"
          >
            <Pause v-if="!paused" :size="16" />
            <Play v-else :size="16" />
          </button>
        </div>
      </template>
    </BrandingHeader>

    <div class="pickup-info__body" :class="{ 'pickup-info__body--switching': switching }">
      <!-- 切换中提示遮罩 -->
      <div v-if="switching" class="pickup-info__switching">
        <div class="pickup-info__switching-spinner spinner"></div>
        <span>正在切换员工...</span>
      </div>

      <!-- 正常态:展示员工 + 订单详情 -->
      <template v-else>
        <!-- 员工头像(放大,图片优先,失败回退首字母) -->
        <div class="pickup-info__avatar">
          <img
            v-if="avatarSrc && !avatarError"
            :src="avatarSrc"
            :alt="employee.name"
            class="pickup-info__avatar-img"
            @error="avatarError = true"
          />
          <span v-else>{{ employee.name?.charAt(0) }}</span>
        </div>
        <!-- 部门·名字 -->
        <div class="pickup-info__user-info">
          <span class="pickup-info__dept-name">
            {{ employee.departmentName || '未分配部门' }} · {{ employee.name }}
          </span>
        </div>

      <!-- 餐别信息卡片 -->
      <div v-if="order" class="pickup-info__card" :class="{ 'pickup-info__card--unsolicited': order.orderSource === 1 }">
        <div class="pickup-info__meal-head">
          <div class="pickup-info__badge" :style="mealBadgeStyle(order.mealType)">
            <component
              :is="mealIconMap[order.mealType]"
              :size="18"
              :stroke-width="2.5"
              :color="mealIconColor(order.mealType)"
            />
            <span>{{ mealTypeLabel(order.mealType) }}</span>
          </div>
          <span class="pickup-info__time">{{ mealTypeTime(order.mealType, mealSlots) }}时段</span>
          <!-- 未订餐用餐标识 -->
          <span v-if="order.orderSource === 1" class="pickup-info__source-tag">
            未订餐用餐
          </span>
        </div>
        <hr class="pickup-info__divider" />

        <div class="pickup-info__dishes">
          <div
            v-for="(it, idx) in order.orderItems"
            :key="idx"
            class="pickup-info__dish"
          >
            <!-- 左:菜品名称(放大加粗) -->
            <span class="pickup-info__dish-name">{{ it.dishName }}</span>
            <!-- 右:辣度角标 + 数量 + 单价 -->
            <div class="pickup-info__dish-right">
              <span
                v-if="Number(it.spiceLevel ?? 0) > 0"
                class="pickup-info__spice-tag pickup-info__spice-tag--spicy"
              >
                辣
                <ChiliIcon
                  v-for="n in Number(it.spiceLevel ?? 0)"
                  :key="n"
                  :size="20"
                />
              </span>
              <span class="pickup-info__dish-qty">×{{ it.quantity }}</span>
              <span class="pickup-info__dish-price">¥{{ Number(it.price ?? 0).toFixed(2) }}</span>
            </div>
          </div>
          <div v-if="!order.orderItems.length" class="pickup-info__empty">暂无菜品</div>
        </div>
      </div>

      <!-- 取餐完成按钮:点击直接返回主页 -->
      <button
        class="pickup-info__confirm"
        :disabled="completing"
        @click="completePickup"
      >
        {{ completing ? '处理中...' : '取餐完成' }}
      </button>
      </template>
    </div>

    <!-- 错误弹窗(关闭遮罩关闭,强制用户确认) -->
    <Modal
      v-model="showError"
      title="操作失败"
      :message="errorMsg"
      variant="warning"
      :close-on-overlay="false"
      :cancel-text="''"
      confirm-text="知道了"
    />
  </main>
</template>

<style scoped>
.pickup-info {
  display: flex;
  flex-direction: column;
  height: 100vh;
  overflow: hidden;
  /* 背景由 App.vue 全局提供(深色底色 + 品牌图),此处透明避免遮挡 */
  background: transparent;
}
.pickup-info__countdown {
  display: flex;
  align-items: center;
  gap: 8px;
}
.pickup-info__countdown-text {
  font-size: var(--fs-sm);
  color: rgba(255, 255, 255, 0.85);
}
.pickup-info__pause {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  border-radius: 50%;
  border: none;
  background: transparent;
  color: rgba(255, 255, 255, 0.85);
  cursor: pointer;
}

/* 完成态已移除:点击取餐完成直接返回主页 */

.pickup-info__body {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 32px 24px;
  gap: 24px;
  position: relative;
  overflow-y: auto;
}
/* 切换中遮罩:半透明覆盖,提示正在切换员工 */
.pickup-info__body--switching {
  opacity: 0.5;
  pointer-events: none;
}
.pickup-info__switching {
  position: absolute;
  inset: 0;
  z-index: 10;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  font-size: var(--fs-lg);
  font-weight: 700;
  color: #ffffff;
  background: var(--doubao-foreground);
}
.pickup-info__switching-spinner {
  width: 40px;
  height: 40px;
  border: 4px solid rgba(255, 255, 255, 0.2);
  border-top-color: var(--doubao-primary);
  border-radius: 50%;
}
.pickup-info__avatar {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 120px;
  height: 120px;
  border-radius: 50%;
  background: var(--doubao-primary);
  border: 3px solid rgba(255, 255, 255, 0.25);
  color: var(--doubao-primary-foreground);
  font-size: 48px;
  font-weight: 700;
  flex-shrink: 0;
  box-shadow: 0 6px 20px rgba(0, 0, 0, 0.25);
  overflow: hidden;
}
.pickup-info__avatar-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.pickup-info__user-info {
  text-align: center;
}
.pickup-info__dept-name {
  font-size: var(--fs-2xl);
  font-weight: 700;
  color: #ffffff;
  line-height: 1.2;
  text-shadow: 0 2px 6px rgba(0, 0, 0, 0.35);
}

.pickup-info__card {
  width: 100%;
  max-width: 600px;
  padding: 28px 24px 24px;
  border-radius: var(--doubao-radius);
  border: 1px solid rgba(255, 255, 255, 0.15);
  /* X86 低端集显禁用 backdrop-filter:GPU 重绘导致整页闪烁 */
  /* 降低透明度(更不透明),让菜品名称等内容在品牌背景上更清晰 */
  background: rgba(0, 0, 0, 0.62);
}
.pickup-info__meal-head {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  margin-bottom: 16px;
}
.pickup-info__badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 14px;
  border-radius: 999px;
  border: 1px solid;
  font-size: var(--fs-lg);
  font-weight: 700;
}
.pickup-info__time {
  font-size: var(--fs-base);
  color: rgba(255, 255, 255, 0.7);
}
.pickup-info__divider {
  border: none;
  border-top: 1px solid rgba(255, 255, 255, 0.15);
  margin: 0;
}

/* 未订餐用餐订单标识:卡片加橙色边框 + 标签 */
.pickup-info__card--unsolicited {
  border: 2px solid #ff9800;
  box-shadow: 0 0 16px rgba(255, 152, 0, 0.3);
}
.pickup-info__source-tag {
  display: inline-flex;
  align-items: center;
  padding: 4px 12px;
  border-radius: 999px;
  background: #ff9800;
  color: #fff;
  font-size: var(--fs-sm);
  font-weight: 700;
  margin-left: 8px;
  letter-spacing: 1px;
  box-shadow: 0 2px 8px rgba(255, 152, 0, 0.4);
}

.pickup-info__dishes {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 20px 12px;
}
/* 横条卡片:左菜品名(放大加粗) + 右辣度&数量&单价 */
.pickup-info__dish {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 18px 22px;
  border-radius: var(--doubao-radius-sm);
  /* 降低透明度(更不透明),菜品行背景更实,菜品名更清晰 */
  background: rgba(0, 0, 0, 0.35);
  border: 1px solid rgba(255, 255, 255, 0.12);
}
/* 辣度角标(仅有辣度时显示) */
.pickup-info__spice-tag {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 4px 10px;
  border-radius: 6px;
  font-size: 18px;
  font-weight: 700;
  line-height: 1.2;
  white-space: nowrap;
}
.pickup-info__spice-tag--spicy {
  background: rgba(244, 67, 54, 0.18);
  color: #ff6b5b;
}
.pickup-info__dish-name {
  flex: 1;
  min-width: 0;
  font-size: 28px;
  font-weight: 700;
  color: #ffffff;
  line-height: 1.3;
  /* 超长菜品名省略号 */
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.pickup-info__dish-right {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 16px;
}
.pickup-info__dish-qty {
  font-size: 24px;
  font-weight: 700;
  color: rgba(255, 255, 255, 0.95);
  font-variant-numeric: tabular-nums;
}
.pickup-info__dish-price {
  font-size: 20px;
  font-weight: 600;
  color: rgba(255, 255, 255, 0.8);
  min-width: 72px;
  text-align: right;
  font-variant-numeric: tabular-nums;
}
.pickup-info__empty {
  font-size: var(--fs-lg);
  color: rgba(255, 255, 255, 0.7);
  text-align: center;
  padding: 40px 0;
}

/* 取餐完成按钮:缩小,一般情况不点,自动倒计时返回 */
.pickup-info__confirm {
  padding: 8px 32px;
  font-size: var(--fs-base);
  font-weight: 700;
  border-radius: var(--doubao-radius-sm);
  background: var(--doubao-primary);
  color: var(--doubao-primary-foreground);
  border: none;
  cursor: pointer;
  font-family: inherit;
  transition: opacity 0.15s ease;
}
.pickup-info__confirm:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
.pickup-info__confirm:not(:disabled):active {
  opacity: 0.85;
}
</style>
