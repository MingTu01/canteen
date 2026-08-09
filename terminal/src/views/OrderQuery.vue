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
import { ref, computed, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import api from '@/api'
import { orderStore, resetOrderFlow } from '@/store/order'
import { useIdleTimer } from '@/composables/useIdleTimer'
import { useOrderConfig } from '@/composables/useOrderConfig'
import { toDateKey, dateWindow } from '@/utils'
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

/** 默认 sidebar 窗口:今天 + 未来10天(含今天共11天) */
const defaultWindow = futureDatesAll.slice(0, 11) // [today, today+1, ..., today+10]

const orders = ref<any[]>([])
const canceling = ref<number | null>(null)
const loading = ref(false)

const selectedDate = ref('')

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

/**
 * sidebar 日期列表:
 * - 显示连续日期范围(今天+未来10天),让用户能完整查看每天的订餐情况
 * - 有订单的日期通过 DateSidebar 圆点标记,无订单的显示空状态
 * - 如果选中日期不在默认窗口内(如过去日期),将其加入窗口
 */
const sidebarDates = computed(() => {
  const set = new Set(defaultWindow)
  // 选中日期始终加入窗口(支持过去日期查看)
  if (selectedDate.value) set.add(selectedDate.value)
  const list = Array.from(set)
  // 排序:今天永远排第一,其余按日期升序(27, 28, 29, 30...)
  list.sort((a, b) => {
    if (a === today) return -1
    if (b === today) return 1
    return a < b ? -1 : a > b ? 1 : 0
  })
  return list
})

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

/** DateSidebar 头部 DatePicker 选中某日期(可能是过去日期) */
const onSelectDate = (key: string) => {
  selectedDate.value = key
}

/** 左侧 DateSidebar 列表选中某日期 */
const onSelectSidebar = (key: string) => {
  selectedDate.value = key
}

onMounted(async () => {
  if (!orderStore.employee) {
    router.replace('/order')
    return
  }
  // 先加载订餐截止配置(驱动取消按钮可见性)
  await loadConfig()
  selectedDate.value = today
  await fetchOrders()
  // 今日无订单,自动选中默认窗口内最近的有订单日期
  if (!availableSet.value.has(selectedDate.value)) {
    const found = defaultWindow.find((d) => availableSet.value.has(d))
    if (found) selectedDate.value = found
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
        :dates="sidebarDates"
        :selected-date="selectedDate"
        :meal-types-for-date="mealTypesFor"
        @select="onSelectSidebar"
      >
        <template #header>
          <DatePicker
            :dates="allDates"
            :selected-date="selectedDate"
            :available-set="availableSet"
            @select="onSelectDate"
          />
        </template>
      </DateSidebar>

      <div class="query__content no-scrollbar">
        <!-- 加载中 -->
        <div v-if="loading" class="query__loading">
          <div class="query__spinner spinner"></div>
          <span>加载订单中...</span>
        </div>

        <!-- 空状态:该日期无订单 -->
        <div v-else-if="!availableSet.has(selectedDate)" class="query__empty">
          <div class="query__empty-icon">📭</div>
          <div class="query__empty-title">该日期无订单</div>
          <div class="query__empty-hint">请在左侧选择其他日期</div>
        </div>

        <!-- 三餐订单卡片 -->
        <template v-else>
          <OrderCard
            v-for="t in mealTypes"
            :key="t"
            :meal-type="t"
            :order="orderFor(selectedDate, t) || null"
            :canceling="canceling === orderFor(selectedDate, t)?.id"
            :cancellable="orderCancellable(orderFor(selectedDate, t))"
            @cancel="onCancelClick"
          />
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
  padding: 20px 32px 80px;
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
