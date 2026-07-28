<script setup lang="ts">
import { ref, computed, onMounted, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { showSuccessToast, showFailToast, showToast, showConfirmDialog } from 'vant'
import EmptyState from '@/components/EmptyState.vue'
import { useAuthStore } from '@/stores/auth'
import { getMyOrders, cancelOrder } from '@/api/order'
import {
  formatMealType,
  formatMealTypeShort,
  formatOrderStatus,
  formatMoney,
  formatDate,
} from '@/composables/useFormat'
import { mealBadgeStyle } from '@/composables/useMealConfig'
import { toChineseDate } from '@/utils/date'
import type { Order } from '@/api/types'

defineOptions({ name: 'Orders' })

const router = useRouter()
const authStore = useAuthStore()

const orders = ref<Order[]>([])
const loading = ref(false)
const refreshing = ref(false)
const loaded = ref(false)
const activeStatus = ref<number>(0)
const cancelling = ref(false)

/** 滚动容器引用(用于监听滚动,控制下拉刷新启用/禁用) */
const scrollContainerRef = ref<HTMLElement | null>(null)

/** 滚动事件处理:列表在顶部时才允许下拉刷新 */
const handleScroll = (e: Event): void => {
  const target = e.target as HTMLElement
  canRefresh.value = target.scrollTop <= 0
}

/** 挂载后默认允许下拉刷新(初始在顶部) */
onMounted(() => {
  canRefresh.value = true
})

const statusTabs = [
  { value: 0, label: '全部' },
  { value: 1, label: '待取餐' },
  { value: 2, label: '已完成' },
  { value: 3, label: '已取消' },
]

/** 按状态筛选后的订单 */
const filteredOrders = computed(() => {
  if (activeStatus.value === 0) return orders.value
  return orders.value.filter((o) => o.status === activeStatus.value)
})

/** 日期分组标签:今天/昨天/中文完整日期(复用 @/utils/date 的 toChineseDate) */
const dateLabelOf = (dateStr: string): string => {
  const today = formatDate(new Date())
  const yesterday = formatDate(new Date(Date.now() - 86400000))
  const tomorrow = formatDate(new Date(Date.now() + 86400000))
  if (dateStr === today) return `今天 · ${toChineseDate(dateStr)}`
  if (dateStr === yesterday) return `昨天 · ${toChineseDate(dateStr)}`
  if (dateStr === tomorrow) return `明天 · ${toChineseDate(dateStr)}`
  return toChineseDate(dateStr)
}

/** 平铺的菜品行(同一餐别下所有订单的菜品合并展示) */
interface DishRow {
  key: string
  orderId: number
  name: string
  quantity: number
  price: number
  status: number
  pickupCode?: string
}

/** 餐别分组(同一餐别的所有订单合并展示) */
interface MealGroup {
  mealType: number
  mealName: string // 单字:早/中/晚
  mealFullName: string // 全称:早餐/午餐/晚餐
  rows: DishRow[]
  /** 所有菜品均为待取餐(可整体取消) */
  cancellable: boolean
  /** 餐别小计 */
  subtotal: number
}

/** 日期分组(含多个餐别,只展示有订单的餐别) */
interface DateGroup {
  label: string
  dateStr: string
  meals: MealGroup[]
}

/**
 * 按订餐日期(order.date)正序分组,每个日期内按餐别(早→中→晚)分组,
 * 同一餐别下所有订单的菜品合并平铺展示。
 *
 * 排序规则:
 * - 日期正序:过去日期在上方,今天在中间,未来日期在下方。
 * - 今天即使无订单也插入一个空分组(占位锚点),保证"今天"始终是可滚动定位点。
 *   用户进入页面看到的是今天;过去日期隐藏在上方,需上拉才能看到。
 */
const groupedOrders = computed<DateGroup[]>(() => {
  // 1. 按订餐日期分组
  const dateMap = new Map<string, Order[]>()
  for (const o of filteredOrders.value) {
    // 优先用 order.date(订餐日期),没有时回退到 createdAt(下单时间)
    const dateStr = (o.date && formatDate(o.date)) || formatDate(o.createdAt) || '未知日期'
    if (!dateMap.has(dateStr)) dateMap.set(dateStr, [])
    dateMap.get(dateStr)!.push(o)
  }

  // 2. 今天即使无订单也加入占位(空数组),保证有今天的锚点 section
  const today = formatDate(new Date())
  if (!dateMap.has(today)) {
    dateMap.set(today, [])
  }

  // 3. 日期正序:过去→今天→未来(过去日期在上方,今天在中间,未来在下)
  const sortedDates = Array.from(dateMap.keys()).sort((a, b) => (a < b ? -1 : a > b ? 1 : 0))

  // 4. 每个日期内按餐别分组,菜品平铺
  const groups: DateGroup[] = []
  for (const dateStr of sortedDates) {
    const list = dateMap.get(dateStr)!
    const mealMap = new Map<number, Order[]>()
    for (const o of list) {
      if (!mealMap.has(o.mealType)) mealMap.set(o.mealType, [])
      mealMap.get(o.mealType)!.push(o)
    }

    const meals: MealGroup[] = []
    // 餐别按 早→中→晚 排序
    const mealTypes = Array.from(mealMap.keys()).sort((a, b) => a - b)
    for (const mt of mealTypes) {
      const mealOrders = mealMap.get(mt)!
      // 同一餐别订单按 createdAt 倒序
      mealOrders.sort((a, b) => {
        const ta = new Date(a.createdAt || 0).getTime()
        const tb = new Date(b.createdAt || 0).getTime()
        return tb - ta
      })

      // 平铺菜品行
      const rows: DishRow[] = []
      let subtotal = 0
      for (const o of mealOrders) {
        const items = o.items || []
        if (items.length > 0) {
          for (let idx = 0; idx < items.length; idx++) {
            const it = items[idx]
            rows.push({
              key: `${o.id}-${it.id ?? idx}`,
              orderId: o.id,
              name: it.dishName || '未知菜品',
              quantity: it.quantity,
              price: it.price,
              status: o.status,
              pickupCode: o.pickupCode,
            })
            subtotal += it.price * it.quantity
          }
        } else {
          // 无 items 回退为单行摘要
          rows.push({
            key: `${o.id}-summary`,
            orderId: o.id,
            name: formatMealType(o.mealType) + ' 订单',
            quantity: 1,
            price: o.totalAmount,
            status: o.status,
            pickupCode: o.pickupCode,
          })
          subtotal += o.totalAmount
        }
      }

      const allPending = mealOrders.every((o) => o.status === 1)
      meals.push({
        mealType: mt,
        mealName: formatMealTypeShort(mt),
        mealFullName: formatMealType(mt),
        rows,
        cancellable: allPending,
        subtotal: Math.round(subtotal * 100) / 100,
      })
    }

    groups.push({
      label: dateLabelOf(dateStr),
      dateStr,
      meals,
    })
  }
  return groups
})

/** 初始是否允许下拉刷新(列表不在顶部时禁止,避免误触发) */
const canRefresh = ref(false)

/** 餐别中可复制取餐码的订单(按 orderId 去重,一个订单只显示一个取餐码) */
const pickupOrders = (meal: MealGroup): DishRow[] => {
  const seen = new Set<number>()
  const result: DishRow[] = []
  for (const r of meal.rows) {
    if (r.status === 1 && r.pickupCode && !seen.has(r.orderId)) {
      seen.add(r.orderId)
      result.push(r)
    }
  }
  return result
}

/**
 * 滚动到今天日期所在的分组位置。
 * 用于:每次进入页面 / 下拉刷新后,确保今天订单首先可见。
 * 由于 groupedOrders 已保证今天始终有占位 section(即使无订单),
 * 这里直接定位今天的 section,过去日期自然隐藏在上方需要上拉才能看到。
 */
const scrollToToday = async (): Promise<void> => {
  await nextTick()
  const container = scrollContainerRef.value
  if (!container) return
  const today = formatDate(new Date())
  const target = container.querySelector<HTMLElement>(`[data-date="${today}"]`)
  if (!target) {
    container.scrollTo({ top: 0, behavior: 'auto' })
    return
  }
  // 用 getBoundingClientRect 计算相对滚动容器的偏移,避免 offsetParent 不一致
  // 偏移 0:让今天的 section 顶部完全贴容器顶部,sticky 日期标题正好吸顶显示"今天"
  const containerRect = container.getBoundingClientRect()
  const targetRect = target.getBoundingClientRect()
  const offset = targetRect.top - containerRect.top + container.scrollTop
  container.scrollTo({ top: Math.max(0, offset), behavior: 'auto' })
}

/** 加载订单列表(不自动滚动,由调用方决定是否滚动到今天) */
const loadOrders = async (): Promise<void> => {
  if (!authStore.employeeId) {
    loaded.value = true
    return
  }
  loading.value = true
  try {
    // 后端在无订单时可能返回 null,兜底为空数组避免后续 .filter / .sort 抛错
    orders.value = (await getMyOrders(authStore.employeeId)) ?? []
  } catch {
    /* 拦截器已 toast 具体错误信息 */
  } finally {
    loading.value = false
    loaded.value = true
  }
}

/** 下拉刷新:刷新后滚动到今天,确保今天订单首先可见 */
const onRefresh = async (): Promise<void> => {
  try {
    await loadOrders()
  } finally {
    refreshing.value = false
    // 等 van-pull-refresh 收起刷新指示器后再滚动,避免 translate 影响位置计算
    await scrollToToday()
  }
}

/** Tab 切换:列表由 computed 自动筛选,无需重新请求;切换后滚到今天保证今天在第一屏 */
const selectTab = (value: number): void => {
  activeStatus.value = value
  // groupedOrders 是 computed,nextTick 后 DOM 才更新;scrollToToday 内部已 await nextTick
  // 但 tab 切换瞬间 activeStatus 变化,computed 重算需要一帧,这里再补一次 nextTick 更稳
  void nextTick(() => scrollToToday())
}

const goDetail = (id: number): void => {
  router.push(`/orders/${id}`)
}

/** 复制取餐码 */
const copyCode = async (code: string, e: Event): Promise<void> => {
  e.stopPropagation()
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(code)
    } else {
      const textarea = document.createElement('textarea')
      textarea.value = code
      textarea.style.position = 'fixed'
      textarea.style.opacity = '0'
      document.body.appendChild(textarea)
      textarea.select()
      document.execCommand('copy')
      document.body.removeChild(textarea)
    }
    showSuccessToast('取餐码已复制')
  } catch {
    showToast('复制失败,请手动复制')
  }
}

