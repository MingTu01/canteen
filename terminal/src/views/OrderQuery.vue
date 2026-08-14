<script setup lang="ts">
/**
 * 订单查询页
 *
 * 按日期查询员工已订餐订单,支持取消。
 * - 顶栏(返回订餐菜单 + 右上角时钟)
 * - 左侧 DateSidebar(默认显示今天+未来10天中有订单的日期;过去日期通过日历选择)
 * - 右侧 OrderCard 列表(三餐:已订/未订两态,展示菜品明细)
 * - 取消订餐:自定义 Modal 二次确认 + 错误提示
 * - 无操作 30 秒自动返回待机
 *
 * 日期规则:
 * - sidebar 默认:今天 + 未来10天中"有订单"的日期(降序,今天在前)
 * - 过去日期:点击 DatePicker 选择具体日期后才检索
 * - allDates(DatePicker 全量日历):过去30天 + 未来30天
 *
 * items fallback:
 * - 后端 getOrdersByEmployee 可能未填充 items(旧版容器),前端对 items 为空的订单
 *   批量调用 GET /order/{id} 补充菜品明细(getOrderDetail 返回 {order, items})
 */
import { ref, computed, onMounted, nextTick, watch } from 'vue'
import { useRouter } from 'vue-router'
import api, { loadConfig as loadTerminalConfig } from '@/api'
import { orderStore, resetOrderFlow } from '@/store/order'
import { useIdleTimer } from '@/composables/useIdleTimer'
import { useOrderConfig } from '@/composables/useOrderConfig'
import { toDateKey, dateWindow, shortDate, dateRelLabel } from '@/utils'
import { menuInvalidated } from '@/utils/cache'
import TopBar from '@/components/TopBar.vue'
import DatePicker from '@/components/DatePicker.vue'
import DateSidebar from '@/components/DateSidebar.vue'
import OrderCard from '@/components/OrderCard.vue'
import Modal from '@/components/Modal.vue'

const router = useRouter()

/** 今天 */
const today = toDateKey(new Date())

/**
 * allDates(DatePicker 全量日历):过去30天 + 未来30天(含今天)。
 * 未来30天覆盖订餐范围,过去30天覆盖历史订单查询。
 */
const futureDatesAll = dateWindow(today, 31, 1) // [today, today+1, ..., today+30]
const pastDatesAll = dateWindow(today, 31, -1).slice(1) // [today-1, ..., today-30]
const allDates = [...futureDatesAll, ...pastDatesAll]

/** 排序后的全部日期(过去30天 → 今天 → 未来30天,升序),供 sidebar + 堆叠渲染 */
const sortedDates = computed(() => [...allDates].sort())

const orders = ref<any[]>([])
const canceling = ref<number | null>(null)
const loading = ref(false)

/** 右侧内容区滚动容器(用于 scroll 事件监听 + 滚动控制) */
const contentRef = ref<HTMLElement | null>(null)
/** 当前可视日期(滚动时动态更新,驱动左侧栏高亮 + sticky 日期指示器) */
const visibleDate = ref<string>('')
/** 每个日期 section 的 DOM 引用,用于滚动定位 + 可视检测 */
const daySectionRefs: Record<string, HTMLElement | null> = {}
/** scroll 事件节流时间戳 */
let lastScrollCalcTime = 0
/** 程序滚动标志(点击左侧栏跳转时屏蔽可视日期更新,避免跳动) */
let isProgrammaticScroll = false

useIdleTimer(() => {
  resetOrderFlow()
  router.replace('/order')
})

/** 有效订单(待取餐 status=1 + 已完成 status=2,排除已取消 status=3) */
const activeOrders = computed(() =>
  orders.value.filter((o) => o.status === 1 || o.status === 2),
)

/** 有订单的日期集合 */
const availableSet = computed(() => {
  const s = new Set<string>()
  for (const o of activeOrders.value) {
    if (o.date) s.add(o.date)
  }
  return s
})

/** 有订单的日期列表(按时间升序,用于堆叠渲染) */
const dateList = computed(() => sortedDates.value.filter((d) => availableSet.value.has(d)))

/** 各日期已有订单的餐别列表(用于 DateSidebar 圆点提示) */
const mealTypesMap = computed(() => {
  const m: Record<string, number[]> = {}
  for (const o of activeOrders.value) {
    if (!o.date) continue
    if (!m[o.date]) m[o.date] = []
    if (!m[o.date].includes(o.mealType)) m[o.date].push(o.mealType)
  }
  for (const k in m) m[k].sort((a, b) => a - b)
  return m
})

const mealTypesFor = (date: string) => mealTypesMap.value[date] || []

