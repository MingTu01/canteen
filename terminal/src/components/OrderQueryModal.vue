<script setup lang="ts">
/**
 * 菜品查询弹窗(取餐端 X86)
 *
 * 在取餐待机页右下角触发,提供两大功能:
 * 1. 员工订餐查询:按日期 / 姓名/卡号 / 餐别 / 已吃·未吃 筛选订单列表,
 *    点击订单行弹出小票形式的订单详情。
 * 2. 订单汇总:按餐别(早/午/晚)分组统计当天各菜品的剩余份数(跟随已吃减少)。
 *
 * 数据来源:GET /api/order/store/{storeId}(终端 token 自动携带)。
 * 汇总中的"剩余份数"= status=1(待取餐/未吃)订单的菜品数量之和;
 * 员工取餐后订单变为 status=2(已完成),剩余份数随之减少。
 *
 * 弹窗不随遮罩点击关闭,只能通过关闭按钮关闭。
 */
import { ref, computed, watch } from 'vue'
import api, { loadConfig } from '@/api'
import { useMealConfig } from '@/composables/useMealConfig'
import { formatMoney, formatDateTime } from '@/composables/useFormat'
import {
  mealTypeLabel, toDateKey, shortDate, weekdayLabel, dateRelLabel,
  dateWindow, shiftKey,
} from '@/utils'
import {
  Search, X, ChevronLeft, Loader2, FileText,
  CheckCircle2, Clock, Utensils, ChevronRight,
} from 'lucide-vue-next'

interface OrderItem {
  dishId: number
  dishName: string
  price: number | string
  quantity: number
}
interface Order {
  id: number
  orderNo: string
  storeId: number
  employeeId: number
  date: string
  mealType: number
  totalAmount: number | string
  status: number
  orderSource?: string
  createdAt: string
  updatedAt?: string
  employeeName?: string
  cardNo?: string
  departmentName?: string
  items?: OrderItem[]
}

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ (e: 'update:modelValue', v: boolean): void }>()

const { mealBadgeStyle } = useMealConfig()

/** 当前终端绑定的门店 */
const storeId = computed(() => loadConfig()?.storeId ?? null)
const storeName = computed(() => loadConfig()?.storeName || '当前门店')

/** 视图:list=查询主界面,summary=订单汇总 */
const view = ref<'list' | 'summary'>('list')

const today = toDateKey(new Date())

/* ============ 日期选择器(横向日历条,近 14 天) ============
 * 显示今天及过去 13 天共 14 天,横向滚动,今天高亮,
 * 点击日期后立即查询,无需点查询按钮。
 * 可通过左右箭头按钮快速翻动日期。
 */
const DATE_RANGE_DAYS = 14
/** 日期列表:今天往前推 13 天(共 14 天,含今天) */
const dateList = computed(() => {
  const startKey = shiftKey(today, -(DATE_RANGE_DAYS - 1))
  return dateWindow(startKey, DATE_RANGE_DAYS, 1)
})

/* ============ 查询主界面 ============ */
const queryDate = ref(today)
const keyword = ref('')
/** 餐别筛选:0=全部 1=早 2=午 3=晚 */
const mealFilter = ref(0)
/** 就餐状态筛选:all=全部 eaten=已吃 uneaten=未吃 */
const statusFilter = ref<'all' | 'eaten' | 'uneaten'>('all')

const orders = ref<Order[]>([])
const loading = ref(false)
const page = ref(1)
const PAGE_SIZE = 20
const total = ref(0)
const hasMore = computed(() => orders.value.length < total.value)

/** 订单详情(小票子弹窗) */
const detailOrder = ref<Order | null>(null)

/* ============ 订单汇总 ============ */
const summaryDate = ref(today)
const summaryLoading = ref(false)
interface SummaryRow {
  dishName: string
  price: number | string
  /** 剩余份数(status=1 待取餐) */
  remaining: number
  /** 订购总份数(status=1+2) */
  total: number
}
/** 餐别 -> 菜品行数组 */
const summaryMap = ref<Record<number, SummaryRow[]>>({ 1: [], 2: [], 3: [] })

const MEAL_TYPES = [1, 2, 3]

/** 列表行状态信息(文字 + 样式类) */
const statusInfo = (s: number) => {
  switch (s) {
    case 1: return { text: '未吃', cls: 'is-uneaten' }
    case 2: return { text: '已吃', cls: 'is-eaten' }
    case 3: return { text: '已取消', cls: 'is-cancelled' }
    case 4: return { text: '超时', cls: 'is-timeout' }
    default: return { text: '未知', cls: 'is-cancelled' }
  }
}

/** 拉取订单列表(reset=true 时回到第一页) */
const fetchOrders = async (reset = false) => {
  if (storeId.value == null) return
  loading.value = true
  if (reset) {
    page.value = 1
    orders.value = []
  }
  try {
    const params: Record<string, number | string> = {
      startDate: queryDate.value,
      endDate: queryDate.value,
      page: page.value,
      size: PAGE_SIZE,
    }
    if (mealFilter.value) params.mealType = mealFilter.value
    if (statusFilter.value === 'eaten') params.status = 2
    else if (statusFilter.value === 'uneaten') params.status = 1
    const kw = keyword.value.trim()
    if (kw) params.keyword = kw

    const resp = await api.get(`/order/store/${storeId.value}`, { params })
    if (resp.data.code === 200) {
      const data = resp.data.data || {}
      const recs: Order[] = data.records || []
      orders.value = reset ? recs : [...orders.value, ...recs]
      total.value = data.total || 0
    }
  } catch {
    if (reset) orders.value = []
  } finally {
    loading.value = false
  }
}

