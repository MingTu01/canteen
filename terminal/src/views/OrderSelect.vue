<script setup lang="ts">
/**
 * 选菜页
 *
 * 订餐流程核心页:
 * - 顶栏(返回订餐菜单 + 右上角时钟)
 * - 左右布局:
 *     左侧 DateSidebar(7 天快捷导航,上下翻页)
 *     右侧 MealSection 列表(按餐别分组,3列网格菜品,支持 +/- 调数量)
 * - 悬浮购物车(常驻底部,点击信息区打开预览弹窗)
 * - 日期范围:从明天起 30 天(若已过今天 15:00 截止,则从后天起)
 * - 已下单餐别锁定:整块不可加菜,已订菜品框出 + "已订" 标记,日期圆点保留
 * - 无操作 30 秒自动返回待机
 */
import { ref, computed, onMounted, nextTick, watch } from 'vue'
import { useRouter } from 'vue-router'
import api, { loadConfig as loadTerminalConfig } from '@/api'
import {
  orderStore,
  resetOrderFlow,
  addDish,
  decDish,
  removeDish,
  dishQuantity,
  getMealTypesForDate,
  getCartDateGroups,
  cartTotalCount,
  cartTotalAmount,
} from '@/store/order'
import { useIdleTimer } from '@/composables/useIdleTimer'
import { useMealConfig } from '@/composables/useMealConfig'
import { useOrderConfig } from '@/composables/useOrderConfig'
import { formatMoney } from '@/composables/useFormat'
import { toDateKey, dateWindow, parseDateKey, relativeLabel, pad2, shortDate, dateRelLabel } from '@/utils'
import { mealTypeLabel } from '@/utils'
import { menuInvalidated, getCachedMenu, cacheMenu } from '@/utils/cache'
import { ShoppingCart, X, Plus, Minus, Trash2 } from 'lucide-vue-next'
import TopBar from '@/components/TopBar.vue'
import DatePicker from '@/components/DatePicker.vue'
import DateSidebar from '@/components/DateSidebar.vue'
import MealSection from '@/components/MealSection.vue'

const router = useRouter()

/**
 * 可订餐日期范围(规则:不显示今天,从明天起;若已过今天截止时间,则从后天起)。
 * 截止时间由后端 order-config 驱动(默认 15:00),通过 isOrderableByDeadline 判定。
 * 配置加载后 config 变化会自动重算起始日期与日期窗口。
 */
const { loadConfig, isOrderableByDeadline } = useOrderConfig()
const now = new Date()
const startDateKey = computed(() => {
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  const tomorrow = new Date(today)
  tomorrow.setDate(tomorrow.getDate() + 1)
  if (isOrderableByDeadline(toDateKey(tomorrow), now)) {
    return toDateKey(tomorrow) // 明天起
  }
  const dayAfter = new Date(today)
  dayAfter.setDate(dayAfter.getDate() + 2)
  return toDateKey(dayAfter) // 后天起
})
/** 未来 30 天可订餐日期 */
const allDates = computed(() => dateWindow(startDateKey.value, 30, 1))

/** 左侧 DateSidebar 候选日期(全部 30 天,由 DateSidebar 按 availableSet 过滤为有菜单的日期) */
const sidebarDates = computed(() => allDates.value)

/** menuCache: 日期 -> 转换后的菜单数组 [{id, mealType, menuItems}] */
const menuCache = ref<Record<string, any[]>>({})
const loadingSet = ref<Set<string>>(new Set())

/** 已下单订单(从后端拉取,用于锁定已订餐别) */
const orderedOrders = ref<any[]>([])

/** 右侧菜品区滚动容器(用于 scroll 事件监听 + 滚动控制) */
const contentRef = ref<HTMLElement | null>(null)

/** 当前可视日期(滚动时动态更新,驱动左侧栏高亮 + sticky 日期指示器) */
const visibleDate = ref<string>('')
/** 每个日期 section 的 DOM 引用,用于滚动定位 + 可视检测 */
const daySectionRefs: Record<string, HTMLElement | null> = {}
/** scroll 事件节流时间戳 */
let lastScrollCalcTime = 0
/** 程序滚动标志(点击左侧栏跳转时屏蔽可视日期更新,避免跳动) */
let isProgrammaticScroll = false

/** 当前选中日期(双向同步到 orderStore,与 visibleDate 保持同步) */
const selectedDate = computed({
  get: () => orderStore.selectedDate,
  set: (v: string) => { orderStore.selectedDate = v },
})

/**
 * 将后端菜单响应拍平为前端结构。
 * 后端返回 [{menu:{id,mealType,...}, items:[{item,dish}]}]
 * 前端拍平成 [{id, mealType, menuItems:[{dishId,dishName,price,category,image,spiceLevel}]}]
 */
