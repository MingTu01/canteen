<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import {
  ElButton,
  ElDatePicker,
  ElDescriptions,
  ElDescriptionsItem,
  ElDialog,
  ElDrawer,
  ElForm,
  ElFormItem,
  ElInput,
  ElOption,
  ElPagination,
  ElSelect,
  ElTable,
  ElTableColumn,
  ElMessage,
  ElMessageBox,
} from 'element-plus'
import { Eye, CheckCircle2, XCircle, Download, ScanLine } from 'lucide-vue-next'
import * as XLSX from 'xlsx'
import Layout from '@/components/Layout.vue'
import PageContainer from '@/components/PageContainer.vue'
import SearchBar from '@/components/SearchBar.vue'
import StatusTag from '@/components/StatusTag.vue'
import EmptyState from '@/components/EmptyState.vue'
import { useAuthStore } from '@/stores/auth'
import { orderApi } from '@/api'
import type { Order, OrderDetail, OrderItem, OrderQuery, PageResult } from '@/api/types'
import { ORDER_STATUS, MEAL_TYPE, ORDER_SOURCE } from '@/constants/dict'

const authStore = useAuthStore()
// 超管未选择食堂时返回 null,不再静默回退到 storeId=1
const sid = computed(() => authStore.storeId || null)
const noStoreSelected = computed(() => sid.value === null)

type OrderRow = Order & { employeeName?: string; cardNo?: string; departmentName?: string }

const orders = ref<OrderRow[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)
const exporting = ref(false)

const filters = reactive({
  status: undefined as number | undefined,
  mealType: undefined as number | undefined,
  dateRange: [] as string[],
  keyword: '',
})

const statusOptions = Object.entries(ORDER_STATUS).map(([k, v]) => ({
  value: Number(k),
  label: v.label,
}))
const mealTypeOptions = Object.entries(MEAL_TYPE).map(([k, v]) => ({
  value: Number(k),
  label: v.label,
}))

const mealLabel = (m?: number) =>
  m != null ? (MEAL_TYPE as Record<number, { label: string }>)[m]?.label : ''

const statusLabel = (s?: number) =>
  s != null ? (ORDER_STATUS as Record<number, { label: string }>)[s]?.label : '—'

const sourceLabel = (s?: number) =>
  s != null ? (ORDER_SOURCE as Record<number, { label: string }>)[s]?.label : '正常订餐'

const buildQuery = (overrides: Partial<OrderQuery> = {}): OrderQuery => ({
  storeId: sid.value ?? 0,
  page: page.value,
  size: size.value,
  status: filters.status,
  mealType: filters.mealType,
  startDate: filters.dateRange?.[0],
  endDate: filters.dateRange?.[1],
  keyword: filters.keyword,
  ...overrides,
})

const fetchOrders = async () => {
  // 超管未选择食堂:不请求,清空列表
  if (noStoreSelected.value) {
    orders.value = []
    total.value = 0
    return
  }
  loading.value = true
  try {
    const res = await orderApi.list(buildQuery())
    const data = res as unknown as PageResult<OrderRow> | OrderRow[]
    orders.value = Array.isArray(data) ? data : data.records ?? []
    total.value = Array.isArray(data) ? data.length : data.total ?? orders.value.length
  } catch {
    /* 错误已由拦截器统一提示 */
  } finally {
    loading.value = false
  }
}

const handleSearch = () => {
  page.value = 1
  fetchOrders()
}

const handleReset = () => {
  filters.status = undefined
  filters.mealType = undefined
  filters.dateRange = []
  filters.keyword = ''
  page.value = 1
  fetchOrders()
}

const handlePageChange = (p: number) => {
  page.value = p
  fetchOrders()
}

const handleSizeChange = (s: number) => {
  size.value = s
  page.value = 1
  fetchOrders()
}