/** 加载更多(分页) */
const loadMore = () => {
  if (loading.value || !hasMore.value) return
  page.value++
  fetchOrders(false)
}

/** 筛选条件变化时重置查询 */
const onFilterChange = () => fetchOrders(true)
/** 关键字回车查询 */
const onKeywordEnter = () => fetchOrders(true)

/* ============ 日期选择器交互 ============ */
/** 日期条横向滚动容器引用 */
const dateStripRef = ref<HTMLElement | null>(null)
/** 单个日期项宽度(含 margin),用于翻页滚动 */
const DATE_ITEM_WIDTH = 86

/** 选择日期(主界面):立即查询 */
const onSelectQueryDate = (d: string) => {
  if (queryDate.value === d) return
  queryDate.value = d
  fetchOrders(true)
}

/** 选择日期(汇总):立即查询 */
const onSelectSummaryDate = (d: string) => {
  if (summaryDate.value === d) return
  summaryDate.value = d
  fetchSummary()
}

/** 日期条向左滚动(看更早的日期) */
const scrollDateLeft = () => {
  const el = dateStripRef.value
  if (!el) return
  el.scrollBy({ left: -DATE_ITEM_WIDTH * 3, behavior: 'smooth' })
}

/** 日期条向右滚动(看更晚的日期) */
const scrollDateRight = () => {
  const el = dateStripRef.value
  if (!el) return
  el.scrollBy({ left: DATE_ITEM_WIDTH * 3, behavior: 'smooth' })
}

/** 滚动到选中日期(弹窗打开时调用,让今天可见) */
const scrollSelectedIntoView = () => {
  setTimeout(() => {
    const el = dateStripRef.value
    if (!el) return
    const active = el.querySelector('.oqm__date-item--active') as HTMLElement | null
    if (active) {
      active.scrollIntoView({ behavior: 'auto', block: 'nearest', inline: 'center' })
    }
  }, 30)
}

/* ============ 订单汇总:客户端聚合 ============ */
/**
 * 拉取指定日期全部订单(分页循环),按餐别 + 菜品聚合:
 * - remaining(剩余)= status=1(待取餐)数量,随取餐减少
 * - total(订购)= status=1+2 数量
 */
const fetchSummary = async () => {
  if (storeId.value == null) return
  summaryLoading.value = true
  try {
    const allOrders: Order[] = []
    let p = 1
    const pageSize = 500
    // 分页循环拉满当天订单(安全上限 20 页)
    while (p <= 20) {
      const resp = await api.get(`/order/store/${storeId.value}`, {
        params: { startDate: summaryDate.value, endDate: summaryDate.value, page: p, size: pageSize },
      })
      if (resp.data.code !== 200) break
      const data = resp.data.data || {}
      const recs: Order[] = data.records || []
      allOrders.push(...recs)
      const t = data.total || 0
      if (recs.length < pageSize || allOrders.length >= t) break
      p++
    }

    // 聚合:餐别 -> 菜品(key) -> 行
    const map: Record<number, Record<string, SummaryRow>> = { 1: {}, 2: {}, 3: {} }
    for (const o of allOrders) {
      if (o.status !== 1 && o.status !== 2) continue // 排除已取消/超时
      const mt = o.mealType
      if (!map[mt]) map[mt] = {}
      for (const it of (o.items || [])) {
        const key = `${it.dishId}|${it.dishName}|${it.price}`
        if (!map[mt][key]) {
          map[mt][key] = { dishName: it.dishName, price: it.price, remaining: 0, total: 0 }
        }
        const qty = Number(it.quantity || 1)
        map[mt][key].total += qty
        if (o.status === 1) map[mt][key].remaining += qty
      }
    }

    const result: Record<number, SummaryRow[]> = { 1: [], 2: [], 3: [] }
    for (const mt of MEAL_TYPES) {
      result[mt] = Object.values(map[mt] || {}).sort(
        (a, b) => b.remaining - a.remaining || b.total - a.total,
      )
    }
    summaryMap.value = result
  } catch {
    summaryMap.value = { 1: [], 2: [], 3: [] }
  } finally {
    summaryLoading.value = false
  }
}

/** 汇总某餐别总剩余份数 */
const mealRemaining = (mt: number) =>
  (summaryMap.value[mt] || []).reduce((s, r) => s + r.remaining, 0)
/** 汇总某餐别订购总份数 */
const mealTotal = (mt: number) =>
  (summaryMap.value[mt] || []).reduce((s, r) => s + r.total, 0)
/** 汇总全部剩余份数 */
const allRemaining = computed(() =>
  MEAL_TYPES.reduce((s, mt) => s + mealRemaining(mt), 0))

/** 切换到汇总视图并拉取数据 */
const goSummary = () => {
  view.value = 'summary'
  fetchSummary()
}
/** 返回查询主界面 */
const backToList = () => { view.value = 'list' }

/** 关闭弹窗 */
const close = () => {
  emit('update:modelValue', false)
}

/** 订单详情:小计 = 单品行金额 */
const lineTotal = (it: OrderItem) => Number(it.price) * Number(it.quantity || 1)
/** 订单详情:合计金额(优先用后端 totalAmount) */
const orderTotal = (o: Order) => {
  if (o.totalAmount != null) return Number(o.totalAmount)
  return (o.items || []).reduce((s, it) => s + lineTotal(it), 0)
}