const orderFor = (date: string, mealType: number) =>
  activeOrders.value.find((o) => o.date === date && o.mealType === mealType)

const mealTypes = [1, 2, 3]

/**
 * 订餐截止配置:驱动取消按钮可见性。
 * 过截止时间(cancel_deadline_time)的次日订单,隐藏取消按钮。
 */
const { loadConfig, isCancellableByDeadline } = useOrderConfig()

/** 订单是否仍可取消(按截止配置判定;无日期视为可取消) */
const orderCancellable = (order: any) => {
  if (!order?.date) return true
  return isCancellableByDeadline(order.date, new Date())
}

/* ============ 无极滑动切换日期(单页堆叠模式,对齐 H5 Order.vue) ============ */
/** 设置日期 section 的 DOM 引用(供 v-for :ref 回调用) */
const setDaySectionRef = (date: string, el: Element | null): void => {
  daySectionRefs[date] = (el as HTMLElement) || null
}

/**
 * 内容区滚动事件处理(单页堆叠模式):
 * - 节流计算当前可视日期(触发线算法)→ 更新 visibleDate
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
  }
}

/** sticky 日期指示器:当前可视日期的数字日期 + 相对标签 */
const visibleDateNumber = computed(() => shortDate(visibleDate.value))
const visibleDateRel = computed(() => dateRelLabel(visibleDate.value))

const fetchOrders = async () => {
  loading.value = true
  try {
    const resp = await api.get(`/order/employee/${orderStore.employee?.id}`)
    if (resp.data.code === 200) {
      orders.value = resp.data.data || []
      // items fallback:对 items 为空的订单,批量请求 /order/{id} 补充菜品明细
      await fillMissingItems()
    }
  } catch {
    orders.value = []
  } finally {
    loading.value = false
  }
}

/**
 * items fallback:
 * 后端 getOrdersByEmployee 可能未填充 items(旧版容器),
 * 前端对 items 为空的订单批量调用 GET /order/{id} 补充菜品明细。
 * getOrderDetail 返回 {order, items},items 为菜品列表。
 */
