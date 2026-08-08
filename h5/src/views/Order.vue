<script setup lang="ts">
import { ref, computed, watch, onMounted, onActivated, onDeactivated, onBeforeUnmount, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import {
  showToast,
  showSuccessToast,
  showLoadingToast,
  closeToast,
  showConfirmDialog,
} from 'vant'
import { ShoppingCart, Plus, Minus, Check } from 'lucide-vue-next'
import { useAuthStore } from '@/stores/auth'
import { useCartStore } from '@/stores/cart'
import * as menuApi from '@/api/menu'
import * as orderApi from '@/api/order'
import { useFormat } from '@/composables/useFormat'
import {
  useMealConfig,
  mealPillStyle as mealPillStyleFn,
  mealBadgeStyle as mealBadgeStyleFn,
  mealIconColor as mealIconColorFn,
  mealDotColor as mealDotColorFn,
} from '@/composables/useMealConfig'
import EmptyState from '@/components/EmptyState.vue'
import {
  formatDateStr,
  addDays,
  compareDate,
  numericDateLabel,
  relativeDateLabel,
  formatGroupDate,
} from '@/utils/date'
import { getCachedImage } from '@/utils/imageCache'
import { useOrderConfig } from '@/composables/useOrderConfig'
import type {
  MenuWithItems,
  MenuItemView,
  Dish,
  Order,
  OrderCreateDTO,
  DiningTimeSlot,
} from '@/api/types'

defineOptions({ name: 'Order' })

const router = useRouter()
const authStore = useAuthStore()
const cartStore = useCartStore()
const { formatMoney, formatMealType, formatMealTypeShort } = useFormat()
const { loadConfig, isOrderableByDeadline } = useOrderConfig()

// ============ 状态 ============
/**
 * 所有已加载日期的菜单数据(date → MenuWithItems[])。
 * 单页堆叠模式:所有可订餐日期同时渲染在 DOM 中,上下滚动自然浏览,无"切换日期"动作。
 */
const menusByDate = ref<Map<string, MenuWithItems[]>>(new Map())
const showCart = ref(false)
const submitting = ref(false)
/** 加载失败的菜品图片(menu item id),触发 v-if 回退到 emoji 占位 */
const erroredImages = ref<Set<number>>(new Set())
/** 标记首次挂载,避免 onMounted + onActivated 双重触发重复加载 */
let firstMount = true
/** 下单成功后跳转订单页的定时器,组件失活时需清理避免意外导航 */
let navTimer: ReturnType<typeof setTimeout> | null = null

/** 已配置菜单的日期集合(yyyy-MM-dd) */
const menuDates = ref<Set<string>>(new Set())
/** 餐别时段配置(用于判断"过点不订") */
const diningTimes = ref<DiningTimeSlot[]>([])
/** 已下单订单列表(status=1 待取餐,用于锁定已订餐别 + 显示已订标记) */
const orderedOrders = ref<Order[]>([])
/** 右侧内容区滚动容器引用(用于 scroll 事件监听 + 滚动控制) */
const contentRef = ref<HTMLElement | null>(null)
/** 菜单缓存:date → MenuWithItems[],避免重复请求 */
const menuCache = ref<Map<string, MenuWithItems[]>>(new Map())
/** 当前可视日期(滚动时动态更新,用于左侧栏高亮 + sticky 日期指示器) */
const visibleDate = ref<string>('')
/** 所有日期菜单是否已全部加载完成(统一渲染,杜绝懒加载布局抖动) */
const allMenusLoaded = ref(false)
/** 每个日期 section 的 DOM 引用,用于滚动定位 + 可视检测 */
const daySectionRefs: Record<string, HTMLElement | null> = {}
/** scroll 事件节流时间戳 */
let lastScrollCalcTime = 0
/** 用户正在主动滚动(点击左侧日期栏跳转时屏蔽可视日期更新,避免跳动) */
let isProgrammaticScroll = false
/**
 * 已激活图片加载的日期集合。
 * 智能图片加载策略:day-section 进入视口前 1 屏时被 IntersectionObserver 标记为激活,
 * 激活后该日期的所有 <img> 才会渲染并请求图片;未激活则显示 emoji 占位,不发请求。
 * 一旦激活不清空(避免回滚重复请求,靠 HTTP immutable 缓存命中)。
 */
const imageActivatedDates = ref<Set<string>>(new Set())
/** 图片懒加载 IntersectionObserver 实例(根为内容区,rootMargin 下扩 1 屏) */
let imageObserver: IntersectionObserver | null = null

/**
 * 增量加载(按周)状态:
 * - 初始只加载"今天所在周"的菜单日期,滚动到底部再加载下一周
 * - 窗口起始固定为今天,结束日期随加载向后扩展
 */
let windowStartDate = formatDateStr(new Date())
let windowEndDate = addDays(windowStartDate, 6)
/** 是否正在加载更多(防重入) */
const loadingMore = ref(false)
/** 是否已到末尾(某周无新增可订餐日期) */
const noMoreData = ref(false)

// ============ 餐别配置(颜色 + 图标,从 @/composables/useMealConfig 复用) ============
// 颜色规范:早餐橙、午餐绿、晚餐紫,与 Orders.vue 共享
const { mealIconMap } = useMealConfig()
/** 胶囊样式:菜单页餐别标题 */
const mealPillStyle = mealPillStyleFn
/** badge 样式:订单列表与确认弹窗 */
const mealBadgeStyle = mealBadgeStyleFn
/** 餐别图标颜色 */
const mealIconColor = mealIconColorFn
/** 餐别圆点颜色(日期竖列) */
const mealDotColor = mealDotColorFn

// ============ 日期工具 ============
// 纯函数(formatDateStr / parseTimeToMinutes / nowMinutes / compareDate / addDays /
// numericDateLabel / relativeDateLabel / formatGroupDate)已抽出到 @/utils/date,
// 这里仅保留依赖响应式状态的日期判断函数。

/** 判断指定日期+餐别是否可订餐 */
const isMealOrderable = (date: string, _mealType: number): boolean => {
  // 用配置驱动的截止时间判断:
  // - 今天及之前:截止时间(昨天15:00)已过 → 不可订
  // - 明天:截止时间是今天15:00 → 15:00前可订
  // - 后天及以后:可订(在提前天数内)
  return isOrderableByDeadline(date, new Date())
}

/** 判断指定日期是否可订餐 */
const isDateOrderable = (date: string): boolean => {
  if (!menuDates.value.has(date)) return false
  return isOrderableByDeadline(date, new Date())
}

// ============ 日期选择竖列(只显示可订餐日期) ============
interface DateItem {
  date: string // yyyy-MM-dd
  dateLabel: string // "7月14日"
  weekday: string // "周一"
  isToday: boolean
}

/** 可订餐日期列表(过滤:未来日期 + 菜单存在 + 至少一餐未过点) */
const dateList = computed<DateItem[]>(() => {
  if (menuDates.value.size === 0) return []
  const weekdays = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']
  const today = formatDateStr(new Date())
  const list: DateItem[] = []
  // 遍历 menuDates 集合,过滤后排序
  const sorted = Array.from(menuDates.value).sort(compareDate)
  for (const dateStr of sorted) {
    if (!isDateOrderable(dateStr)) continue
    const d = new Date(`${dateStr}T00:00:00`)
    list.push({
      date: dateStr,
      dateLabel: `${d.getMonth() + 1}月${d.getDate()}日`,
      weekday: weekdays[d.getDay()],
      isToday: dateStr === today,
    })
  }
  return list
})

/** 当前可视日期的日期数字部分(如 "7月26日"),用于 sticky 顶部指示器 */
const visibleDateNumber = computed(() => {
  const dateStr = visibleDate.value || cartStore.selectedDate
  return numericDateLabel(dateStr)
})

/** 当前可视日期的相对标签(今天/明天/后天/周X),用于 sticky 顶部指示器 */
const visibleDateRelativeLabel = computed(() => {
  const dateStr = visibleDate.value || cartStore.selectedDate
  return relativeDateLabel(dateStr)
})

/** 指定日期已订餐的餐别列表(购物车 + 已下单订单,用于日期竖列的三餐色圆点) */
const dateMealTypes = (date: string): number[] => {
  const set = new Set<number>(cartStore.getMealTypesForDate(date))
  for (const o of orderedOrders.value) {
    if (o.date === date && o.status === 1) set.add(Number(o.mealType))
  }
  return Array.from(set).sort((a, b) => a - b)
}

/** 三餐固定顺序(早/中/晚),用于日期竖列圆点固定位置渲染 */
const MEAL_TYPE_ORDER: number[] = [1, 2, 3]

/** 判断指定日期+餐别是否已订餐(用于圆点是否渲染) */
const isMealOrdered = (date: string, mealType: number): boolean => {
  return dateMealTypes(date).includes(mealType)
}

/**
 * 指定日期下,已下单(status=1)的餐别集合(锁定不可再加菜)。
 * 参考 X86 订餐端 OrderSelect.vue 的 lockedMealTypes 模式。
 */
const lockedMealTypesFor = (date: string): Set<number> => {
  const s = new Set<number>()
  for (const o of orderedOrders.value) {
    if (o.date === date && o.status === 1) s.add(Number(o.mealType))
  }
  return s
}

/** 判断指定日期+餐别是否已锁定(已下单) */
const isMealLocked = (date: string, mealType: number): boolean => {
  return lockedMealTypesFor(date).has(mealType)
}

/** 指定日期+餐别下,已下单菜品映射:dishId -> quantity(用于菜品卡片回显数量+禁操作) */
const orderedItemsFor = (date: string, mealType: number): Map<number, number> => {
  const m = new Map<number, number>()
  for (const o of orderedOrders.value) {
    if (o.date !== date || Number(o.mealType) !== mealType || o.status !== 1) continue
    const items = (o as any).items || []
    for (const it of items) {
      const id = Number((it as any).dishId ?? (it as any).dish_id ?? 0)
      const qty = Number((it as any).quantity ?? 1)
      if (id > 0) m.set(id, qty)
    }
  }
  return m
}

/** 拉取当前员工已下单订单(status=1 待取餐,用于锁定已订餐别 + 已订菜品回显) */
const fetchOrderedOrders = async (): Promise<void> => {
  const employeeId = authStore.employeeId
  if (!employeeId) return
  try {
    const list = await orderApi.getMyOrders(employeeId)
    const arr = Array.isArray(list) ? list : []
    // 仅保留 status=1 待取餐
    orderedOrders.value = arr.filter((o) => o.status === 1)
    // 后端可能未填充 items,对 items 为空的订单批量请求详情补充
    await fillMissingOrderItems(orderedOrders.value)
  } catch {
    orderedOrders.value = []
  }
}

/** items fallback:对 items 为空的订单批量请求 /order/{id} 补充菜品明细 */
const fillMissingOrderItems = async (orderList: Order[]): Promise<void> => {
  const needFill = orderList.filter((o) => (!o.items || o.items.length === 0) && o.id)
  if (needFill.length === 0) return
  const CONCURRENCY = 5
  const queue = [...needFill]
  const workers = Array.from({ length: Math.min(CONCURRENCY, queue.length) }, async () => {
    while (queue.length) {
      const o = queue.shift()
      if (!o) break
      try {
        const detail = await orderApi.getOrderDetail(o.id)
        const d: any = detail as any
        if (d && d.items) {
          ;(o as any).items = d.items
        }
      } catch {
        /* 单个订单详情失败不影响整体 */
      }
    }
  })
  await Promise.all(workers)
}

// ============ 指定日期的餐别 section(只展示可订餐 + 有菜品的餐别) ============
interface MealSection {
  type: number
  name: string
  items: MenuItemView[]
}

/**
 * 获取指定日期的可订餐餐别列表(单页堆叠模式:每个日期独立计算)。
 * 已下单(锁定)的餐别也显示出来,展示已订菜品 + "已订"标记,禁止操作。
 * @param date yyyy-MM-dd
 */
const getMealSectionsForDate = (date: string): MealSection[] => {
  const sections: MealSection[] = []
  const dayMenus = menusByDate.value.get(date)
  if (!dayMenus || dayMenus.length === 0) return sections
  const lockedSet = lockedMealTypesFor(date)
  for (const mt of [1, 2, 3]) {
    const menu = dayMenus.find((m) => m.menu.mealType === mt)
    const isLocked = lockedSet.has(mt)
    // 已锁定餐别:即使过点也显示(展示已订菜品)
    if (!isLocked) {
      // 未锁定:过滤掉过点餐别
      if (!isMealOrderable(date, mt)) continue
    }
    if (menu && menu.items.length > 0) {
      sections.push({
        type: mt,
        name: formatMealType(mt),
        items: menu.items,
      })
    }
  }
  return sections
}

// ============ 购物车相关 ============
const totalCount = computed(() => cartStore.totalCount)
const totalPrice = computed(() => cartStore.totalPrice)
const hasItems = computed(() => cartStore.hasItems)

/** 获取菜品在指定日期+餐别的购物车数量 */
const getQty = (dishId: number, date: string, mealType: number): number => {
  return cartStore.getQuantity(dishId, date, mealType)
}

/** 添加菜品到指定日期+餐别 */
const handleAdd = (dish: Dish, date: string, mealType: number): void => {
  // 已锁定(已下单)餐别禁止加菜
  if (isMealLocked(date, mealType)) {
    showToast('该餐别已下单,无法修改')
    return
  }
  // 显式判断非启用(status!==1),兼容 null/undefined 异常情况
  if (dish.status !== 1) {
    showToast('该菜品已售罄')
    return
  }
  cartStore.addItem(dish, date, mealType)
}

/** 减少指定日期+餐别的菜品 */
const handleDecrease = (dishId: number, date: string, mealType: number): void => {
  cartStore.decreaseItem(dishId, date, mealType)
}

/** 购物车摘要文本(用于黑色浮动栏横滑展示) */
const cartSummaryText = computed(() => {
  if (!cartStore.hasItems) return '还没选菜品'
  const parts: string[] = []
  for (const dateGroup of cartDateGrouped.value) {
    parts.push(`[${dateGroup.dateLabel}]`)
    for (const meal of dateGroup.meals) {
      parts.push(`${meal.mealName}:`)
      for (const entry of meal.entries) {
        parts.push(`${entry.dish.name}x${entry.quantity}`)
      }
    }
  }
  return parts.join(' ')
})

/** 购物车树形分组:日期 → 餐别 → 菜品(购物车弹层 + 确认弹窗共用) */
interface CartMealGroup {
  mealType: number
  mealName: string // 单字:早/中/晚
  mealFullName: string // 全称:早餐/午餐/晚餐
  entries: { dish: Dish; quantity: number }[]
  subtotal: number
}
interface CartDateGroup {
  date: string
  dateLabel: string // 今天/明天/后天/M月d日
  meals: CartMealGroup[]
  dateSubtotal: number
}

const cartDateGrouped = computed<CartDateGroup[]>(() => {
  const groups = cartStore.getAllGroups() // 已按 date+mealType 排序
  const dateMap = new Map<string, CartDateGroup>()
  for (const g of groups) {
    let dg = dateMap.get(g.date)
    if (!dg) {
      dg = {
        date: g.date,
        dateLabel: formatGroupDate(g.date),
        meals: [],
        dateSubtotal: 0,
      }
      dateMap.set(g.date, dg)
    }
    dg.meals.push({
      mealType: g.mealType,
      mealName: formatMealTypeShort(g.mealType),
      mealFullName: formatMealType(g.mealType),
      entries: g.entries,
      subtotal: g.subtotal,
    })
    dg.dateSubtotal = Math.round((dg.dateSubtotal + g.subtotal) * 100) / 100
  }
  return Array.from(dateMap.values())
})

/** 清空购物车(弹确认框) */
const handleClearCart = (): void => {
  if (!cartStore.hasItems) return
  showConfirmDialog({
    title: '清空购物车',
    message: '确定要清空购物车中的所有菜品吗?跨日期的所有菜品都会被清空。',
  })
    .then(() => {
      cartStore.clearAll()
      showCart.value = false
    })
    .catch(() => {
      // 取消
    })
}

// ============ 下单确认弹窗 ============
/** 是否显示下单确认弹窗 */
const showSubmitConfirm = ref(false)

/** 确认弹窗复用 cartDateGrouped(与购物车弹层结构一致:日期 → 餐别 → 菜品) */

/** 点击"去结算"按钮:打开确认弹窗,而不是直接下单 */
const handleSubmitClick = (): void => {
  if (!cartStore.hasItems || submitting.value) return
  showSubmitConfirm.value = true
}

/** 确认弹窗中点击"确认下单":真正执行下单 */
const confirmSubmit = async (): Promise<void> => {
  // 关闭确认弹窗,执行原下单逻辑
  showSubmitConfirm.value = false
  await doSubmit()
}

// ============ 数据加载 ============
/** 加载餐别时段配置 */
const loadDiningTimes = async (): Promise<void> => {
  const storeId = authStore.storeId
  if (!storeId) return
  try {
    const data = await menuApi.getDiningTimes(storeId)
    diningTimes.value = data || []
  } catch {
    diningTimes.value = []
  }
}

/**
 * 查询 [start, end] 范围内所有月份已配置菜单的日期,合并写入 menuDates。
 * 按需增量:只查询窗口覆盖的月份,日期限定在 [start, end] 内,避免拉取窗口外数据。
 */
const fetchMenuDatesForRange = async (start: string, end: string): Promise<void> => {
  const storeId = authStore.storeId
  if (!storeId) return
  // 收集窗口覆盖的月份集合(yyyy-m)
  const monthSet = new Set<string>()
  const cursor = new Date(`${start}T00:00:00`)
  const endDate = new Date(`${end}T00:00:00`)
  while (cursor <= endDate) {
    monthSet.add(`${cursor.getFullYear()}-${cursor.getMonth() + 1}`)
    cursor.setMonth(cursor.getMonth() + 1)
  }
  const next = new Set(menuDates.value)
  for (const key of monthSet) {
    const [year, month] = key.split('-').map(Number)
    try {
      const list = await menuApi.getMenuDates(storeId, year, month)
      for (const d of list || []) {
        const date = typeof d === 'string' ? d : d && d.published ? d.date : ''
        if (date && date >= start && date <= end) next.add(date)
      }
    } catch {
      // 忽略单月失败
    }
  }
  menuDates.value = next
}

/** 加载当前窗口(今天 → windowEndDate)的菜单日期 */
const loadMenuDates = async (): Promise<void> => {
  menuDates.value = new Set()
  await fetchMenuDatesForRange(windowStartDate, windowEndDate)
}

/**
 * 并发加载指定日期的菜单(并发上限 5,避免瞬时打满服务器和带宽)。
 * 单日失败:写入空数组,占位区显示"暂无菜品",不影响其他日期。
 */
const loadMenusFor = async (dates: string[]): Promise<void> => {
  const storeId = authStore.storeId
  if (!storeId || dates.length === 0) return
  // 滑动窗口控制并发:5 个 worker 同时跑,每个 worker 顺序取下一个未处理日期
  const CONCURRENCY = 5
  let cursor = 0
  const worker = async (): Promise<void> => {
    while (cursor < dates.length) {
      const date = dates[cursor++]
      // 命中缓存:直接写入 Map,跳过网络
      const cached = menuCache.value.get(date)
      if (cached) {
        menusByDate.value.set(date, cached)
        continue
      }
      try {
        const data = await menuApi.getMenuByDate(storeId, date)
        const arr = data || []
        menuCache.value.set(date, arr)
        menusByDate.value.set(date, arr)
      } catch {
        // 单日失败:写入空数组,占位区显示"暂无菜品"
        menusByDate.value.set(date, [])
      }
    }
  }
  await Promise.all(
    Array.from({ length: Math.min(CONCURRENCY, dates.length) }, () => worker()),
  )
}

/**
 * 加载当前窗口内所有可订餐日期的菜单。
 * 加载完成后 allMenusLoaded=true,统一渲染当前窗口的日期 section。
 */
const loadAllMenus = async (): Promise<void> => {
  const storeId = authStore.storeId
  if (!storeId) {
    showToast('请先登录')
    router.replace('/login')
    return
  }
  const dates = dateList.value.map((d) => d.date)
  if (dates.length === 0) {
    allMenusLoaded.value = true
    return
  }
  await loadMenusFor(dates)
  allMenusLoaded.value = true
}

/**
 * 滚动接近底部时加载下一周(窗口向后扩展 7 天)。
 * 若新增的那一周没有新的可订餐日期,认为已到末尾,停止继续加载。
 */
const loadMoreWeek = async (): Promise<void> => {
  if (loadingMore.value || noMoreData.value) return
  loadingMore.value = true
  try {
    const prevSize = menuDates.value.size
    const newEnd = addDays(windowEndDate, 7)
    // 只查询新增那一周(上一周结束次日 → 新结束)的菜单日期
    await fetchMenuDatesForRange(addDays(windowEndDate, 1), newEnd)
    windowEndDate = newEnd
    // 新增的可订餐日期(尚未加载菜单)
    const newOrderable = dateList.value.filter((d) => !menusByDate.value.has(d.date))
    if (newOrderable.length === 0) {
      // 新增周无任何菜单日期 → 已到末尾
      if (menuDates.value.size === prevSize) noMoreData.value = true
    } else {
      await loadMenusFor(newOrderable.map((d) => d.date))
    }
  } finally {
    loadingMore.value = false
  }
}

/** 滚动时检测是否接近底部,触发加载下一周 */
const checkLoadMore = (): void => {
  const container = contentRef.value
  if (!container || !allMenusLoaded.value || loadingMore.value || noMoreData.value) return
  // 距底部不足 200px 时触发
  if (container.scrollHeight - (container.scrollTop + container.clientHeight) < 200) {
    void loadMoreWeek()
  }
}

/** 设置日期 section 的 DOM 引用(供 v-for :ref 回调用) */
const setDaySectionRef = (date: string, el: Element | null): void => {
  daySectionRefs[date] = (el as HTMLElement) || null
}

/**
 * 点击左侧日期栏:平滑滚动到对应日期 section(不切换、不替换数据)。
 * 单页堆叠模式:所有日期已在 DOM 中,滚动即可。
 */
const scrollToDate = (date: string): void => {
  const el = daySectionRefs[date]
  if (!el) return
  // 屏蔽滚动期间的可视日期更新,避免与平滑滚动冲突
  isProgrammaticScroll = true
  el.scrollIntoView({ behavior: 'smooth', block: 'start' })
  // 同步更新 selectedDate(下单时用)+ visibleDate(高亮)
  cartStore.selectedDate = date
  visibleDate.value = date
  // 平滑滚动结束后恢复监听(500ms 足够覆盖大多数滚动时长)
  setTimeout(() => { isProgrammaticScroll = false }, 500)
}

/**
 * 内容区滚动事件处理(单页堆叠模式):
 * - 节流计算当前可视日期(最接近顶部的 day-section)→ 更新 visibleDate + selectedDate
 * - 程序滚动期间(点击左侧栏跳转)忽略,避免与平滑滚动冲突
 */
const handleContentScroll = (): void => {
  if (isProgrammaticScroll) return
  const now = Date.now()
  if (now - lastScrollCalcTime < 100) return // 100ms 节流
  lastScrollCalcTime = now
  updateVisibleDate()
  // 滚动接近底部时增量加载下一周
  checkLoadMore()
}

/**
 * 计算当前可视日期(使用"触发线"算法,稳定不跳动)。
 * 触发线位于容器顶部下方 60px(刚好在 sticky 日期指示器之下):
 * - 若触发线落在某 section 内 → 选定该日期
 * - 否则(触发线在两个 section 之间空隙) → 选触发线上方最近的 section
 * 相比旧的"top<=40"算法,触发线落在 section 内时才切换,避免轻微滚动就跳日。
 */
const updateVisibleDate = (): void => {
  const container = contentRef.value
  if (!container) return
  const containerTop = container.getBoundingClientRect().top
  const TRIGGER_Y = 60 // 触发线:容器顶部下方 60px(sticky 指示器之下)
  let bestDate = ''
  let bestTop = -Infinity
  for (const d of dateList.value) {
    const el = daySectionRefs[d.date]
    if (!el) continue
    const rect = el.getBoundingClientRect()
    const top = rect.top - containerTop
    const bottom = rect.bottom - containerTop
    // 触发线在 section 内:直接选定,跳出循环(稳定锚点)
    if (top <= TRIGGER_Y && bottom > TRIGGER_Y) {
      bestDate = d.date
      break
    }
    // 否则记录"触发线上方的最后一个 section"(section 已完全滚过触发线)
    if (top <= TRIGGER_Y && top >= bestTop) {
      bestTop = top
      bestDate = d.date
    }
  }
  if (bestDate && bestDate !== visibleDate.value) {
    visibleDate.value = bestDate
    // 同步 selectedDate,下单时默认归到当前可视日期
    cartStore.selectedDate = bestDate
  }
}

/**
 * 判断指定日期是否已激活图片加载(模板里 v-if 控制 <img> 是否渲染)。
 * 未激活时显示 emoji 占位,不渲染 <img> 也不发图片请求。
 */
const isImageActivated = (date: string): boolean => imageActivatedDates.value.has(date)

/**
 * 初始化图片懒加载 IntersectionObserver。
 * 当 day-section 进入视口前 1 屏(rootMargin 下扩 100%)时,标记该日期为"图片可加载",
 * 模板 v-if 切换到 <img> 渲染并发请求;已激活的不取消(回滚靠 HTTP 缓存命中,避免重复请求)。
 *
 * 与 day-section DOM 解耦:day-section 始终全量渲染保证滚动丝滑,仅 <img> 按需渲染。
 */
const initImageObserver = (): void => {
  if (imageObserver) imageObserver.disconnect()
  const root = contentRef.value
  if (!root) return
  imageObserver = new IntersectionObserver(
    (entries) => {
      // 批量更新,避免每个 entry 触发一次响应式
      let changed = false
      const next = new Set(imageActivatedDates.value)
      for (const entry of entries) {
        if (entry.isIntersecting) {
          const date = (entry.target as HTMLElement).dataset.date
          if (date && !next.has(date)) {
            next.add(date)
            changed = true
          }
        }
      }
      if (changed) imageActivatedDates.value = next
    },
    {
      root,
      // 下方向扩展 1 屏:用户滚到该 section 之前提前发请求,滚到时图片已就绪
      rootMargin: '0px 0px 100% 0px',
      threshold: 0,
    },
  )
  for (const date of Object.keys(daySectionRefs)) {
    const el = daySectionRefs[date]
    if (el) imageObserver!.observe(el)
  }
}

// ============ 下单逻辑(跨日期提交) ============
const doSubmit = async () => {
  if (!cartStore.hasItems) {
    showToast('购物车是空的')
    return
  }

  const storeId = authStore.storeId
  const employeeId = authStore.employeeId
  if (!storeId || !employeeId) {
    showToast('请先登录')
    router.replace('/login')
    return
  }

  // 余额检查
  const balance = authStore.balance
  const total = cartStore.totalPrice
  if (balance < total) {
    showToast('余额不足,请联系管理员充值')
    return
  }

  // 下单用扁平的 (date, mealType) 列表,逐个调用 createOrder
  const groups = cartStore.getAllGroups()

  submitting.value = true
  showLoadingToast({ message: '下单中...', duration: 0, forbidClick: true })

  // 成功的 group key 列表(date|mealType)
  const successKeys: string[] = []
  const errors: string[] = []

  try {
    for (const group of groups) {
      const dto: OrderCreateDTO = {
        storeId,
        employeeId,
        date: group.date,
        mealType: group.mealType,
        items: group.entries.map((entry) => ({
          dishId: entry.dish.id,
          quantity: entry.quantity,
        })),
      }
      try {
        await orderApi.createOrder(dto)
        successKeys.push(`${group.date}|${group.mealType}`)
      } catch (err) {
        // 拦截器对业务错误 reject 的是 ApiResponse(body,含 message 字段);
        // 对网络错误 reject 的是 AxiosError(含 response.data.message 或 message)
        const e = err as { message?: string; response?: { data?: { message?: string } } }
        const msg = e?.response?.data?.message || e?.message || '下单失败'
        const dateLabel = formatGroupDate(group.date)
        const mealName = formatMealType(group.mealType)
        errors.push(`${dateLabel} ${mealName}: ${msg}`)
      }
    }

    closeToast()

    // 清空已成功的 group
    for (const key of successKeys) {
      const [date, mtStr] = key.split('|')
      cartStore.clearGroup(date, Number(mtStr))
    }

    // 刷新余额
    await authStore.refreshEmployee()

    if (errors.length === 0) {
      // 全部成功
      showSuccessToast('下单成功')
      navTimer = setTimeout(() => {
        navTimer = null
        router.replace('/orders')
      }, 1000)
    } else if (successKeys.length > 0) {
      // 部分成功
      showToast(`部分订单成功，${errors.join('；')}`)
      navTimer = setTimeout(() => {
        navTimer = null
        router.replace('/orders')
      }, 1500)
    } else {
      // 全部失败
      showToast(`下单失败：${errors.join('；')}`)
    }
  } finally {
    submitting.value = false
  }
}

// ============ 图片降级 ============
/** 标记该项图片加载失败,触发 v-if 切换到 emoji 占位 */
const handleImgError = (itemId: number): void => {
  if (erroredImages.value.has(itemId)) return
  const next = new Set(erroredImages.value)
  next.add(itemId)
  erroredImages.value = next
}

/** 菜品图片缓存 Map（item.id → 缓存后的 blob URL 或原 URL），异步填充 */
const cachedDishImages = ref<Map<number, string>>(new Map())

/** 批量预加载菜品图片到缓存 Map（懒加载：首次 fetch 后缓存，后续命中缓存直接返回 blob URL） */
const refreshDishImages = async (): Promise<void> => {
  const map = new Map<number, string>()
  const promises: Promise<void>[] = []
  for (const [, menus] of menusByDate.value) {
    for (const menu of menus) {
      for (const iv of menu.items) {
        if (!iv.item.id || map.has(iv.item.id)) continue
        const raw = iv.dish?.imageUrl || iv.dish?.image
        if (!raw) continue
        // 完整 URL 或 data URL 直接使用，不走 IndexedDB 缓存
        if (/^(https?:)?\/\//.test(raw) || raw.startsWith('data:')) {
          map.set(iv.item.id, raw)
          continue
        }
        promises.push(
          getCachedImage(raw).then((url) => { map.set(iv.item.id, url) }),
        )
      }
    }
  }
  await Promise.all(promises)
  cachedDishImages.value = map
}

/** 当前项的菜品图片地址(优先返回缓存 blob URL);已失败的返回空串触发 emoji 占位 */
const getDishImg = (iv: MenuItemView): string => {
  if (erroredImages.value.has(iv.item.id)) return ''
  return cachedDishImages.value.get(iv.item.id) || ''
}

// 菜单数据变化时异步刷新缓存 Map
watch(menusByDate, refreshDishImages, { deep: true, immediate: true })

const categoryEmoji = (category?: string): string => {
  const cat = (category || '').toLowerCase()
  if (cat.includes('荤') || cat.includes('肉')) return '🥩'
  if (cat.includes('素') || cat.includes('菜')) return '🥬'
  if (cat.includes('汤') || cat.includes('羹')) return '🍲'
  if (cat.includes('主') || cat.includes('饭') || cat.includes('面')) return '🍚'
  if (cat.includes('凉')) return '🥗'
  return '🍽️'
}

// ============ 生命周期 ============
/**
 * 重置到"今天所在周"并加载:
 * - 窗口起始固定为今天(顶部分从未过去日期),初始仅加载今天起一周
 * - 清空旧数据与缓存,确保每次进入订餐页都以当天为顶
 */
const resetToTodayAndLoad = async (): Promise<void> => {
  const today = formatDateStr(new Date())
  windowStartDate = today
  windowEndDate = addDays(today, 6)
  loadingMore.value = false
  noMoreData.value = false
  allMenusLoaded.value = false
  menusByDate.value = new Map()
  menuCache.value = new Map()
  // 清空旧日期 section 引用与图片激活状态,避免残留影响滚动定位与懒加载
  for (const k of Object.keys(daySectionRefs)) delete daySectionRefs[k]
  imageActivatedDates.value = new Set()
  cartStore.selectedDate = today
  await loadMenuDates()
  // 如果今天已不可订餐,自动切到当天起第一个可订餐日期
  if (dateList.value.length > 0 && !isDateOrderable(cartStore.selectedDate)) {
    cartStore.selectedDate = dateList.value[0].date
  }
  visibleDate.value = cartStore.selectedDate
  // 加载当前窗口内所有可订餐日期的菜单
  await loadAllMenus()
  // DOM 渲染后滚动到当前日期 section(保持在当天)
  await nextTick()
  const targetEl = daySectionRefs[cartStore.selectedDate]
  if (targetEl) {
    isProgrammaticScroll = true
    targetEl.scrollIntoView({ behavior: 'auto', block: 'start' })
    setTimeout(() => { isProgrammaticScroll = false }, 100)
  }
  // 初始化图片懒加载观察器(DOM 已就绪后即可观察)
  initImageObserver()
}

onMounted(async () => {
  // 加载后端订餐配置(截止时间、提前天数等),供 isOrderableByDeadline 使用
  await loadConfig()
  await Promise.all([loadDiningTimes(), fetchOrderedOrders()])
  await resetToTodayAndLoad()
})

onActivated(async () => {
  // keep-alive 首次挂载时 onMounted + onActivated 均触发,跳过首次避免重复
  if (firstMount) {
    firstMount = false
    return
  }
  // keep-alive 激活(从其他页切回订餐页):重新拉取已下单订单 + 重置到当天并重新加载
  void fetchOrderedOrders()
  await resetToTodayAndLoad()
})

/** 清理跳转定时器与图片懒加载观察器,避免离开页面后被意外触发 */
const cleanupNavAndObserver = () => {
  // 清理跳转定时器,避免用户离开页面后被意外导航到订单页
  if (navTimer) {
    clearTimeout(navTimer)
    navTimer = null
  }
  // 断开图片懒加载观察器,避免失活后继续触发回调
  if (imageObserver) {
    imageObserver.disconnect()
    imageObserver = null
  }
}

onDeactivated(() => {
  cleanupNavAndObserver()
})

onBeforeUnmount(() => {
  cleanupNavAndObserver()
})
</script>

<template>
  <div class="order-page">
    <!-- 主体:左侧日期竖列 + 右侧内容区 -->
    <div class="order-main">
      <!-- 左侧日期竖列(点击跳转到对应日期 section,平滑滚动) -->
      <div class="date-sidebar">
        <button
          v-for="d in dateList"
          :key="d.date"
          type="button"
          class="date-sidebar__item"
          :class="{ 'date-sidebar__item--active': visibleDate === d.date }"
          @click="scrollToDate(d.date)"
        >
          <span class="date-sidebar__date">{{ d.dateLabel }}</span>
          <span class="date-sidebar__weekday">{{ d.weekday }}</span>
          <!-- 三餐固定3个位置(早/中/晚):已订餐显色,未订餐不渲染圆点但保留位置空间;整日未订不渲染容器 -->
          <div v-if="dateMealTypes(d.date).length > 0" class="date-sidebar__dots">
            <template v-for="mt in MEAL_TYPE_ORDER" :key="mt">
              <span
                v-if="isMealOrdered(d.date, mt)"
                class="date-sidebar__dot"
                :style="{ backgroundColor: mealDotColor(mt) }"
              ></span>
              <!-- 未订餐的餐别:不渲染圆点,用等宽占位保留位置 -->
              <span v-else class="date-sidebar__dot-placeholder"></span>
            </template>
          </div>
        </button>

        <!-- 空状态:无可订餐日期 -->
        <div v-if="dateList.length === 0" class="date-sidebar__empty">
          暂无可订餐日期
        </div>
      </div>

      <!-- 右侧内容区:所有可订餐日期垂直堆叠,上下滚动自然浏览,无切换动作 -->
      <div
        ref="contentRef"
        class="content-area"
        @scroll.passive="handleContentScroll"
      >
        <!-- 顶部 sticky 日期指示器:跟随当前可视日期(只在菜单加载完成后显示) -->
        <div v-if="allMenusLoaded && dateList.length > 0" class="content-area__sticky-date">
          <span class="content-area__date-num">{{ visibleDateNumber }}</span>
          <span class="content-area__date-rel">{{ visibleDateRelativeLabel }}</span>
        </div>

        <!-- 空状态:无可订餐日期(加载完成后才判断,避免初始 flash) -->
        <EmptyState
          v-if="allMenusLoaded && dateList.length === 0"
          text="暂无可订餐日期"
          icon="default"
        />

        <!-- 全页加载态:并发加载所有日期菜单时显示,加载完成后统一渲染,杜绝布局抖动 -->
        <div v-else-if="!allMenusLoaded" class="content-area__loading">
          <van-loading size="24px">加载菜单中...</van-loading>
        </div>

        <!-- 所有可订餐日期垂直堆叠(单页滚动,无切换;数据已全部就绪,无懒加载占位) -->
        <template v-else>
          <section
            v-for="d in dateList"
            :key="d.date"
            :ref="(el) => setDaySectionRef(d.date, el as Element | null)"
            class="day-section"
            :class="{ 'day-section--active': visibleDate === d.date }"
            :data-date="d.date"
          >
            <!-- 日期标题胶囊(两段式:7月26日 + 今天/明天/周X) -->
            <div class="day-section__header">
              <span class="day-section__date-num">{{ d.dateLabel }}</span>
              <span class="day-section__date-rel">{{ d.weekday }}{{ d.isToday ? ' · 今天' : '' }}</span>
            </div>

            <!-- 当日菜单已就绪:渲染餐别 + 菜品 -->
            <template v-if="getMealSectionsForDate(d.date).length > 0">
              <section
                v-for="section in getMealSectionsForDate(d.date)"
                :key="section.type"
                class="meal-section"
                :class="{ 'meal-section--locked': isMealLocked(d.date, section.type) }"
              >
                <!-- 餐别标题(胶囊样式居中,按餐别配色;已订餐别显示"已订"标记) -->
                <div class="meal-section__pill">
                  <component
                    :is="mealIconMap[section.type]"
                    :size="14"
                    :stroke-width="2.5"
                    class="meal-section__icon"
                    :color="mealIconColor(section.type)"
                  />
                  <span class="meal-section__name" :style="mealPillStyle(section.type)">{{ section.name }}</span>
                  <span v-if="isMealLocked(d.date, section.type)" class="meal-section__locked-tag">已订</span>
                </div>

                <div class="meal-section__grid">
                  <div
                    v-for="iv in section.items"
                    :key="iv.item.id"
                    class="dish-card"
                    :class="{
                      'dish-card--selected':
                        !!iv.dish && getQty(iv.dish.id, d.date, section.type) > 0,
                      'dish-card--disabled': iv.dish?.status === 0,
                      'dish-card--ordered': isMealLocked(d.date, section.type) && !!orderedItemsFor(d.date, section.type).get(iv.dish?.id || 0),
                      'dish-card--locked': isMealLocked(d.date, section.type) && !orderedItemsFor(d.date, section.type).get(iv.dish?.id || 0),
                    }"
                  >
                    <!-- 图片区(智能加载:section 进入视口前 1 屏才渲染 <img> 发请求,否则 emoji 占位) -->
                    <div class="dish-card__img-wrap">
                      <img
                        v-if="isImageActivated(d.date) && getDishImg(iv)"
                        :src="getDishImg(iv)"
                        class="dish-card__img"
                        loading="lazy"
                        @error="handleImgError(iv.item.id)"
                      />
                      <span v-else class="dish-card__emoji">{{ categoryEmoji(iv.dish?.category) }}</span>
                      <!-- 售罄遮罩 -->
                      <span v-if="iv.dish?.status === 0" class="dish-card__soldout">已售罄</span>
                      <!-- 已订数量徽标(锁定餐别下已订菜品图片右上角,显示份数) -->
                      <span
                        v-if="isMealLocked(d.date, section.type) && orderedItemsFor(d.date, section.type).get(iv.dish?.id || 0)"
                        class="dish-card__ordered-badge"
                      >已订{{ orderedItemsFor(d.date, section.type).get(iv.dish?.id || 0) }}份</span>
                    </div>

                    <!-- 底部:菜名+价格 + 操作按钮 -->
                    <div class="dish-card__bottom">
                      <div class="dish-card__info">
                        <p class="dish-card__name">{{ iv.dish?.name || '未知菜品' }}</p>
                        <p class="dish-card__price">¥{{ formatMoney(iv.dish?.price) }}</p>
                      </div>
                      <!-- 已订菜品:绿色 ✅ 标记(突出显示已订状态) -->
                      <div
                        v-if="isMealLocked(d.date, section.type) && orderedItemsFor(d.date, section.type).get(iv.dish?.id || 0)"
                        class="dish-card__ordered-qty"
                      >
                        <Check :size="14" :stroke-width="2.5" />
                        <span>{{ orderedItemsFor(d.date, section.type).get(iv.dish?.id || 0) }}</span>
                      </div>
                      <!-- 未订菜品(锁定餐别下):显示灰色禁用占位,保持卡片对齐 -->
                      <div
                        v-else-if="isMealLocked(d.date, section.type)"
                        class="dish-card__locked-placeholder"
                      ></div>
                      <template v-else-if="iv.dish && iv.dish.status !== 0">
                        <button
                          v-if="getQty(iv.dish.id, d.date, section.type) === 0"
                          type="button"
                          class="dish-card__add"
                          aria-label="加入购物车"
                          @click="handleAdd(iv.dish, d.date, section.type)"
                        >
                          <Plus :size="14" :stroke-width="2.5" />
                        </button>
                        <button
                          v-else
                          type="button"
                          class="dish-card__check"
                          aria-label="减少一份"
                          @click="handleDecrease(iv.dish.id, d.date, section.type)"
                        >
                          <Check :size="14" :stroke-width="2.5" />
                        </button>
                      </template>
                    </div>
                  </div>
                </div>
              </section>
            </template>

            <!-- 已加载但无菜品 -->
            <div v-else class="day-section__empty">
              <span>当日暂无可订餐菜品</span>
            </div>
          </section>

          <!-- 底部提示:加载更多 / 已到末尾 -->
          <div class="content-area__end-hint">
            <span v-if="loadingMore">加载更多...</span>
            <span v-else-if="noMoreData">已展示所有可订餐日期</span>
            <span v-else>向下滚动加载更多</span>
          </div>
        </template>
      </div>
    </div>

    <!-- 黑色浮动购物车栏 -->
    <div class="cart-bar">
      <!-- 购物车图标 + 数量 badge(点击打开弹层查看明细) -->
      <button
        type="button"
        class="cart-bar__icon"
        aria-label="查看购物车"
        @click="showCart = true"
      >
        <ShoppingCart :size="20" :stroke-width="2" />
        <span v-if="totalCount > 0" class="cart-bar__badge">{{ totalCount }}</span>
      </button>

      <!-- 横滑摘要 -->
      <div class="cart-bar__summary">
        <p>{{ cartSummaryText }}</p>
      </div>

      <!-- 结算按钮(点击打开确认弹窗) -->
      <button
        type="button"
        class="cart-bar__btn"
        :class="{ 'cart-bar__btn--disabled': !hasItems || submitting }"
        :disabled="!hasItems || submitting"
        @click="handleSubmitClick"
      >
        <span>¥{{ formatMoney(totalPrice) }}</span>
        <span class="cart-bar__btn-divider"></span>
        <span>去结算</span>
      </button>
    </div>

    <!-- 购物车弹层(查看明细 + 清空) -->
    <van-popup
      v-model:show="showCart"
      position="bottom"
      round
      closeable
      close-icon-position="top-left"
      :style="{ maxHeight: '70%' }"
    >
      <div class="cart-popup">
        <div class="cart-popup__title">
          <span>购物车</span>
          <van-button
            v-if="hasItems"
            type="danger"
            size="mini"
            plain
            class="cart-popup__clear"
            @click="handleClearCart"
          >
            清空
          </van-button>
        </div>

        <div v-if="!hasItems" class="cart-popup__empty">
          <EmptyState text="购物车是空的" icon="default" />
        </div>

        <div v-else class="cart-popup__body">
          <!-- 树形结构:日期 → 餐别 → 菜品 -->
          <div
            v-for="dateGroup in cartDateGrouped"
            :key="dateGroup.date"
            class="cart-popup__date"
          >
            <!-- 日期标题 -->
            <div class="cart-popup__date-header">
              <span class="cart-popup__date-label">{{ dateGroup.dateLabel }}</span>
              <span class="cart-popup__date-subtotal">¥{{ formatMoney(dateGroup.dateSubtotal) }}</span>
            </div>

            <!-- 餐别分组 -->
            <div
              v-for="meal in dateGroup.meals"
              :key="`${dateGroup.date}-${meal.mealType}`"
              class="cart-popup__meal"
            >
              <!-- 餐别标题(单字 badge 按餐别配色 + 全称 + 小计) -->
              <div class="cart-popup__meal-header">
                <span class="cart-popup__meal-badge" :style="mealBadgeStyle(meal.mealType)">{{ meal.mealName }}</span>
                <span class="cart-popup__meal-fullname">{{ meal.mealFullName }}</span>
                <span class="cart-popup__meal-subtotal">¥{{ formatMoney(meal.subtotal) }}</span>
              </div>

              <!-- 菜品行 -->
              <div
                v-for="entry in meal.entries"
                :key="entry.dish.id"
                class="cart-popup__item"
              >
                <div class="cart-popup__item-name">{{ entry.dish.name }}</div>
                <div class="cart-popup__item-price">¥{{ formatMoney(entry.dish.price) }}</div>
                <!-- 自定义+/-按钮(避免 van-stepper 受控模式时序 bug:emit minus 后内部 updateValue 用已更新的 currentValue 再减一次,导致 decreaseItem 被调用两次) -->
                <div class="cart-popup__stepper">
                  <button
                    type="button"
                    class="cart-popup__stepper-btn cart-popup__stepper-btn--minus"
                    @click="cartStore.decreaseItem(entry.dish.id, dateGroup.date, meal.mealType)"
                  >
                    <Minus :size="13" strokeWidth=2.5 />
                  </button>
                  <span class="cart-popup__stepper-value">{{ entry.quantity }}</span>
                  <button
                    type="button"
                    class="cart-popup__stepper-btn cart-popup__stepper-btn--plus"
                    @click="cartStore.addItem(entry.dish, dateGroup.date, meal.mealType)"
                  >
                    <Plus :size="13" strokeWidth=2.5 />
                  </button>
                </div>
                <div class="cart-popup__item-subtotal">¥{{ formatMoney(entry.dish.price * entry.quantity) }}</div>
              </div>
            </div>
          </div>
        </div>

        <div v-if="hasItems" class="cart-popup__footer safe-area-bottom">
          <div class="cart-popup__footer-total">
            合计 <span class="cart-popup__footer-price">¥{{ formatMoney(totalPrice) }}</span>
          </div>
          <van-button
            type="primary"
            round
            :loading="submitting"
            class="cart-popup__footer-btn"
            @click="handleSubmitClick"
          >
            立即下单
          </van-button>
        </div>
      </div>
    </van-popup>

    <!-- 下单确认弹窗:显示跨日期/餐别汇总,确认后才真正下单 -->
    <van-popup
      v-model:show="showSubmitConfirm"
      position="bottom"
      round
      closeable
      close-icon-position="top-left"
      :style="{ maxHeight: '80%' }"
    >
      <div class="confirm-popup">
        <div class="confirm-popup__title">确认订单</div>

        <div class="confirm-popup__body">
          <!-- 树形结构:日期 → 餐别 → 菜品(与购物车弹层保持一致) -->
          <div
            v-for="dateGroup in cartDateGrouped"
            :key="dateGroup.date"
            class="confirm-popup__date"
          >
            <!-- 日期标题 -->
            <div class="confirm-popup__date-header">
              <span class="confirm-popup__date-label">{{ dateGroup.dateLabel }}</span>
              <span class="confirm-popup__date-subtotal">¥{{ formatMoney(dateGroup.dateSubtotal) }}</span>
            </div>

            <!-- 餐别分组 -->
            <div
              v-for="meal in dateGroup.meals"
              :key="`${dateGroup.date}-${meal.mealType}`"
              class="confirm-popup__meal"
            >
              <!-- 餐别标题(单字 badge 按餐别配色 + 全称 + 小计) -->
              <div class="confirm-popup__meal-header">
                <span class="confirm-popup__meal-badge" :style="mealBadgeStyle(meal.mealType)">{{ meal.mealName }}</span>
                <span class="confirm-popup__meal-fullname">{{ meal.mealFullName }}</span>
                <span class="confirm-popup__meal-subtotal">¥{{ formatMoney(meal.subtotal) }}</span>
              </div>

              <!-- 菜品行 -->
              <div
                v-for="entry in meal.entries"
                :key="entry.dish.id"
                class="confirm-popup__row"
              >
                <span class="confirm-popup__row-name">{{ entry.dish.name }}</span>
                <span class="confirm-popup__row-qty">x{{ entry.quantity }}</span>
                <span class="confirm-popup__row-price">¥{{ formatMoney(entry.dish.price * entry.quantity) }}</span>
              </div>
            </div>
          </div>
        </div>

        <!-- 底部总价 + 确认按钮 -->
        <div class="confirm-popup__footer safe-area-bottom">
          <div class="confirm-popup__total">
            合计 <span class="confirm-popup__total-price">¥{{ formatMoney(totalPrice) }}</span>
          </div>
          <van-button
            type="primary"
            round
            :loading="submitting"
            class="confirm-popup__btn"
            @click="confirmSubmit"
          >
            确认下单
          </van-button>
        </div>
      </div>
    </van-popup>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables' as *;