// 弹窗打开时初始化并拉取当天订单;关闭时清理详情
watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      view.value = 'list'
      queryDate.value = today
      summaryDate.value = today
      keyword.value = ''
      mealFilter.value = 0
      statusFilter.value = 'all'
      detailOrder.value = null
      fetchOrders(true)
      scrollSelectedIntoView()
    } else {
      detailOrder.value = null
    }
  },
)
</script>

<template>
  <Teleport to="body">
    <Transition name="oqm">
      <div v-if="modelValue" class="oqm__overlay">
        <div class="oqm__panel">
          <!-- ========== 查询主界面 ========== -->
          <template v-if="view === 'list'">
            <!-- 顶部工具栏 -->
            <header class="oqm__toolbar">
              <div class="oqm__toolbar-left">
                <FileText :size="22" class="oqm__toolbar-icon" />
                <span class="oqm__toolbar-title">订单查询</span>
              </div>
              <div class="oqm__toolbar-right">
                <button class="oqm__btn oqm__btn--summary btn-press" @click="goSummary">
                  <Utensils :size="16" />
                  订单汇总
                </button>
                <button class="oqm__btn oqm__btn--close btn-press" aria-label="关闭" @click="close">
                  <X :size="18" />
                </button>
              </div>
            </header>

            <!-- 日期选择器(横向日历条,点击即查询) -->
            <div class="oqm__date-strip-wrap">
              <button
                class="oqm__date-nav btn-press"
                aria-label="向前"
                @click="scrollDateLeft"
              >
                <ChevronLeft :size="18" />
              </button>
              <div ref="dateStripRef" class="oqm__date-strip no-scrollbar">
                <button
                  v-for="d in dateList"
                  :key="d"
                  type="button"
                  class="oqm__date-item btn-press"
                  :class="{
                    'oqm__date-item--active': d === queryDate,
                    'oqm__date-item--today': d === today,
                  }"
                  @click="onSelectQueryDate(d)"
                >
                  <span class="oqm__date-item-rel">{{ dateRelLabel(d) }}</span>
                  <span class="oqm__date-item-num">{{ shortDate(d) }}</span>
                  <span class="oqm__date-item-week">{{ weekdayLabel(d) }}</span>
                </button>
              </div>
              <button
                class="oqm__date-nav btn-press"
                aria-label="向后"
                @click="scrollDateRight"
              >
                <ChevronRight :size="18" />
              </button>
            </div>

            <!-- 筛选条 -->
            <div class="oqm__filters">
              <!-- 姓名/卡号搜索 -->
              <label class="oqm__field oqm__field--search">
                <span class="oqm__field-label">姓名/卡号</span>
                <div class="oqm__search-wrap">
                  <input
                    v-model="keyword"
                    type="text"
                    placeholder="输入姓名或卡号"
                    class="oqm__text-input"
                    @keyup.enter="onKeywordEnter"
                  />
                  <button class="oqm__search-btn btn-press" aria-label="搜索" @click="onKeywordEnter">
                    <Search :size="16" />
                  </button>
                </div>
              </label>

              <!-- 餐别选择 -->
              <label class="oqm__field">
                <span class="oqm__field-label">餐别</span>
                <select v-model="mealFilter" class="oqm__select" @change="onFilterChange">
                  <option :value="0">全部</option>
                  <option :value="1">早餐</option>
                  <option :value="2">午餐</option>
                  <option :value="3">晚餐</option>
                </select>
              </label>

              <!-- 就餐状态选择 -->
              <label class="oqm__field">
                <span class="oqm__field-label">状态</span>
                <select v-model="statusFilter" class="oqm__select" @change="onFilterChange">
                  <option value="all">全部</option>
                  <option value="uneaten">未吃</option>
                  <option value="eaten">已吃</option>
                </select>
              </label>
            </div>

            <!-- 列表表头 -->
            <div class="oqm__list-head">
              <span class="oqm__col oqm__col--no">单号</span>
              <span class="oqm__col oqm__col--card">会员卡号</span>
              <span class="oqm__col oqm__col--name">姓名</span>
              <span class="oqm__col oqm__col--meal">餐次</span>
              <span class="oqm__col oqm__col--status">状态</span>
            </div>

            <!-- 列表区(可滚动) -->
            <div class="oqm__list no-scrollbar">
              <!-- 加载中(首次) -->
              <div v-if="loading && orders.length === 0" class="oqm__state">
                <Loader2 :size="32" class="oqm__spin spinner" />
                <span>加载中...</span>
              </div>

              <!-- 空状态 -->
              <div v-else-if="orders.length === 0" class="oqm__state">
                <div class="oqm__state-icon">📭</div>
                <span>没有符合条件的订单</span>
              </div>

              <!-- 订单行 -->
              <template v-else>
                <button
                  v-for="o in orders"
                  :key="o.id"
                  type="button"
                  class="oqm__row btn-press"
                  @click="detailOrder = o"
                >
                  <span class="oqm__col oqm__col--no oqm__ellipsis">{{ o.orderNo || '-' }}</span>
                  <span class="oqm__col oqm__col--card oqm__ellipsis">{{ o.cardNo || '-' }}</span>
                  <span class="oqm__col oqm__col--name oqm__ellipsis">{{ o.employeeName || '-' }}</span>
                  <span class="oqm__col oqm__col--meal">
                    <span class="oqm__meal-badge" :style="mealBadgeStyle(o.mealType)">
                      {{ mealTypeLabel(o.mealType) }}
                    </span>
                  </span>
                  <span class="oqm__col oqm__col--status">
                    <span class="oqm__status" :class="statusInfo(o.status).cls">
                      {{ statusInfo(o.status).text }}
                    </span>
                  </span>
                </button>

                <!-- 加载更多 -->
                <div v-if="hasMore" class="oqm__more">
                  <button
                    class="oqm__more-btn btn-press"
                    :disabled="loading"
                    @click="loadMore"
                  >
                    <Loader2 v-if="loading" :size="16" class="spinner" />
                    {{ loading ? '加载中...' : '加载更多' }}
                  </button>
                </div>
                <div v-else class="oqm__list-end">
                  共 {{ orders.length }} 条 · 已全部加载
                </div>
              </template>
            </div>
          </template>

          <!-- ========== 订单汇总界面 ========== -->
          <template v-else>
            <!-- 顶部工具栏 -->
            <header class="oqm__toolbar">
              <div class="oqm__toolbar-left">
                <button class="oqm__btn oqm__btn--back btn-press" aria-label="返回" @click="backToList">
                  <ChevronLeft :size="20" />
                </button>
                <Utensils :size="22" class="oqm__toolbar-icon" />
                <span class="oqm__toolbar-title">订单汇总</span>
              </div>
              <div class="oqm__toolbar-right">
                <button class="oqm__btn oqm__btn--close btn-press" aria-label="关闭" @click="close">
                  <X :size="18" />
                </button>
              </div>
            </header>

            <!-- 汇总日期选择器(横向日历条,点击即查询) -->
            <div class="oqm__date-strip-wrap">
              <button
                class="oqm__date-nav btn-press"
                aria-label="向前"
                @click="scrollDateLeft"
              >
                <ChevronLeft :size="18" />
              </button>
              <div ref="dateStripRef" class="oqm__date-strip no-scrollbar">
                <button
                  v-for="d in dateList"
                  :key="d"
                  type="button"
                  class="oqm__date-item btn-press"
                  :class="{
                    'oqm__date-item--active': d === summaryDate,
                    'oqm__date-item--today': d === today,
                  }"
                  @click="onSelectSummaryDate(d)"
                >
                  <span class="oqm__date-item-rel">{{ dateRelLabel(d) }}</span>
                  <span class="oqm__date-item-num">{{ shortDate(d) }}</span>
                  <span class="oqm__date-item-week">{{ weekdayLabel(d) }}</span>
                </button>
              </div>
              <button
                class="oqm__date-nav btn-press"
                aria-label="向后"
                @click="scrollDateRight"
              >
                <ChevronRight :size="18" />
              </button>
            </div>

            <!-- 汇总信息条 -->
            <div class="oqm__filters">
              <div class="oqm__field oqm__field--store">
                <span class="oqm__field-label">店铺</span>
                <span class="oqm__store-name oqm__ellipsis">{{ storeName }}</span>
              </div>
              <div class="oqm__field oqm__field--total">
                <span class="oqm__field-label">剩余总份数</span>
                <span class="oqm__total-num">{{ allRemaining }}</span>
              </div>
            </div>

            <!-- 汇总小票(不滚动,所有菜一屏显示) -->
            <div class="oqm__receipt-summary">
              <div v-if="summaryLoading" class="oqm__state">
                <Loader2 :size="32" class="oqm__spin spinner" />
                <span>统计中...</span>
              </div>

              <div v-else-if="allRemaining === 0 && !MEAL_TYPES.some(m => mealTotal(m) > 0)" class="oqm__state">
                <div class="oqm__state-icon">🍱</div>
                <span>该日期暂无订餐数据</span>
              </div>

              <template v-else>
                <!-- 小票头部 -->
                <div class="oqm__rs-head">
                  <div class="oqm__rs-title">{{ storeName }}</div>
                  <div class="oqm__rs-date">{{ summaryDate }}</div>
                </div>
                <div class="oqm__rs-divider"></div>

                <!-- 按餐别分组(三列布局,紧凑小票风格) -->
                <div class="oqm__rs-body">
                  <section
                    v-for="mt in MEAL_TYPES"
                    :key="mt"
                    class="oqm__rs-meal"
                  >
                    <div class="oqm__rs-meal-head">
                      <span class="oqm__rs-meal-name">{{ mealTypeLabel(mt) }}</span>
                      <span class="oqm__rs-meal-stat">{{ mealRemaining(mt) }}/{{ mealTotal(mt) }}</span>
                    </div>
                    <div v-if="(summaryMap[mt] || []).length" class="oqm__rs-dishes">
                      <div
                        v-for="(row, idx) in summaryMap[mt]"
                        :key="`${mt}-${idx}`"
                        class="oqm__rs-dish"
                        :class="{ 'oqm__rs-dish--zero': row.remaining === 0 }"
                      >
                        <span class="oqm__rs-dish-name">{{ row.dishName }}</span>
                        <span class="oqm__rs-dish-qty">{{ row.remaining }}</span>
                      </div>
                    </div>
                    <div v-else class="oqm__rs-dish-empty">无</div>
                  </section>
                </div>

                <div class="oqm__rs-divider"></div>
                <div class="oqm__rs-foot">剩余份数 / 订购总份数</div>
              </template>
            </div>
          </template>
        </div>

        <!-- ========== 订单详情(小票子弹窗) ========== -->
        <Transition name="oqm-detail">
          <div v-if="detailOrder" class="oqm__detail-overlay">
            <div class="oqm__receipt">
              <!-- 关闭按钮 -->
              <button class="oqm__receipt-close btn-press" aria-label="关闭" @click="detailOrder = null">
                <X :size="18" />
              </button>

              <!-- 小票头部 -->
              <div class="oqm__receipt-head">
                <div class="oqm__receipt-store">{{ storeName }}</div>
                <div class="oqm__receipt-sub">取餐凭证</div>
              </div>

              <!-- 订单元信息 -->
              <div class="oqm__receipt-meta">
                <div class="oqm__receipt-meta-row">
                  <span class="oqm__receipt-meta-label">单号</span>
                  <span class="oqm__receipt-meta-value">{{ detailOrder.orderNo || '-' }}</span>
                </div>
                <div class="oqm__receipt-meta-row">
                  <span class="oqm__receipt-meta-label">姓名</span>
                  <span class="oqm__receipt-meta-value">{{ detailOrder.employeeName || '-' }}</span>
                </div>
                <div class="oqm__receipt-meta-row">
                  <span class="oqm__receipt-meta-label">卡号</span>
                  <span class="oqm__receipt-meta-value">{{ detailOrder.cardNo || '-' }}</span>
                </div>
                <div class="oqm__receipt-meta-row">
                  <span class="oqm__receipt-meta-label">餐次</span>
                  <span class="oqm__receipt-meta-value">
                    <span class="oqm__meal-badge" :style="mealBadgeStyle(detailOrder.mealType)">
                      {{ mealTypeLabel(detailOrder.mealType) }}
                    </span>
                  </span>
                </div>
                <div class="oqm__receipt-meta-row">
                  <span class="oqm__receipt-meta-label">时间</span>
                  <span class="oqm__receipt-meta-value oqm__receipt-time">
                    <Clock :size="13" />
                    {{ formatDateTime(detailOrder.createdAt) || '-' }}
                  </span>
                </div>
                <div class="oqm__receipt-meta-row">
                  <span class="oqm__receipt-meta-label">状态</span>
                  <span class="oqm__receipt-meta-value">
                    <span class="oqm__status" :class="statusInfo(detailOrder.status).cls">
                      <CheckCircle2 v-if="detailOrder.status === 2" :size="13" />
                      {{ statusInfo(detailOrder.status).text }}
                    </span>
                  </span>
                </div>
              </div>

              <!-- 分隔线 -->
              <div class="oqm__receipt-divider"></div>

              <!-- 菜品列表 -->
              <div class="oqm__receipt-items">
                <div class="oqm__receipt-items-head">
                  <span>菜品</span>
                  <span>金额</span>
                </div>
                <div
                  v-for="(it, idx) in (detailOrder.items || [])"
                  :key="idx"
                  class="oqm__receipt-item"
                >
                  <span class="oqm__receipt-dish">
                    {{ it.dishName }}
                    <span v-if="Number(it.quantity || 1) > 1" class="oqm__receipt-qty">×{{ it.quantity }}</span>
                  </span>
                  <span class="oqm__receipt-price">¥{{ formatMoney(lineTotal(it)) }}</span>
                </div>
                <div v-if="!(detailOrder.items && detailOrder.items.length)" class="oqm__receipt-no-items">
                  暂无菜品明细
                </div>
              </div>

              <!-- 分隔线 -->
              <div class="oqm__receipt-divider"></div>

              <!-- 合计 -->
              <div class="oqm__receipt-total">
                <span>合计</span>
                <span class="oqm__receipt-total-num">¥{{ formatMoney(orderTotal(detailOrder)) }}</span>
              </div>

              <!-- 小票底部 -->
              <div class="oqm__receipt-foot">
                —— 感谢您的使用 ——
              </div>
            </div>
          </div>
        </Transition>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
