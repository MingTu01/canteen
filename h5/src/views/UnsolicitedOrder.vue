<script setup lang="ts">
/**
 * 未订餐用餐页面
 *
 * 场景:员工未提前订餐,到现场加餐。
 * - 进入页面后从后端获取服务器时间,自动判断当前餐别
 * - 只能选择当前餐别的菜品(其他餐别不可选)
 * - 提示需经打菜人员确认菜品后下单
 * - 下单时带 orderSource=1,后端绕过截止时间和防重复校验
 */
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { showSuccessToast, showFailToast, showConfirmDialog } from 'vant'
import { ArrowLeft, Plus, Minus, ShoppingCart, AlertCircle } from 'lucide-vue-next'
import ChiliIcon from '@/components/ChiliIcon.vue'
import { useAuthStore } from '@/stores/auth'
import { getMenuByDate, getDiningTimes } from '@/api/menu'
import { createOrder } from '@/api/order'
import { fetchServerTime, type ServerTime } from '@/composables/useServerTime'
import { useOrderConfig } from '@/composables/useOrderConfig'
import { parseTimeToMinutes, formatDateStr } from '@/utils/date'
import { formatMoney, formatMealType } from '@/composables/useFormat'
import type { MenuWithItems, Dish, DiningTimeSlot } from '@/api/types'

defineOptions({ name: 'UnsolicitedOrder' })

const router = useRouter()
const authStore = useAuthStore()

// ============ 状态 ============
const loading = ref(true)
const serverTime = ref<ServerTime | null>(null)
const diningTimes = ref<DiningTimeSlot[]>([])
const menus = ref<MenuWithItems[]>([])
const cart = ref<Map<number, number>>(new Map()) // dishId -> quantity
const submitting = ref(false)
const showCartSheet = ref(false)

// ============ 餐别判断 ============
/** 根据服务器时间判断当前餐别(1早2中3晚) */
const currentMealType = computed((): number | null => {
  if (!serverTime.value || diningTimes.value.length === 0) return null
  const nowMin = serverTime.value.minutes
  // 优先匹配有时段配置的餐别
  for (const slot of diningTimes.value) {
    const startMin = parseTimeToMinutes(slot.startTime)
    const endMin = parseTimeToMinutes(slot.endTime)
    if (startMin >= 0 && endMin >= 0 && nowMin >= startMin && nowMin < endMin) {
      return slot.mealType
    }
  }
  // 无匹配时:11 点前视为早餐,11-16 视为午餐,其他视为晚餐
  if (nowMin < 11 * 60) return 1
  if (nowMin < 16 * 60) return 2
  return 3
})

/** 当前餐别的标签 */
const currentMealLabel = computed(() => {
  const mt = currentMealType.value
  return mt ? formatMealType(mt) : '未知'
})

/** 当前餐别的菜单 */
const currentMenu = computed(() => {
  const mt = currentMealType.value
  if (!mt) return null
  return menus.value.find((m) => m.menu.mealType === mt) || null
})

/** 当前餐别的菜品列表(从 MenuItemView 中提取 dish) */
const currentDishes = computed<Dish[]>(() => {
  if (!currentMenu.value) return []
  return currentMenu.value.items
    .map((iv) => iv.dish)
    .filter((d): d is Dish => !!d)
})

// ============ 购物车操作 ============
const addToCart = (dish: Dish) => {
  const qty = cart.value.get(dish.id) || 0
  const max = dish.maxPerOrder || 99
  if (qty >= max) {
    showFailToast(`单次限购 ${max} 份`)
    return
  }
  cart.value.set(dish.id, qty + 1)
  cart.value = new Map(cart.value) // 触发响应式更新
}

const decreaseFromCart = (dish: Dish) => {
  const qty = cart.value.get(dish.id) || 0
  if (qty <= 0) return
  if (qty === 1) {
    cart.value.delete(dish.id)
  } else {
    cart.value.set(dish.id, qty - 1)
  }
  cart.value = new Map(cart.value)
}

const getDishQty = (dishId: number): number => cart.value.get(dishId) || 0

/** 购物车总数 */
const cartCount = computed(() => {
  let count = 0
  for (const qty of cart.value.values()) count += qty
  return count
})

/** 购物车总金额 */
const cartTotal = computed(() => {
  let total = 0
  for (const [dishId, qty] of cart.value.entries()) {
    const dish = currentDishes.value.find((d) => d.id === dishId)
    if (dish) total += Number(dish.price) * qty
  }
  return total
})