const fillMissingItems = async () => {
  const needFill = orders.value.filter(
    (o) => (!o.items || o.items.length === 0) && o.id,
  )
  if (needFill.length === 0) return
  // 并发请求,限制并发5个
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

/* ============ 取消订餐:Modal 二次确认 + 错误提示 ============ */
const cancelTarget = ref<any | null>(null)
const showCancelModal = ref(false)
const showErrorModal = ref(false)
const errorMsg = ref('')

const onCancelClick = (order: any) => {
  cancelTarget.value = order
  showCancelModal.value = true
}

const confirmCancel = async () => {
  const order = cancelTarget.value
  if (!order) return
  showCancelModal.value = false
  canceling.value = order.id
  try {
    await api.put(`/order/${order.id}/cancel`)
    await fetchOrders()
  } catch (e: any) {
    errorMsg.value = e?.response?.data?.message || '取消失败,请稍后重试'
    showErrorModal.value = true
  } finally {
    canceling.value = null
    cancelTarget.value = null
  }
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
  // 平滑滚动结束后恢复监听(500ms 足够覆盖大多数滚动时长)
  setTimeout(() => { isProgrammaticScroll = false }, 500)
}

/** DateSidebar 头部 DatePicker 选中某日期(可能是过去日期) */
const onSelectDate = (key: string) => {
  scrollToDate(key)
}

/** 左侧 DateSidebar 列表选中某日期 */
const onSelectSidebar = (key: string) => {
  scrollToDate(key)
}

onMounted(async () => {
  if (!orderStore.employee) {
    router.replace('/order')
    return
  }
  // 先加载订餐截止配置(按门店,驱动取消按钮可见性)
  await loadConfig(loadTerminalConfig()?.storeId ?? null)
  await fetchOrders()
  // 初始可视日期:今天有订单则今天,否则最近的有订单日期
  let initDate = availableSet.value.has(today) ? today : ''
  if (!initDate && dateList.value.length > 0) {
    // 优先未来最近的有订单日期,否则过去最近
    initDate = dateList.value.find((d) => d >= today) || dateList.value[dateList.value.length - 1]
  }
  if (initDate) {
    visibleDate.value = initDate
    // 即时滚动到初始日期(非平滑,避免初始动画抖动)
    nextTick(() => {
      const el = daySectionRefs[initDate as string]
      if (el) el.scrollIntoView({ block: 'start' })
    })
  }
})

/**
 * 监听 SSE 菜单失效事件:菜单变更时重新加载订单数据。
 * 解决"SSE menu_changed 事件无法触达 UI"问题(cache.ts 触发 menuInvalidated ref)。
 */
watch(menuInvalidated, (v) => {
  if (!v || !v.date) return
  const storeId = orderStore.employee?.storeId
  if (v.storeId !== storeId) return // 仅本门店事件
  fetchOrders()
})
</script>

<template>
  <main class="query">
    <TopBar title="订单查询" @back="router.push('/order/menu')" />

    <!-- 左右布局:左侧 DateSidebar + 右侧订单卡片 -->
    <div class="query__body">
      <DateSidebar
        :dates="sortedDates"
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
            @select="onSelectDate"
          />
        </template>
      </DateSidebar>

      <!-- 右侧内容区:所有有订单的日期垂直堆叠,上下滚动自然浏览,无切换动作 -->
      <div
        ref="contentRef"
        class="query__content no-scrollbar"
        @scroll.passive="handleContentScroll"
      >
        <!-- 加载中 -->
        <div v-if="loading" class="query__loading">
          <div class="query__spinner spinner"></div>
          <span>加载订单中...</span>
        </div>

        <!-- 空状态:无任何订单 -->
        <div v-else-if="dateList.length === 0" class="query__empty">
          <div class="query__empty-icon">📭</div>
          <div class="query__empty-title">暂无订单</div>
          <div class="query__empty-hint">请在左侧选择其他日期</div>
        </div>

        <!-- 所有有订单的日期垂直堆叠(单页滚动,无切换) -->
        <template v-else>
          <!-- 顶部 sticky 日期指示器:跟随当前可视日期吸顶显示 -->
          <div class="query__sticky-date">
            <span class="query__sticky-num">{{ visibleDateNumber }}</span>
            <span class="query__sticky-rel">{{ visibleDateRel }}</span>
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

            <!-- 三餐订单卡片 -->
            <OrderCard
              v-for="t in mealTypes"
              :key="t"
              :meal-type="t"
              :order="orderFor(d, t) || null"
              :canceling="canceling === orderFor(d, t)?.id"
              :cancellable="orderCancellable(orderFor(d, t))"
              @cancel="onCancelClick"
            />
          </section>
        </template>
      </div>
    </div>

    <!-- 取消订餐二次确认 -->
    <Modal
      v-model="showCancelModal"
      title="确认取消订餐?"
      message="取消后无法恢复,余额将原路退回。"
      variant="danger"
      confirm-text="确认取消"
      cancel-text="再想想"
      @confirm="confirmCancel"
    />

    <!-- 取消失败错误提示 -->
    <Modal
      v-model="showErrorModal"
      title="取消失败"
      :message="errorMsg"
      variant="warning"
      :cancel-text="''"
      confirm-text="知道了"
    />
  </main>
</template>

<style scoped>
.query {
  display: flex;
  flex-direction: column;
  height: 100vh;
  overflow: hidden;
  /* 内容页:不显示品牌背景,用不透明白色遮住全局背景 */
  background: var(--doubao-background);
}
.query__body {
  flex: 1;
  display: flex;
  overflow: hidden;
  min-height: 0;
}
.query__content {
  flex: 1;
  overflow-y: auto;
  min-width: 0;
  overscroll-behavior: contain;
  /* 顶部无 padding:让 sticky 日期指示器吸顶 */
  padding: 0 32px 80px;
}

/* ============ 顶部 sticky 日期指示器(跟随当前可视日期吸顶) ============ */
.query__sticky-date {
  position: sticky;
  top: 0;
  z-index: 10;
  display: flex;
  justify-content: center;
  align-items: center;
  padding: 10px 0;
  margin-bottom: 4px;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.95) 70%, rgba(255, 255, 255, 0));
}
.query__sticky-num,
.query__sticky-rel {
  display: inline-flex;
  align-items: center;
  padding: 6px 16px;
  font-size: var(--fs-base);
  font-weight: 700;
  border-radius: 999px;
  letter-spacing: 0.5px;
}
.query__sticky-num {
  background: rgba(0, 101, 253, 0.12);
  color: var(--doubao-primary);
  border: 1px solid rgba(0, 101, 253, 0.25);
  border-top-right-radius: 0;
  border-bottom-right-radius: 0;
  border-right: none;
}
.query__sticky-rel {
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
.query__loading,
.query__empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 80px 0;
  color: var(--doubao-muted-foreground);
  font-size: var(--fs-base);
}
.query__empty-icon {
  font-size: 56px;
}
.query__empty-title {
  font-size: var(--fs-lg);
  font-weight: 700;
  color: var(--doubao-foreground);
}
.query__empty-hint {
  font-size: var(--fs-sm);
}
.query__spinner {
  width: 40px;
  height: 40px;
  border: 4px solid var(--doubao-border);
  border-top-color: var(--doubao-primary);
  border-radius: 50%;
}
</style>