const transformMenu = (raw: any[]) =>
  raw.map((m: any) => ({
    id: m.menu?.id,
    mealType: Number(m.menu?.mealType),
    menuItems: (m.items || []).map((it: any) => ({
      dishId: it.dish?.id,
      dishName: it.dish?.name,
      price: it.dish?.price,
      category: it.dish?.category,
      image: it.dish?.image,
      spiceLevel: it.dish?.spiceLevel,
    })),
  }))

/**
 * 加载某日菜单(三级缓存:内存 → IndexedDB → 后端)。
 * 1. 内存命中 → 直接返回(最快,同次会话内)
 * 2. IndexedDB 命中 → 秒开,后台静默刷新后端数据
 * 3. 后端拉取 → 写入 IndexedDB + 内存
 */
const loadMenu = async (date: string) => {
  if (!date || (menuCache.value[date] && menuCache.value[date].length > 0) || loadingSet.value.has(date)) return
  loadingSet.value.add(date)
  const storeId = orderStore.employee?.storeId
  try {
    // 1. 先查 IndexedDB 本地缓存(秒开,页面刷新后仍可用)
    if (storeId) {
      const local = await getCachedMenu<any[]>(storeId, date)
      if (local && local.length > 0) {
        menuCache.value[date] = transformMenu(local)
        // 后台静默刷新后端数据(不阻塞 UI,有变化时覆盖)
        refreshMenuFromBackend(storeId, date).catch(() => {})
        return
      }
    }
    // 2. 本地无缓存 → 直接请求后端
    await fetchAndCacheMenu(storeId, date)
  } catch {
    menuCache.value[date] = []
  } finally {
    loadingSet.value.delete(date)
  }
}

/** 从后端拉取菜单并写入 IndexedDB + 内存 */
const fetchAndCacheMenu = async (storeId: number | undefined, date: string) => {
  const resp = await api.get(`/menu/store/${storeId}/date/${date}`)
  const raw = resp.data.code === 200 ? resp.data.data || [] : []
  menuCache.value[date] = transformMenu(raw)
  // 写入 IndexedDB 持久化缓存(下次启动仍可秒开)
  if (storeId) {
    await cacheMenu(storeId, date, raw).catch(() => {})
  }
}

/** 后台静默刷新:从后端拉取最新菜单,有变化时更新内存 + IndexedDB */
const refreshMenuFromBackend = async (storeId: number | undefined, date: string) => {
  const resp = await api.get(`/menu/store/${storeId}/date/${date}`)
  const raw = resp.data.code === 200 ? resp.data.data || [] : []
  const transformed = transformMenu(raw)
  // 仅在数据变化时更新(避免不必要的响应式触发)
  const current = JSON.stringify(menuCache.value[date] || [])
  if (JSON.stringify(transformed) !== current) {
    menuCache.value[date] = transformed
    if (storeId) {
      await cacheMenu(storeId, date, raw).catch(() => {})
    }
  }
}

/** 并发加载 30 天菜单 */
const loadAllMenus = async () => {
  // 限制并发 5 个,避免一次性发 30 个请求
  const queue = [...allDates.value]
  const concurrency = 5
  const workers = Array.from({ length: concurrency }, async () => {
    while (queue.length) {
      const d = queue.shift()
      if (d) await loadMenu(d)
    }
  })
  await Promise.all(workers)
}

/** 拉取员工已下单订单(status=1 待取餐,用于锁定已订餐别) */
const fetchOrderedOrders = async () => {
  try {
    const empId = orderStore.employee?.id
    if (!empId) return
    const resp = await api.get(`/order/employee/${empId}`)
    const list: any[] = resp.data?.code === 200 ? resp.data.data || [] : []
    // 仅保留 status=1(待取餐)的订单,不做日期范围过滤
    orderedOrders.value = list.filter((o) => o.status === 1)
    // items fallback:后端可能未填充 items,对 items 为空的订单批量请求 /order/{id} 补充
    await fillMissingOrderItems(orderedOrders.value)
  } catch {
    orderedOrders.value = []
  }
}

/**
 * items fallback:
 * 后端 getOrdersByEmployee 可能未填充 items(旧版容器),
 * 前端对 items 为空的订单批量调用 GET /order/{id} 补充菜品明细。
 * getOrderDetail 返回 {order, items},items 为菜品列表。
 */
const fillMissingOrderItems = async (orderList: any[]) => {
  const needFill = orderList.filter(
    (o) => (!o.items || o.items.length === 0) && o.id,
  )
  if (needFill.length === 0) return
  const concurrency = 5
  const queue = [...needFill]
  const workers = Array.from({ length: Math.min(concurrency, queue.length) }, async () => {
    while (queue.length) {
      const o = queue.shift()
      if (!o) break
      try {
        const resp = await api.get(`/order/${o.id}`)
        const data = resp.data?.data
        if (data && data.items) {
          o.items = data.items
        }
      } catch {
        /* 单个订单详情获取失败不影响整体 */
      }
    }
  })
  await Promise.all(workers)
}