/* 遮罩层 */
.oqm__overlay {
  position: fixed;
  inset: 0;
  z-index: 200;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: rgba(14, 17, 21, 0.32);
}

/* 主面板:自适应大小,最大 90vw × 85vh */
.oqm__panel {
  position: relative;
  width: 90vw;
  max-width: 1100px;
  height: 85vh;
  max-height: 85vh;
  display: flex;
  flex-direction: column;
  background: var(--doubao-background);
  border: 1px solid var(--doubao-border);
  border-radius: var(--doubao-radius);
  box-shadow: 0 24px 64px rgba(0, 0, 0, 0.28);
  overflow: hidden;
}

/* ============ 顶部工具栏 ============ */
.oqm__toolbar {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 20px;
  background: var(--doubao-card);
  border-bottom: 1px solid var(--doubao-border);
}
.oqm__toolbar-left {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.oqm__toolbar-icon {
  color: var(--doubao-primary);
  flex-shrink: 0;
}
.oqm__toolbar-title {
  font-size: var(--fs-xl);
  font-weight: 700;
  color: var(--doubao-foreground);
  white-space: nowrap;
}
.oqm__toolbar-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

/* 通用按钮 */
.oqm__btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  height: 40px;
  padding: 0 14px;
  border-radius: var(--doubao-radius-sm);
  border: none;
  font-family: inherit;
  font-size: var(--fs-sm);
  font-weight: 700;
  cursor: pointer;
  transition: background 0.15s ease, transform 0.12s ease, opacity 0.15s ease;
}
.oqm__btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.oqm__btn--summary {
  background: var(--doubao-accent);
  color: var(--doubao-primary);
}
.oqm__btn--summary:hover {
  background: var(--doubao-primary);
  color: var(--doubao-primary-foreground);
}
.oqm__btn--back,
.oqm__btn--close {
  width: 40px;
  padding: 0;
  background: var(--doubao-muted);
  color: var(--doubao-secondary-foreground);
}
.oqm__btn--back:hover,
.oqm__btn--close:hover {
  background: var(--doubao-border);
  color: var(--doubao-foreground);
}