.order-page {
  display: flex;
  flex-direction: column;
  // 视口高度减去 TabBar(64px + 安全区),由 App.vue 的 padding-bottom 保证不被 TabBar 遮挡
  height: calc(100vh - 64px - env(safe-area-inset-bottom));
  background: $brand-card;
}

/* 主体区域:左侧日期竖列 + 右侧内容 */
.order-main {
  flex: 1;
  display: flex;
  overflow: hidden;
  min-height: 0;
}

/* ============ 左侧日期竖列 ============ */
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
    padding: 15px 4px;
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
      /* 激活态下圆点保持自身餐别颜色,不被覆盖 */
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
    /* backgroundColor 由内联样式按餐别动态控制 */
  }

  /* 未订餐的餐别占位:不渲染圆点,但保留等宽等高位置,保持3个位置布局固定 */
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
}

/* ============ 右侧内容区 ============ */
.content-area {
  flex: 1;
  overflow-y: auto;
  // 底部留白避开浮动购物车栏(购物车栏占位约 64px+8px+56px=128px,留 140px 余量)
  padding: 0 12px 140px;
  -webkit-overflow-scrolling: touch;
  /* 防止滚动链传到父容器,避免误触页面级滚动 */
  overscroll-behavior: contain;

  /* 顶部 sticky 日期指示器:跟随当前可视日期,吸顶显示 */
  &__sticky-date {
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

  /* 全页加载态:并发加载所有日期菜单时显示 */
  &__loading {
    display: flex;
    justify-content: center;
    align-items: center;
    min-height: 200px;
    padding: 40px 0;
  }

  /* 日期数字 + 相对标签(两段式胶囊,sticky 指示器复用) */
  &__date-num,
  &__date-rel {
    display: inline-flex;
    align-items: center;
    padding: 8px 18px;
    font-size: 15px;
    font-weight: 700;
    border-radius: 999px;
    letter-spacing: 0.5px;
  }

  &__date-num {
    background: linear-gradient(135deg, rgba(0, 101, 253, 0.08), rgba(0, 101, 253, 0.18));
    color: $brand-primary;
    border: 1px solid rgba(0, 101, 253, 0.25);
    box-shadow: 0 2px 8px rgba(0, 101, 253, 0.12);
    border-top-right-radius: 0;
    border-bottom-right-radius: 0;
    border-right: none;
  }

  &__date-rel {
    background: $brand-primary;
    color: #fff;
    border: 1px solid $brand-primary;
    border-top-left-radius: 0;
    border-bottom-left-radius: 0;
    box-shadow: 0 2px 8px rgba(0, 101, 253, 0.2);
  }

  /* 底部提示 */
  &__end-hint {
    display: flex;
    justify-content: center;
    align-items: center;
    padding: 16px 0 24px;

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
}

/* ============ 日期 section(单页堆叠模式:每个日期一个 section) ============ */
.day-section {
  padding: 4px 0 8px;
  /* 滚动跳转锚点偏移:避免 sticky 日期指示器遮挡日期标题 */
  scroll-margin-top: 0;

  &--active {
    /* 当前可视日期高亮(可选,左侧栏已有高亮) */
  }

  /* 日期标题胶囊(两段式:7月26日 + 今天/明天/周X) */
  &__header {
    display: flex;
    justify-content: center;
    align-items: center;
    margin: 16px 0 12px;
  }

  &__date-num {
    display: inline-flex;
    align-items: center;
    padding: 6px 14px;
    font-size: 14px;
    font-weight: 600;
    color: $brand-secondary-foreground;
    background: $brand-card;
    border: 1px solid $brand-border;
    border-radius: 999px;
    border-top-right-radius: 0;
    border-bottom-right-radius: 0;
    border-right: none;
  }

  &__date-rel {
    display: inline-flex;
    align-items: center;
    padding: 6px 14px;
    font-size: 14px;
    font-weight: 600;
    color: $brand-primary;
    background: rgba(0, 101, 253, 0.08);
    border: 1px solid rgba(0, 101, 253, 0.2);
    border-radius: 999px;
    border-top-left-radius: 0;
    border-bottom-left-radius: 0;
  }

  /* 已加载但无菜品 */
  &__empty {
    display: flex;
    justify-content: center;
    align-items: center;
    min-height: 80px;
    padding: 16px 0;
    color: $brand-muted-foreground;
    font-size: 13px;
  }
}

/* ============ 餐别 section ============ */
.meal-section {
  margin-bottom: 20px;

  /* 已锁定(已下单)餐别:整体降低饱和度,提示不可操作 */
  &--locked {
    .meal-section__grid {
      opacity: 0.85;
    }
  }

  /* "已订"标记(餐别标题旁) */
  &__locked-tag {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    margin-left: 8px;
    padding: 2px 10px;
    font-size: 11px;
    font-weight: 700;
    color: #fff;
    background: $brand-primary;
    border-radius: 999px;
    letter-spacing: 0.5px;
  }

  /* 餐别胶囊(居中,加宽;颜色由内联样式动态控制:早餐橙/午餐蓝/晚餐紫) */
  &__pill {
    display: flex;
    justify-content: center;
    align-items: center;
    gap: 6px;
    margin: 0 0 12px;
  }

  &__icon {
    flex-shrink: 0;
    /* color 由模板内联 color 属性动态控制 */
  }

  &__name {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    min-width: 110px;
    padding: 6px 28px;
    font-size: 14px;
    font-weight: 700;
    border-radius: 999px;
    /* background / color / border-color / box-shadow 由内联样式动态控制 */
    letter-spacing: 1px;
  }

  &__grid {
    display: grid;
    grid-template-columns: repeat(2, 1fr);
    gap: 8px;
  }
}

/* ============ 菜品卡片 ============ */
.dish-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 10px;
  background: $brand-card;
  border: 1px solid $brand-border;
  border-radius: 12px;
  transition: border-color 0.15s ease;

  &--selected {
    border: 2px solid $brand-primary;
    // 抵消多出的 1px border,保持视觉尺寸一致
    padding: 9px;
  }

  &--disabled {
    opacity: 0.55;
  }

  /* 已订菜品(锁定餐别下已下单的菜):绿色边框 + 浅绿背景,突出显示已订状态 */
  &--ordered {
    border: 2px solid #16a34a;
    background: #f0fdf4;
    padding: 9px;
  }

  /* 未订菜品(锁定餐别下未下单的菜):灰色不可选 */
  &--locked {
    opacity: 0.5;
    border-color: $brand-border;
    background: $brand-muted;
  }

  &__img-wrap {
    position: relative;
    width: 80px;
    height: 80px;
    border-radius: 8px;
    background: $brand-muted;
    display: flex;
    align-items: center;
    justify-content: center;
    overflow: hidden;
  }

  &__img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }

  &__emoji {
    font-size: 32px;
    line-height: 1;
  }

  &__new {
    position: absolute;
    top: 0;
    left: 0;
    padding: 2px 6px;
    background: $brand-accent;
    color: $brand-accent-foreground;
    font-size: 10px;
    font-weight: 600;
    border-radius: 8px 0 8px 0;
  }

  &__soldout {
    position: absolute;
    inset: 0;
    display: flex;
    align-items: center;
    justify-content: center;
    background: rgba(255, 255, 255, 0.85);
    color: $brand-muted-foreground;
    font-size: 12px;
    font-weight: 600;
  }

  &__bottom {
    width: 100%;
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 4px;
  }

  &__info {
    flex: 1;
    min-width: 0;
  }

  &__name {
    margin: 0;
    font-size: 12px;
    font-weight: 500;
    color: $brand-card-foreground;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__price {
    margin: 2px 0 0;
    font-size: 12px;
    font-weight: 700;
    color: $brand-primary;
  }

  &__add,
  &__check {
    flex-shrink: 0;
    width: 24px;
    height: 24px;
    border-radius: 50%;
    background: $brand-primary;
    color: $brand-primary-fg;
    border: none;
    cursor: pointer;
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 0;

    &:active {
      opacity: 0.85;
    }
  }

  /* 已订数量徽标(已订菜品图片右上角,绿色,显示已订份数) */
  &__ordered-badge {
    position: absolute;
    top: 0;
    right: 0;
    padding: 2px 6px;
    font-size: 10px;
    font-weight: 700;
    color: #fff;
    background: #16a34a;
    border-radius: 0 8px 0 8px;
    box-shadow: 0 1px 4px rgba(22, 163, 74, 0.3);
    letter-spacing: 0.3px;
  }

  /* 已订数量显示(绿色 ✅ + 份数,代替加/减按钮) */
  &__ordered-qty {
    flex-shrink: 0;
    display: inline-flex;
    align-items: center;
    gap: 2px;
    min-width: 24px;
    height: 24px;
    padding: 0 6px;
    border-radius: 12px;
    background: #16a34a;
    color: #fff;
    font-size: 12px;
    font-weight: 700;
    border: 1px solid #15803d;
    font-variant-numeric: tabular-nums;
  }

  /* 未订菜品占位(锁定餐别下未下单的菜,灰色占位保持卡片对齐) */
  &__locked-placeholder {
    flex-shrink: 0;
    width: 24px;
    height: 24px;
    border-radius: 50%;
    background: $brand-border;
    opacity: 0.6;
  }
}

/* ============ 黑色浮动购物车栏 ============ */
.cart-bar {
  position: fixed;
  left: 12px;
  right: 12px;
  // 位于 TabBar(64px + 安全区)之上,留 8px 间距
  bottom: calc(64px + 8px + env(safe-area-inset-bottom));
  z-index: 50;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 16px;
  background: $brand-foreground;
  border-radius: 16px;
  box-shadow: 0 -4px 20px rgba(0, 0, 0, 0.15);

  &__icon {
    position: relative;
    flex-shrink: 0;
    width: 28px;
    height: 28px;
    background: transparent;
    border: none;
    cursor: pointer;
    color: $brand-primary-fg;
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 0;
  }

  &__badge {
    position: absolute;
    top: -6px;
    right: -8px;
    min-width: 16px;
    height: 16px;
    padding: 0 4px;
    border-radius: 8px;
    background: $brand-primary;
    color: $brand-primary-fg;
    font-size: 10px;
    font-weight: 700;
    line-height: 16px;
    text-align: center;
    box-sizing: border-box;
  }

  &__summary {
    flex: 1;
    overflow-x: auto;
    white-space: nowrap;
    -webkit-overflow-scrolling: touch;
    scrollbar-width: none;
    &::-webkit-scrollbar { display: none; }

    p {
      margin: 0;
      font-size: 12px;
      color: rgba(255, 255, 255, 0.7);
    }
  }

  &__btn {
    flex-shrink: 0;
    height: 36px;
    padding: 0 16px;
    border-radius: 12px;
    background: $brand-primary;
    color: $brand-primary-fg;
    border: none;
    cursor: pointer;
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 14px;
    font-weight: 600;

    &:active {
      opacity: 0.85;
    }

    &--disabled {
      opacity: 0.5;
      cursor: not-allowed;
    }
  }

  &__btn-divider {
    width: 1px;
    height: 16px;
    background: rgba(255, 255, 255, 0.3);
  }
}

/* ============ 购物车弹层 ============ */
.cart-popup {
  display: flex;
  flex-direction: column;
  max-height: 70vh;

  &__title {
    position: relative;
    text-align: center;
    font-size: 16px;
    font-weight: 600;
    padding: 14px 16px 10px;
    border-bottom: 1px solid $brand-border;
  }

  &__clear {
    position: absolute;
    right: 12px;
    top: 50%;
    transform: translateY(-50%);
  }

  &__empty {
    padding: 40px 0;
  }

  &__body {
    flex: 1;
    overflow-y: auto;
    padding: 8px 0;
    -webkit-overflow-scrolling: touch;
  }

  /* 日期分组(树形结构:日期 → 餐别 → 菜品) */
  &__date {
    margin-bottom: 8px;
  }

  &__date-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 10px 16px 6px;
    background: $brand-secondary;
  }

  &__date-label {
    font-size: 14px;
    font-weight: 700;
    color: $brand-foreground;
  }

  &__date-subtotal {
    font-size: 13px;
    font-weight: 600;
    color: $brand-primary;
  }

  /* 餐别分组 */
  &__meal {
    padding: 4px 0 8px;
  }

  &__meal-header {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 6px 16px;
  }

  &__meal-badge {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 22px;
    height: 22px;
    font-size: 13px;
    font-weight: 700;
    border-radius: 50%;
    /* background / color / border-color 由内联样式动态控制 */
  }

  &__meal-fullname {
    flex: 1;
    font-size: 13px;
    font-weight: 600;
    color: $brand-foreground;
  }

  &__meal-subtotal {
    font-size: 12px;
    font-weight: 600;
    color: $brand-muted-foreground;
  }

  &__item {
    display: flex;
    align-items: center;
    padding: 10px 16px;
    gap: 8px;
  }

  &__item-name {
    flex: 1;
    font-size: 14px;
    color: $brand-foreground;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__item-price {
    font-size: 12px;
    color: $brand-muted-foreground;
    flex-shrink: 0;
  }

  &__item-subtotal {
    width: 60px;
    text-align: right;
    font-size: 14px;
    font-weight: 600;
    color: $brand-primary;
    flex-shrink: 0;
  }

  /* 自定义步进器(替代 van-stepper,避免受控模式时序 bug) */
  &__stepper {
    display: flex;
    align-items: center;
    gap: 6px;
    flex-shrink: 0;
  }

  &__stepper-btn {
    width: 24px;
    height: 24px;
    border-radius: 50%;
    border: none;
    display: flex;
    align-items: center;
    justify-content: center;
    cursor: pointer;
    padding: 0;
    transition: opacity 0.15s, transform 0.1s;
    user-select: none;

    &:active {
      transform: scale(0.88);
    }

    &--minus {
      background: rgba(0, 0, 0, 0.06);
      color: $brand-secondary-foreground;
    }

    &--plus {
      background: $brand-primary;
      color: #fff;
    }
  }

  &__stepper-value {
    min-width: 22px;
    text-align: center;
    font-size: 14px;
    font-weight: 600;
    color: $brand-foreground;
    font-variant-numeric: tabular-nums;
  }

  &__footer {
    display: flex;
    align-items: center;
    padding: 10px 16px;
    border-top: 1px solid $brand-border;
    background: $brand-card;
    gap: 12px;
  }

  &__footer-total {
    flex: 1;
    font-size: 14px;
    color: $brand-muted-foreground;
  }

  &__footer-price {
    font-size: 18px;
    font-weight: 700;
    color: $brand-primary;
  }

  &__footer-btn {
    min-width: 120px;
  }
}

/* ============ 下单确认弹窗 ============ */
.confirm-popup {
  display: flex;
  flex-direction: column;
  max-height: 80vh;

  &__title {
    text-align: center;
    font-size: 16px;
    font-weight: 700;
    padding: 14px 16px 10px;
    border-bottom: 1px solid $brand-border;
    color: $brand-foreground;
  }

  &__body {
    flex: 1;
    overflow-y: auto;
    padding: 8px 0;
    -webkit-overflow-scrolling: touch;
  }

  /* 日期分组(树形结构:日期 → 餐别 → 菜品) */
  &__date {
    margin-bottom: 8px;
  }

  &__date-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 10px 16px 6px;
    background: $brand-secondary;
  }

  &__date-label {
    font-size: 14px;
    font-weight: 700;
    color: $brand-foreground;
  }

  &__date-subtotal {
    font-size: 13px;
    font-weight: 600;
    color: $brand-primary;
  }

  /* 餐别分组 */
  &__meal {
    padding: 4px 0 8px;
  }

  &__meal-header {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 6px 16px;
  }

  &__meal-badge {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 22px;
    height: 22px;
    font-size: 13px;
    font-weight: 700;
    border-radius: 50%;
    /* background / color / border-color 由内联样式动态控制 */
  }

  &__meal-fullname {
    flex: 1;
    font-size: 13px;
    font-weight: 600;
    color: $brand-foreground;
  }

  &__meal-subtotal {
    font-size: 12px;
    font-weight: 600;
    color: $brand-muted-foreground;
  }

  &__row {
    display: flex;
    align-items: center;
    padding: 8px 16px;
    gap: 12px;
  }

  &__row-name {
    flex: 1;
    font-size: 14px;
    color: $brand-foreground;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__row-qty {
    font-size: 13px;
    color: $brand-muted-foreground;
    flex-shrink: 0;
  }

  &__row-price {
    width: 72px;
    text-align: right;
    font-size: 14px;
    font-weight: 600;
    color: $brand-foreground;
    flex-shrink: 0;
  }

  &__footer {
    display: flex;
    align-items: center;
    padding: 10px 16px;
    border-top: 1px solid $brand-border;
    background: $brand-card;
    gap: 12px;
  }

  &__total {
    flex: 1;
    font-size: 14px;
    color: $brand-muted-foreground;
  }

  &__total-price {
    font-size: 18px;
    font-weight: 700;
    color: $brand-primary;
  }

  &__btn {
    min-width: 140px;
  }
}
</style>