/** 有菜单的日期集合 */
const availableSet = computed(() => {
  const s = new Set<string>()
  for (const d of allDates.value) {
    const list = menuCache.value[d]
    if (list && list.length > 0) s.add(d)
  }
  return s
})

/** 已订餐日期集合(status=1 的已下单订单日期,用于 DatePicker 蓝点标记) */
const orderedDateSet = computed(() => {
  const s = new Set<string>()
  for (const o of orderedOrders.value) {
    if (o.date && o.status === 1) s.add(o.date)
  }
  return s
})

/** 可订餐日期列表(有菜单的日期,按时间升序,用于堆叠渲染 + 左侧栏) */
const dateList = computed(() => allDates.value.filter((d) => availableSet.value.has(d)))

/** 指定日期的菜单按餐别排序 */
const mealSectionsFor = (date: string) => {
  const menus = menuCache.value[date] || []
  return [...menus].sort((a: any, b: any) => a.mealType - b.mealType)
}

/** 购物车全局总数/总价(跨日期) */
const cartCount = cartTotalCount
const cartTotal = cartTotalAmount
/** 全部菜单是否加载完成(完成后统一渲染堆叠 section,杜绝布局抖动) */
const allLoaded = ref(false)

/**
 * 某日期已订餐的餐别列表:
 * - 已下单订单中的(status=1)
 * - 购物车中已选的(getMealTypesForDate)
 * 合并去重后传给 DateSidebar,显示 3 个圆点。
 */
const mealTypesFor = (date: string) => {
  const set = new Set<number>(getMealTypesForDate(date))
  for (const o of orderedOrders.value) {
    if (o.date === date && o.status === 1) set.add(Number(o.mealType))
  }
  return Array.from(set).sort((a, b) => a - b)
}

/** 指定日期下,已下单(status=1)的餐别集合 */
const lockedMealTypesFor = (date: string): Set<number> => {
  const s = new Set<number>()
  for (const o of orderedOrders.value) {
    if (o.date === date && o.status === 1) s.add(Number(o.mealType))
  }
  return s
}

/** 指定日期 + 餐别下,已下单的菜品映射:dishId -> quantity */
const orderedItemsFor = (date: string, mealType: number): Map<number, number> => {
  const m = new Map<number, number>()
  for (const o of orderedOrders.value) {
    if (o.date === date && Number(o.mealType) === mealType && o.status === 1) {
      // 后端返回 items 字段(批量查询填充)
      const items: any[] = o.items ?? []
      for (const it of items) {
        // 兼容驼峰/下划线/旧字段名
        const id = Number(it.dishId ?? it.dish_id ?? it.dishID ?? 0)
        const qty = Number(it.quantity ?? 1)
        if (id > 0) m.set(id, qty)
      }
    }
  }
  return m
}

/**
 * 点击左侧 DateSidebar / 头部 DatePicker:平滑滚动到对应日期 section。
 * 单页堆叠模式:所有日期已在 DOM 中,滚动即可(不切换、不替换数据)。
 */
const scrollToDate = (date: string): void => {
  const el = daySectionRefs[date]
  if (!el) return
  // 屏蔽滚动期间的可视日期更新,避免与平滑滚动冲突
  isProgrammaticScroll = true
  el.scrollIntoView({ behavior: 'smooth', block: 'start' })
  visibleDate.value = date
  selectedDate.value = date
  loadMenu(date)
  // 平滑滚动结束后恢复监听(500ms 足够覆盖大多数滚动时长)
  setTimeout(() => { isProgrammaticScroll = false }, 500)
}

/** DateSidebar 头部 DatePicker 选中某日期 */
const onSelectDate = (key: string) => {
  scrollToDate(key)
}

/** 左侧 DateSidebar 列表选中某日期 */
const onSelectSidebar = (key: string) => {
  scrollToDate(key)
}

/** 菜品 + / -(按所在日期 section 的日期归位购物车) */
const onInc = (item: any, mealType: number, date: string) => {
  if (lockedMealTypesFor(date).has(mealType)) return
  addDish(item, mealType, date)
}
const onDec = (item: any, mealType: number, date: string) => {
  if (lockedMealTypesFor(date).has(mealType)) return
  decDish(item.dishId, mealType, date)
}

/** 取某菜品在指定日期 + 餐别下的购物车数量 */
const getQuantity = (dishId: number, date: string, mealType: number) =>
  dishQuantity(dishId, date, mealType)

/* ============ 无极滑动切换日期(单页堆叠模式,对齐 H5 Order.vue) ============ */
/** 设置日期 section 的 DOM 引用(供 v-for :ref 回调用) */
const setDaySectionRef = (date: string, el: Element | null): void => {
  daySectionRefs[date] = (el as HTMLElement) || null
}

