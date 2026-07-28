<script setup lang="ts">
/**
 * 员工消费统计 Tab(Report 子组件)
 *
 * 职责:按日期范围查询每位员工的消费汇总
 * 数据来源:reportApi.employeeConsumption
 * 依赖:storeId(来自父组件)
 */
import { ref, watch } from 'vue'
import { ElDatePicker, ElTable, ElTableColumn } from 'element-plus'
import { Users } from 'lucide-vue-next'
import EmptyState from '@/components/EmptyState.vue'
import { monthStartStr, todayStr } from '@/utils/date'
import { money } from '@/utils/money'
import { reportApi } from '@/api'
import type { EmployeeConsumptionReport } from '@/api'

const props = defineProps<{ storeId: number | null }>()

const empRange = ref<[string, string]>([monthStartStr(), todayStr()])
const loading = ref(false)
const empData = ref<EmployeeConsumptionReport>({
  employees: [],
  totalConsumption: 0,
  totalOrders: 0,
})

const fetchEmployeeConsumption = async (): Promise<void> => {
  const storeId = props.storeId
  if (!storeId) {
    empData.value = { employees: [], totalConsumption: 0, totalOrders: 0 }
    return
  }
  const range = empRange.value
  if (!Array.isArray(range) || range.length < 2 || !range[0] || !range[1]) return
  loading.value = true
  try {
    empData.value = await reportApi.employeeConsumption({
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

watch(() => props.storeId, fetchEmployeeConsumption, { immediate: true })

defineExpose({ refresh: fetchEmployeeConsumption })
</script>

<template>
  <!-- 筛选区 -->
  <div class="mb-5 flex flex-wrap items-center gap-3 rounded-xl border border-border bg-card p-4 shadow-sm">
    <ElDatePicker
      v-model="empRange"
      type="daterange"
      value-format="YYYY-MM-DD"
      range-separator="至"
      start-placeholder="开始日期"
      end-placeholder="结束日期"
      :clearable="false"
      style="width: 280px"
      @change="fetchEmployeeConsumption"
    />
    <span class="text-xs text-text-muted">统计所选日期范围内每位员工的消费汇总</span>
  </div>

  <!-- 数据表格 -->
  <div v-loading="loading" class="rounded-xl border border-border bg-card shadow-sm overflow-hidden">
    <ElTable :data="empData.employees" style="width: 100%" :show-overflow-tooltip="true">
      <ElTableColumn label="员工姓名" min-width="160">
        <template #default="{ row }">
          <div class="flex items-center gap-2">
            <Users class="h-4 w-4 text-text-muted" />
            <span class="font-medium text-text">{{ row.employeeName }}</span>
          </div>
        </template>
      </ElTableColumn>
      <ElTableColumn prop="departmentName" label="部门" min-width="160" align="left">
        <template #default="{ row }">
          <span class="text-text-secondary">{{ row.departmentName || '—' }}</span>
        </template>
      </ElTableColumn>
      <ElTableColumn label="消费总额" width="180" align="right">
        <template #default="{ row }">
          <span class="tabular-nums font-semibold text-primary">{{ money(row.totalConsumption) }}</span>
        </template>
      </ElTableColumn>
      <ElTableColumn prop="orderCount" label="订单数" width="140" align="right">
        <template #default="{ row }">
          <span class="tabular-nums text-text">{{ row.orderCount }}</span>
        </template>
      </ElTableColumn>
      <template #empty>
        <EmptyState description="暂无员工消费数据" />
      </template>
    </ElTable>
    <!-- 总计行 -->
    <div class="flex flex-wrap items-center justify-end gap-6 border-t border-border-light bg-bg-secondary px-5 py-3">
      <span class="text-sm text-text-secondary">
        合计消费：<span class="font-semibold tabular-nums text-text">{{ money(empData.totalConsumption) }}</span>
      </span>
      <span class="text-sm text-text-secondary">
        合计订单：<span class="font-semibold tabular-nums text-text">{{ empData.totalOrders }}</span>
      </span>
    </div>
  </div>
</template>