/* ============ 筛选条 ============ */
.oqm__filters {
  flex-shrink: 0;
  display: flex;
  align-items: flex-end;
  gap: 14px;
  flex-wrap: wrap;
  padding: 14px 20px;
  background: var(--doubao-card);
  border-bottom: 1px solid var(--doubao-border);
}
.oqm__field {
  display: flex;
  flex-direction: column;
  gap: 5px;
  min-width: 0;
}
.oqm__field--search { flex: 1; min-width: 220px; }
.oqm__field--store { flex: 1; min-width: 160px; }
.oqm__field--total { width: auto; }
.oqm__field-label {
  font-size: var(--fs-xs);
  font-weight: 700;
  color: var(--doubao-muted-foreground);
}
.oqm__text-input,
.oqm__select {
  height: 40px;
  padding: 0 12px;
  border: 1.5px solid var(--doubao-border);
  border-radius: var(--doubao-radius-sm);
  background: var(--doubao-background);
  color: var(--doubao-foreground);
  font-family: inherit;
  font-size: var(--fs-base);
  outline: none;
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
}
.oqm__text-input:focus,
.oqm__select:focus {
  border-color: var(--doubao-primary);
  box-shadow: 0 0 0 3px rgba(0, 101, 253, 0.12);
}

/* ============ 日期选择器(横向日历条) ============ */
.oqm__date-strip-wrap {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 10px 16px;
  background: var(--doubao-card);
  border-bottom: 1px solid var(--doubao-border);
}
.oqm__date-nav {
  flex-shrink: 0;
  width: 32px;
  height: 64px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--doubao-border);
  border-radius: var(--doubao-radius-sm);
  background: var(--doubao-background);
  color: var(--doubao-secondary-foreground);
  cursor: pointer;
  transition: background 0.15s ease, color 0.15s ease;
}
.oqm__date-nav:hover {
  background: var(--doubao-muted);
  color: var(--doubao-foreground);
}
.oqm__date-strip {
  flex: 1;
  display: flex;
  gap: 8px;
  overflow-x: auto;
  scroll-behavior: smooth;
  padding: 2px 0;
}
.oqm__date-item {
  flex-shrink: 0;
  width: 78px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  padding: 8px 4px;
  border: 1.5px solid var(--doubao-border);
  border-radius: var(--doubao-radius-sm);
  background: var(--doubao-background);
  color: var(--doubao-foreground);
  font-family: inherit;
  cursor: pointer;
  transition: background 0.15s ease, border-color 0.15s ease, transform 0.1s ease;
}
.oqm__date-item:hover {
  background: var(--doubao-muted);
  border-color: var(--doubao-primary);
}
.oqm__date-item:active {
  transform: scale(0.96);
}
.oqm__date-item--today {
  border-color: var(--doubao-primary);
  border-width: 2px;
}
.oqm__date-item--active {
  background: var(--doubao-primary);
  border-color: var(--doubao-primary);
  color: var(--doubao-primary-foreground);
}
.oqm__date-item--active:hover {
  background: var(--doubao-primary);
}
.oqm__date-item-rel {
  font-size: var(--fs-xs);
  font-weight: 700;
  color: var(--doubao-primary);
  line-height: 1.1;
}
.oqm__date-item--active .oqm__date-item-rel {
  color: var(--doubao-primary-foreground);
}
.oqm__date-item-num {
  font-size: var(--fs-sm);
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  line-height: 1.2;
}
.oqm__date-item-week {
  font-size: 10px;
  color: var(--doubao-muted-foreground);
  line-height: 1;
}
.oqm__date-item--active .oqm__date-item-week {
  color: rgba(255, 255, 255, 0.85);
}
.oqm__search-wrap {
  position: relative;
  display: flex;
  align-items: center;
}
.oqm__search-wrap .oqm__text-input {
  width: 100%;
  padding-right: 44px;
}
.oqm__search-btn {
  position: absolute;
  right: 4px;
  top: 50%;
  transform: translateY(-50%);
  width: 32px;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: var(--doubao-radius-xs);
  background: var(--doubao-primary);
  color: var(--doubao-primary-foreground);
  cursor: pointer;
}
.oqm__store-name {
  height: 40px;
  display: flex;
  align-items: center;
  padding: 0 12px;
  border: 1.5px solid var(--doubao-border);
  border-radius: var(--doubao-radius-sm);
  background: var(--doubao-muted);
  color: var(--doubao-foreground);
  font-size: var(--fs-base);
  font-weight: 700;
}
.oqm__total-num {
  height: 40px;
  display: flex;
  align-items: center;
  padding: 0 16px;
  border-radius: var(--doubao-radius-sm);
  background: linear-gradient(135deg, rgba(0, 101, 253, 0.12), rgba(0, 101, 253, 0.2));
  color: var(--doubao-primary);
  font-size: var(--fs-lg);
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}