/**
 * 内容区滚动事件处理(单页堆叠模式):
 * - 节流计算当前可视日期(触发线算法)→ 更新 visibleDate + selectedDate
 * - 程序滚动期间(点击左侧栏跳转)忽略,避免与平滑滚动冲突
 */
const handleContentScroll = (): void => {
  if (isProgrammaticScroll) return
  const now = Date.now()
  if (now - lastScrollCalcTime < 100) return // 100ms 节流
  lastScrollCalcTime = now
  updateVisibleDate()
}

/**
 * 计算当前可视日期(触发线算法,稳定不跳动)。
 * 触发线位于容器顶部下方 60px(sticky 日期指示器之下):
 * - 若触发线落在某 section 内 → 选定该日期
 * - 否则(触发线在 section 之间空隙)→ 选触发线上方最近的 section
 */
const updateVisibleDate = (): void => {
  const container = contentRef.value
  if (!container) return
  const containerTop = container.getBoundingClientRect().top
  const TRIGGER_Y = 60
  let bestDate = ''
  let bestTop = -Infinity
  for (const d of dateList.value) {
    const el = daySectionRefs[d]
    if (!el) continue
    const rect = el.getBoundingClientRect()
    const top = rect.top - containerTop
    const bottom = rect.bottom - containerTop
    // 触发线在 section 内:直接选定,跳出循环(稳定锚点)
    if (top <= TRIGGER_Y && bottom > TRIGGER_Y) {
      bestDate = d
      break
    }
    // 否则记录"触发线上方的最后一个 section"(section 已完全滚过触发线)
    if (top <= TRIGGER_Y && top >= bestTop) {
      bestTop = top
      bestDate = d
    }
  }
  if (bestDate && bestDate !== visibleDate.value) {
    visibleDate.value = bestDate
    selectedDate.value = bestDate
  }
}

/** sticky 日期指示器:当前可视日期的数字日期 + 相对标签 */
const visibleDateNumber = computed(() => shortDate(visibleDate.value || selectedDate.value))
const visibleDateRel = computed(() => dateRelLabel(visibleDate.value || selectedDate.value))

const goCheckout = () => {
  if (cartCount.value === 0) return
  router.push('/order/confirm')
}

/* ============ 购物车预览弹窗 ============ */
const showCartModal = ref(false)
const { mealBadgeStyle, mealIconMap, mealIconColor } = useMealConfig()

/** 购物车按"日期 → 餐别"两级分组(用于预览弹窗) */
const cartGroups = computed(() => getCartDateGroups())

/** 日期格式化:07-27 明天 */
const formatDate = (date: string): string => {
  const d = parseDateKey(date)
  const rel = relativeLabel(date)
  return rel
    ? `${pad2(d.getMonth() + 1)}-${pad2(d.getDate())} ${rel}`
    : `${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`
}

const openCart = () => {
  if (cartCount.value === 0) return
  showCartModal.value = true
}

/** 购物车弹窗内 + / - */
const onCartInc = (it: any) => addDish(
  { dishId: it.dishId, dishName: it.name, price: it.price, category: it.category },
  it.mealType,
  it.date,
)
const onCartDec = (it: any) => decDish(it.dishId, it.mealType, it.date)
const onCartRemove = (it: any) => removeDish(it.dishId, it.mealType, it.date)

useIdleTimer(() => {
  resetOrderFlow()
  router.replace('/order')
})

onMounted(async () => {
  if (!orderStore.employee) {
    router.replace('/order')
    return
  }
  // 先加载订餐截止配置(按门店,驱动可订餐日期范围)
  await loadConfig(loadTerminalConfig()?.storeId ?? null)
  // 默认选中可订餐起始日期
  if (!orderStore.selectedDate || !allDates.value.includes(orderStore.selectedDate)) {
    orderStore.selectedDate = startDateKey.value
  }
  // 并发加载菜单和已下单订单(全部就绪后统一渲染堆叠 section,避免布局抖动)
  await Promise.all([loadAllMenus(), fetchOrderedOrders()])
  allLoaded.value = true
  // 初始可视日期:优先当前选中日期,否则第一个有菜单的日期
  let initDate = orderStore.selectedDate
  if (!availableSet.value.has(initDate)) {
    initDate = allDates.value.find((d) => availableSet.value.has(d)) || ''
  }
  if (initDate) {
    visibleDate.value = initDate
    orderStore.selectedDate = initDate
    // 即时滚动到初始日期(非平滑,避免初始动画抖动)
    nextTick(() => {
      const el = daySectionRefs[initDate]
      if (el) el.scrollIntoView({ block: 'start' })
    })
  }
})

/**
 * 监听 SSE 菜单失效事件:菜单变更时清除对应日期的内存缓存并重新拉取。
 * 解决"SSE menu_changed 事件无法触达 UI"问题(cache.ts 触发 menuInvalidated ref)。
 */
