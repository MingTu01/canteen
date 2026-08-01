<script setup lang="ts">
/**
 * 财务对账 Tab(Report 子组件)
 *
 * 职责:按日期范围查询充值/消费/退款/余额/净流水
 * 数据来源:reportApi.finance
 * 依赖:storeId(来自父组件)
 */
import { ref, watch, onMounted } from 'vue'
import { ElDatePicker } from 'element-plus'
import { Wallet, DollarSign, RotateCcw, ArrowDownUp } from 'lucide-vue-next'
import StatCard from '@/components/StatCard.vue'
import { monthStartStr, todayStr } from '@/utils/date'
import { money } from '@/utils/money'
import { reportApi } from '@/api'
import type { FinanceReport } from '@/api'

const props = defineProps<{ storeId: number | null }>()

const financeRange = ref<[string, string]>([monthStartStr(), todayStr()])
const loading = ref(false)
const finance = ref<FinanceReport>({
  totalRecharge: 0,
  totalConsumption: 0,
  totalRefund: 0,
  currentBalance: 0,
  netFlow: 0,
})

const fetchFinance = async (): Promise<void> => {
  const storeId = props.storeId
  if (!storeId) {
    finance.value = { totalRecharge: 0, totalConsumption: 0, totalRefund: 0, currentBalance: 0, netFlow: 0 }
    return
  }
  const range = financeRange.value
  if (!Array.isArray(range) || range.length < 2 || !range[0] || !range[1]) return
  loading.value = true
  try {
    finance.value = await reportApi.finance({
      storeId,
      startDate: range[0],
      endDate: range[1],
    })
  } catch {
    /* 拦截器提示 */
  } finally {
    loading.value = false
  }
}

/* storeId 变化或挂载时自动 fetch */
watch(() => props.storeId, fetchFinance)

onMounted(() => {
  fetchFinance()
})

/* 暴露刷新方法供父组件 "刷新" 按钮调用 */
defineExpose({ refresh: fetchFinance })
</script>

<template>
  <!-- 筛选区 -->
  <div class="mb-5 flex flex-wrap items-center gap-3 rounded-xl border border-border bg-card p-4 shadow-sm">
    <ElDatePicker
      v-model="financeRange"
      type="daterange"
      value-format="YYYY-MM-DD"
      range-separator="至"
      start-placeholder="开始日期"
      end-placeholder="结束日期"
      :clearable="false"
      style="width: 280px"
      @change="fetchFinance"
    />
    <span class="text-xs text-text-muted">统计所选日期范围内的充值、消费、退款流水</span>
  </div>

  <!-- 统计卡 -->
  <div v-loading="loading" class="mb-5 grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-5">
    <StatCard title="充值总额" :value="money(finance.totalRecharge)" :icon="Wallet" color="success" />
    <StatCard title="消费总额" :value="money(finance.totalConsumption)" :icon="DollarSign" color="primary" />
    <StatCard title="退款总额" :value="money(finance.totalRefund)" :icon="RotateCcw" color="danger" />
    <StatCard title="当前余额" :value="money(finance.currentBalance)" :icon="Wallet" color="warning" />
    <StatCard title="净流水" :value="money(finance.netFlow)" :icon="ArrowDownUp" color="accent" />
  </div>

  <!-- 说明 -->
  <div class="rounded-xl border border-border bg-card p-5 shadow-sm">
    <h3 class="mb-2 text-base font-semibold text-text">对账说明</h3>
    <ul class="list-disc space-y-1 pl-5 text-sm text-text-secondary">
      <li>充值总额:统计区间内所有员工充值金额合计。</li>
      <li>消费总额:统计区间内所有已支付订单金额合计。</li>
      <li>退款总额:统计区间内所有退款金额合计。</li>
      <li>当前余额:本食堂所有员工账户余额合计(实时)。</li>
      <li>净流水 = 充值总额 − 消费总额,用于核对资金收支平衡。</li>
    </ul>
  </div>
</template>
