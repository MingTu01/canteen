<script setup lang="ts">
import { ref, computed, onMounted, nextTick, watch } from 'vue'
import { useRouter } from 'vue-router'
import { showSuccessToast, showFailToast, showToast, showConfirmDialog } from 'vant'
import { Popup as VanPopup } from 'vant'
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
import { mealBadgeStyle, mealDotColor } from '@/composables/useMealConfig'
import { useOrderConfig } from '@/composables/useOrderConfig'
import {
  formatDateStr,
  numericDateLabel,
  relativeDateLabel,
  weekdayLabel,
  toChineseDate,
  compareDate,
  addDays,
} from '@/utils/date'
import type { Order } from '@/api/types'

defineOptions({ name: 'Orders' })

const router = useRouter()
const authStore = useAuthStore()
const { loadConfig, isCancellableByDeadline } = useOrderConfig()

const orders = ref<Order[]>([])
const loading = ref(false)
const refreshing = ref(false)
const loaded = ref(false)
const activeStatus = ref<number>(0)
const cancelling = ref(false)

/** 右侧内容区滚动容器引用(用于 scroll 事件监听 + 滚动控制) */
const contentRef = ref<HTMLElement | null>(null)
/** 每个日期 section 的 DOM 引用,用于滚动定位 + 可视检测 */
const daySectionRefs: Record<string, HTMLElement | null> = {}
/** 当前可视日期(滚动时动态更新,用于左侧栏高亮 + sticky 日期指示器) */
const visibleDate = ref<string>('')
/** scroll 事件节流时间戳 */
let lastScrollCalcTime = 0
/** 点击左侧日期栏跳转时屏蔽可视日期更新,避免跳动 */
let isProgrammaticScroll = false
/** 初始是否允许下拉刷新(内容区不在顶部时禁止,避免误触发) */
const canRefresh = ref(false)

