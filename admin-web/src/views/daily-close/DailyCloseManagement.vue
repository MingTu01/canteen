<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import {
  ElButton,
  ElDatePicker,
  ElTable,
  ElTableColumn,
  ElPagination,
  ElMessage,
  ElMessageBox,
  ElTag,
} from 'element-plus'
import {
  RefreshCw,
  ClipboardCheck,
  ShoppingCart,
  CheckCircle2,
  XCircle,
  Wallet,
  TrendingUp,
  TrendingDown,
  Users,
  Coins,
} from 'lucide-vue-next'
import Layout from '@/components/Layout.vue'
import PageContainer from '@/components/PageContainer.vue'
import SearchBar from '@/components/SearchBar.vue'
import StatCard from '@/components/StatCard.vue'
import EmptyState from '@/components/EmptyState.vue'
import { useAuthStore } from '@/stores/auth'
import { dailyCloseApi } from '@/api'
import type { DailyCloseSummary, DailyCloseRecord } from '@/api'
import { todayStr } from '@/utils/date'
import { money } from '@/utils/money'
import { normalizeList } from '@/utils/list'

const authStore = useAuthStore()
// 超管未选食堂时返回 null,不再静默回退到 storeId=1
const sid = computed(() => authStore.storeId || null)

const dateValue = ref<string>(todayStr())
const loading = ref(false)
const confirming = ref(false)
const summary = ref<DailyCloseSummary | null>(null)

const fetchSummary = async () => {
  const storeId = sid.value
  if (!storeId) {
    summary.value = null
    return
  }
  loading.value = true
  try {
    summary.value = await dailyCloseApi.summary({ storeId, date: dateValue.value })
  } catch {
    summary.value = null
  } finally {
    loading.value = false
  }
}

/* 历史对账记录 */
const historyList = ref<DailyCloseRecord[]>([])
const historyTotal = ref(0)
const historyLoading = ref(false)
const historyPage = ref(1)
const historySize = ref(10)

