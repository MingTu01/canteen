<script setup lang="ts">
/**
 * 同比分析 Tab(Report 子组件)
 *
 * 职责:对比所选日期范围与去年同期的订单数、营业额、退款额
 * 数据来源:reportApi.yoy
 * 依赖:storeId(来自父组件)
 */
import { ref, watch, nextTick } from 'vue'
import { ElDatePicker, ElTable, ElTableColumn } from 'element-plus'
import EmptyState from '@/components/EmptyState.vue'
import { useEcharts } from '@/composables/useEcharts'
import { monthStartStr, todayStr } from '@/utils/date'
import { money } from '@/utils/money'
import { growthColor, growthText } from '@/utils/format'
import { reportApi } from '@/api'
import type { ComparisonReport } from '@/api'

const props = defineProps<{ storeId: number | null }>()

const yoyRange = ref<[string, string]>([monthStartStr(), todayStr()])
const loading = ref(false)
const data = ref<ComparisonReport | null>(null)

const { chartRef, setOption } = useEcharts()

const renderChart = (): void => {
  if (!data.value) return
  const cur = data.value.current
  const prev = data.value.previous
  setOption({
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    legend: { data: ['本期', '同期'], top: 0, textStyle: { color: '#64748b' } },
    grid: { left: 56, right: 24, top: 40, bottom: 36 },
    xAxis: {
      type: 'category',
      data: ['订单数', '营业额', '退款额'],
      axisLabel: { color: '#64748b' },
    },
    yAxis: { type: 'value', axisLabel: { color: '#64748b' }, splitLine: { lineStyle: { color: '#f1f5f9' } } },
    series: [
      {
        name: '本期',
        type: 'bar',
        barGap: 0,
        itemStyle: { borderRadius: [6, 6, 0, 0], color: '#0065fd' },
        data: [cur.orderCount, cur.revenue, cur.refund],
      },
      {
        name: '同期',
        type: 'bar',
        itemStyle: { borderRadius: [6, 6, 0, 0], color: '#94a3b8' },
        data: [prev.orderCount, prev.revenue, prev.refund],
      },
    ],
  })
}

const fetchYoy = async (): Promise<void> => {
  const storeId = props.storeId
  if (!storeId) {
    data.value = null
    return
  }
  const range = yoyRange.value
  if (!Array.isArray(range) || range.length < 2 || !range[0] || !range[1]) return
  loading.value = true
  try {
    data.value = await reportApi.yoy({ storeId, startDate: range[0], endDate: range[1] })
    await nextTick()
    renderChart()
  } catch {
    /* 拦截器提示 */
  } finally {
    loading.value = false
  }
}

watch(() => props.storeId, fetchYoy, { immediate: true })

defineExpose({ refresh: fetchYoy })

/** 明细表格数据(基于 current/previous/growth 派生) */
const tableRows = () => {
  if (!data.value) return []
  const d = data.value
  return [
    { name: '订单数', current: d.current.orderCount, previous: d.previous.orderCount, growth: d.growth.orderCountGrowth, unit: '' },
    { name: '营业额', current: d.current.revenue, previous: d.previous.revenue, growth: d.growth.revenueGrowth, unit: '¥' },
    { name: '退款额', current: d.current.refund, previous: d.previous.refund, growth: d.growth.refundGrowth, unit: '¥' },
  ]
}
</script>

<template>
  <!-- 筛选区 -->
  <div class="mb-5 flex flex-wrap items-center gap-3 rounded-xl border border-border bg-card p-4 shadow-sm">
    <ElDatePicker
      v-model="yoyRange"
      type="daterange"
      value-format="YYYY-MM-DD"
      range-separator="至"
      start-placeholder="开始日期"
      end-placeholder="结束日期"
      :clearable="false"
      style="width: 280px"
      @change="fetchYoy"
    />
    <span class="text-xs text-text-muted">对比所选日期范围与去年同期的订单数、营业额、退款额</span>
  </div>

  <div v-if="!data" class="rounded-xl border border-border bg-card p-8 shadow-sm">
    <EmptyState description="选择日期范围后点击刷新查看同比数据" />
  </div>
  <div v-else v-loading="loading" class="space-y-5">
    <!-- 增长率卡 -->
    <div class="grid grid-cols-1 gap-4 sm:grid-cols-3">
      <div class="rounded-2xl border border-border bg-card p-5 shadow-sm">
        <div class="mb-1 text-sm text-text-muted">订单数增长</div>
        <div class="text-2xl font-bold tabular-nums" :style="{ color: growthColor(data.growth.orderCountGrowth) }">
          {{ growthText(data.growth.orderCountGrowth) }}
        </div>
        <div class="mt-1 text-xs text-text-muted">
          本期 {{ data.current.orderCount }} / 同期 {{ data.previous.orderCount }}
        </div>
      </div>
      <div class="rounded-2xl border border-border bg-card p-5 shadow-sm">
        <div class="mb-1 text-sm text-text-muted">营业额增长</div>
        <div class="text-2xl font-bold tabular-nums" :style="{ color: growthColor(data.growth.revenueGrowth) }">
          {{ growthText(data.growth.revenueGrowth) }}
        </div>
        <div class="mt-1 text-xs text-text-muted">
          本期 {{ money(data.current.revenue) }} / 同期 {{ money(data.previous.revenue) }}
        </div>
      </div>
      <div class="rounded-2xl border border-border bg-card p-5 shadow-sm">
        <div class="mb-1 text-sm text-text-muted">退款额增长</div>
        <div class="text-2xl font-bold tabular-nums" :style="{ color: growthColor(data.growth.refundGrowth) }">
          {{ growthText(data.growth.refundGrowth) }}
        </div>
        <div class="mt-1 text-xs text-text-muted">
          本期 {{ money(data.current.refund) }} / 同期 {{ money(data.previous.refund) }}
        </div>
      </div>
    </div>

    <!-- 柱状图对比 -->
    <div class="rounded-2xl border border-border bg-card p-5 shadow-sm">
      <h3 class="mb-3 text-base font-semibold text-text">本期 vs 同期 对比</h3>
      <div ref="chartRef" class="h-72 w-full"></div>
    </div>

    <!-- 明细表格 -->
    <div class="rounded-xl border border-border bg-card shadow-sm overflow-hidden">
      <ElTable :data="tableRows()" style="width: 100%">
        <ElTableColumn label="指标" min-width="160">
          <template #default="{ row }">
            <span class="font-medium text-text">{{ row.name }}</span>
          </template>
        </ElTableColumn>
        <ElTableColumn label="本期" width="180" align="right">
          <template #default="{ row }">
            <span class="tabular-nums text-text">{{ row.unit === '¥' ? money(Number(row.current)) : row.current }}</span>
          </template>
        </ElTableColumn>
        <ElTableColumn label="去年同期" width="180" align="right">
          <template #default="{ row }">
            <span class="tabular-nums text-text-secondary">{{ row.unit === '¥' ? money(Number(row.previous)) : row.previous }}</span>
          </template>
        </ElTableColumn>
        <ElTableColumn label="增长率" width="160" align="right">
          <template #default="{ row }">
            <span class="tabular-nums font-semibold" :style="{ color: growthColor(row.growth) }">
              {{ growthText(row.growth) }}
            </span>
          </template>
        </ElTableColumn>
      </ElTable>
    </div>
  </div>
</template>
