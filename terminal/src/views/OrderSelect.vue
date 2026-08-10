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
import { toDateKey, dateWindow, parseDateKey, relativeLabel, pad2 } from '@/utils'
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

/** 左侧 DateSidebar 窗口(7 天,可上下翻页) */
const windowStart = ref(startDateKey.value)
const sidebarDates = computed(() => dateWindow(windowStart.value, 7, 1))

/** menuCache: 日期 -> 转换后的菜单数组 [{id, mealType, menuItems}] */
const menuCache = ref<Record<string, any[]>>({})
const loadingSet = ref<Set<string>>(new Set())

/** 已下单订单(从后端拉取,用于锁定已订餐别) */
const orderedOrders = ref<any[]>([])

/** 右侧菜品区容器(切换日期时滚动到顶部,确保早餐可见) */
const contentRef = ref<HTMLElement | null>(null)

/** 当前选中日期(双向同步到 orderStore) */
const selectedDate = computed({
  get: () => orderStore.selectedDate,
  set: (v: string) => { orderStore.selectedDate = v },
})

/**
 * 将后端菜单响应拍平为前端结构。
 * 后端返回 [{menu:{id,mealType,...}, items:[{item,dish}]}]
 * 前端拍平成 [{id, mealType, menuItems:[{dishId,dishName,price,category,image}]}]
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

/** 当日菜单按餐别排序 */
const mealSections = computed(() => {
  const menus = selectedDate.value ? menuCache.value[selectedDate.value] || [] : []
  return [...menus].sort((a: any, b: any) => a.mealType - b.mealType)
})

/** 购物车全局总数/总价(跨日期) */
const cartCount = cartTotalCount
const cartTotal = cartTotalAmount
const loading = computed(() => loadingSet.value.has(selectedDate.value))

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

/** 当前选中日期下,已下单(status=1)的餐别集合 */
const lockedMealTypes = computed<Set<number>>(() => {
  const s = new Set<number>()
  if (!selectedDate.value) return s
  for (const o of orderedOrders.value) {
    if (o.date === selectedDate.value && o.status === 1) s.add(Number(o.mealType))
  }
  return s
})

/** 当前选中日期 + 指定餐别下,已下单的菜品映射:dishId -> quantity */
const orderedItemsFor = (mealType: number): Map<number, number> => {
  const m = new Map<number, number>()
  if (!selectedDate.value) return m
  for (const o of orderedOrders.value) {
    if (o.date === selectedDate.value && Number(o.mealType) === mealType && o.status === 1) {
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

/** DateSidebar 头部 DatePicker 选中某日期 */
const onSelectDate = (key: string) => {
  selectedDate.value = key
  // 同步左侧 sidebar 窗口,使选中日期可见
  if (!sidebarDates.value.includes(key)) {
    windowStart.value = key
  }
  loadMenu(key)
}

/** 左侧 DateSidebar 列表选中某日期 */
const onSelectSidebar = (key: string) => {
  selectedDate.value = key
  loadMenu(key)
}

/** 菜品 + / - */
const onInc = (item: any, mealType: number) => {
  if (lockedMealTypes.value.has(mealType)) return
  addDish(item, mealType, selectedDate.value)
}
const onDec = (item: any, mealType: number) => {
  if (lockedMealTypes.value.has(mealType)) return
  decDish(item.dishId, mealType, selectedDate.value)
}

/** 取某菜品在购物车中的数量 */
const getQuantity = (dishId: number, mealType: number) =>
  dishQuantity(dishId, selectedDate.value, mealType)

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
  // 并发加载菜单和已下单订单
  await Promise.all([loadAllMenus(), fetchOrderedOrders()])
  // 起始日期无菜单,自动选中第一个有菜单的日期
  if (!availableSet.value.has(orderStore.selectedDate)) {
    const first = allDates.value.find((d) => availableSet.value.has(d))
    if (first) orderStore.selectedDate = first
  }
})

/** 切换日期时自动滚动到顶部,确保每天首屏都是早餐 */
watch(selectedDate, () => {
  nextTick(() => {
    if (contentRef.value) contentRef.value.scrollTop = 0
  })
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
        :selected-date="selectedDate"
        :meal-types-for-date="mealTypesFor"
        :available-set="availableSet"
        @select="onSelectSidebar"
      >
        <template #header>
          <DatePicker
            :dates="allDates"
            :selected-date="selectedDate"
            :available-set="availableSet"
            :marked-set="orderedDateSet"
            @select="onSelectDate"
          />
        </template>
      </DateSidebar>

      <div ref="contentRef" class="select__content no-scrollbar">
        <!-- 加载中 -->
        <div v-if="loading" class="select__loading">
          <div class="select__spinner spinner"></div>
          <span>加载菜单中...</span>
        </div>

        <!-- 空状态 -->
        <div v-else-if="mealSections.length === 0" class="select__empty">
          <div class="select__empty-icon">📭</div>
          <div class="select__empty-title">该日期暂未配置菜单</div>
          <div class="select__empty-hint">请在上方选择其他日期</div>
        </div>

        <!-- 餐别区块列表 -->
        <template v-else>
          <MealSection
            v-for="menu in mealSections"
            :key="menu.id"
            :meal-type="menu.mealType"
            :items="menu.menuItems"
            :get-quantity="getQuantity"
            :locked="lockedMealTypes.has(menu.mealType)"
            :ordered-items="orderedItemsFor(menu.mealType)"
            @inc="(item) => onInc(item, menu.mealType)"
            @dec="(item) => onDec(item, menu.mealType)"
          />
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
                          <Minus :size="14" stroke-width="2.5" />
                        </button>
                        <span class="cart-modal__qty-num">{{ it.quantity }}</span>
                        <button
                          class="cart-modal__qty-btn cart-modal__qty-btn--inc btn-press"
                          aria-label="增加"
                          @click="onCartInc(it)"
                        >
                          <Plus :size="14" stroke-width="2.5" />
                        </button>
                        <button
                          class="cart-modal__remove btn-press"
                          aria-label="删除"
                          @click="onCartRemove(it)"
                        >
                          <Trash2 :size="14" stroke-width="2.5" />
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
  /* 底部 padding 防止最后菜品被悬浮购物车遮挡(购物车高约 64px + bottom 16px = 80px,留 120px 余量) */
  padding: 20px 32px 120px;
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
  padding: 8px 0;
  font-size: var(--fs-base);
  color: var(--doubao-secondary-foreground);
}
.cart-modal__dish-info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.cart-modal__dish-name {
  font-weight: 400;
  color: var(--doubao-foreground);
}
.cart-modal__dish-price {
  font-size: var(--fs-xs);
  color: var(--doubao-muted-foreground);
}

/* 数量调整 + 删除按钮 */
.cart-modal__ctrl {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}
.cart-modal__qty-num {
  min-width: 20px;
  text-align: center;
  font-size: var(--fs-base);
  font-weight: 700;
  color: var(--doubao-foreground);
  font-variant-numeric: tabular-nums;
}
.cart-modal__qty-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
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
  width: 28px;
  height: 28px;
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
