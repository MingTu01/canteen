<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import * as echarts from 'echarts'
import {
  ElTable,
  ElTableColumn,
} from 'element-plus'
import {
  ShoppingBag,
  Wallet,
  CheckCircle2,
  Clock,
  ChefHat,
  ClipboardList,
  CreditCard,
  BarChart3,
  ArrowRight,
} from 'lucide-vue-next'
import Layout from '@/components/Layout.vue'
import PageContainer from '@/components/PageContainer.vue'
import StatCard from '@/components/StatCard.vue'
import StatusTag from '@/components/StatusTag.vue'
import { useAuthStore } from '@/stores/auth'
import { useThemeStore } from '@/stores/theme'
import { orderApi } from '@/api'
import type { Order, PageResult } from '@/api/types'
import { ORDER_STATUS, MEAL_TYPE } from '@/constants/dict'

const authStore = useAuthStore()
const themeStore = useThemeStore()
// 超管未选择食堂时返回 null,不再静默回退到 storeId=1
const sid = computed(() => authStore.storeId || null)
const noStoreSelected = computed(() => sid.value === null)

type OrderRow = Order & { employeeName?: string }

interface TrendItem {
  date: string
  orderCount: number
  revenue: number
}

interface DashboardData {
  todayOrders: number
  todayRevenue: number | string
  completedOrders: number
  pendingOrders: number
  completionRate: number
  mealTypeStats: { breakfast: number; lunch: number; dinner: number }
  trend?: TrendItem[]
}

const stats = ref<DashboardData>({
  todayOrders: 0,
  todayRevenue: '0.00',
  completedOrders: 0,
  pendingOrders: 0,
  completionRate: 0,
  mealTypeStats: { breakfast: 0, lunch: 0, dinner: 0 },
})

const recentOrders = ref<OrderRow[]>([])
const trendDays = ref<string[]>([])
const trendCounts = ref<number[]>([])

const pieChartRef = ref<HTMLElement | null>(null)
const lineChartRef = ref<HTMLElement | null>(null)
let pieChart: echarts.ECharts | null = null
let lineChart: echarts.ECharts | null = null

const todayRevenueText = computed(() => {
  const v = stats.value.todayRevenue
  return `¥${typeof v === 'number' ? v.toFixed(2) : v}`
})

const buildTrend = (trend?: TrendItem[]) => {
  if (Array.isArray(trend) && trend.length) {
    // 后端返回的 date 格式为 YYYY-MM-DD,取后 5 位(MM-DD)作为坐标轴标签,与原展示风格一致
    trendDays.value = trend.map((t) => t.date.slice(5))
    trendCounts.value = trend.map((t) => t.orderCount)
  } else {
    trendDays.value = []
    trendCounts.value = []
  }
}

const fetchDashboard = async () => {
  // 超管未选择食堂:不请求,重置为空状态
  if (sid.value === null) {
    stats.value = {
      todayOrders: 0,
      todayRevenue: '0.00',
      completedOrders: 0,
      pendingOrders: 0,
      completionRate: 0,
      mealTypeStats: { breakfast: 0, lunch: 0, dinner: 0 },
    }
    recentOrders.value = []
    trendDays.value = []
    trendCounts.value = []
    return
  }
  try {
    const [dash, recent] = await Promise.all([
      orderApi.dashboard(sid.value),
      orderApi.list({ storeId: sid.value, size: 5 }),
    ])
    stats.value = dash as unknown as DashboardData

    const r = recent as unknown as PageResult<OrderRow> | OrderRow[]
    recentOrders.value = Array.isArray(r) ? r : r.records ?? []

    // 趋势数据直接使用后端 dashboard 接口返回的 trend 字段
    buildTrend(stats.value.trend)
  } catch {
    /* 错误已由拦截器统一提示 */
  }
}