const statusTabs = [
  { value: 0, label: '全部' },
  { value: 1, label: '待取餐' },
  { value: 2, label: '已完成' },
  { value: 3, label: '已取消' },
  { value: 4, label: '未就餐' },
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

  // 3. 日期正序:过去→今天→未来
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
      // 还需检查是否在取消截止时间内
      const deadlineCancellable = isCancellableByDeadline(dateStr, new Date())
      meals.push({
        mealType: mt,
        mealName: formatMealTypeShort(mt),
        mealFullName: formatMealType(mt),
        rows,
        cancellable: allPending && deadlineCancellable,
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

// ============ 左侧日期竖列(与订餐页一致:日期 + 周几 + 三餐餐别圆点) ============
interface DateItem {
  date: string
  dateLabel: string // "7月14日"
  weekday: string // "周一"
  isToday: boolean
  /** 该日期已下单的餐别集合(用于渲染三餐色圆点) */
  mealTypes: number[]
}

/** 三餐固定顺序(早/中/晚),用于日期竖列圆点固定位置渲染 */
const MEAL_TYPE_ORDER: number[] = [1, 2, 3]

/** 将日期分组转为左侧栏条目 */
const toDateItem = (group: DateGroup): DateItem => ({
  date: group.dateStr,
  dateLabel: numericDateLabel(group.dateStr),
  weekday: weekdayLabel(group.dateStr),
  isToday: group.dateStr === formatDateStr(new Date()),
  mealTypes: group.meals.map((m) => m.mealType),
})

/** 左侧栏当前展示的最早日期(默认今天 → 只显示"今天+未来");下拉加载更早历史时前移 */
const earliestShownDate = ref(formatDateStr(new Date()))
/** 是否正在加载历史(防止重复触发) */
const loadingHistory = ref(false)
/** 是否还有比当前最早展示日期更早的历史可加载 */
const hasMoreHistory = computed(() =>
  groupedOrders.value.some(
    (g) => g.meals.length > 0 && compareDate(g.dateStr, earliestShownDate.value) < 0,
  ),
)

/**
 * 左侧日期栏:默认只显示"今天+未来",今天固定为列表顶端首项(时间正序)。
 * 历史日期(今天之前)默认隐藏,手指下拉/滚动到顶时按周加载,出现在今天上方。
 * 无订单的日期不显示(不循环补空)。
 */
const dateList = computed<DateItem[]>(() => {
  const today = formatDateStr(new Date())

  // 今天及未来(>= 最早展示日期)按时间正序,今天为列表顶端
  const items = groupedOrders.value
    .filter((g) => compareDate(g.dateStr, earliestShownDate.value) >= 0)
    .map(toDateItem)
    .sort((a, b) => compareDate(a.date, b.date))

  // 今天即使无订单也作为锚点,保证"今天"始终是列表顶端首项
  if (!items.some((i) => i.date === today)) {
    items.push({
      date: today,
      dateLabel: numericDateLabel(today),
      weekday: weekdayLabel(today),
      isToday: true,
      mealTypes: [],
    })
    items.sort((a, b) => compareDate(a.date, b.date))
  }

  return items
})

/** 下拉/滚动到顶:把更早一周的历史日期加载出来,插入到今天上方 */
const loadMoreHistory = (): void => {
  if (loadingHistory.value || !hasMoreHistory.value) return
  loadingHistory.value = true
  try {
    const cutoff = addDays(earliestShownDate.value, -7)
    const older = groupedOrders.value
      .filter(
        (g) => g.meals.length > 0 && compareDate(g.dateStr, earliestShownDate.value) < 0,
      )
      .map((g) => g.dateStr)
      .sort((a, b) => compareDate(a, b)) // 正序:最早在前
    const batch = older.filter((d) => compareDate(d, cutoff) >= 0)
    earliestShownDate.value = (batch.length > 0 ? batch : older)[0]
  } finally {
    loadingHistory.value = false
  }
}

/** 侧栏滚动到顶时加载更早历史(桌面/滚动场景) */
const handleSidebarScroll = (e: Event): void => {
  const el = e.target as HTMLElement
  if (el.scrollTop <= 0) loadMoreHistory()
}

/** 手指下拉到顶时加载更早历史(移动端触控场景) */
const sidebarTouchStartY = ref(0)
const onSidebarTouchStart = (e: TouchEvent): void => {
  sidebarTouchStartY.value = e.touches[0].clientY
}
const onSidebarTouchMove = (e: TouchEvent): void => {
  const sidebar = sidebarRef.value
  if (!sidebar || sidebar.scrollTop > 0) return
  const dy = e.touches[0].clientY - sidebarTouchStartY.value
  if (dy > 30) {
    loadMoreHistory()
    sidebarTouchStartY.value = e.touches[0].clientY
  }
}

/** 左侧栏滚动容器引用 */
const sidebarRef = ref<HTMLElement | null>(null)

/**
 * 联动:右侧滚动到历史日期时,左侧自动前移 earliestShownDate 以包含该日期,
 * 然后滚动左侧到对应项,实现两侧联动高亮。
 */
watch(visibleDate, (d) => {
  if (!d) return
  // 右侧滚到了比左侧最早显示日期还早的历史 → 自动展开左侧
  if (compareDate(d, earliestShownDate.value) < 0) {
    earliestShownDate.value = d
  }
  // 滚动左侧到当前高亮日期项
  void nextTick(() => {
    const el = sidebarRef.value?.querySelector<HTMLElement>(`[data-sidebar-date="${d}"]`)
    el?.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
  })
})

/** 判断指定日期+餐别是否已下单(用于圆点是否渲染) */
const isMealOrdered = (d: DateItem, mt: number): boolean => d.mealTypes.includes(mt)

/** 当前可视日期的数字部分(如 "7月26日"),用于 sticky 顶部指示器 */
const visibleDateNumber = computed(() =>
  numericDateLabel(visibleDate.value || formatDateStr(new Date())),
)

/** 当前可视日期的相对标签(今天/明天/周X),用于 sticky 顶部指示器 */
const visibleDateRelativeLabel = computed(() =>
  relativeDateLabel(visibleDate.value || formatDateStr(new Date())),
)

/** 设置日期 section 的 DOM 引用(供 v-for :ref 回调用) */
const setDaySectionRef = (date: string, el: Element | null): void => {
  daySectionRefs[date] = (el as HTMLElement) || null
}

/**
 * 点击左侧日期栏:平滑滚动到对应日期 section(不切换、不替换数据)。
 * 单页堆叠模式:所有日期已在 DOM 中,滚动即可;日期跟随滚动同步高亮。
 */
const scrollToDate = (date: string): void => {
  const el = daySectionRefs[date]
  if (!el) return
  isProgrammaticScroll = true
  el.scrollIntoView({ behavior: 'smooth', block: 'start' })
  visibleDate.value = date
  setTimeout(() => { isProgrammaticScroll = false }, 500)
}

/**
 * 右侧内容区滚动事件处理:
 * - 更新下拉刷新可用状态(仅在顶部时可下拉)
 * - 节流计算当前可视日期 → 同步左侧栏高亮 + sticky 日期指示器
 */
const handleScroll = (e: Event): void => {
  const target = e.target as HTMLElement
  canRefresh.value = target.scrollTop <= 0
  if (isProgrammaticScroll) return
  const now = Date.now()
  if (now - lastScrollCalcTime < 100) return // 100ms 节流
  lastScrollCalcTime = now
  updateVisibleDate()
}

/**
 * 计算当前可视日期(使用"触发线"算法,稳定不跳动)。
 * 触发线位于容器顶部下方 60px(sticky 日期指示器之下):
 * - 若触发线落在某 section 内 → 选定该日期
 * - 否则 → 选触发线上方最近的 section
 */
const updateVisibleDate = (): void => {
  const container = contentRef.value
  if (!container) return
  const containerTop = container.getBoundingClientRect().top
  const TRIGGER_Y = 60
  let bestDate = ''
  let bestTop = -Infinity
  for (const g of groupedOrders.value) {
    const el = daySectionRefs[g.dateStr]
    if (!el) continue
    const rect = el.getBoundingClientRect()
    const top = rect.top - containerTop
    const bottom = rect.bottom - containerTop
    if (top <= TRIGGER_Y && bottom > TRIGGER_Y) {
      bestDate = g.dateStr
      break
    }
    if (top <= TRIGGER_Y && top >= bestTop) {
      bestTop = top
      bestDate = g.dateStr
    }
  }
  if (bestDate && bestDate !== visibleDate.value) {
    visibleDate.value = bestDate
  }
}

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
 * 用于:每次进入页面 / 下拉刷新 / 切换 Tab 后,确保今天订单首先可见。
 */
const scrollToToday = async (): Promise<void> => {
  await nextTick()
  const container = contentRef.value
  if (!container) return
  const today = formatDate(new Date())
  const target = container.querySelector<HTMLElement>(`[data-date="${today}"]`)
  if (!target) {
    container.scrollTo({ top: 0, behavior: 'auto' })
    return
  }
  const containerRect = container.getBoundingClientRect()
  const targetRect = target.getBoundingClientRect()
  const offset = targetRect.top - containerRect.top + container.scrollTop
  container.scrollTo({ top: Math.max(0, offset), behavior: 'auto' })
  visibleDate.value = today
}

/** 加载订单列表(不自动滚动,由调用方决定是否滚动到今天) */
const loadOrders = async (): Promise<void> => {
  if (!authStore.isLoggedIn) {
    loaded.value = true
    return
  }
  loading.value = true
  try {
    // 后端在无订单时可能返回 null,兜底为空数组
    orders.value = (await getMyOrders()) ?? []
  } catch {
    /* 拦截器已 toast */
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

/** Tab 切换:列表由 computed 自动筛选,无需重新请求;切换后滚到今天 */
const selectTab = (value: number): void => {
  activeStatus.value = value
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

// ============ 日历选择器 ============
/** 日历弹窗显示状态 */
const showCalendar = ref(false)
/** 所有有订单的日期集合(用于日历高亮可点击) */
const orderDatesSet = computed(
  () => new Set(groupedOrders.value.filter((g) => g.meals.length > 0).map((g) => g.dateStr)),
)
/** 日历当前显示的月份 yyyy-MM,默认跟随当前可视日期 */
const calendarMonth = ref(formatDateStr(new Date()).slice(0, 7))
/** 日历月份标签 */
const calendarMonthLabel = computed(() => {
  const [y, m] = calendarMonth.value.split('-').map(Number)
  return `${y}年${m}月`
})
/** 日历表头 */
const WEEK_HEADER = ['日', '一', '二', '三', '四', '五', '六']
/** 日历网格单元格 */
interface CalendarCell {
  key: string
  day: number
  hasOrder: boolean
  isToday: boolean
  isSelected: boolean
  isPlaceholder: boolean
}
/** 日历当月网格(含前后占位补齐7列) */
const calendarGrid = computed<CalendarCell[]>(() => {
  const [y, m] = calendarMonth.value.split('-').map(Number)
  const firstDay = new Date(y, m - 1, 1)
  const leadDays = firstDay.getDay()
  const daysInMonth = new Date(y, m, 0).getDate()
  const today = formatDateStr(new Date())
  const cells: CalendarCell[] = []
  for (let i = 0; i < leadDays; i++) {
    cells.push({ key: `ph-${i}`, day: 0, hasOrder: false, isToday: false, isSelected: false, isPlaceholder: true })
  }
  for (let day = 1; day <= daysInMonth; day++) {
    const key = `${y}-${String(m).padStart(2, '0')}-${String(day).padStart(2, '0')}`
    cells.push({
      key,
      day,
      hasOrder: orderDatesSet.value.has(key),
      isToday: key === today,
      isSelected: key === visibleDate.value,
      isPlaceholder: false,
    })
  }
  while (cells.length % 7 !== 0) {
    cells.push({ key: `ph-end-${cells.length}`, day: 0, hasOrder: false, isToday: false, isSelected: false, isPlaceholder: true })
  }
  return cells
})
/** 日历上一月 */
const calendarPrevMonth = (): void => {
  const [y, m] = calendarMonth.value.split('-').map(Number)
  const d = new Date(y, m - 2, 1)
  calendarMonth.value = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
}
/** 日历下一月 */
const calendarNextMonth = (): void => {
  const [y, m] = calendarMonth.value.split('-').map(Number)
  const d = new Date(y, m, 1)
  calendarMonth.value = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
}
/** 打开日历时,月份跟随当前可视日期 */
const openCalendar = (): void => {
  if (visibleDate.value) calendarMonth.value = visibleDate.value.slice(0, 7)
  showCalendar.value = true
}
/** 选择日历日期:滚动到该日期并关闭弹窗 */
const onCalendarSelect = (key: string): void => {
  if (!orderDatesSet.value.has(key)) return
  showCalendar.value = false
  // 如果选的是比左侧最早显示日期还早的历史,前移以包含它
  if (compareDate(key, earliestShownDate.value) < 0) {
    earliestShownDate.value = key
  }
  void nextTick(() => scrollToDate(key))
}

// ============ 生命周期 ============
// Orders 未启用 keep-alive,每次进入都是全新挂载,保证每次进入都重新加载 + 滚到今天
onMounted(() => {
  canRefresh.value = true
  // 加载后端订餐配置(按门店,取消截止时间等),供 isCancellableByDeadline 使用
  loadConfig(authStore.storeId)
  // 入口:加载订单后,右侧内容区滚到今天(左侧栏默认即"今天+未来",今天置顶)
  loadOrders().then(async () => {
    await scrollToToday()
  })
})
</script>

<template>
  <div class="orders-page">
    <!-- 状态 Tab + 日历按钮 -->
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
      <!-- 日历选择按钮 -->
      <button
        type="button"
        class="orders-page__cal-btn"
        aria-label="选择日期"
        @click="openCalendar"
      >
        <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <rect x="3" y="4" width="18" height="18" rx="2" ry="2" />
          <line x1="16" y1="2" x2="16" y2="6" />
          <line x1="8" y1="2" x2="8" y2="6" />
          <line x1="3" y1="10" x2="21" y2="10" />
        </svg>
      </button>
    </nav>

    <!-- 日历弹窗(居中) -->
    <VanPopup
      v-model:show="showCalendar"
      position="center"
      round
      teleport="body"
      :style="{ width: '90%', maxWidth: '400px' }"
    >
      <div class="calendar">
        <!-- 月份导航 -->
        <div class="calendar__head">
          <button type="button" class="calendar__nav" aria-label="上一月" @click="calendarPrevMonth">
            <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="15 18 9 12 15 6" /></svg>
          </button>
          <span class="calendar__month">{{ calendarMonthLabel }}</span>
          <button type="button" class="calendar__nav" aria-label="下一月" @click="calendarNextMonth">
            <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="9 18 15 12 9 6" /></svg>
          </button>
        </div>
        <!-- 表头 -->
        <div class="calendar__week-header">
          <span v-for="w in WEEK_HEADER" :key="w" class="calendar__week-cell">{{ w }}</span>
        </div>
        <!-- 网格 -->
        <div class="calendar__grid">
          <button
            v-for="cell in calendarGrid"
            :key="cell.key"
            type="button"
            :disabled="cell.isPlaceholder || !cell.hasOrder"
            class="calendar__cell"
            :class="{
              'calendar__cell--placeholder': cell.isPlaceholder,
              'calendar__cell--has-order': cell.hasOrder,
              'calendar__cell--no-order': !cell.isPlaceholder && !cell.hasOrder,
              'calendar__cell--today': cell.isToday,
              'calendar__cell--selected': cell.isSelected,
            }"
            @click="onCalendarSelect(cell.key)"
          >
            <span v-if="!cell.isPlaceholder" class="calendar__day">{{ cell.day }}</span>
            <span v-if="!cell.isPlaceholder && cell.hasOrder" class="calendar__dot"></span>
          </button>
        </div>
        <!-- 底部提示 -->
        <div class="calendar__footer">
          <span>有订单的日期可点击选择</span>
        </div>
      </div>
    </VanPopup>

    <!-- 主体:左侧日期竖列 + 右侧内容区(单页堆叠,无切换动作) -->
    <div class="orders-page__main">
      <!-- 左侧日期竖列:默认今天+未来(今天顶端),历史日期今天上方默认隐藏,下拉/滚动到顶加载 -->
      <div
        ref="sidebarRef"
        class="date-sidebar"
        @scroll.passive="handleSidebarScroll"
        @touchstart.passive="onSidebarTouchStart"
        @touchmove.passive="onSidebarTouchMove"
      >
        <!-- 顶部提示:存在更早历史时下拉加载 -->
        <div v-if="hasMoreHistory" class="date-sidebar__hint">下拉加载更早</div>

        <button
          v-for="d in dateList"
          :key="d.date"
          type="button"
          class="date-sidebar__item"
          :class="{ 'date-sidebar__item--active': visibleDate === d.date }"
          :data-sidebar-date="d.date"
          @click="scrollToDate(d.date)"
        >
          <span class="date-sidebar__date">{{ d.dateLabel }}</span>
          <span class="date-sidebar__weekday">{{ d.weekday }}</span>
          <!-- "今天"标记 -->
          <span v-if="d.isToday" class="date-sidebar__today">今天</span>
          <!-- 三餐固定3个位置(早/中/晚):已订餐显色,未订餐保留占位 -->
          <div class="date-sidebar__dots">
            <template v-for="mt in MEAL_TYPE_ORDER" :key="mt">
              <span
                v-if="isMealOrdered(d, mt)"
                class="date-sidebar__dot"
                :style="{ backgroundColor: mealDotColor(mt) }"
              ></span>
              <span v-else class="date-sidebar__dot-placeholder"></span>
            </template>
          </div>
        </button>

        <!-- 空状态:暂无任何订单 -->
        <div v-if="orders.length === 0" class="date-sidebar__empty">
          暂无订餐
        </div>
      </div>

      <!-- 右侧内容区:所有日期垂直堆叠,上下滚动无切换;日期跟随滚动同步 -->
      <van-pull-refresh
        v-model="refreshing"
        :disabled="!canRefresh"
        @refresh="onRefresh"
      >
        <div
          ref="contentRef"
          class="content-area"
          @scroll.passive="handleScroll"
        >
          <!-- 顶部 sticky 日期指示器:跟随当前可视日期吸顶显示 -->
          <div v-if="dateList.length > 0" class="content-area__sticky-date">
            <span class="content-area__date-num">{{ visibleDateNumber }}</span>
            <span class="content-area__date-rel">{{ visibleDateRelativeLabel }}</span>
          </div>

          <!-- 加载中 -->
          <div v-if="loading && !loaded" class="content-area__loading">
            <van-loading size="24px">加载中...</van-loading>
          </div>

          <!-- 空列表 -->
          <EmptyState v-else-if="loaded && orders.length === 0" text="暂无订单" />

          <!-- 订单列表(按日期 + 餐别分组,菜品平铺) -->
          <div v-else class="content-area__list">
            <section
              v-for="group in groupedOrders"
              :key="group.dateStr"
              :ref="(el) => setDaySectionRef(group.dateStr, el as Element | null)"
              class="day-section"
              :class="{ 'day-section--active': visibleDate === group.dateStr }"
              :data-date="group.dateStr"
            >
              <!-- 日期标题(居中 + 胶囊包裹) -->
              <div class="day-section__header">
                <span class="day-section__header-pill">{{ group.label }}</span>
              </div>

              <!-- 当日无订单占位(仅今天会渲染) -->
              <div v-if="group.meals.length === 0" class="day-section__empty">
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

            <!-- 底部提示 -->
            <div class="content-area__end-hint">
              <span>已展示所有订单</span>
            </div>
          </div>
        </div>
      </van-pull-refresh>
    </div>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables' as *;

.orders-page {
  // 视口高度减去 TabBar(64px + 安全区),与订餐页一致,避免页面溢出出现滚动条
  height: calc(100vh - 64px - env(safe-area-inset-bottom));
  height: calc(100dvh - 64px - env(safe-area-inset-bottom)); // 移动端动态视口高度
  background: $brand-card;
  display: flex;
  flex-direction: column;
  overflow: hidden;

  &__tabs {
    display: flex;
    align-items: center;
    gap: 20px;
    padding: 12px 16px 12px;
    flex-shrink: 0;
  }

  &__cal-btn {
    margin-left: auto;
    display: flex;
    align-items: center;
    justify-content: center;
    width: 32px;
    height: 32px;
    border-radius: 50%;
    border: none;
    background: $brand-secondary;
    color: $brand-foreground;
    cursor: pointer;
    flex-shrink: 0;
    transition: background 0.2s;

    &:active {
      background: rgba(0, 101, 253, 0.1);
      color: $brand-primary;
    }
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

  /* 主体区域:左侧日期竖列 + 右侧内容 */
  &__main {
    flex: 1;
    display: flex;
    overflow: hidden;
    min-height: 0;
  }

  /* 让 van-pull-refresh 占满剩余高度,让 flex 链贯通到滚动容器 */
  :deep(.van-pull-refresh) {
    flex: 1;
    min-width: 0;
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

  /* ============ 左侧日期竖列(与订餐页一致) ============ */
  .date-sidebar {
    width: 96px;
    flex-shrink: 0;
    background: $brand-secondary;
    border-right: 1px solid $brand-border;
    overflow-y: auto;
    -webkit-overflow-scrolling: touch;
    scrollbar-width: none;
    &::-webkit-scrollbar { display: none; }

    &__item {
      position: relative;
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      gap: 5px;
      width: 100%;
      /* 固定项高:避免 MiSans 字体按需加载换排导致行高变化 → 布局漂移,
         确保"今天"项吸顶定位不受字体加载时序影响(适配稳定性) */
      height: 70px;
      padding: 0 4px;
      box-sizing: border-box;
      overflow: hidden;
      background: transparent;
      border: none;
      border-bottom: 1px solid $brand-border;
      cursor: pointer;

      &:last-child {
        border-bottom: none;
      }

      &:active {
        background: rgba(0, 0, 0, 0.04);
      }

      &--active {
        background: $brand-primary;

        .date-sidebar__date {
          color: $brand-primary-fg;
          font-weight: 500;
        }
        .date-sidebar__weekday {
          color: rgba(255, 255, 255, 0.8);
        }
        .date-sidebar__today {
          color: rgba(255, 255, 255, 0.8);
        }
      }
    }

    &__date {
      font-size: 13px;
      color: $brand-secondary-foreground;
      line-height: 1.2;
    }

    &__weekday {
      font-size: 10px;
      color: $brand-muted-foreground;
      line-height: 1.2;
    }

    &__today {
      font-size: 10px;
      color: $brand-primary;
      line-height: 1.2;
    }

    /* 三餐色竖排圆点容器:早橙/中绿/晚紫,固定3个位置 */
    &__dots {
      position: absolute;
      top: 8px;
      right: 6px;
      display: flex;
      flex-direction: column;
      gap: 2px;
    }

    &__dot {
      width: 6px;
      height: 6px;
      border-radius: 50%;
    }

    /* 未订餐的餐别占位:保留等宽等高位置 */
    &__dot-placeholder {
      width: 6px;
      height: 6px;
    }

    &__empty {
      padding: 24px 8px;
      text-align: center;
      font-size: 11px;
      color: $brand-muted-foreground;
      line-height: 1.5;
    }

    /* 顶部"下拉加载更早"提示(吸顶,不挤占今天项位置) */
    &__hint {
      position: sticky;
      top: 0;
      z-index: 2;
      padding: 6px 4px;
      text-align: center;
      font-size: 10px;
      line-height: 1.2;
      color: $brand-muted-foreground;
      background: $brand-secondary;
    }
  }

  /* ============ 右侧内容区 ============ */
  :deep(.content-area) {
    flex: 1;
    min-width: 0;
    overflow-y: auto;
    -webkit-overflow-scrolling: touch;
    min-height: 0;
    // 给固定 TabBar 让位,避免最后一条被盖住
    padding-bottom: calc(64px + env(safe-area-inset-bottom) + 12px);
    overscroll-behavior: contain;
  }

  /* 顶部 sticky 日期指示器:跟随当前可视日期,吸顶显示 */
  :deep(.content-area__sticky-date) {
    position: sticky;
    top: 0;
    z-index: 10;
    display: flex;
    justify-content: center;
    align-items: center;
    gap: 0;
    padding: 8px 0;
    margin-bottom: 4px;
    background: linear-gradient(180deg, rgba(255, 255, 255, 0.95) 70%, rgba(255, 255, 255, 0));
    backdrop-filter: blur(8px);
    -webkit-backdrop-filter: blur(8px);
  }

  :deep(.content-area__date-num),
  :deep(.content-area__date-rel) {
    display: inline-flex;
    align-items: center;
    padding: 8px 18px;
    font-size: 15px;
    font-weight: 700;
    border-radius: 999px;
    letter-spacing: 0.5px;
  }

  :deep(.content-area__date-num) {
    background: linear-gradient(135deg, rgba(0, 101, 253, 0.08), rgba(0, 101, 253, 0.18));
    color: $brand-primary;
    border: 1px solid rgba(0, 101, 253, 0.25);
    box-shadow: 0 2px 8px rgba(0, 101, 253, 0.12);
    border-top-right-radius: 0;
    border-bottom-right-radius: 0;
    border-right: none;
  }

  :deep(.content-area__date-rel) {
    background: $brand-primary;
    color: #fff;
    border: 1px solid $brand-primary;
    border-top-left-radius: 0;
    border-bottom-left-radius: 0;
    box-shadow: 0 2px 8px rgba(0, 101, 253, 0.2);
  }

  :deep(.content-area__loading) {
    display: flex;
    justify-content: center;
    align-items: center;
    min-height: 200px;
    padding: 40px 0;
  }

  :deep(.content-area__list) {
    padding: 0 12px;
  }

  /* 底部提示 */
  :deep(.content-area__end-hint) {
    display: flex;
    justify-content: center;
    align-items: center;
    padding: 16px 0 8px;

    span {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      padding: 6px 14px;
      background: $brand-secondary;
      border-radius: 999px;
      font-size: 11px;
      color: $brand-muted-foreground;
      border: 1px dashed $brand-border;
    }
  }

  /* ============ 日期 section(单页堆叠模式:每个日期一个 section) ============ */
  :deep(.day-section) {
    padding: 4px 0 8px;
  }

  :deep(.day-section__header) {
    display: flex;
    justify-content: center;
    padding: 12px 12px 10px;
  }

  :deep(.day-section__header-pill) {
    display: inline-flex;
    align-items: center;
    padding: 6px 18px;
    font-size: 14px;
    font-weight: 700;
    color: $brand-foreground;
    background: $brand-secondary;
    border: 1px solid $brand-border;
    border-radius: 999px;
    box-shadow: 0 2px 6px rgba(0, 0, 0, 0.05);
  }

  :deep(.day-section__empty) {
    display: flex;
    justify-content: center;
    align-items: center;
    min-height: 80px;
    padding: 16px 0;
    color: $brand-muted-foreground;
    font-size: 13px;
    background: $brand-card;
    border: 1px dashed $brand-border;
    border-radius: 12px;
  }

  /* ============ 餐别卡片 ============ */
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

/* ============ 日历弹窗 ============ */
.calendar {
  padding: 20px 16px 16px;

  &__head {
    display: flex;
    align-items: center;
    justify-content: center;
    position: relative;
    padding-bottom: 16px;
    border-bottom: 1px solid $brand-border;
  }

  &__nav {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 36px;
    height: 36px;
    border-radius: 50%;
    border: none;
    background: $brand-secondary;
    color: $brand-foreground;
    cursor: pointer;

    &:active {
      background: rgba(0, 101, 253, 0.1);
      color: $brand-primary;
    }
  }

  &__month {
    margin: 0 24px;
    font-size: 16px;
    font-weight: 700;
    color: $brand-foreground;
  }

  &__week-header {
    display: grid;
    grid-template-columns: repeat(7, 1fr);
    gap: 4px;
    margin: 16px 0 8px;
  }

  &__week-cell {
    text-align: center;
    font-size: 12px;
    font-weight: 700;
    color: $brand-muted-foreground;
    padding: 4px 0;
  }

  &__grid {
    display: grid;
    grid-template-columns: repeat(7, 1fr);
    gap: 4px;
  }

  &__cell {
    position: relative;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 3px;
    aspect-ratio: 1;
    min-height: 44px;
    border-radius: 10px;
    border: 1.5px solid transparent;
    background: transparent;
    cursor: pointer;
    transition: background 0.15s, border-color 0.15s;

    &--placeholder {
      background: transparent;
      border: none;
      cursor: default;
      pointer-events: none;
    }

    &--has-order {
      background: $brand-card;
      border-color: $brand-border;
    }

    &--no-order {
      background: $brand-secondary;
      border-color: transparent;
      color: $brand-muted-foreground;
      opacity: 0.4;
      cursor: not-allowed;
    }

    &--today {
      border-color: $brand-primary;
    }

    &--selected {
      background: $brand-primary !important;
      border-color: $brand-primary !important;
      color: #fff;
    }
  }

  &__day {
    font-size: 14px;
    font-weight: 600;
    line-height: 1;
  }

  &__dot {
    width: 5px;
    height: 5px;
    border-radius: 50%;
    background: $brand-primary;
  }

  &__cell--selected &__dot {
    background: #fff;
  }

  &__footer {
    margin-top: 16px;
    padding-top: 12px;
    border-top: 1px solid $brand-border;
    text-align: center;
    font-size: 12px;
    color: $brand-muted-foreground;
  }
}
</style>