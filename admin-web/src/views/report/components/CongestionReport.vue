<script setup lang="ts">
/**
 * 拥堵分析 Tab(Report 子组件)
 *
 * 职责:单日时段分布(daily)或高峰时段分析(range)
 * 数据来源:reportApi.hourly / reportApi.peak
 * 依赖:storeId(来自父组件)
 */
import { ref, watch, nextTick } from 'vue'
import { ElDatePicker, ElRadioGroup, ElRadioButton, ElTable, ElTableColumn } from 'element-plus'
import EmptyState from '@/components/EmptyState.vue'
import { useEcharts } from '@/composables/useEcharts'
import { todayStr, monthStartStr } from '@/utils/date'
import { fmtHour } from '@/utils/format'
import { reportApi } from '@/api'
import type { HourlyDistributionReport, PeakHoursReport } from '@/api'

const props = defineProps<{ storeId: number | null }>()

type CongestionMode = 'daily' | 'range'
const mode = ref<CongestionMode>('daily')
const date = ref<string>(todayStr())
const range = ref<[string, string]>([monthStartStr(), todayStr()])
const loading = ref(false)
const hourlyData = ref<HourlyDistributionReport | null>(null)
const peakData = ref<PeakHoursReport | null>(null)

const { chartRef, setOption } = useEcharts()

const renderChart = (): void => {
  if (mode.value === 'daily' && hourlyData.value) {
    const data = hourlyData.value.hourly
    const peakHour = hourlyData.value.peakHour
    // 补全 0-23 小时
    const hourMap = new Map<number, number>()
    for (let h = 0; h < 24; h++) hourMap.set(h, 0)
    data.forEach((d: any) => { if (d.hour >= 0 && d.hour < 24) hourMap.set(d.hour, d.count) })
    const hours = Array.from({ length: 24 }, (_, i) => i)
    const counts = hours.map((h) => hourMap.get(h) || 0)
    setOption({
      tooltip: {
        trigger: 'axis',
        axisPointer: { type: 'shadow' },
        formatter: (params: unknown) => {
          const arr = Array.isArray(params) ? params : [params]
          const p = arr[0] as { name: string; value: number }
          return `${p.name}<br/>订单数: <b>${p.value}</b>`
        },
      },
      grid: { left: 48, right: 24, top: 40, bottom: 36 },
      xAxis: {
        type: 'category',
        data: hours.map((h) => fmtHour(h)),
        axisLabel: { color: '#64748b', interval: 1 },
      },
      yAxis: {
        type: 'value',
        axisLabel: { color: '#64748b' },
        splitLine: { lineStyle: { color: '#f1f5f9' } },
      },
      series: [
        {
          type: 'bar',
          barWidth: '60%',
          itemStyle: {
            borderRadius: [4, 4, 0, 0],
            color: (params: { dataIndex: number }) => {
              const h = hours[params.dataIndex]
              return h === peakHour && peakHour >= 0 ? '#ef4444' : '#0065fd'
            },
          },
          data: counts,
        },
      ],
    })
  } else if (mode.value === 'range' && peakData.value) {
    const data = peakData.value.hours
    const threshold = peakData.value.threshold
    setOption({
      tooltip: {
        trigger: 'axis',
        axisPointer: { type: 'shadow' },
        formatter: (params: unknown) => {
          const arr = Array.isArray(params) ? params : [params]
          const p = arr[0] as { dataIndex: number; value: number }
          const h = data[p.dataIndex]
          return `${fmtHour(h.hour)}<br/>总订单: <b>${h.totalOrders}</b><br/>日均: <b>${h.avgOrders}</b>${h.isPeak ? '<br/><span style="color:#ef4444">高峰时段</span>' : ''}`
        },
      },
      grid: { left: 48, right: 24, top: 40, bottom: 36 },
      xAxis: {
        type: 'category',
        data: data.map((d) => fmtHour(d.hour)),
        axisLabel: { color: '#64748b', interval: 1 },
      },
      yAxis: {
        type: 'value',
        axisLabel: { color: '#64748b' },
        splitLine: { lineStyle: { color: '#f1f5f9' } },
      },
      series: [
        {
          type: 'bar',
          barWidth: '60%',
          itemStyle: {
            borderRadius: [4, 4, 0, 0],
            color: (params: { dataIndex: number }) =>
              data[params.dataIndex]?.isPeak ? '#ef4444' : '#0065fd',
          },
          markLine: {
            silent: true,
            symbol: 'none',
            lineStyle: { color: '#f59e0b', type: 'dashed' },
            data: [{ yAxis: threshold, name: '高峰阈值' }],
            label: { formatter: '阈值: {c}', color: '#f59e0b' },
          },
          data: data.map((d) => d.totalOrders),
        },
      ],
    })
  }
}