const initCharts = () => {
  const textColor = themeStore.isDark ? '#cbd5e1' : '#475569'
  const borderColor = themeStore.isDark ? '#1e2530' : '#e2e8f0'
  const cardBg = themeStore.isDark ? '#161b22' : '#ffffff'

  if (pieChartRef.value) {
    pieChart = echarts.init(pieChartRef.value)
    const m = stats.value.mealTypeStats
    pieChart.setOption({
      tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
      legend: { bottom: 0, icon: 'circle', textStyle: { color: textColor } },
      series: [
        {
          type: 'pie',
          radius: ['45%', '70%'],
          center: ['50%', '45%'],
          avoidLabelOverlap: false,
          itemStyle: { borderRadius: 8, borderColor: cardBg, borderWidth: 2 },
          label: { show: true, formatter: '{b}\n{c}', color: textColor, fontSize: 12 },
          labelLine: { length: 10, length2: 10 },
          data: [
            { value: m.breakfast, name: MEAL_TYPE[1].label, itemStyle: { color: MEAL_TYPE[1].color } },
            { value: m.lunch, name: MEAL_TYPE[2].label, itemStyle: { color: MEAL_TYPE[2].color } },
            { value: m.dinner, name: MEAL_TYPE[3].label, itemStyle: { color: MEAL_TYPE[3].color } },
          ],
        },
      ],
    })
  }

  if (lineChartRef.value) {
    lineChart = echarts.init(lineChartRef.value)
    lineChart.setOption({
      tooltip: { trigger: 'axis' },
      grid: { left: 40, right: 20, top: 20, bottom: 30 },
      xAxis: {
        type: 'category',
        data: trendDays.value,
        boundaryGap: false,
        axisLine: { lineStyle: { color: borderColor } },
        axisLabel: { color: textColor, fontSize: 12 },
      },
      yAxis: {
        type: 'value',
        minInterval: 1,
        axisLine: { show: false },
        axisTick: { show: false },
        axisLabel: { color: textColor, fontSize: 12 },
        splitLine: { lineStyle: { color: borderColor } },
      },
      series: [
        {
          type: 'line',
          data: trendCounts.value,
          smooth: true,
          symbol: 'circle',
          symbolSize: 8,
          lineStyle: { width: 3, color: '#0065fd' },
          itemStyle: { color: '#0065fd' },
          areaStyle: {
            color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
              { offset: 0, color: 'rgba(0,101,253,0.25)' },
              { offset: 1, color: 'rgba(0,101,253,0)' },
            ]),
          },
        },
      ],
    })
  }
}

const handleResize = () => {
  pieChart?.resize()
  lineChart?.resize()
}

onMounted(async () => {
  await fetchDashboard()
  initCharts()
  window.addEventListener('resize', handleResize)
})

// 超管切换食堂后自动刷新数据
watch(() => authStore.storeId, async () => {
  await fetchDashboard()
  initCharts()
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  pieChart?.dispose()
  lineChart?.dispose()
  pieChart = null
  lineChart = null
})

const quickActions = [
  { label: '新增菜品', desc: '管理菜品库', icon: ChefHat, to: '/dish', color: 'from-primary-500 to-primary-600' },
  { label: '订单管理', desc: '处理订单', icon: ClipboardList, to: '/order', color: 'from-emerald-500 to-emerald-600' },
  { label: '充值管理', desc: '员工充值', icon: CreditCard, to: '/recharge', color: 'from-amber-500 to-amber-600' },
  { label: '查看报表', desc: '数据分析', icon: BarChart3, to: '/report', color: 'from-violet-500 to-violet-600' },
]
</script>

