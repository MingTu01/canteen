<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue'
import {
  ElButton,
  ElDatePicker,
  ElSelect,
  ElOption,
  ElTable,
  ElTableColumn,
  ElPagination,
  ElTag,
  ElMessage,
  ElMessageBox,
} from 'element-plus'
import {
  RefreshCw,
  FileCheck2,
  CheckCircle2,
  Lock,
  ShoppingCart,
  DollarSign,
  RotateCcw,
  Wallet,
  TrendingUp,
  Utensils,
  XCircle,
  CalendarCheck,
} from 'lucide-vue-next'
import Layout from '@/components/Layout.vue'
import PageContainer from '@/components/PageContainer.vue'
import StatCard from '@/components/StatCard.vue'
import EmptyState from '@/components/EmptyState.vue'
import { useAuthStore } from '@/stores/auth'
import { settlementApi } from '@/api'
import type { DailySettlement, DailySettlementStatus } from '@/api'
import { useStoreSwitch } from '@/composables/useStoreSwitch'
import { todayStr } from '@/utils/date'
import { money } from '@/utils/money'
import { normalizeList } from '@/utils/list'

const authStore = useAuthStore()

/* 门店选择(超管)— 复用 composable */
const { isSuperAdmin, stores, selectedStoreId, activeStoreId, fetchStores } = useStoreSwitch()

/* 日期选择 */
const dateValue = ref<string>(todayStr())

/* 状态与数据 */
const loading = ref(false)
const generating = ref(false)
const confirming = ref(false)
const closing = ref(false)
const settlement = ref<DailySettlement | null>(null)
const statusInfo = ref<DailySettlementStatus | null>(null)

/** 状态文本:未对账(null) / 待对账(1) / 已对账(2) / 已关店(3) */
const statusText = computed(() => {
  const s = settlement.value?.status
  if (s === 1) return '待对账'
  if (s === 2) return '已对账'
  if (s === 3) return '已关店'
  return '未对账'
})

const statusTagType = computed<'info' | 'warning' | 'success' | 'danger'>(() => {
  const s = settlement.value?.status
  if (s === 1) return 'warning'
  if (s === 2) return 'success'
  if (s === 3) return 'info'
  return 'info'
})

/** 拉取指定日期对账数据 + 今日状态 */
const fetchData = async () => {
  const storeId = activeStoreId.value
  if (!storeId) {
    settlement.value = null
    statusInfo.value = null
    return
  }
  loading.value = true
  try {
    const [data, status] = await Promise.all([
      settlementApi.get({ storeId, date: dateValue.value }),
      settlementApi.today({ storeId, date: dateValue.value }),
    ])
    settlement.value = data
    statusInfo.value = status
  } catch {
    settlement.value = null
    statusInfo.value = null
  } finally {
    loading.value = false
  }
}

/* 生成/刷新对账数据 */
const handleGenerate = async () => {
  const storeId = activeStoreId.value
  if (!storeId) {
    ElMessage.warning('请先选择食堂')
    return
  }
  generating.value = true
  try {
    await settlementApi.generate({ storeId, date: dateValue.value })
    ElMessage.success('对账数据已生成')
    await fetchData()
  } catch {
    /* 拦截器提示 */
  } finally {
    generating.value = false
  }
}

/* 确认对账 */
const handleConfirm = async () => {
  const id = settlement.value?.id
  if (!id) {
    ElMessage.warning('请先生成对账数据')
    return
  }
  try {
    await ElMessageBox.confirm(
      `确认对 ${dateValue.value} 的对账数据进行确认?确认后将进入"已对账"状态。`,
      '确认对账',
      {
        confirmButtonText: '确认对账',
        cancelButtonText: '取消',
        type: 'warning',
      }
    )
  } catch {
    return
  }
  confirming.value = true
  try {
    await settlementApi.confirm(id)
    ElMessage.success('对账已确认')
    await fetchData()
  } catch {
    /* 拦截器提示 */
  } finally {
    confirming.value = false
  }
}