const fetchCongestion = async (): Promise<void> => {
  const storeId = props.storeId
  if (!storeId) {
    hourlyData.value = null
    peakData.value = null
    return
  }
  loading.value = true
  try {
    if (mode.value === 'daily') {
      if (!date.value) return
      hourlyData.value = await reportApi.hourly({ storeId, date: date.value })
      peakData.value = null
    } else {
      const r = range.value
      if (!Array.isArray(r) || r.length < 2 || !r[0] || !r[1]) return
      peakData.value = await reportApi.peak({ storeId, startDate: r[0], endDate: r[1] })
      hourlyData.value = null
    }
    await nextTick()
    renderChart()
  } catch {
    /* 拦截器提示 */
  } finally {
    loading.value = false
  }
}

const onModeChange = (): void => {
  hourlyData.value = null
  peakData.value = null
  fetchCongestion()
}

watch(() => props.storeId, fetchCongestion, { immediate: true })

defineExpose({ refresh: fetchCongestion })
</script>

<template>
  <!-- 筛选区 -->
  <div class="mb-5 flex flex-wrap items-center gap-3 rounded-xl border border-border bg-card p-4 shadow-sm">
    <ElRadioGroup v-model="mode" @change="onModeChange">
      <ElRadioButton value="daily">单日时段分布</ElRadioButton>
      <ElRadioButton value="range">高峰时段分析</ElRadioButton>
    </ElRadioGroup>
    <ElDatePicker
      v-if="mode === 'daily'"
      v-model="date"
      type="date"
      value-format="YYYY-MM-DD"
      placeholder="选择日期"
      :clearable="false"
      style="width: 180px"
      @change="fetchCongestion"
    />
    <ElDatePicker
      v-else
      v-model="range"
      type="daterange"
      value-format="YYYY-MM-DD"
      range-separator="至"
      start-placeholder="开始日期"
      end-placeholder="结束日期"
      :clearable="false"
      style="width: 280px"
      @change="fetchCongestion"
    />
  </div>

  <div v-if="!hourlyData && !peakData" class="rounded-xl border border-border bg-card p-8 shadow-sm">
    <EmptyState description="选择日期后点击刷新查看拥堵分析" />
  </div>
  <div v-else v-loading="loading" class="space-y-5">
    <!-- 单日时段分布 -->
    <template v-if="mode === 'daily' && hourlyData">
      <!-- 顶部统计卡 -->
      <div class="grid grid-cols-1 gap-4 sm:grid-cols-3">
        <div class="rounded-2xl border border-border bg-card p-5 shadow-sm">
          <div class="mb-1 text-sm text-text-muted">当日订单总数</div>
          <div class="text-2xl font-bold tabular-nums text-text">{{ hourlyData.totalOrders }}</div>
        </div>
        <div class="rounded-2xl border border-border bg-card p-5 shadow-sm">
          <div class="mb-1 text-sm text-text-muted">峰值时段</div>
          <div class="text-2xl font-bold tabular-nums" :class="hourlyData.peakHour >= 0 ? 'text-red-500' : 'text-text-muted'">
            {{ hourlyData.peakHour >= 0 ? fmtHour(hourlyData.peakHour) : '—' }}
          </div>
        </div>
        <div class="rounded-2xl border border-border bg-card p-5 shadow-sm">
          <div class="mb-1 text-sm text-text-muted">峰值订单数</div>
          <div class="text-2xl font-bold tabular-nums text-text">{{ hourlyData.peakCount }}</div>
        </div>
      </div>
      <!-- 时段分布柱状图 -->
      <div class="rounded-2xl border border-border bg-card p-5 shadow-sm">
        <h3 class="mb-3 text-base font-semibold text-text">当日各时段订单分布(红色为峰值)</h3>
        <div ref="chartRef" class="h-72 w-full"></div>
      </div>
      <!-- 时段明细表 -->
      <div class="rounded-xl border border-border bg-card shadow-sm overflow-hidden">
        <ElTable :data="hourlyData.hourly.filter((h) => h.count > 0)" style="width: 100%">
          <ElTableColumn label="时段" min-width="120">
            <template #default="{ row }">
              <span class="font-medium text-text">{{ fmtHour(row.hour) }}</span>
              <span v-if="row.hour === hourlyData.peakHour && hourlyData.peakHour >= 0" class="ml-2 inline-flex items-center rounded bg-red-100 px-1.5 py-0.5 text-xs font-semibold text-red-600">峰值</span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="订单数" width="180" align="right">
            <template #default="{ row }">
              <span class="tabular-nums font-semibold" :class="row.hour === hourlyData.peakHour && hourlyData.peakHour >= 0 ? 'text-red-500' : 'text-primary'">{{ row.count }}</span>
            </template>
          </ElTableColumn>
          <template #empty>
            <EmptyState description="当日暂无订单数据" />
          </template>
        </ElTable>
      </div>
    </template>

    <!-- 高峰时段分析 -->
    <template v-if="mode === 'range' && peakData">
      <!-- 顶部统计卡 -->
      <div class="grid grid-cols-1 gap-4 sm:grid-cols-4">
        <div class="rounded-2xl border border-border bg-card p-5 shadow-sm">
          <div class="mb-1 text-sm text-text-muted">统计天数</div>
          <div class="text-2xl font-bold tabular-nums text-text">{{ peakData.days }}</div>
        </div>
        <div class="rounded-2xl border border-border bg-card p-5 shadow-sm">
          <div class="mb-1 text-sm text-text-muted">总订单数</div>
          <div class="text-2xl font-bold tabular-nums text-text">{{ peakData.totalOrders }}</div>
        </div>
        <div class="rounded-2xl border border-border bg-card p-5 shadow-sm">
          <div class="mb-1 text-sm text-text-muted">高峰阈值(1.5×均值)</div>
          <div class="text-2xl font-bold tabular-nums text-amber-500">{{ peakData.threshold }}</div>
        </div>
        <div class="rounded-2xl border border-border bg-card p-5 shadow-sm">
          <div class="mb-1 text-sm text-text-muted">高峰时段数</div>
          <div class="text-2xl font-bold tabular-nums text-red-500">{{ peakData.peakHours.length }}</div>
        </div>
      </div>
      <!-- 高峰时段柱状图 -->
      <div class="rounded-2xl border border-border bg-card p-5 shadow-sm">
        <h3 class="mb-3 text-base font-semibold text-text">各时段订单汇总(红色为高峰,虚线为阈值)</h3>
        <div ref="chartRef" class="h-72 w-full"></div>
      </div>
      <!-- 高峰时段列表 -->
      <div class="rounded-xl border border-border bg-card shadow-sm overflow-hidden">
        <div class="border-b border-border-light px-5 py-4">
          <h3 class="text-base font-semibold text-text">高峰时段列表</h3>
        </div>
        <ElTable :data="peakData.peakHours" style="width: 100%">
          <ElTableColumn label="时段" min-width="120">
            <template #default="{ row }">
              <span class="font-medium text-text">{{ fmtHour(row.hour) }}</span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="总订单数" width="160" align="right">
            <template #default="{ row }">
              <span class="tabular-nums font-semibold text-red-500">{{ row.totalOrders }}</span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="日均订单数" width="160" align="right">
            <template #default="{ row }">
              <span class="tabular-nums text-text">{{ row.avgOrders }}</span>
            </template>
          </ElTableColumn>
          <template #empty>
            <EmptyState description="所选时间段内无明显高峰时段" />
          </template>
        </ElTable>
      </div>
    </template>
  </div>
</template>