/** 取消整个餐别的订单(二次确认,取消后金额退回余额) */
const onCancelMeal = (meal: MealGroup): void => {
  if (!meal.cancellable || meal.rows.length === 0 || cancelling.value) return
  // 同一餐别可能有多个订单,统计 orderId 去重
  const orderIds = Array.from(new Set(meal.rows.map((r) => r.orderId)))
  const count = orderIds.length
  const message =
    count === 1
      ? '确定要取消该订单吗?取消后金额将退回余额。'
      : `确定要取消该餐别的 ${count} 个订单吗?取消后金额将退回余额。`
  showConfirmDialog({
    title: '取消订单',
    message,
    confirmButtonText: '确定取消',
    cancelButtonText: '再想想',
    confirmButtonColor: '#ee0a24',
  })
    .then(async () => {
      cancelling.value = true
      try {
        const results = await Promise.allSettled(orderIds.map((id) => cancelOrder(id)))
        const succeeded = results.filter((r) => r.status === 'fulfilled').length
        const failed = results.length - succeeded
        if (failed === 0) {
          showSuccessToast(`成功取消 ${succeeded} 个订单`)
        } else {
          showFailToast(`${succeeded} 个成功,${failed} 个失败`)
        }
        await authStore.refreshEmployee()
        await loadOrders()
      } catch {
        /* 拦截器已提示 */
      } finally {
        cancelling.value = false
      }
    })
    .catch(() => {
      /* 用户取消 */
    })
}

