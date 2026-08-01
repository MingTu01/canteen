<script setup lang="ts">
/**
 * 营业报表 Tab(Report 子组件)
 *
 * 职责:按日/周/月查询营业数据,展示趋势图/餐次占比/热销 TOP5
 * 数据来源:reportApi.daily / weekly / monthly
 * 依赖:storeId(来自父组件)
 *
 * 内置:3 个 echarts 图表 + Excel 导出
 */
import { ref, computed, watch, nextTick, onMounted } from 'vue'
import {
  ElRadioGroup,
  ElRadioButton,
  ElDatePicker,
  ElButton,
  ElTable,
  ElTableColumn,
  ElMessage,
} from 'element-plus'
import { Download, RefreshCw } from 'lucide-vue-next'
import * as XLSX from 'xlsx'
import StatCard from '@/components/StatCard.vue'
import EmptyState from '@/components/EmptyState.vue'
import {
  ShoppingCart,
  DollarSign,
  TrendingUp,
  CheckCircle,
} from 'lucide-vue-next'
import { useEcharts } from '@/composables/useEcharts'
import { todayStr, monthStr } from '@/utils/date'
import { money } from '@/utils/money'
import { reportApi } from '@/api'
import type { ReportData } from '@/api'

const props = defineProps<{ storeId: number | null }>()

type ReportType = 'daily' | 'weekly' | 'monthly'
const reportType = ref<ReportType>('daily')

const dateValue = ref<string>(todayStr())
const datePickerType = computed(() => (reportType.value === 'monthly' ? 'month' : 'date'))
const dateValueFormat = computed(() => (reportType.value === 'monthly' ? 'YYYY-MM' : 'YYYY-MM-DD'))
const datePlaceholder = computed(() =>
  reportType.value === 'monthly' ? '选择月份' : reportType.value === 'weekly' ? '选择起始日期' : '选择日期'
)

const onTypeChange = (): void => {
  dateValue.value = reportType.value === 'monthly' ? monthStr() : todayStr()
  fetchReport()
}

/* ============ 数据解析(后端 ReportData 为动态结构,做防御性解析) ============ */
interface TopDish {
  name: string
  count: number
  revenue?: number
}
interface TrendPoint {
  date: string
  revenue: number
}

const num = (v: unknown): number => {
  const n = Number(v ?? 0)
  return Number.isFinite(n) ? n : 0
}

const asTopDishes = (raw: unknown): TopDish[] => {
  if (!Array.isArray(raw)) return []
  return raw.slice(0, 5).map((item) => {
    const o = (item ?? {}) as Record<string, unknown>
    return {
      name: String(o.name ?? o.dishName ?? '未知'),
      count: num(o.count ?? o.quantity ?? o.sales ?? o.total),
      revenue: o.revenue != null ? num(o.revenue) : undefined,
    }
  })
}

const asTrend = (raw: unknown, fallbackLabel: string, fallbackRevenue: number): TrendPoint[] => {
  if (Array.isArray(raw) && raw.length) {
    return raw.map((item) => {
      const o = (item ?? {}) as Record<string, unknown>
      return {
        date: String(o.date ?? o.day ?? o.label ?? ''),
        revenue: num(o.revenue ?? o.totalRevenue ?? o.amount ?? o.total),
      }
    })
  }
  return [{ date: fallbackLabel, revenue: fallbackRevenue }]
}

const asMealStats = (raw: unknown): { breakfast: number; lunch: number; dinner: number } => {
  const result = { breakfast: 0, lunch: 0, dinner: 0 }
  if (Array.isArray(raw)) {
    raw.forEach((item) => {
      const o = (item ?? {}) as Record<string, unknown>
      const mt = num(o.mealType ?? o.type)
      const cnt = num(o.count ?? o.total ?? o.orders ?? o.amount ?? o.orderCount)
      if (mt === 1) result.breakfast = cnt
      else if (mt === 2) result.lunch = cnt
      else if (mt === 3) result.dinner = cnt
    })
  } else if (raw && typeof raw === 'object') {
    const o = raw as Record<string, unknown>
    // 后端格式: {count: {breakfast, lunch, dinner}, ratio: {...}}
    const countObj = (o.count ?? o) as Record<string, unknown>
    result.breakfast = num(countObj.breakfast ?? countObj.morning ?? o.breakfast)
    result.lunch = num(countObj.lunch ?? countObj.noon ?? o.lunch)
    result.dinner = num(countObj.dinner ?? countObj.evening ?? o.dinner)
  }
  return result
}