watch(menuInvalidated, (v) => {
  if (!v || !v.date) return
  const storeId = orderStore.employee?.storeId
  if (v.storeId !== storeId) return // 仅本门店事件
  // 清除对应日期的内存缓存,触发重新拉取
  if (menuCache.value[v.date]) {
    delete menuCache.value[v.date]
    // 若当前选中日期就是失效日期,立即重新加载
    if (selectedDate.value === v.date) {
      loadMenu(v.date)
    }
  }
})
</script>

<template>
  <main class="select">
    <TopBar title="选菜" @back="router.push('/order/menu')" />

    <!-- 左右布局:左侧 DateSidebar + 右侧菜品 -->
    <div class="select__body">
      <DateSidebar
        :dates="sidebarDates"
        :selected-date="visibleDate"
        :meal-types-for-date="mealTypesFor"
        :available-set="availableSet"
        @select="onSelectSidebar"
      >
        <template #header>
          <DatePicker
            :dates="allDates"
            :selected-date="visibleDate"
            :available-set="availableSet"
            :marked-set="orderedDateSet"
            @select="onSelectDate"
          />
        </template>
      </DateSidebar>

      <!-- 右侧内容区:所有可订餐日期垂直堆叠,上下滚动自然浏览,无切换动作 -->
      <div
        ref="contentRef"
        class="select__content no-scrollbar"
        @scroll.passive="handleContentScroll"
      >
        <!-- 全页加载态:并发加载所有日期菜单时显示 -->
        <div v-if="!allLoaded" class="select__loading">
          <div class="select__spinner spinner"></div>
          <span>加载菜单中...</span>
        </div>

        <!-- 空状态:无可订餐日期 -->
        <div v-else-if="dateList.length === 0" class="select__empty">
          <div class="select__empty-icon">📭</div>
          <div class="select__empty-title">暂无可订餐日期</div>
          <div class="select__empty-hint">请稍后再试</div>
        </div>

        <!-- 所有可订餐日期垂直堆叠(单页滚动,无切换;数据已全部就绪) -->
        <template v-else>
          <!-- 顶部 sticky 日期指示器:跟随当前可视日期吸顶显示 -->
          <div class="select__sticky-date">
            <span class="select__sticky-num">{{ visibleDateNumber }}</span>
            <span class="select__sticky-rel">{{ visibleDateRel }}</span>
          </div>

          <section
            v-for="d in dateList"
            :key="d"
            :ref="(el) => setDaySectionRef(d, el as Element | null)"
            class="day-section"
            :data-date="d"
          >
            <!-- 日期标题胶囊(数字日期 + 今天/明天/周X) -->
            <div class="day-section__header">
              <span class="day-section__date-num">{{ shortDate(d) }}</span>
              <span class="day-section__date-rel">{{ dateRelLabel(d) }}</span>
            </div>

            <!-- 当日有菜单:渲染餐别区块 -->
            <template v-if="mealSectionsFor(d).length > 0">
              <MealSection
                v-for="menu in mealSectionsFor(d)"
                :key="menu.id"
                :meal-type="menu.mealType"
                :items="menu.menuItems"
                :get-quantity="(dishId, mt) => getQuantity(dishId, d, mt)"
                :locked="lockedMealTypesFor(d).has(menu.mealType)"
                :ordered-items="orderedItemsFor(d, menu.mealType)"
                @inc="(item) => onInc(item, menu.mealType, d)"
                @dec="(item) => onDec(item, menu.mealType, d)"
              />
            </template>

            <!-- 已加载但无菜品 -->
            <div v-else class="day-section__empty">
              <span>当日暂无可订餐菜品</span>
            </div>
          </section>
        </template>
      </div>
    </div>

    <!-- 悬浮购物车(常驻底部,不被隐藏) -->
    <div class="select__cart-float">
      <div class="select__cart-info" @click="openCart">
        <div class="select__cart-icon">
          <ShoppingCart :size="24" />
          <span v-if="cartCount > 0" class="select__cart-badge">{{ cartCount }}</span>
        </div>
        <div class="select__cart-text">
          <span v-if="cartCount === 0" class="select__cart-empty">购物车为空</span>
          <template v-else>
            <span class="select__cart-count">已选 {{ cartCount }} 道</span>
            <span class="select__cart-total">¥{{ formatMoney(cartTotal) }}</span>
          </template>
        </div>
      </div>
      <button
        class="select__cart-btn btn-press"
        :disabled="cartCount === 0"
        @click="goCheckout"
      >
        去结算
      </button>
    </div>

    <!-- 购物车预览弹窗(支持 +/- 数量与删除) -->
    <Teleport to="body">
      <Transition name="cart-modal">
        <div v-if="showCartModal" class="cart-modal__overlay" @click.self="showCartModal = false">
          <div class="cart-modal__panel">
            <!-- 顶部标题 + 关闭 -->
            <div class="cart-modal__head">
              <h2 class="cart-modal__title">购物车</h2>
              <button class="cart-modal__close btn-press" aria-label="关闭" @click="showCartModal = false">
                <X :size="20" />
              </button>
            </div>

            <!-- 购物车内容(按日期 → 餐别分组) -->
            <div class="cart-modal__body no-scrollbar">
              <div v-if="cartGroups.length === 0" class="cart-modal__empty">
                购物车为空
              </div>
              <div v-for="dg in cartGroups" :key="dg.date" class="cart-modal__date-group">
                <div class="cart-modal__date-head">
                  <span class="cart-modal__date-title">{{ formatDate(dg.date) }}</span>
                  <span class="cart-modal__date-sub">¥{{ formatMoney(dg.dateSubtotal) }}</span>
                </div>
                <div
                  v-for="mg in dg.meals"
                  :key="`${dg.date}-${mg.mealType}`"
                  class="cart-modal__meal-group"
                >
                  <div class="cart-modal__meal-head">
                    <div class="cart-modal__badge" :style="mealBadgeStyle(mg.mealType)">
                      <component
                        :is="mealIconMap[mg.mealType]"
                        :size="14"
                        :stroke-width="2.5"
                        :color="mealIconColor(mg.mealType)"
                      />
                      <span>{{ mealTypeLabel(mg.mealType) }}</span>
                    </div>
                    <span class="cart-modal__meal-sub">¥{{ formatMoney(mg.subtotal) }}</span>
                  </div>
                  <div class="cart-modal__items">
                    <div v-for="it in mg.items" :key="`${it.dishId}-${it.mealType}`" class="cart-modal__item">
                      <div class="cart-modal__dish-info">
                        <span class="cart-modal__dish-name text-ellipsis">{{ it.name }}</span>
                        <span class="cart-modal__dish-price">¥{{ formatMoney(it.price) }}/份</span>
                      </div>
                      <!-- 数量调整 + 删除 -->
                      <div class="cart-modal__ctrl">
                        <button
                          class="cart-modal__qty-btn cart-modal__qty-btn--dec btn-press"
                          aria-label="减少"
                          @click="onCartDec(it)"
                        >
                          <Minus :size="20" stroke-width="2.5" />
                        </button>
                        <span class="cart-modal__qty-num">{{ it.quantity }}</span>
                        <button
                          class="cart-modal__qty-btn cart-modal__qty-btn--inc btn-press"
                          aria-label="增加"
                          @click="onCartInc(it)"
                        >
                          <Plus :size="20" stroke-width="2.5" />
                        </button>
                        <button
                          class="cart-modal__remove btn-press"
                          aria-label="删除"
                          @click="onCartRemove(it)"
                        >
                          <Trash2 :size="18" stroke-width="2.5" />
                        </button>
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            </div>

            <!-- 底部合计 + 去结算 -->
            <div class="cart-modal__footer">
              <div class="cart-modal__total">
                <span>合计</span>
                <span class="cart-modal__total-num">¥{{ formatMoney(cartTotal) }}</span>
              </div>
              <button class="cart-modal__checkout btn-press" @click="showCartModal = false; goCheckout()">
                去结算
              </button>
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>
  </main>