/* ============ 列表表头 ============ */
.oqm__list-head {
  flex-shrink: 0;
  display: grid;
  grid-template-columns: 1.4fr 1fr 0.9fr 0.8fr 0.7fr;
  gap: 12px;
  padding: 10px 20px;
  background: var(--doubao-muted);
  border-bottom: 1px solid var(--doubao-border);
  font-size: var(--fs-xs);
  font-weight: 700;
  color: var(--doubao-muted-foreground);
}

/* ============ 列表区 ============ */
.oqm__list {
  flex: 1;
  overflow-y: auto;
  min-height: 0;
  padding: 8px 12px 16px;
}
.oqm__row {
  display: grid;
  grid-template-columns: 1.4fr 1fr 0.9fr 0.8fr 0.7fr;
  gap: 12px;
  align-items: center;
  width: 100%;
  padding: 14px 8px;
  border: none;
  border-bottom: 1px solid var(--doubao-border);
  background: transparent;
  font-family: inherit;
  font-size: var(--fs-base);
  color: var(--doubao-foreground);
  text-align: left;
  cursor: pointer;
  transition: background 0.15s ease;
}
.oqm__row:hover {
  background: var(--doubao-muted);
}
.oqm__row:active {
  background: var(--doubao-accent);
}
.oqm__col { min-width: 0; }
.oqm__ellipsis {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.oqm__col--name { font-weight: 700; }

/* 餐别胶囊 */
.oqm__meal-badge {
  display: inline-flex;
  align-items: center;
  padding: 3px 10px;
  border-radius: 999px;
  border: 1px solid;
  font-size: var(--fs-xs);
  font-weight: 700;
  white-space: nowrap;
}

/* 状态标签 */
.oqm__status {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 3px 10px;
  border-radius: 999px;
  font-size: var(--fs-xs);
  font-weight: 700;
  white-space: nowrap;
}
.oqm__status.is-uneaten {
  background: rgba(255, 151, 106, 0.15);
  color: var(--doubao-warning);
}
.oqm__status.is-eaten {
  background: rgba(7, 193, 96, 0.12);
  color: var(--doubao-success);
}
.oqm__status.is-cancelled {
  background: var(--doubao-muted);
  color: var(--doubao-muted-foreground);
}
.oqm__status.is-timeout {
  background: rgba(239, 68, 68, 0.12);
  color: var(--doubao-destructive);
}

/* 列表状态(加载/空) */
.oqm__state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 64px 0;
  color: var(--doubao-muted-foreground);
  font-size: var(--fs-base);
}
.oqm__state-icon { font-size: 48px; }
.oqm__spin { color: var(--doubao-primary); }