// ============ 生命周期 ============
// Orders 未启用 keep-alive,每次进入都是全新挂载,保证每次进入都重新加载 + 滚到今天
onMounted(() => {
  // 入口:加载订单 → 滚到今天(过去日期在上方隐藏,今天在第一屏可见)
  loadOrders().then(() => scrollToToday())
})
</script>

<template>
  <div class="orders-page">
    <!-- 标题 -->
    <h1 class="orders-page__title">我的订单</h1>

    <!-- 状态 Tab(文字 + 下划线) -->
    <nav class="orders-page__tabs">
      <button
        v-for="t in statusTabs"
        :key="t.value"
        type="button"
        class="orders-page__tab"
        :class="{ 'orders-page__tab--active': activeStatus === t.value }"
        @click="selectTab(t.value)"
      >
        {{ t.label }}
      </button>
    </nav>

    <!-- 下拉刷新 + 列表(列表不在顶部时禁用下拉刷新,避免误触发) -->
    <van-pull-refresh
      v-model="refreshing"
      :disabled="!canRefresh"
      @refresh="onRefresh"
    >
      <div
        class="orders-page__scroll"
        ref="scrollContainerRef"
        @scroll.passive="handleScroll"
      >
      <!-- 加载中 -->
      <div v-if="loading && !loaded" class="orders-page__loading">
        <van-loading size="24px">加载中...</van-loading>
      </div>

      <!-- 空列表 -->
      <EmptyState v-else-if="groupedOrders.length === 0" text="暂无订单" />

      <!-- 订单列表(按日期 + 餐别分组,菜品平铺) -->
      <div v-else class="orders-page__list">
        <section
          v-for="group in groupedOrders"
          :key="group.dateStr"
          class="orders-page__group"
          :data-date="group.dateStr"
        >
          <!-- 日期标题(完整中文日期,sticky 吸顶) -->
          <div class="orders-page__date-header">{{ group.label }}</div>

          <!-- 当日无订单占位(仅今天会渲染,过去日期无订单时不会进入 groupedOrders) -->
          <div v-if="group.meals.length === 0" class="orders-page__empty-day">
            <span>今日暂无订单</span>
          </div>

          <!-- 餐别卡片(只展示有订单的餐别) -->
          <article
            v-for="meal in group.meals"
            :key="meal.mealType"
            class="orders-page__meal"
            :class="{
              'orders-page__meal--pending': meal.cancellable,
              'orders-page__meal--done': !meal.cancellable,
            }"
          >
            <!-- 餐别标题(单字 badge 按餐别配色 + 全称 + 小计) -->
            <div class="orders-page__meal-header">
              <span class="orders-page__meal-badge" :style="mealBadgeStyle(meal.mealType)">{{ meal.mealName }}</span>
              <span class="orders-page__meal-fullname">{{ meal.mealFullName }}</span>
              <span class="orders-page__meal-subtotal">¥{{ formatMoney(meal.subtotal) }}</span>
            </div>

            <!-- 菜品行(平铺,每行一道菜) -->
            <div
              v-for="row in meal.rows"
              :key="row.key"
              class="orders-page__dish-row"
              @click="goDetail(row.orderId)"
            >
              <span class="orders-page__dish-name">{{ row.name }}</span>
              <span class="orders-page__dish-qty">x{{ row.quantity }}</span>
              <span class="orders-page__dish-price">¥{{ formatMoney(row.price * row.quantity) }}</span>
              <span
                class="orders-page__status-tag"
                :class="{
                  'orders-page__status-tag--accent': row.status === 1,
                  'orders-page__status-tag--primary': row.status === 2,
                  'orders-page__status-tag--muted': row.status === 3,
                }"
              >{{ formatOrderStatus(row.status) }}</span>
            </div>

            <!-- 取餐码(待取餐且存在 pickupCode) -->
            <div
              v-if="meal.cancellable && pickupOrders(meal).length > 0"
              class="orders-page__pickup-row"
            >
              <button
                v-for="(row, idx) in pickupOrders(meal)"
                :key="`${row.key}-${idx}`"
                type="button"
                class="orders-page__pickup-pill"
                @click.stop="row.pickupCode && copyCode(row.pickupCode, $event)"
              >
                取餐码 {{ row.pickupCode }}
              </button>
            </div>

            <!-- 取消按钮(仅待取餐,位于卡片右下角) -->
            <div v-if="meal.cancellable" class="orders-page__meal-foot">
              <button
                type="button"
                class="orders-page__cancel-btn"
                :disabled="cancelling"
                @click.stop="onCancelMeal(meal)"
              >取消订单</button>
            </div>
          </article>
        </section>
      </div>
      </div>
    </van-pull-refresh>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables' as *;