/** 购物车明细(用于底部弹层) */
const cartItems = computed(() => {
  const items: { dish: Dish; quantity: number; subtotal: number }[] = []
  for (const [dishId, qty] of cart.value.entries()) {
    const dish = currentDishes.value.find((d) => d.id === dishId)
    if (dish) {
      items.push({
        dish,
        quantity: qty,
        subtotal: Number(dish.price) * qty,
      })
    }
  }
  return items
})

// ============ 未订餐用餐手续费 ============
const { config: orderConfig, loadConfig } = useOrderConfig()

/** 当前餐别的手续费金额(未启用或无配置为 0) */
const feeAmount = computed(() => {
  if (!orderConfig.value.unsolicited_fee_enabled) return 0
  switch (currentMealType.value) {
    case 1: return orderConfig.value.unsolicited_fee_breakfast || 0
    case 2: return orderConfig.value.unsolicited_fee_lunch || 0
    case 3: return orderConfig.value.unsolicited_fee_dinner || 0
    default: return 0
  }
})

/** 手续费是否生效 */
const feeEnabled = computed(() => feeAmount.value > 0)

/** 实付合计 = 菜品小计 + 手续费(手续费按订单收取,与购物车数量无关) */
const payTotal = computed(() => cartTotal.value + (feeEnabled.value ? feeAmount.value : 0))

// ============ 下单 ============
const submitOrder = async () => {
  if (cart.value.size === 0) {
    showFailToast('请先选择菜品')
    return
  }
  if (!currentMealType.value) {
    showFailToast('无法判断当前餐别')
    return
  }
  // 强制刷新服务器时间,避免下单时时间已过期
  serverTime.value = await fetchServerTime(true)

  showConfirmDialog({
    title: '请打菜人员确认',
    message: `当前餐别:${currentMealLabel.value}\n菜品数量:${cartCount.value} 份\n合计金额:¥${formatMoney(payTotal.value)}${feeEnabled.value ? `\n(含手续费 ¥${formatMoney(feeAmount.value)})` : ''}\n\n请打菜人员确认菜品后点击确认下单。`,
    confirmButtonText: '已确认,下单',
    cancelButtonText: '取消',
  })
    .then(async () => {
      submitting.value = true
      try {
        const storeId = authStore.employee?.storeId
        const employeeId = authStore.employee?.id
        if (!storeId || !employeeId) {
          showFailToast('员工信息缺失,请重新登录')
          return
        }
        const today = serverTime.value?.date || formatDateStr(new Date())
        const mealType = currentMealType.value
        if (!mealType) {
          showFailToast('无法判断当前餐别')
          return
        }
        await createOrder({
          storeId,
          employeeId,
          date: today,
          mealType,
          items: Array.from(cart.value.entries()).map(([dishId, quantity]) => ({
            dishId,
            quantity,
          })),
          orderSource: 1, // 未订餐用餐
        })
        showSuccessToast('下单成功,请前往取餐')
        // 跳转订单页
        setTimeout(() => {
          router.replace('/orders')
        }, 1500)
      } catch (e: any) {
        const msg = e?.response?.data?.message || e?.message || '下单失败'
        showFailToast(msg)
      } finally {
        submitting.value = false
      }
    })
    .catch(() => {
      // 用户取消
    })
}

// ============ 初始化 ============
let timer: ReturnType<typeof setInterval> | null = null

onMounted(async () => {
  loading.value = true
  try {
    const storeId = authStore.employee?.storeId
    if (!storeId) {
      showFailToast('员工信息缺失,请重新登录')
      router.replace('/profile')
      return
    }
    // 并行加载:服务器时间 + 餐别时段 + 订餐配置(含未订餐用餐手续费)
    const [time, slots] = await Promise.all([
      fetchServerTime(),
      getDiningTimes(storeId),
      loadConfig(storeId),
    ])
    serverTime.value = time
    diningTimes.value = slots
    // 加载今日菜单
    const today = time.date || formatDateStr(new Date())
    menus.value = (await getMenuByDate(storeId, today)) ?? []
    // 每 30 秒刷新服务器时间(更新分钟数,影响餐别判断)
    timer = setInterval(async () => {
      serverTime.value = await fetchServerTime()
    }, 30000)
  } catch (e: any) {
    const msg = e?.response?.data?.message || e?.message || '加载失败'
    showFailToast(msg)
  } finally {
    loading.value = false
  }
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
})
</script>