/* 加载更多 */
.oqm__more {
  display: flex;
  justify-content: center;
  padding: 16px 0;
}
.oqm__more-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 40px;
  padding: 0 24px;
  border: 1.5px solid var(--doubao-border);
  border-radius: 999px;
  background: var(--doubao-card);
  color: var(--doubao-secondary-foreground);
  font-family: inherit;
  font-size: var(--fs-sm);
  font-weight: 700;
  cursor: pointer;
}
.oqm__more-btn:disabled { opacity: 0.6; cursor: not-allowed; }
.oqm__list-end {
  text-align: center;
  padding: 14px 0;
  font-size: var(--fs-xs);
  color: var(--doubao-muted-foreground);
}

/* ============ 订单汇总(小票模式,不滚动) ============ */
.oqm__receipt-summary {
  flex: 1;
  overflow: hidden;
  min-height: 0;
  padding: 12px 20px 16px;
  display: flex;
  flex-direction: column;
}
/* 小票头部 */
.oqm__rs-head {
  text-align: center;
  padding: 2px 0 6px;
}
.oqm__rs-title {
  font-size: var(--fs-lg);
  font-weight: 700;
  color: var(--doubao-foreground);
}
.oqm__rs-date {
  margin-top: 2px;
  font-size: var(--fs-xs);
  color: var(--doubao-muted-foreground);
  font-variant-numeric: tabular-nums;
}
.oqm__rs-divider {
  height: 0;
  border-top: 1px dashed var(--doubao-border);
  margin: 4px 0;
}
/* 三列餐别布局 */
.oqm__rs-body {
  flex: 1;
  display: grid;
  grid-template-columns: 1fr 1fr 1fr;
  gap: 10px;
  min-height: 0;
  overflow: hidden;
}
.oqm__rs-meal {
  display: flex;
  flex-direction: column;
  min-height: 0;
  border: 1px solid var(--doubao-border);
  border-radius: var(--doubao-radius-sm);
  background: var(--doubao-card);
  overflow: hidden;
}
.oqm__rs-meal-head {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 10px;
  background: var(--doubao-muted);
  border-bottom: 1px solid var(--doubao-border);
}
.oqm__rs-meal-name {
  font-size: var(--fs-sm);
  font-weight: 700;
  color: var(--doubao-foreground);
}
.oqm__rs-meal-stat {
  font-size: var(--fs-xs);
  font-weight: 700;
  color: var(--doubao-muted-foreground);
  font-variant-numeric: tabular-nums;
}
.oqm__rs-dishes {
  flex: 1;
  overflow-y: auto;
  padding: 2px 0;
}
.oqm__rs-dish {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
  padding: 4px 10px;
  border-bottom: 1px dashed var(--doubao-border);
  font-size: var(--fs-xs);
}
.oqm__rs-dish:last-child { border-bottom: none; }
.oqm__rs-dish--zero { opacity: 0.4; }
.oqm__rs-dish-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--doubao-foreground);
  font-weight: 600;
}
.oqm__rs-dish-qty {
  flex-shrink: 0;
  font-size: var(--fs-sm);
  font-weight: 700;
  color: var(--doubao-primary);
  font-variant-numeric: tabular-nums;
  min-width: 24px;
  text-align: right;
}
.oqm__rs-dish--zero .oqm__rs-dish-qty { color: var(--doubao-muted-foreground); }
.oqm__rs-dish-empty {
  padding: 12px 10px;
  text-align: center;
  font-size: var(--fs-xs);
  color: var(--doubao-muted-foreground);
}
.oqm__rs-foot {
  text-align: center;
  font-size: var(--fs-xs);
  color: var(--doubao-muted-foreground);
  padding: 2px 0;
}