/* 关店(二次确认) */
const handleClose = async () => {
  const id = settlement.value?.id
  if (!id) {
    ElMessage.warning('请先确认对账')
    return
  }
  try {
    await ElMessageBox.confirm(
      `确认对 ${dateValue.value} 执行关店操作?关店后将锁定当日对账数据,无法再修改。`,
      '关店确认',
      {
        confirmButtonText: '确认关店',
        cancelButtonText: '取消',
        type: 'warning',
      }
    )
  } catch {
    return
  }
  closing.value = true
  try {
    await settlementApi.close(id)
    ElMessage.success('关店成功')
    await fetchData()
    await fetchHistory()
  } catch {
    /* 拦截器提示 */
  } finally {
    closing.value = false
  }
}

/* 按钮可用状态 */
const canGenerate = computed(() => {
  const s = settlement.value?.status
  // 只在未对账(null)或待对账(1)时允许生成,已对账(2)/已关店(3)不可生成
  return s === undefined || s === null || s === 1
})
const canConfirm = computed(() => settlement.value?.status === 1)
const canClose = computed(() => settlement.value?.status === 2)

/* ============================================================
 * 历史列表(最近30天)
 * ============================================================ */
const historyList = ref<DailySettlement[]>([])
const historyTotal = ref(0)
const historyLoading = ref(false)
const historyPage = ref(1)
const historySize = ref(10)