<template>
  <div class="unsolicited">
    <!-- 顶部导航 -->
    <header class="unsolicited__header">
      <button class="unsolicited__back" @click="router.back()">
        <ArrowLeft :size="20" />
      </button>
      <h1 class="unsolicited__title">未订餐用餐</h1>
    </header>

    <!-- 加载中 -->
    <div v-if="loading" class="unsolicited__loading">
      <div class="unsolicited__spinner"></div>
      <p>加载中...</p>
    </div>

    <template v-else>
      <!-- 当前餐别提示 -->
      <section class="unsolicited__meal-info">
        <div class="unsolicited__meal-row">
          <span class="unsolicited__meal-label">当前餐别</span>
          <span class="unsolicited__meal-value">{{ currentMealLabel }}</span>
        </div>
        <div class="unsolicited__meal-row">
          <span class="unsolicited__meal-label">服务器时间</span>
          <span class="unsolicited__meal-value">{{ serverTime?.time || '--:--' }}</span>
        </div>
        <div class="unsolicited__meal-row">
          <span class="unsolicited__meal-label">日期</span>
          <span class="unsolicited__meal-value">{{ serverTime?.date || '-' }}</span>
        </div>
      </section>

      <!-- 提示:需打菜人员确认 -->
      <section class="unsolicited__notice">
        <AlertCircle :size="16" />
        <span>下单前请打菜人员确认菜品</span>
      </section>

      <!-- 提示:未订餐用餐手续费(启用且当前餐别金额 > 0 时展示) -->
      <section v-if="feeEnabled && feeAmount > 0" class="unsolicited__fee-notice">
        <AlertCircle :size="16" />
        <span>未订餐用餐将按单收取手续费 ¥{{ formatMoney(feeAmount) }}({{ currentMealLabel }})</span>
      </section>

      <!-- 当前餐别菜品列表 -->
      <section v-if="currentDishes.length > 0" class="unsolicited__dishes">
        <h2 class="unsolicited__section-title">
          {{ currentMealLabel }}菜品({{ currentDishes.length }} 道)
        </h2>
        <div class="unsolicited__dish-list">
          <div
            v-for="dish in currentDishes"
            :key="dish.id"
            class="unsolicited__dish"
            :class="{ 'unsolicited__dish--selected': getDishQty(dish.id) > 0 }"
            @click="dish.status !== 0 && getDishQty(dish.id) === 0 && addToCart(dish)"
          >
            <!-- 第一行:菜名(左) + 辣度(右) -->
            <div class="unsolicited__dish-top">
              <h3 class="unsolicited__dish-name">{{ dish.name }}</h3>
              <div
                v-if="dish.spiceLevel && dish.spiceLevel > 0"
                class="unsolicited__dish-spice"
              >
                <span class="unsolicited__dish-spice-label">辣</span>
                <ChiliIcon v-for="n in dish.spiceLevel" :key="n" :size="12" />
              </div>
            </div>
            <!-- 第二行:价格(左) + 操作区(右) -->
            <div class="unsolicited__dish-bottom">
              <span class="unsolicited__dish-price">¥{{ formatMoney(dish.price) }}</span>
              <span v-if="dish.status === 0" class="unsolicited__dish-soldout">已售罄</span>
              <button
                v-else-if="getDishQty(dish.id) === 0"
                type="button"
                class="unsolicited__dish-add"
                aria-label="加入购物车"
                @click.stop="addToCart(dish)"
              >
                <Plus :size="18" :stroke-width="2.5" />
              </button>
              <div v-else class="unsolicited__dish-stepper">
                <button
                  type="button"
                  class="unsolicited__stepper-btn unsolicited__stepper-btn--minus"
                  aria-label="减少一份"
                  @click.stop="decreaseFromCart(dish)"
                >
                  <Minus :size="16" :stroke-width="2.5" />
                </button>
                <span class="unsolicited__stepper-val">{{ getDishQty(dish.id) }}</span>
                <button
                  type="button"
                  class="unsolicited__stepper-btn unsolicited__stepper-btn--plus"
                  aria-label="增加一份"
                  @click.stop="addToCart(dish)"
                >
                  <Plus :size="16" :stroke-width="2.5" />
                </button>
              </div>
            </div>
          </div>
        </div>
      </section>

      <!-- 空状态 -->
      <section v-else class="unsolicited__empty">
        <div class="unsolicited__empty-icon">🍽️</div>
        <p>当前餐别暂无可选菜品</p>
      </section>
    </template>

    <!-- 底部购物车栏 -->
    <footer v-if="cartCount > 0" class="unsolicited__footer">
      <button class="unsolicited__cart-btn" @click="showCartSheet = true">
        <ShoppingCart :size="20" />
        <span class="unsolicited__cart-badge">{{ cartCount }}</span>
      </button>
      <div class="unsolicited__footer-info">
        <div class="unsolicited__footer-amount">
          <span class="unsolicited__footer-total">¥{{ formatMoney(payTotal) }}</span>
          <!-- 仅有手续费时展示 -->
          <span v-if="feeEnabled" class="unsolicited__footer-fee">含手续费 ¥{{ formatMoney(feeAmount) }}</span>
        </div>
        <button
          class="unsolicited__submit-btn"
          :disabled="submitting"
          @click="submitOrder"
        >
          {{ submitting ? '下单中...' : '确认下单' }}
        </button>
      </div>
    </footer>

    <!-- 购物车弹层 -->
    <van-action-sheet v-model:show="showCartSheet" title="已选菜品">
      <div class="unsolicited__cart-sheet">
        <div v-if="cartItems.length === 0" class="unsolicited__cart-empty">
          购物车为空
        </div>
        <div v-else class="unsolicited__cart-list">
          <div v-for="item in cartItems" :key="item.dish.id" class="unsolicited__cart-item">
            <span class="unsolicited__cart-name">{{ item.dish.name }}</span>
            <span class="unsolicited__cart-qty">×{{ item.quantity }}</span>
            <span class="unsolicited__cart-subtotal">¥{{ formatMoney(item.subtotal) }}</span>
          </div>
          <div class="unsolicited__cart-total-row">
            <span>合计{{ feeEnabled ? `(含手续费 ¥${formatMoney(feeAmount)})` : '' }}</span>
            <span class="unsolicited__cart-total-num">¥{{ formatMoney(payTotal) }}</span>
          </div>
        </div>
      </div>
    </van-action-sheet>
  </div>