/* ============ 状态 ============ */
const loading = ref(false)
const summary = ref({ totalOrders: 0, totalRevenue: 0, avgOrderAmount: 0, completionRate: 0 })
const topDishes = ref<TopDish[]>([])
const trendPoints = ref<TrendPoint[]>([])
const mealStats = ref({ breakfast: 0, lunch: 0, dinner: 0 })

/* ============ 3 个 echarts 图表(独立 useEcharts 实例) ============ */
const { chartRef: trendRef, setOption: setTrendOption } = useEcharts()
const { chartRef: pieRef, setOption: setPieOption } = useEcharts()
const { chartRef: barRef, setOption: setBarOption } = useEcharts()

const renderCharts = (): void => {
  setTrendOption({
    tooltip: { trigger: 'axis' },
    grid: { left: 48, right: 24, top: 40, bottom: 36 },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: trendPoints.value.map((p) => p.date),
      axisLabel: { color: '#64748b' },
    },
    yAxis: {
      type: 'value',
      axisLabel: { color: '#64748b', formatter: '{value}' },
      splitLine: { lineStyle: { color: '#f1f5f9' } },
    },
    series: [
      {
        name: '营业额',
        type: 'line',
        smooth: true,
        symbol: 'circle',
        symbolSize: 8,
        itemStyle: { color: '#0065fd' },
        lineStyle: { width: 3, color: '#0065fd' },
        areaStyle: {
          color: {
            type: 'linear',
            x: 0,
            y: 0,
            x2: 0,
            y2: 1,
            colorStops: [
              { offset: 0, color: 'rgba(0,101,253,0.25)' },
              { offset: 1, color: 'rgba(0,101,253,0.02)' },
            ],
          },
        },
        data: trendPoints.value.map((p) => p.revenue),
      },
    ],
  })

  const pieData = [
    { name: '早餐', value: mealStats.value.breakfast },
    { name: '午餐', value: mealStats.value.lunch },
    { name: '晚餐', value: mealStats.value.dinner },
  ].filter((i) => i.value > 0)
  setPieOption({
    tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
    legend: { bottom: 0, textStyle: { color: '#64748b' } },
    series: [
      {
        type: 'pie',
        radius: ['42%', '68%'],
        center: ['50%', '46%'],
        avoidLabelOverlap: true,
        itemStyle: { borderRadius: 8, borderColor: '#fff', borderWidth: 2 },
        label: { show: true, formatter: '{b}\n{d}%', color: '#475569' },
        color: ['#f59e0b', '#10b981', '#6366f1'],
        data: pieData.length
          ? pieData
          : [
              { name: '早餐', value: 1 },
              { name: '午餐', value: 1 },
              { name: '晚餐', value: 1 },
            ],
      },
    ],
  })

  const dishes = topDishes.value
  setBarOption({
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    grid: { left: 48, right: 24, top: 40, bottom: 36 },
    xAxis: {
      type: 'category',
      data: dishes.map((d) => d.name),
      axisLabel: { color: '#64748b', interval: 0, rotate: dishes.length > 4 ? 20 : 0 },
    },
    yAxis: {
      type: 'value',
      axisLabel: { color: '#64748b' },
      splitLine: { lineStyle: { color: '#f1f5f9' } },
    },
    series: [
      {
        type: 'bar',
        barWidth: '46%',
        itemStyle: {
          borderRadius: [6, 6, 0, 0],
          color: {
            type: 'linear',
            x: 0,
            y: 0,
            x2: 0,
            y2: 1,
            colorStops: [
              { offset: 0, color: '#1a73fe' },
              { offset: 1, color: '#80b2fe' },
            ],
          },
        },
        data: dishes.map((d) => d.count),
      },
    ],
  })
}