const defaultRange = () => {
  const end = new Date()
  const start = new Date()
  start.setDate(start.getDate() - 29)
  const fmt = (d: Date) =>
    `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
  return { start: fmt(start), end: fmt(end) }
}

const fetchHistory = async () => {
  const storeId = activeStoreId.value
  if (!storeId) {
    historyList.value = []
    historyTotal.value = 0
    return
  }
  historyLoading.value = true
  const { start, end } = defaultRange()
  try {
    const res = await settlementApi.list({
      storeId,
      startDate: start,
      endDate: end,
      page: historyPage.value,
      size: historySize.value,
    })
    historyList.value = normalizeList<DailySettlement>(res?.records)
    historyTotal.value = Number(res?.total ?? 0)
  } catch {
    historyList.value = []
    historyTotal.value = 0
  } finally {
    historyLoading.value = false
  }
}

const onHistoryPageChange = (p: number) => {
  historyPage.value = p
  fetchHistory()
}
const onHistorySizeChange = (s: number) => {
  historySize.value = s
  historyPage.value = 1
  fetchHistory()
}

/** 点击历史记录查看详情:切换日期并拉取对账数据 */
const handleViewDetail = (row: DailySettlement) => {
  if (row.settleDate) {
    dateValue.value = row.settleDate
    fetchData()
  }
}

const onStoreChange = () => {
  historyPage.value = 1
  fetchData()
  fetchHistory()
}

onMounted(async () => {
  await fetchStores()
  await fetchData()
  await fetchHistory()
})

watch(
  () => authStore.storeId,
  () => {
    if (!isSuperAdmin.value) {
      selectedStoreId.value = authStore.storeId || undefined
      onStoreChange()
    }
  }
)
</script>

<template>
  <Layout>
    <PageContainer
      title="日终对账/关店"
      description="关店前核对当日订单、营业额、退款、充值与消费,确认对账后执行关店流程。"
    >
      <template #actions>
        <ElButton :icon="RefreshCw" :loading="loading" @click="fetchData">刷新</ElButton>
        <ElButton
          type="primary"
          :icon="FileCheck2"
          :loading="generating"
          :disabled="!activeStoreId || !canGenerate"
          @click="handleGenerate"
        >
          生成对账
        </ElButton>
        <ElButton
          type="success"
          :icon="CheckCircle2"
          :loading="confirming"
          :disabled="!canConfirm"
          @click="handleConfirm"
        >
          确认对账
        </ElButton>
        <ElButton
          type="danger"
          :icon="Lock"
          :loading="closing"
          :disabled="!canClose"
          @click="handleClose"
        >
          关店
        </ElButton>
      </template>

      <div
        v-if="!activeStoreId"
        class="mb-4 rounded-lg border border-amber-300 bg-amber-50 px-4 py-3 text-sm text-amber-700"
      >
        请先选择食堂后再查看数据。
      </div>

      <!-- 门店选择(超管) -->
      <div
        v-if="isSuperAdmin"
        class="mb-5 flex flex-wrap items-center gap-3 rounded-xl border border-border bg-card p-4 shadow-sm"
      >
        <span class="text-sm text-text-muted">选择门店</span>
        <ElSelect
          v-model="selectedStoreId"
          placeholder="选择门店"
          style="width: 220px"
          @change="onStoreChange"
        >
          <ElOption
            v-for="s in stores"
            :key="s.id"
            :label="s.name"
            :value="s.id as number"
          />
        </ElSelect>
      </div>

      <!-- 顶部状态卡片:对账状态 + 日期选择 -->
      <div
        class="mb-5 flex flex-col gap-4 rounded-xl border border-border bg-card p-5 shadow-sm sm:flex-row sm:items-center sm:justify-between"
      >
        <div class="flex items-center gap-4">
          <div
            class="flex h-14 w-14 shrink-0 items-center justify-center rounded-2xl"
            :class="
              settlement?.status === 3
                ? 'bg-bg-tertiary'
                : settlement?.status === 2
                  ? 'bg-success-light'
                  : settlement?.status === 1
                    ? 'bg-warning-light'
                    : 'bg-bg-tertiary'
            "
          >
            <CalendarCheck
              class="h-7 w-7"
              :class="
                settlement?.status === 3
                  ? 'text-text-muted'
                  : settlement?.status === 2
                    ? 'text-success'
                    : settlement?.status === 1
                      ? 'text-warning'
                      : 'text-text-muted'
              "
            />
          </div>
          <div>
            <div class="text-sm text-text-muted">对账状态</div>
            <div class="mt-1 flex items-center gap-2">
              <ElTag :type="statusTagType" effect="light" size="large">{{ statusText }}</ElTag>
              <span v-if="settlement?.settledAt" class="text-xs text-text-muted">
                对账于 {{ settlement.settledAt }}
              </span>
              <span v-if="settlement?.closedAt" class="text-xs text-text-muted">
                关店于 {{ settlement.closedAt }}
              </span>
            </div>
          </div>
        </div>
        <div class="flex items-center gap-3">
          <span class="text-sm text-text-muted">对账日期</span>
          <ElDatePicker
            v-model="dateValue"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="选择日期"
            :clearable="false"
            style="width: 180px"
            @change="fetchData"
          />
        </div>
      </div>

      <div v-loading="loading">
        <!-- 统计卡片 -->
        <div class="mb-5 grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-5">
          <StatCard
            title="订单数"
            :value="Number(settlement?.orderCount ?? 0)"
            :icon="ShoppingCart"
            color="primary"
          />
          <StatCard
            title="营业额"
            :value="money(settlement?.totalRevenue)"
            :icon="DollarSign"
            color="success"
          />
          <StatCard
            title="退款额"
            :value="money(settlement?.totalRefund)"
            :icon="RotateCcw"
            color="danger"
          />
          <StatCard
            title="充值额"
            :value="money(settlement?.totalRecharge)"
            :icon="Wallet"
            color="accent"
          />
          <StatCard
            title="消费额"
            :value="money(settlement?.totalConsumption)"
            :icon="TrendingUp"
            color="warning"
          />
        </div>

        <!-- 详细数据表格:已完成/已取消/已取餐订单数对比 -->
        <div class="mb-5 rounded-xl border border-border bg-card shadow-sm overflow-hidden">
          <div class="border-b border-border-light px-5 py-4">
            <h3 class="text-base font-semibold text-text">订单状态对比</h3>
          </div>
          <ElTable :data="settlement ? [settlement] : []" style="width: 100%">
            <ElTableColumn label="已完成订单" align="center">
              <template #default="{ row }">
                <span class="inline-flex items-center gap-1.5 tabular-nums font-semibold text-success">
                  <CheckCircle2 class="h-4 w-4" />
                  {{ row.completedCount ?? 0 }}
                </span>
              </template>
            </ElTableColumn>
            <ElTableColumn label="已取消订单" align="center">
              <template #default="{ row }">
                <span class="inline-flex items-center gap-1.5 tabular-nums font-semibold text-danger">
                  <XCircle class="h-4 w-4" />
                  {{ row.cancelledCount ?? 0 }}
                </span>
              </template>
            </ElTableColumn>
            <ElTableColumn label="已取餐订单" align="center">
              <template #default="{ row }">
                <span class="inline-flex items-center gap-1.5 tabular-nums font-semibold text-primary">
                  <Utensils class="h-4 w-4" />
                  {{ row.servedCount ?? 0 }}
                </span>
              </template>
            </ElTableColumn>
            <ElTableColumn label="订单总数" align="center">
              <template #default="{ row }">
                <span class="tabular-nums font-semibold text-text">{{ row.orderCount ?? 0 }}</span>
              </template>
            </ElTableColumn>
            <template #empty>
              <EmptyState description="暂无对账数据,请先生成对账" />
            </template>
          </ElTable>
        </div>

        <!-- 充值明细 -->
        <div class="mb-5 grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
          <StatCard
            title="现金充值"
            :value="money(settlement?.cashRevenue)"
            :icon="Wallet"
            color="success"
          />
          <StatCard
            title="线上充值"
            :value="money(settlement?.onlineRevenue)"
            :icon="Wallet"
            color="accent"
          />
          <StatCard
            title="净流水"
            :value="money(
              Number(settlement?.totalRecharge ?? 0) -
                Number(settlement?.totalConsumption ?? 0) +
                Number(settlement?.totalRefund ?? 0)
            )"
            :icon="TrendingUp"
            color="warning"
          />
        </div>
      </div>

      <!-- 历史列表 -->
      <div class="rounded-xl border border-border bg-card shadow-sm overflow-hidden">
        <div class="border-b border-border-light px-5 py-4">
          <h3 class="text-base font-semibold text-text">历史对账记录(最近30天)</h3>
        </div>
        <ElTable
          v-loading="historyLoading"
          :data="historyList"
          style="width: 100%"
          :show-overflow-tooltip="true"
          @row-click="handleViewDetail"
        >
          <ElTableColumn prop="settleDate" label="对账日期" width="130" />
          <ElTableColumn label="订单数" width="90" align="right">
            <template #default="{ row }">
              <span class="tabular-nums text-text">{{ row.orderCount ?? 0 }}</span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="营业额" width="130" align="right">
            <template #default="{ row }">
              <span class="tabular-nums font-semibold text-success">{{ money(row.totalRevenue) }}</span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="退款额" width="130" align="right">
            <template #default="{ row }">
              <span class="tabular-nums text-danger">{{ money(row.totalRefund) }}</span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="充值额" width="130" align="right">
            <template #default="{ row }">
              <span class="tabular-nums text-accent">{{ money(row.totalRecharge) }}</span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="消费额" width="130" align="right">
            <template #default="{ row }">
              <span class="tabular-nums text-text-secondary">{{ money(row.totalConsumption) }}</span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="状态" width="100" align="center">
            <template #default="{ row }">
              <ElTag v-if="row.status === 1" type="warning" effect="light">待对账</ElTag>
              <ElTag v-else-if="row.status === 2" type="success" effect="light">已对账</ElTag>
              <ElTag v-else-if="row.status === 3" type="info" effect="light">已关店</ElTag>
              <ElTag v-else type="info" effect="light">未知</ElTag>
            </template>
          </ElTableColumn>
          <ElTableColumn label="操作人" width="120" align="center">
            <template #default="{ row }">
              <span class="text-text-secondary">{{ row.operatorName || (row.operatorId ? `管理员#${row.operatorId}` : '—') }}</span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="对账时间" min-width="160">
            <template #default="{ row }">
              <span class="tabular-nums text-text-muted">{{ row.settledAt || row.createdAt || '—' }}</span>
            </template>
          </ElTableColumn>
          <template #empty>
            <EmptyState description="暂无历史对账记录" />
          </template>
        </ElTable>
        <div class="flex justify-end border-t border-border-light px-4 py-3">
          <ElPagination
            :current-page="historyPage"
            :page-size="historySize"
            :total="historyTotal"
            :page-sizes="[10, 20, 50]"
            layout="total, sizes, prev, pager, next, jumper"
            background
            @current-change="onHistoryPageChange"
            @size-change="onHistorySizeChange"
          />
        </div>
      </div>
    </PageContainer>
  </Layout>
</template>