</template>

<style scoped>
.unsolicited {
  min-height: 100vh;
  background: #f5f6fa;
  padding-bottom: 80px;
}

.unsolicited__header {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px;
  background: #fff;
  border-bottom: 1px solid #eee;
  position: sticky;
  top: 0;
  z-index: 10;
}
.unsolicited__back {
  width: 32px;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: transparent;
  cursor: pointer;
  color: #333;
}
.unsolicited__title {
  font-size: 18px;
  font-weight: 600;
  margin: 0;
  flex: 1;
}

.unsolicited__loading {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 80px 0;
  color: #999;
}
.unsolicited__spinner {
  width: 32px;
  height: 32px;
  border: 3px solid #eee;
  border-top-color: #1989fa;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}
@keyframes spin {
  to { transform: rotate(360deg); }
}

.unsolicited__meal-info {
  background: #fff;
  margin: 12px;
  border-radius: 8px;
  padding: 16px;
}
.unsolicited__meal-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 0;
}
.unsolicited__meal-row + .unsolicited__meal-row {
  border-top: 1px solid #f0f0f0;
}
.unsolicited__meal-label {
  color: #666;
  font-size: 14px;
}
.unsolicited__meal-value {
  color: #1989fa;
  font-weight: 600;
  font-size: 16px;
}

.unsolicited__notice {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0 12px;
  padding: 10px 12px;
  background: #fff7e6;
  border-radius: 6px;
  color: #fa8c16;
  font-size: 13px;
}

/* 手续费提示条(醒目,红橙色系) */
.unsolicited__fee-notice {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 8px 12px 0;
  padding: 10px 12px;
  background: #fee2e2;
  border-radius: 6px;
  color: #ef4444;
  font-size: 13px;
  font-weight: 600;
}

.unsolicited__section-title {
  font-size: 15px;
  font-weight: 600;
  margin: 16px 12px 8px;
  color: #333;
}