</template>

<style scoped>
.select {
  display: flex;
  flex-direction: column;
  height: 100vh;
  overflow: hidden;
  /* 内容页:不显示品牌背景,用不透明白色遮住全局背景 */
  background: var(--doubao-background);
}
.select__body {
  flex: 1;
  display: flex;
  overflow: hidden;
  min-height: 0;
}
.select__content {
  flex: 1;
  overflow-y: auto;
  min-width: 0;
  /* 顶部无 padding:让 sticky 日期指示器吸顶;底部留白避开悬浮购物车 */
  overscroll-behavior: contain;
  padding: 0 32px 120px;
}

/* ============ 顶部 sticky 日期指示器(跟随当前可视日期吸顶) ============ */
.select__sticky-date {
  position: sticky;
  top: 0;
  z-index: 10;
  display: flex;
  justify-content: center;
  align-items: center;
  padding: 10px 0;
  margin-bottom: 4px;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.95) 70%, rgba(255, 255, 255, 0));
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
}
.select__sticky-num,
.select__sticky-rel {
  display: inline-flex;
  align-items: center;
  padding: 6px 16px;
  font-size: var(--fs-base);
  font-weight: 700;
  border-radius: 999px;
  letter-spacing: 0.5px;
}
.select__sticky-num {
  background: rgba(0, 101, 253, 0.12);
  color: var(--doubao-primary);
  border: 1px solid rgba(0, 101, 253, 0.25);
  border-top-right-radius: 0;
  border-bottom-right-radius: 0;
  border-right: none;
}
.select__sticky-rel {
  background: var(--doubao-primary);
  color: var(--doubao-primary-foreground);
  border: 1px solid var(--doubao-primary);
  border-top-left-radius: 0;
  border-bottom-left-radius: 0;
}