/* ============ 拉取数据 ============ */
const fetchReport = async (): Promise<void> => {
  const storeId = props.storeId
  if (!storeId) {
    summary.value = { totalOrders: 0, totalRevenue: 0, avgOrderAmount: 0, completionRate: 0 }
    topDishes.value = []
    trendPoints.value = []
    mealStats.value = { breakfast: 0, lunch: 0, dinner: 0 }
    return
  }
  loading.value = true
  try {
    const params =
      reportType.value === 'daily'
        ? { storeId, date: dateValue.value }
        : reportType.value === 'weekly'
          ? { storeId, startDate: dateValue.value }
          : { storeId, month: dateValue.value }
    let data: ReportData
    if (reportType.value === 'daily') data = await reportApi.daily(params)
    else if (reportType.value === 'weekly') data = await reportApi.weekly(params)
    else data = await reportApi.monthly(params)

    const d = (data ?? {}) as Record<string, unknown>
    summary.value = {
      totalOrders: num(d.totalOrders ?? d.orderCount ?? d.orders),
      totalRevenue: num(d.totalRevenue ?? d.revenue ?? d.totalAmount),
      avgOrderAmount: num(d.avgOrderAmount ?? d.averageAmount ?? d.avgAmount),
      completionRate: num(d.completionRate ?? d.completedRate),
    }
    topDishes.value = asTopDishes(d.topDishes ?? d.hotDishes ?? d.dishes)
    mealStats.value = asMealStats(d.mealTypeStats ?? d.mealStats ?? d.mealDistribution)
    trendPoints.value = asTrend(
      d.trend ?? d.revenueTrend ?? d.dailyTrend,
      dateValue.value,
      summary.value.totalRevenue
    )
    await nextTick()
    renderCharts()
  } catch {
    /* 拦截器提示 */
  } finally {
    loading.value = false
  }
}

/* ============ 导出 Excel ============ */
const handleExport = (): void => {
  if (loading.value) return
  const wb = XLSX.utils.book_new()
  const typeLabel = reportType.value === 'daily' ? '日报' : reportType.value === 'weekly' ? '周报' : '月报'
  const summaryRows = [
    { 指标: '报表类型', 数值: typeLabel },
    { 指标: '统计区间', 数值: dateValue.value },
    { 指标: '订单总数', 数值: summary.value.totalOrders },
    { 指标: '总营业额', 数值: summary.value.totalRevenue },
    { 指标: '客单价', 数值: summary.value.avgOrderAmount },
    { 指标: '完成率(%)', 数值: summary.value.completionRate },
    { 指标: '早餐订单', 数值: mealStats.value.breakfast },
    { 指标: '午餐订单', 数值: mealStats.value.lunch },
    { 指标: '晚餐订单', 数值: mealStats.value.dinner },
  ]
  XLSX.utils.book_append_sheet(wb, XLSX.utils.json_to_sheet(summaryRows), '汇总')
  if (topDishes.value.length) {
    const dishRows = topDishes.value.map((d, i) => ({ 排名: i + 1, 菜品: d.name, 销量: d.count }))
    XLSX.utils.book_append_sheet(wb, XLSX.utils.json_to_sheet(dishRows), '热销TOP5')
  }
  if (trendPoints.value.length) {
    const trendRows = trendPoints.value.map((p) => ({ 日期: p.date, 营业额: p.revenue }))
    XLSX.utils.book_append_sheet(wb, XLSX.utils.json_to_sheet(trendRows), '营业额趋势')
  }
  XLSX.writeFile(wb, `报表_${typeLabel}_${dateValue.value}.xlsx`)
  ElMessage.success('导出成功')
}

// 门店切换时重新拉取(不使用 immediate,改用 onMounted 触发首次加载,确保 DOM 已就绪)
watch(() => props.storeId, fetchReport)

// 挂载后触发首次加载(DOM ref 已绑定,ECharts 可正常初始化)
onMounted(() => {
  fetchReport()
})

defineExpose({ refresh: fetchReport })
</script>