/** 导出当前筛选条件下的订单为 Excel(一次性拉取最多 10000 条) */
const handleExport = async () => {
  if (noStoreSelected.value) {
    ElMessage.warning('请先选择食堂')
    return
  }
  exporting.value = true
  try {
    const res = await orderApi.list(buildQuery({ page: 1, size: 10000 }))
    const data = res as unknown as PageResult<OrderRow> | OrderRow[]
    const rows = Array.isArray(data) ? data : data.records ?? []
    if (rows.length === 0) {
      ElMessage.warning('当前筛选条件下没有可导出的订单')
      return
    }
    const exportData = rows.map((o, idx) => ({
      '序号': idx + 1,
      '订单号': o.orderNo ?? '',
      '卡号': o.cardNo ?? '',
      '员工姓名': o.employeeName ?? `#${o.employeeId}`,
      '日期': o.date ?? '',
      '餐次': mealLabel(o.mealType),
      '订单来源': sourceLabel(o.orderSource),
      '金额': o.totalAmount ?? 0,
      '状态': statusLabel(o.status),
      '取餐码': o.pickupCode ?? '',
      '下单时间': o.createdAt ?? '',
    }))
    const ws = XLSX.utils.json_to_sheet(exportData)
    // 列宽
    ws['!cols'] = [
      { wch: 6 }, { wch: 22 }, { wch: 16 }, { wch: 12 }, { wch: 12 }, { wch: 8 },
      { wch: 12 }, { wch: 10 }, { wch: 10 }, { wch: 10 }, { wch: 20 },
    ]
    const wb = XLSX.utils.book_new()
    XLSX.utils.book_append_sheet(wb, ws, '订单列表')
    const dateStr = new Date().toISOString().slice(0, 10).replace(/-/g, '')
    XLSX.writeFile(wb, `订单列表_${dateStr}.xlsx`)
    ElMessage.success(`已导出 ${rows.length} 条订单`)
  } catch {
    /* 错误已由拦截器统一提示 */
  } finally {
    exporting.value = false
  }
}

// 详情抽屉
const drawerVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<OrderDetail | null>(null)

const openDetail = async (row: OrderRow) => {
  if (!row.id) return
  drawerVisible.value = true
  detailLoading.value = true
  detail.value = null
  try {
    detail.value = await orderApi.detail(row.id)
  } catch {
    /* 错误已由拦截器统一提示 */
  } finally {
    detailLoading.value = false
  }
}

const itemSubtotal = (item: OrderItem) =>
  ((item.price ?? 0) * (item.quantity ?? 0)).toFixed(2)