.orders-page {
  // 固定高度 + overflow hidden:让自身不滚动,把滚动职责交给 .orders-page__scroll
  // 否则 van-pull-refresh 不是 flex 容器,内部 .orders-page__scroll 的 flex:1 无效,
  // 真正滚动的是 window,scrollContainerRef.scrollTo 失效,scrollToToday 不起作用。
  height: 100vh;
  height: 100dvh; // 移动端动态视口高度,避免 iOS 地址栏伸缩抖动
  background: $brand-card;
  display: flex;
  flex-direction: column;
  overflow: hidden;

  // 让 van-pull-refresh 占满剩余高度(除 title/tabs 外),让 flex 链贯通到滚动容器
  :deep(.van-pull-refresh) {
    flex: 1;
    min-height: 0;
    display: flex;
    flex-direction: column;
  }

  :deep(.van-pull-refresh__track) {
    flex: 1;
    min-height: 0;
    display: flex;
    flex-direction: column;
  }

  &__title {
    margin: 0;
    padding: 16px 16px 12px;
    font-size: 18px;
    font-weight: 700;
    color: $brand-foreground;
    flex-shrink: 0;
  }

  &__tabs {
    display: flex;
    gap: 20px;
    padding: 0 16px 12px;
    flex-shrink: 0;
  }

  &__tab {
    position: relative;
    padding: 4px 0 8px;
    font-size: 14px;
    color: $brand-muted-foreground;
    background: transparent;
    border: none;
    cursor: pointer;
    transition: color 0.2s;

    &--active {
      color: $brand-primary;
      font-weight: 600;

      &::after {
        content: '';
        position: absolute;
        bottom: 0;
        left: 50%;
        transform: translateX(-50%);
        width: 20px;
        height: 3px;
        border-radius: 2px;
        background: $brand-primary;
      }
    }
  }

  /* 滚动容器(覆盖 van-pull-refresh 内部,用于监听滚动控制下拉刷新) */
  &__scroll {
    flex: 1;
    overflow-y: auto;
    -webkit-overflow-scrolling: touch;
    min-height: 0;
    // 给 fixed TabBar 让位,避免最后一条订单被盖住(原架构是 window 滚动 + app-container padding)
    padding-bottom: calc(64px + env(safe-area-inset-bottom) + 12px);
    // 防止滚动链传到父容器,避免误触页面级滚动
    overscroll-behavior: contain;
  }

  &__loading {
    padding: 48px 0;
    display: flex;
    justify-content: center;
  }

  &__list {
    // 底部 padding 由 .orders-page__scroll 提供(给 TabBar 让位),这里只管左右
    padding: 0 16px;
  }

  &__group {
    margin-bottom: 16px;
  }

  /* 当日无订单占位(仅今天会渲染) */
  &__empty-day {
    padding: 24px 16px;
    text-align: center;
    color: $brand-muted-foreground;
    font-size: 13px;
    background: $brand-card;
    border: 1px dashed $brand-border;
    border-radius: 12px;
  }

  /* 日期标题:完整中文日期,sticky 吸顶 */
  &__date-header {
    position: sticky;
    top: 0;
    z-index: 10;
    padding: 10px 12px;
    margin: 0 -12px 12px;
    background: $brand-card;
    font-size: 14px;
    font-weight: 700;
    color: $brand-foreground;
    border-bottom: 1px solid $brand-border;
  }

  &__meal {
    margin-bottom: 12px;
    border-radius: 16px;
    overflow: hidden;
    background: $brand-card;
    border: 1px solid $brand-border;

    &--pending {
      border-left: 3px solid $brand-primary;
    }

    &--done {
      border-left: 3px solid $brand-muted-foreground;
    }
  }

  /* 餐别标题:单字 badge + 全称 + 小计 */
  &__meal-header {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 12px 16px 6px;
  }

  &__meal-badge {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 24px;
    height: 24px;
    font-size: 14px;
    font-weight: 700;
    border-radius: 50%;
    /* background / color / border-color 由内联样式动态控制(早餐橙/午餐蓝/晚餐紫) */
  }

  &__meal-fullname {
    flex: 1;
    font-size: 14px;
    font-weight: 600;
    color: $brand-foreground;
  }

  &__meal-subtotal {
    font-size: 13px;
    font-weight: 600;
    color: $brand-primary;
  }

  &__dish-row {
    display: flex;
    align-items: center;
    padding: 8px 16px;
    gap: 10px;
    cursor: pointer;

    &:active {
      background: rgba(0, 0, 0, 0.03);
    }
  }

  &__dish-name {
    flex: 1;
    font-size: 14px;
    color: $brand-card-foreground;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__dish-qty {
    font-size: 12px;
    color: $brand-muted-foreground;
    flex-shrink: 0;
  }

  &__dish-price {
    font-size: 13px;
    font-weight: 500;
    color: $brand-foreground;
    flex-shrink: 0;
    min-width: 60px;
    text-align: right;
  }

  &__status-tag {
    font-size: 10px;
    padding: 2px 8px;
    border-radius: 999px;
    font-weight: 500;
    line-height: 1.4;
    white-space: nowrap;
    flex-shrink: 0;

    &--accent {
      background: $brand-accent;
      color: $brand-accent-foreground;
    }

    &--primary {
      color: $brand-primary;
      background: rgba(0, 101, 253, 0.08);
    }

    &--muted {
      color: $brand-muted-foreground;
      background: transparent;
    }
  }

  &__pickup-row {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    padding: 4px 16px 8px;
  }

  &__pickup-pill {
    font-size: 12px;
    padding: 4px 10px;
    border-radius: 999px;
    border: 1px dashed $brand-primary;
    background: rgba(0, 101, 253, 0.04);
    color: $brand-primary;
    cursor: pointer;

    &:active {
      background: rgba(0, 101, 253, 0.1);
    }
  }

  &__meal-foot {
    padding: 0 16px 12px;
    display: flex;
    justify-content: flex-end;
  }

  &__cancel-btn {
    font-size: 12px;
    font-weight: 500;
    padding: 4px 12px;
    border-radius: 999px;
    border: 1px solid $brand-destructive;
    color: $brand-destructive;
    background: transparent;
    cursor: pointer;

    &:active {
      background: rgba(239, 68, 68, 0.06);
    }

    &:disabled {
      opacity: 0.5;
    }
  }
}
</style>