<template>
  <!-- 筛选区 + 操作按钮 -->
  <div class="mb-5 flex flex-wrap items-center gap-3 rounded-xl border border-border bg-card p-4 shadow-sm">
    <ElRadioGroup v-model="reportType" @change="onTypeChange">
      <ElRadioButton value="daily">日报</ElRadioButton>
      <ElRadioButton value="weekly">周报</ElRadioButton>
      <ElRadioButton value="monthly">月报</ElRadioButton>
    </ElRadioGroup>
    <ElDatePicker
      v-model="dateValue"
      :type="datePickerType"
      :value-format="dateValueFormat"
      :placeholder="datePlaceholder"
      :clearable="false"
      style="width: 180px"
      @change="fetchReport"
    />
    <div class="ml-auto flex items-center gap-2">
      <ElButton :icon="RefreshCw" @click="fetchReport">刷新</ElButton>
      <ElButton type="primary" :icon="Download" @click="handleExport">导出 Excel</ElButton>
    </div>
  </div>

  <!-- 报表内容区域(整体 loading 遮罩) -->
  <div v-loading="loading" element-loading-text="正在加载报表数据...">
    <!-- 统计卡 -->
    <div class="mb-5 grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
      <StatCard title="订单总数" :value="summary.totalOrders" :icon="ShoppingCart" color="primary" />
      <StatCard title="总营业额" :value="money(summary.totalRevenue)" :icon="DollarSign" color="success" />
      <StatCard title="客单价" :value="money(summary.avgOrderAmount)" :icon="TrendingUp" color="warning" />
      <StatCard title="完成率" :value="`${summary.completionRate}%`" :icon="CheckCircle" color="accent" />
    </div>

    <!-- 图表区 -->
    <div class="mb-5 grid grid-cols-1 gap-5 lg:grid-cols-2">
      <div class="rounded-2xl border border-border bg-card p-5 shadow-sm">
        <h3 class="mb-3 text-base font-semibold text-text">营业额趋势</h3>
        <div ref="trendRef" class="h-72 w-full"></div>
      </div>
      <div class="rounded-2xl border border-border bg-card p-5 shadow-sm">
        <h3 class="mb-3 text-base font-semibold text-text">餐次占比</h3>
        <div ref="pieRef" class="h-72 w-full"></div>
      </div>
    </div>
    <div class="mb-5 rounded-2xl border border-border bg-card p-5 shadow-sm">
      <h3 class="mb-3 text-base font-semibold text-text">热销菜品 TOP5</h3>
      <div ref="barRef" class="h-72 w-full"></div>
    </div>

    <!-- 数据表格 -->
    <div class="rounded-xl border border-border bg-card shadow-sm overflow-hidden">
      <div class="border-b border-border-light px-5 py-4">
        <h3 class="text-base font-semibold text-text">热销菜品明细</h3>
      </div>
      <ElTable :data="topDishes" style="width: 100%">
        <ElTableColumn label="排名" width="90" align="center">
          <template #default="{ $index }">
            <span
              class="inline-flex h-6 w-6 items-center justify-center rounded-full text-xs font-bold text-white"
              :style="{ background: $index < 3 ? 'var(--color-primary)' : 'var(--color-text-muted)' }"
            >{{ $index + 1 }}</span>
          </template>
        </ElTableColumn>
        <ElTableColumn label="菜品名称" min-width="200">
          <template #default="{ row }">
            <span class="font-medium text-text">{{ row.name }}</span>
          </template>
        </ElTableColumn>
        <ElTableColumn label="销量" width="140" align="right">
          <template #default="{ row }">
            <span class="tabular-nums font-semibold text-primary">{{ row.count }}</span>
          </template>
        </ElTableColumn>
        <ElTableColumn label="营业额" width="160" align="right">
          <template #default="{ row }">
            <span class="tabular-nums text-text-secondary">{{ row.revenue != null ? money(row.revenue) : '—' }}</span>
          </template>
        </ElTableColumn>
        <template #empty>
          <EmptyState description="暂无热销菜品数据" />
        </template>
      </ElTable>
    </div>
  </div>
</template>