/* ============ 日期 section(单页堆叠模式:每个日期一个 section) ============ */
.day-section {
  padding: 4px 0 8px;
}
.day-section__header {
  display: flex;
  justify-content: center;
  align-items: center;
  margin: 16px 0 12px;
}
.day-section__date-num,
.day-section__date-rel {
  display: inline-flex;
  align-items: center;
  padding: 8px 18px;
  font-size: var(--fs-lg);
  font-weight: 700;
  border-radius: 999px;
}
.day-section__date-num {
  color: var(--doubao-secondary-foreground);
  background: var(--doubao-card);
  border: 1px solid var(--doubao-border);
  border-top-right-radius: 0;
  border-bottom-right-radius: 0;
  border-right: none;
}
.day-section__date-rel {
  color: var(--doubao-primary);
  background: rgba(0, 101, 253, 0.08);
  border: 1px solid rgba(0, 101, 253, 0.2);
  border-top-left-radius: 0;
  border-bottom-left-radius: 0;
}
.day-section__empty {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 80px;
  padding: 16px 0;
  color: var(--doubao-muted-foreground);
  font-size: var(--fs-sm);
}
.select__loading,
.select__empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 80px 0;
  color: var(--doubao-muted-foreground);
  font-size: var(--fs-base);
}
.select__empty-icon {
  font-size: 56px;
}
.select__empty-title {
  font-size: var(--fs-lg);
  font-weight: 700;
  color: var(--doubao-foreground);
}
.select__empty-hint {
  font-size: var(--fs-sm);
}
.select__spinner {
  width: 40px;
  height: 40px;
  border: 4px solid var(--doubao-border);
  border-top-color: var(--doubao-primary);
  border-radius: 50%;
}

/* 悬浮购物车(常驻底部,悬浮感) */
.select__cart-float {
  position: fixed;
  left: 16px;
  right: 16px;
  bottom: 16px;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  background: var(--doubao-foreground);
  border-radius: var(--doubao-radius);
  box-shadow: 0 -6px 28px rgba(0, 0, 0, 0.22), 0 2px 8px rgba(0, 0, 0, 0.1);
  z-index: 50;
}
.select__cart-info {
  display: flex;
  align-items: center;
  gap: 12px;
  flex: 1;
  cursor: pointer;
  min-width: 0;
}
.select__cart-icon {
  position: relative;
  width: 44px;
  height: 44px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--doubao-primary);
  color: var(--doubao-primary-foreground);
  flex-shrink: 0;
}
.select__cart-badge {
  position: absolute;
  top: -4px;
  right: -6px;
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  border-radius: 9px;
  background: var(--doubao-primary);
  color: white;
  font-size: 11px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
}
.select__cart-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.select__cart-empty {
  color: rgba(255, 255, 255, 0.7);
  font-size: var(--fs-sm);
}
.select__cart-count {
  color: rgba(255, 255, 255, 0.7);
  font-size: var(--fs-sm);
}
.select__cart-total {
  color: white;
  font-size: var(--fs-lg);
  font-weight: 700;
}
.select__cart-btn {
  height: 44px;
  padding: 0 24px;
  border: none;
  border-radius: var(--doubao-radius-sm);
  background: var(--doubao-primary);
  color: var(--doubao-primary-foreground);
  font-size: var(--fs-base);
  font-weight: 700;
  cursor: pointer;
  flex-shrink: 0;
  transition: opacity 0.15s ease, transform 0.12s ease;
}
.select__cart-btn:active { transform: scale(0.96); }
.select__cart-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* ============ 购物车预览弹窗 ============ */
.cart-modal__overlay {
  position: fixed;
  inset: 0;
  z-index: 150;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 32px;
  background: rgba(14, 17, 21, 0.3);
  backdrop-filter: blur(6px);
}
.cart-modal__panel {
  width: 100%;
  max-width: 560px;
  max-height: 80vh;
  display: flex;
  flex-direction: column;
  background: var(--doubao-card);
  border-radius: var(--doubao-radius);
  border: 1px solid var(--doubao-border);
  box-shadow: 0 24px 64px rgba(0, 0, 0, 0.28);
  overflow: hidden;
}
.cart-modal__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 18px 24px;
  border-bottom: 1px solid var(--doubao-border);
}
.cart-modal__title {
  margin: 0;
  font-size: var(--fs-xl);
  font-weight: 700;
  color: var(--doubao-foreground);
}
.cart-modal__close {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border-radius: 50%;
  border: none;
  background: transparent;
  color: var(--doubao-muted-foreground);
  cursor: pointer;
  transition: background 0.15s ease, color 0.15s ease;
}
.cart-modal__close:hover {
  background: var(--doubao-muted);
  color: var(--doubao-foreground);
}