const fetchHistory = async () => {
  const storeId = sid.value
  if (!storeId) {
    historyList.value = []
    historyTotal.value = 0
    return
  }
  historyLoading.value = true
  try {
    const res = await dailyCloseApi.history({
      storeId,
      page: historyPage.value,
      size: historySize.value,
    })
    historyList.value = normalizeList<DailyCloseRecord>(res?.records)
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

/* 确认日终对账 */
const handleConfirm = async () => {
  const storeId = sid.value
  if (!storeId) {
    ElMessage.warning('请先选择食堂')
    return
  }
  try {
    await ElMessageBox.confirm(
      `确认对 ${dateValue.value} 当日营业数据进行日终对账?对账后将记录到历史,且无法重复确认。`,
      '日终对账确认',
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
    await dailyCloseApi.confirm({ storeId, date: dateValue.value })
    ElMessage.success('日终对账已确认')
    await fetchHistory()
  } catch {
    /* 拦截器提示 */
  } finally {
    confirming.value = false
  }
}

const handleSearch = () => {
  fetchSummary()
}
const handleReset = () => {
  dateValue.value = todayStr()
  fetchSummary()
}

onMounted(() => {
  fetchSummary()
  fetchHistory()
})

watch(() => authStore.storeId, () => {
  historyPage.value = 1
  fetchSummary()
  fetchHistory()
})
</script>

<template>
  <Layout>
    <PageContainer title="日终对账" description="关店前核对当日订单、营业额、退款、充值与新增员工,确认后归档为对账单。">
      <template #actions>
        <ElButton :icon="RefreshCw" @click="fetchSummary">刷新</ElButton>
        <ElButton
          type="primary"
          :icon="ClipboardCheck"
          :loading="confirming"
          :disabled="!sid"
          @click="handleConfirm"
        >
          确认日终对账
        </ElButton>
      </template>

      <div
        v-if="!sid"
        class="mb-4 rounded-lg border border-amber-300 bg-amber-50 px-4 py-3 text-sm text-amber-700"
      >
        请先选择食堂后再查看数据。
      </div>

      <!-- 顶部日期选择 -->
      <SearchBar @search="handleSearch" @reset="handleReset">
        <span class="text-sm text-text-muted">对账日期</span>
        <ElDatePicker
          v-model="dateValue"
          type="date"
          value-format="YYYY-MM-DD"
          placeholder="选择日期"
          :clearable="false"
          style="width: 180px"
        />
      </SearchBar>

      <div v-loading="loading">
        <template v-if="summary">
          <!-- 统计卡片 -->
          <div class="mb-5 grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
            <StatCard title="订单总数" :value="summary.orderCount" :icon="ShoppingCart" color="primary" />
            <StatCard title="已支付" :value="summary.paidCount" :icon="CheckCircle2" color="success" />
            <StatCard title="已取消" :value="summary.cancelledCount" :icon="XCircle" color="danger" />
            <StatCard title="营业额" :value="money(summary.totalRevenue)" :icon="TrendingUp" color="success" />
            <StatCard title="退款总额" :value="money(summary.totalRefund)" :icon="TrendingDown" color="danger" />
            <StatCard title="充值总额" :value="money(summary.rechargeAmount)" :icon="Wallet" color="accent" />
          </div>

          <!-- 次级统计:新增员工 -->
          <div class="mb-5 grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
            <StatCard title="新增员工" :value="summary.newEmployeeCount" :icon="Users" color="primary" />
            <StatCard title="净流水" :value="money(Number(summary.rechargeAmount) - Number(summary.totalRevenue) + Number(summary.totalRefund))" :icon="Coins" color="warning" />
          </div>

          <!-- 菜品销量 TOP5 -->
          <div class="mb-5 rounded-xl border border-border bg-card shadow-sm overflow-hidden">
            <div class="border-b border-border-light px-5 py-4">
              <h3 class="text-base font-semibold text-text">菜品销量 TOP5</h3>
            </div>
            <ElTable :data="summary.dishSales" style="width: 100%">
              <ElTableColumn label="排名" width="90" align="center">
                <template #default="{ $index }">
                  <span
                    class="inline-flex h-6 w-6 items-center justify-center rounded-full text-xs font-bold text-white"
                    :style="{ background: $index < 3 ? 'var(--color-primary)' : 'var(--color-text-muted)' }"
                  >{{ $index + 1 }}</span>
                </template>
              </ElTableColumn>
              <ElTableColumn prop="dishName" label="菜品名称" min-width="200" />
              <ElTableColumn label="销量" width="140" align="right">
                <template #default="{ row }">
                  <span class="tabular-nums font-semibold text-primary">{{ row.quantity }}</span>
                </template>
              </ElTableColumn>
              <ElTableColumn label="销售额" width="180" align="right">
                <template #default="{ row }">
                  <span class="tabular-nums text-text-secondary">{{ money(row.amount) }}</span>
                </template>
              </ElTableColumn>
              <template #empty>
                <EmptyState description="暂无菜品销量数据" />
              </template>
            </ElTable>
          </div>
        </template>
        <EmptyState v-else-if="!loading && sid" description="暂无对账数据" />
      </div>

      <!-- 历史对账记录 -->
      <div class="rounded-xl border border-border bg-card shadow-sm overflow-hidden">
        <div class="border-b border-border-light px-5 py-4">
          <h3 class="text-base font-semibold text-text">历史对账记录</h3>
        </div>
        <ElTable v-loading="historyLoading" :data="historyList" style="width: 100%">
          <ElTableColumn prop="closeDate" label="对账日期" width="140" />
          <ElTableColumn label="订单数" width="100" align="right">
            <template #default="{ row }">
              <span class="tabular-nums text-text">{{ row.orderCount ?? 0 }}</span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="营业额" width="140" align="right">
            <template #default="{ row }">
              <span class="tabular-nums font-semibold text-success">{{ money(row.totalRevenue) }}</span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="退款额" width="140" align="right">
            <template #default="{ row }">
              <span class="tabular-nums text-danger">{{ money(row.totalRefund) }}</span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="充值额" width="140" align="right">
            <template #default="{ row }">
              <span class="tabular-nums text-accent">{{ money(row.rechargeAmount) }}</span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="状态" width="100" align="center">
            <template #default="{ row }">
              <ElTag v-if="row.status === 1" type="success" effect="light">已对账</ElTag>
              <ElTag v-else type="info" effect="light">未知</ElTag>
            </template>
          </ElTableColumn>
          <ElTableColumn label="操作人" width="120" align="center">
            <template #default="{ row }">
              <span class="text-text-secondary">{{ row.operatorId ? `管理员#${row.operatorId}` : '—' }}</span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="对账时间" min-width="160">
            <template #default="{ row }">
              <span class="tabular-nums text-text-muted">{{ row.createdAt || '—' }}</span>
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