const handleComplete = async (row: OrderRow) => {
  if (!row.id) return
  try {
    await ElMessageBox.confirm('确认将该订单标记为已完成？', '完成确认', {
      type: 'warning',
      confirmButtonText: '确认完成',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await orderApi.complete(row.id)
    ElMessage.success('订单已完成')
    drawerVisible.value = false
    fetchOrders()
  } catch {
    /* 错误已由拦截器统一提示 */
  }
}

const handleCancel = async (row: OrderRow) => {
  if (!row.id) return
  try {
    await ElMessageBox.confirm('确认取消该订单？取消后不可恢复。', '取消确认', {
      type: 'warning',
      confirmButtonText: '确认取消',
      cancelButtonText: '返回',
    })
  } catch {
    return
  }
  try {
    await orderApi.cancel(row.id)
    ElMessage.success('订单已取消')
    drawerVisible.value = false
    fetchOrders()
  } catch {
    /* 错误已由拦截器统一提示 */
  }
}

// ===== 取餐核销 =====
const pickupVisible = ref(false)
const pickupCode = ref('')
const pickupLoading = ref(false)

const openPickup = () => {
  pickupCode.value = ''
  pickupVisible.value = true
}

const confirmPickup = async () => {
  const code = pickupCode.value.trim()
  if (!code) {
    ElMessage.warning('请输入取餐码')
    return
  }
  pickupLoading.value = true
  try {
    await orderApi.pickup({ pickupCode: code })
    ElMessage.success('核销成功')
    pickupVisible.value = false
    fetchOrders()
  } catch {
    /* 错误已由拦截器统一提示 */
  } finally {
    pickupLoading.value = false
  }
}

onMounted(fetchOrders)

// 超管切换食堂后自动刷新订单列表
watch(() => authStore.storeId, () => {
  page.value = 1
  fetchOrders()
})
</script>

<template>
  <Layout>
    <PageContainer title="订单管理" description="查看与处理食堂订单，支持状态、日期、员工姓名筛选与导出">
      <!-- 超管未选择食堂提示 -->
      <div
        v-if="noStoreSelected"
        class="mb-4 rounded-lg border border-amber-300 bg-amber-50 px-4 py-3 text-sm text-amber-700"
      >
        请先选择食堂后再查看订单。
      </div>
      <SearchBar @search="handleSearch" @reset="handleReset">
        <ElInput
          v-model="filters.keyword"
          placeholder="搜索订单号 / 卡号 / 姓名"
          clearable
          style="width: 220px"
          aria-label="搜索订单号、卡号或姓名"
          @keyup.enter="handleSearch"
        />
        <ElSelect v-model="filters.status" placeholder="订单状态" clearable style="width: 120px" aria-label="筛选订单状态">
          <ElOption v-for="o in statusOptions" :key="o.value" :label="o.label" :value="o.value" />
        </ElSelect>
        <ElSelect v-model="filters.mealType" placeholder="餐次" clearable style="width: 100px" aria-label="筛选餐次">
          <ElOption v-for="o in mealTypeOptions" :key="o.value" :label="o.label" :value="o.value" />
        </ElSelect>
        <ElDatePicker
          v-model="filters.dateRange"
          type="daterange"
          range-separator="至"
          start-placeholder="开始"
          end-placeholder="结束"
          value-format="YYYY-MM-DD"
          style="width: 220px"
          aria-label="选择日期范围"
        />
        <template #actions>
          <ElButton v-if="!noStoreSelected" type="success" :icon="ScanLine" aria-label="取餐核销" @click="openPickup">
            取餐核销
          </ElButton>
          <ElButton :icon="Download" :loading="exporting" @click="handleExport">导出Excel</ElButton>
        </template>
      </SearchBar>

      <div class="card overflow-hidden">
        <ElTable
          v-loading="loading"
          :data="orders"
          style="width: 100%"
          :show-overflow-tooltip="true"
          highlight-current-row
          row-key="id"
          aria-label="订单列表"
          @row-click="openDetail"
        >
          <ElTableColumn prop="orderNo" label="订单号" min-width="160" />
          <ElTableColumn prop="cardNo" label="卡号" min-width="130" />
          <ElTableColumn label="员工" min-width="110">
            <template #default="{ row }">
              {{ row.employeeName || `#${row.employeeId}` }}
            </template>
          </ElTableColumn>
          <ElTableColumn prop="date" label="日期" width="120" />
          <ElTableColumn label="餐次" width="90" align="center">
            <template #default="{ row }">{{ mealLabel(row.mealType) }}</template>
          </ElTableColumn>
          <ElTableColumn label="订单来源" width="110" align="center">
            <template #default="{ row }">
              <StatusTag :value="row.orderSource ?? 0" :map="ORDER_SOURCE" />
            </template>
          </ElTableColumn>
          <ElTableColumn label="金额" width="110" align="right">
            <template #default="{ row }">
              <span class="font-medium tabular-nums text-text">¥{{ row.totalAmount }}</span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="状态" width="100" align="center">
            <template #default="{ row }">
              <StatusTag :value="row.status" :map="ORDER_STATUS" />
            </template>
          </ElTableColumn>
          <ElTableColumn label="操作" width="220" fixed="right">
            <template #default="{ row }">
              <ElButton size="small" :icon="Eye" @click.stop="openDetail(row as OrderRow)">详情</ElButton>
              <ElButton
                v-if="row.status === 1"
                size="small"
                type="success"
                :icon="CheckCircle2"
                @click.stop="handleComplete(row as OrderRow)"
              >
                完成
              </ElButton>
              <ElButton
                v-if="row.status === 1"
                size="small"
                type="danger"
                :icon="XCircle"
                @click.stop="handleCancel(row as OrderRow)"
              >
                取消
              </ElButton>
            </template>
          </ElTableColumn>
          <template #empty>
            <EmptyState description="暂无订单数据" />
          </template>
        </ElTable>

        <div class="flex flex-wrap items-center justify-between gap-2 border-t border-border px-4 py-3">
          <span class="text-xs text-text-muted">共 {{ total }} 条</span>
          <ElPagination
            v-model:current-page="page"
            v-model:page-size="size"
            :page-sizes="[20, 50, 100, 200]"
            :total="total"
            layout="total, sizes, prev, pager, next, jumper"
            background
            @current-change="handlePageChange"
            @size-change="handleSizeChange"
          />
        </div>
      </div>

      <!-- 详情抽屉 -->
      <ElDrawer v-model="drawerVisible" title="订单详情" direction="rtl" size="460px">
        <div v-loading="detailLoading">
          <template v-if="detail">
            <ElDescriptions :column="1" border class="mb-6">
              <ElDescriptionsItem label="订单号">
                {{ detail.order.orderNo }}
              </ElDescriptionsItem>
              <ElDescriptionsItem label="卡号">
                {{ (detail.order as OrderRow).cardNo || '—' }}
              </ElDescriptionsItem>
              <ElDescriptionsItem label="员工">
                {{ (detail.order as OrderRow).employeeName || `#${detail.order.employeeId}` }}
              </ElDescriptionsItem>
              <ElDescriptionsItem label="日期">
                {{ detail.order.date }}
              </ElDescriptionsItem>
              <ElDescriptionsItem label="餐次">
                {{ mealLabel(detail.order.mealType) }}
              </ElDescriptionsItem>
              <ElDescriptionsItem label="取餐码">
                {{ detail.order.pickupCode || '—' }}
              </ElDescriptionsItem>
              <ElDescriptionsItem label="状态">
                <StatusTag :value="detail.order.status" :map="ORDER_STATUS" />
              </ElDescriptionsItem>
              <ElDescriptionsItem label="下单时间">
                {{ detail.order.createdAt || '—' }}
              </ElDescriptionsItem>
            </ElDescriptions>

            <h4 class="mb-3 text-sm font-semibold text-text">菜品明细</h4>
            <div class="space-y-2">
              <div
                v-for="item in detail.items"
                :key="item.id"
                class="flex items-center justify-between rounded-lg border border-border bg-bg-secondary px-4 py-3"
              >
                <div class="min-w-0">
                  <div class="truncate text-sm font-medium text-text">
                    {{ item.dishName || `#${item.dishId}` }}
                  </div>
                  <div class="mt-0.5 text-xs text-text-muted">
                    ¥{{ item.price }} × {{ item.quantity }}
                  </div>
                </div>
                <div class="ml-3 shrink-0 text-sm font-semibold tabular-nums text-text">
                  ¥{{ itemSubtotal(item) }}
                </div>
              </div>
              <EmptyState v-if="!detail.items?.length" description="暂无明细" />
            </div>

            <div
              class="mt-4 flex items-center justify-between rounded-lg bg-primary-50 px-4 py-3"
            >
              <span class="text-sm text-text-secondary">订单总额</span>
              <span class="text-lg font-bold tabular-nums text-primary">
                ¥{{ detail.order.totalAmount }}
              </span>
            </div>

            <div class="mt-6 flex gap-3">
              <ElButton
                v-if="detail.order.status === 1"
                type="success"
                class="flex-1"
                @click="handleComplete(detail.order)"
              >
                标记完成
              </ElButton>
              <ElButton
                v-if="detail.order.status === 1"
                type="danger"
                class="flex-1"
                @click="handleCancel(detail.order)"
              >
                取消订单
              </ElButton>
            </div>
          </template>
        </div>
      </ElDrawer>

      <!-- 取餐核销弹窗 -->
      <ElDialog
        v-model="pickupVisible"
        title="取餐核销"
        width="400px"
        :close-on-click-modal="false"
        append-to-body
        destroy-on-close
      >
        <ElForm label-width="80px" @submit.prevent>
          <ElFormItem label="取餐码">
            <ElInput
              v-model="pickupCode"
              placeholder="请输入取餐码"
              clearable
              @keyup.enter="confirmPickup"
            />
          </ElFormItem>
        </ElForm>
        <template #footer>
          <ElButton @click="pickupVisible = false">取消</ElButton>
          <ElButton type="primary" :loading="pickupLoading" @click="confirmPickup">
            确认核销
          </ElButton>
        </template>
      </ElDialog>
    </PageContainer>
  </Layout>
</template>