.cart-modal__body {
  flex: 1;
  overflow-y: auto;
  padding: 16px 24px;
}
.cart-modal__empty {
  text-align: center;
  padding: 40px 0;
  color: var(--doubao-muted-foreground);
  font-size: var(--fs-base);
}
.cart-modal__date-group {
  margin-bottom: 16px;
}
.cart-modal__date-group:last-child {
  margin-bottom: 0;
}
.cart-modal__date-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--doubao-border);
  margin-bottom: 8px;
}
.cart-modal__date-title {
  font-size: var(--fs-lg);
  font-weight: 700;
  color: var(--doubao-foreground);
  font-variant-numeric: tabular-nums;
}
.cart-modal__date-sub {
  font-size: var(--fs-sm);
  font-weight: 700;
  color: var(--doubao-muted-foreground);
}

.cart-modal__meal-group {
  padding: 8px 0;
}
.cart-modal__meal-group + .cart-modal__meal-group {
  border-top: 1px dashed var(--doubao-border);
}
.cart-modal__meal-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
}
.cart-modal__badge {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 3px 10px;
  border-radius: 999px;
  border: 1px solid;
  font-size: var(--fs-sm);
  font-weight: 700;
}
.cart-modal__meal-sub {
  font-size: var(--fs-sm);
  color: var(--doubao-muted-foreground);
}
.cart-modal__items {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.cart-modal__item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 0;
  font-size: 18px;
  color: var(--doubao-secondary-foreground);
}
.cart-modal__dish-info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.cart-modal__dish-name {
  font-weight: 700;
  color: var(--doubao-foreground);
}
.cart-modal__dish-price {
  font-size: 14px;
  color: var(--doubao-muted-foreground);
}

/* 数量调整 + 删除按钮 */
.cart-modal__ctrl {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}
.cart-modal__qty-num {
  min-width: 24px;
  text-align: center;
  font-size: 18px;
  font-weight: 700;
  color: var(--doubao-foreground);
  font-variant-numeric: tabular-nums;
}
.cart-modal__qty-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border-radius: 50%;
  border: none;
  cursor: pointer;
  font-family: inherit;
  transition: transform 0.12s ease, opacity 0.15s ease;
}
.cart-modal__qty-btn:active { transform: scale(0.92); }
.cart-modal__qty-btn--inc {
  background: var(--doubao-primary);
  color: var(--doubao-primary-foreground);
}
.cart-modal__qty-btn--dec {
  background: var(--doubao-muted);
  color: var(--doubao-secondary-foreground);
  border: 1px solid var(--doubao-border);
}
.cart-modal__remove {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  margin-left: 4px;
  border-radius: 50%;
  background: transparent;
  border: 1px solid var(--doubao-border);
  color: var(--doubao-destructive);
  cursor: pointer;
  font-family: inherit;
  transition: background 0.15s ease, transform 0.12s ease;
}
.cart-modal__remove:hover {
  background: rgba(239, 68, 68, 0.08);
}
.cart-modal__remove:active { transform: scale(0.92); }

.cart-modal__footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 16px 24px;
  border-top: 1px solid var(--doubao-border);
  background: var(--doubao-accent);
}
.cart-modal__total {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.cart-modal__total span:first-child {
  font-size: var(--fs-sm);
  color: var(--doubao-muted-foreground);
}
.cart-modal__total-num {
  font-size: var(--fs-2xl);
  font-weight: 700;
  color: var(--doubao-primary);
  font-variant-numeric: tabular-nums;
}
.cart-modal__checkout {
  height: 56px;
  padding: 0 32px;
  border: none;
  border-radius: var(--doubao-radius-sm);
  background: var(--doubao-primary);
  color: var(--doubao-primary-foreground);
  font-size: var(--fs-lg);
  font-weight: 700;
  cursor: pointer;
  font-family: inherit;
  transition: transform 0.12s ease, opacity 0.15s ease;
}
.cart-modal__checkout:active {
  transform: scale(0.97);
  opacity: 0.85;
}

/* 弹窗动画 */
.cart-modal-enter-active {
  transition: opacity 0.2s ease;
}
.cart-modal-enter-active .cart-modal__panel {
  transition: transform 0.25s cubic-bezier(0.34, 1.4, 0.64, 1), opacity 0.2s ease;
}
.cart-modal-enter-from {
  opacity: 0;
}
.cart-modal-enter-from .cart-modal__panel {
  transform: scale(0.92) translateY(8px);
  opacity: 0;
}
.cart-modal-leave-active {
  transition: opacity 0.15s ease;
}
.cart-modal-leave-to {
  opacity: 0;
}

@media (max-width: 1280px) {
  .cart-modal__panel {
    max-width: 460px;
  }
}
</style>