/* ============ 订单详情(小票) ============ */
.oqm__detail-overlay {
  position: fixed;
  inset: 0;
  z-index: 210;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: rgba(14, 17, 21, 0.45);
}
.oqm__receipt {
  position: relative;
  width: 100%;
  max-width: 380px;
  max-height: 86vh;
  overflow-y: auto;
  padding: 24px 22px;
  background: var(--doubao-card);
  border: 1px solid var(--doubao-border);
  border-radius: var(--doubao-radius);
  box-shadow: 0 16px 40px rgba(0, 0, 0, 0.24);
}
.oqm__receipt-close {
  position: absolute;
  top: 10px;
  right: 10px;
  width: 32px;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: 50%;
  background: var(--doubao-muted);
  color: var(--doubao-muted-foreground);
  cursor: pointer;
}
.oqm__receipt-close:hover {
  background: var(--doubao-border);
  color: var(--doubao-foreground);
}

/* 小票头部 */
.oqm__receipt-head {
  text-align: center;
  padding-bottom: 14px;
}
.oqm__receipt-store {
  font-size: var(--fs-lg);
  font-weight: 700;
  color: var(--doubao-foreground);
}
.oqm__receipt-sub {
  margin-top: 2px;
  font-size: var(--fs-xs);
  color: var(--doubao-muted-foreground);
  letter-spacing: 2px;
}

/* 小票元信息 */
.oqm__receipt-meta {
  padding: 12px 0 4px;
}
.oqm__receipt-meta-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 5px 0;
  font-size: var(--fs-sm);
}
.oqm__receipt-meta-label {
  color: var(--doubao-muted-foreground);
  flex-shrink: 0;
}
.oqm__receipt-meta-value {
  color: var(--doubao-foreground);
  font-weight: 700;
  text-align: right;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.oqm__receipt-time {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-weight: 400;
}

/* 分隔线(虚线,小票风格) */
.oqm__receipt-divider {
  height: 0;
  margin: 10px 0;
  border-top: 1px dashed var(--doubao-border);
}

/* 菜品列表 */
.oqm__receipt-items-head {
  display: flex;
  justify-content: space-between;
  padding-bottom: 6px;
  font-size: var(--fs-xs);
  font-weight: 700;
  color: var(--doubao-muted-foreground);
}
.oqm__receipt-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 9px 0;
  font-size: var(--fs-base);
}
.oqm__receipt-dish {
  color: var(--doubao-foreground);
  font-weight: 700;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.oqm__receipt-qty {
  margin-left: 4px;
  font-size: var(--fs-sm);
  font-weight: 400;
  color: var(--doubao-muted-foreground);
}
.oqm__receipt-price {
  font-weight: 700;
  color: var(--doubao-primary);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
.oqm__receipt-no-items {
  padding: 16px 0;
  text-align: center;
  font-size: var(--fs-sm);
  color: var(--doubao-muted-foreground);
}

/* 合计 */
.oqm__receipt-total {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 0 4px;
  font-size: var(--fs-lg);
  font-weight: 700;
  color: var(--doubao-foreground);
}
.oqm__receipt-total-num {
  color: var(--doubao-destructive);
  font-size: var(--fs-xl);
  font-variant-numeric: tabular-nums;
}

/* 小票底部 */
.oqm__receipt-foot {
  margin-top: 12px;
  text-align: center;
  font-size: var(--fs-xs);
  color: var(--doubao-muted-foreground);
  letter-spacing: 1px;
}

/* ============ 动画 ============ */
.oqm-enter-active { transition: opacity 0.2s ease; }
.oqm-enter-active .oqm__panel {
  transition: opacity 0.2s ease;
}
.oqm-enter-from { opacity: 0; }
.oqm-enter-from .oqm__panel { opacity: 0; }
.oqm-leave-active { transition: opacity 0.15s ease; }
.oqm-leave-to { opacity: 0; }

.oqm-detail-enter-active { transition: opacity 0.18s ease; }
.oqm-detail-enter-active .oqm__receipt {
  transition: opacity 0.18s ease;
}
.oqm-detail-enter-from { opacity: 0; }
.oqm-detail-enter-from .oqm__receipt { opacity: 0; }
.oqm-detail-leave-active { transition: opacity 0.13s ease; }
.oqm-detail-leave-to { opacity: 0; }

/* ============ 响应式 ============ */
@media (max-width: 768px) {
  .oqm__panel { width: 96vw; height: 90vh; }
  .oqm__filters { gap: 10px; padding: 12px 14px; }
  .oqm__field--search { min-width: 100%; }
  .oqm__list-head { display: none; }
  .oqm__row {
    grid-template-columns: 1fr 1fr;
    gap: 6px;
    padding: 12px 8px;
  }
  .oqm__date-item { width: 70px; padding: 6px 2px; }
}
</style>