<template>
  <Layout>
    <PageContainer title="数据概览" description="实时掌握食堂运营核心指标">
      <!-- 超管未选择食堂提示 -->
      <div
        v-if="noStoreSelected"
        class="mb-4 rounded-lg border border-amber-300 bg-amber-50 px-4 py-3 text-sm text-amber-700"
      >
        请先选择食堂后再查看数据。
      </div>
      <!-- 统计卡片 -->
      <div class="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard
          title="今日订单"
          :value="stats.todayOrders"
          :icon="ShoppingBag"
          color="primary"
        />
        <StatCard
          title="今日营业额"
          :value="todayRevenueText"
          :icon="Wallet"
          color="success"
        />
        <StatCard
          title="已完成订单"
          :value="stats.completedOrders"
          :icon="CheckCircle2"
          color="accent"
          :trend="`完成率 ${stats.completionRate}%`"
        />
        <StatCard
          title="待取餐"
          :value="stats.pendingOrders"
          :icon="Clock"
          color="warning"
        />
      </div>

      <!-- 图表区 -->
      <div class="mt-6 grid grid-cols-1 gap-6 lg:grid-cols-2">
        <div class="card p-5">
          <h3 class="mb-2 text-base font-semibold text-text">餐次订单占比</h3>
          <div ref="pieChartRef" class="h-72 w-full" role="img" aria-label="餐次订单占比饼图" />
        </div>
        <div class="card p-5">
          <h3 class="mb-2 text-base font-semibold text-text">近 7 天订单趋势</h3>
          <div ref="lineChartRef" class="h-72 w-full" role="img" aria-label="近 7 天订单趋势折线图" />
        </div>
      </div>

      <!-- 最近订单 + 快捷操作 -->
      <div class="mt-6 grid grid-cols-1 gap-6 lg:grid-cols-3">
        <!-- 最近订单 -->
        <div class="card overflow-hidden lg:col-span-2">
          <div class="flex items-center justify-between border-b border-border px-5 py-4">
            <h3 class="text-base font-semibold text-text">最近订单</h3>
            <RouterLink
              to="/order"
              class="flex items-center gap-1 text-sm text-primary transition-opacity hover:opacity-80"
            >
              查看全部 <ArrowRight class="h-3.5 w-3.5" />
            </RouterLink>
          </div>
          <ElTable :data="recentOrders" style="width: 100%" :show-overflow-tooltip="true" aria-label="最近订单列表">
            <ElTableColumn prop="orderNo" label="订单号" min-width="150" />
            <ElTableColumn label="员工" min-width="100">
              <template #default="{ row }">
                {{ row.employeeName || `#${row.employeeId}` }}
              </template>
            </ElTableColumn>
            <ElTableColumn label="金额" width="100" align="right">
              <template #default="{ row }">
                <span class="font-medium tabular-nums text-text">¥{{ row.totalAmount }}</span>
              </template>
            </ElTableColumn>
            <ElTableColumn label="状态" width="100" align="center">
              <template #default="{ row }">
                <StatusTag :value="row.status" :map="ORDER_STATUS" />
              </template>
            </ElTableColumn>
            <ElTableColumn prop="createdAt" label="时间" width="160" />
            <template #empty>
              <EmptyState description="暂无订单数据" />
            </template>
          </ElTable>
        </div>

        <!-- 快捷操作 -->
        <div class="card p-5">
          <h3 class="mb-4 text-base font-semibold text-text">快捷操作</h3>
          <div class="grid grid-cols-2 gap-3">
            <RouterLink
              v-for="action in quickActions"
              :key="action.to"
              :to="action.to"
              class="group flex flex-col gap-3 rounded-xl border border-border bg-bg-secondary p-4 transition-all hover:-translate-y-0.5 hover:border-primary/40 hover:shadow-md"
            >
              <div
                class="flex h-10 w-10 items-center justify-center rounded-lg bg-gradient-to-br text-white"
                :class="action.color"
              >
                <component :is="action.icon" class="h-5 w-5" />
              </div>
              <div>
                <div class="text-sm font-medium text-text">{{ action.label }}</div>
                <div class="mt-0.5 text-xs text-text-muted">{{ action.desc }}</div>
              </div>
            </RouterLink>
          </div>
        </div>
      </div>
    </PageContainer>
  </Layout>
</template>