.unsolicited__dish-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 0 12px;
}
.unsolicited__dish {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 12px 14px;
  background: #fff;
  border: 1px solid #eee;
  border-radius: 14px;
  cursor: pointer;
  transition: border-color 0.15s ease;
  min-width: 0;
}
.unsolicited__dish:active {
  opacity: 0.92;
}
.unsolicited__dish--selected {
  border: 2px solid #1989fa;
  padding: 11px 13px;
}
/* 第一行:菜名(左) + 辣度(右) */
.unsolicited__dish-top {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
  min-width: 0;
}
/* 第二行:价格(左) + 操作区(右) */
.unsolicited__dish-bottom {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-width: 0;
}
.unsolicited__dish-spice {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 2px;
  padding: 3px 6px;
  background: rgba(239, 68, 68, 0.08);
  border-radius: 8px;
  color: #ef4444;
  line-height: 1;
}
.unsolicited__dish-spice-label {
  font-size: 12px;
  font-weight: 700;
  line-height: 1;
}
.unsolicited__dish-name {
  font-size: 16px;
  font-weight: 600;
  margin: 0;
  color: #333;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  word-break: break-all;
  line-height: 1.35;
  flex: 1;
  min-width: 0;
}
.unsolicited__dish-price {
  color: #ee0a24;
  font-weight: 700;
  font-size: 16px;
  font-variant-numeric: tabular-nums;
  flex-shrink: 0;
}
.unsolicited__dish-soldout {
  flex-shrink: 0;
  font-size: 13px;
  font-weight: 600;
  color: #999;
}
.unsolicited__dish-add {
  flex-shrink: 0;
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: #1989fa;
  color: #fff;
  border: none;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0;
}
.unsolicited__dish-add:active {
  opacity: 0.85;
}
.unsolicited__dish-stepper {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 8px;
}
.unsolicited__stepper-btn {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  border: none;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0;
}
.unsolicited__stepper-btn--minus {
  background: #f0f0f0;
  color: #333;
}
.unsolicited__stepper-btn--plus {
  background: #1989fa;
  color: #fff;
}
.unsolicited__stepper-btn:active {
  opacity: 0.85;
}
.unsolicited__stepper-val {
  min-width: 20px;
  text-align: center;
  font-size: 16px;
  font-weight: 700;
  color: #333;
  font-variant-numeric: tabular-nums;
}

.unsolicited__empty {
  text-align: center;
  padding: 80px 0;
  color: #999;
}
.unsolicited__empty-icon {
  font-size: 48px;
  margin-bottom: 12px;
}

.unsolicited__footer {
  position: fixed;
  bottom: 0;
  left: 0;
  right: 0;
  display: flex;
  align-items: center;
  background: #fff;
  padding: 10px 16px;
  box-shadow: 0 -2px 8px rgba(0, 0, 0, 0.06);
  z-index: 20;
}
.unsolicited__cart-btn {
  position: relative;
  width: 48px;
  height: 48px;
  border-radius: 50%;
  background: #1989fa;
  border: none;
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
}
.unsolicited__cart-badge {
  position: absolute;
  top: -4px;
  right: -4px;
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  background: #ee0a24;
  color: #fff;
  border-radius: 9px;
  font-size: 11px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 600;
}
.unsolicited__footer-info {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-left: 12px;
}
.unsolicited__footer-amount {
  display: flex;
  align-items: baseline;
  gap: 6px;
  min-width: 0;
}
.unsolicited__footer-total {
  font-size: 18px;
  font-weight: 700;
  color: #ee0a24;
}
.unsolicited__footer-fee {
  font-size: 12px;
  color: #999;
}
.unsolicited__submit-btn {
  background: #1989fa;
  color: #fff;
  border: none;
  padding: 10px 24px;
  border-radius: 999px;
  font-size: 15px;
  font-weight: 600;
  cursor: pointer;
}
.unsolicited__submit-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.unsolicited__cart-sheet {
  padding: 16px;
}
.unsolicited__cart-empty {
  text-align: center;
  color: #999;
  padding: 40px 0;
}
.unsolicited__cart-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.unsolicited__cart-item {
  display: flex;
  align-items: center;
  gap: 12px;
}
.unsolicited__cart-name {
  flex: 1;
  font-size: 14px;
}
.unsolicited__cart-qty {
  color: #666;
  font-size: 14px;
}
.unsolicited__cart-subtotal {
  color: #ee0a24;
  font-weight: 600;
  min-width: 60px;
  text-align: right;
}
.unsolicited__cart-total-row {
  display: flex;
  justify-content: space-between;
  padding-top: 12px;
  border-top: 1px solid #eee;
  font-size: 16px;
  font-weight: 600;
}
.unsolicited__cart-total-num {
  color: #ee0a24;
}
</style>
