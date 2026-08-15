<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import {
  ElButton,
  ElDatePicker,
  ElDescriptions,
  ElDescriptionsItem,
  ElDrawer,
  ElInput,
  ElOption,
  ElPagination,
  ElSelect,
  ElTable,
  ElTableColumn,
  ElMessage,
  ElMessageBox,
} from 'element-plus'
import { Eye, CheckCircle2, XCircle, Download, Coins } from 'lucide-vue-next'
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

/** 扁平化后的菜品行:同一订单的菜品拆成多行,重点是哪个菜点了几份 */
interface DishRow {
  key: string
  orderId?: number
  orderNo?: string
  date: string
  employeeName: string
  departmentName: string
  cardNo: string
  mealType: number
  totalAmount: number
  updatedAt?: string
  status: number
  orderSource?: number
  serviceFee?: number
  dishName: string
  price: number
  quantity: number
  isFirstRow: boolean
  _order: OrderRow
}

const flatRows = computed<DishRow[]>(() => {
  const result: DishRow[] = []
  for (const o of orders.value) {
    // 展示行 = 菜品明细 + 手续费行(手续费>0 时作为一条「菜品」追加,不再单独成列)
    const fee = Number(o.serviceFee ?? 0)
    const rows: OrderItem[] = o.items && o.items.length > 0 ? [...o.items] : []
    if (fee > 0) {
      rows.push({ dishName: '手续费', price: fee, quantity: 1 } as OrderItem)
    }
    // 无菜品也无手续费:占位一行
    if (rows.length === 0) {
      rows.push({ dishName: '—', price: 0, quantity: 0 } as OrderItem)
    }
    rows.forEach((item, idx) => {
      result.push({
        key: `${o.id ?? 0}-${idx}`,
        orderId: o.id,
        orderNo: o.orderNo,
        date: o.date,
        employeeName: o.employeeName ?? `#${o.employeeId}`,
        departmentName: o.departmentName ?? '—',
        cardNo: o.cardNo ?? '—',
        mealType: o.mealType,
        totalAmount: o.totalAmount,
        updatedAt: o.updatedAt,
        status: o.status,
        orderSource: o.orderSource,
        serviceFee: o.serviceFee,
        dishName: item.dishName ?? '—',
        price: item.price ?? 0,
        quantity: item.quantity ?? 0,
        isFirstRow: idx === 0,
        _order: o,
      })
    })
  }
  return result
})

/** 格式化结帐时间(仅已完成订单显示) */
const formatCheckoutTime = (row: { status?: number; updatedAt?: string }) => {
  if (row.status !== 2 || !row.updatedAt) return '—'
  return row.updatedAt.replace('T', ' ').substring(0, 19)
}

const filters = reactive({
  status: undefined as number | undefined,
  mealType: undefined as number | undefined,
  /** 订单来源:1=未订餐用餐(快捷筛选按钮切换),undefined=全部 */
  orderSource: undefined as number | undefined,
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
  orderSource: filters.orderSource,
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
  filters.orderSource = undefined
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
    // 按菜品拆行导出(同订单号菜品拆出来)
    // 手续费>0 时作为一条「菜品」行导出(菜名=手续费,数量=1),不再单独成列;
    // 价格/数值列一律写数值类型单元格(空缺用 undefined 跳过,不写空串),
    // 保证 Excel 内 SUM/公式可直接计算
    const exportData: Record<string, string | number | undefined>[] = []
    let seq = 0
    for (const o of rows) {
      const fee = Number(o.serviceFee ?? 0)
      const items: OrderItem[] = o.items && o.items.length > 0 ? [...o.items] : []
      if (fee > 0) {
        items.push({ dishName: '手续费', price: fee, quantity: 1 } as OrderItem)
      }
      // 无菜品也无手续费:仅导出一行订单级信息(菜名留空)
      if (items.length === 0) {
        items.push({ dishName: '', price: 0, quantity: 0 } as OrderItem)
      }
      items.forEach((item, idx) => {
        seq++
        exportData.push({
          '序号': seq,
          '订单号': o.orderNo ?? '',
          '日期': o.date ?? '',
          '姓名': o.employeeName ?? `#${o.employeeId}`,
          '部门': o.departmentName ?? '',
          '会员号': o.cardNo ?? '',
          '餐次': mealLabel(o.mealType),
          '菜名': item.dishName ?? '',
          '单价': Number(item.price ?? 0),
          '数量': Number(item.quantity ?? 0),
          '合计价格': Number(((item.price ?? 0) * (item.quantity ?? 0)).toFixed(2)),
          // 订单级字段仅首行填,其余行跳过(保持数值列纯净可计算)
          '订单总额': idx === 0 ? Number(o.totalAmount ?? 0) : undefined,
          '结帐时间': o.status === 2 && o.updatedAt ? o.updatedAt.replace('T', ' ').substring(0, 19) : '',
          '订单状态': statusLabel(o.status),
          '订单来源': sourceLabel(o.orderSource),
        })
      })
    }
    const ws = XLSX.utils.json_to_sheet(exportData)
    // 金额列统一两位小数显示格式(单元格仍为数值,公式可计算)
    const MONEY_COLS = new Set(['单价', '合计价格', '订单总额'])
    const header = Object.keys(exportData[0] ?? {})
    const moneyColIdx = header
      .map((h, i) => (MONEY_COLS.has(h) ? i : -1))
      .filter((i) => i >= 0)
    moneyColIdx.forEach((col) => {
      for (let r = 0; r < exportData.length; r++) {
        const addr = XLSX.utils.encode_cell({ r, c: col })
        const cell = ws[addr]
        if (cell && cell.t === 'n') cell.z = '0.00'
      }
    })
    // 列宽
    ws['!cols'] = [
      { wch: 6 }, { wch: 22 }, { wch: 12 }, { wch: 10 }, { wch: 12 }, { wch: 14 },
      { wch: 8 }, { wch: 18 }, { wch: 8 }, { wch: 8 }, { wch: 12 },
      { wch: 12 }, { wch: 20 }, { wch: 10 }, { wch: 12 },
    ]
    const wb = XLSX.utils.book_new()
    XLSX.utils.book_append_sheet(wb, ws, '订单列表')
    const dateStr = new Date().toISOString().slice(0, 10).replace(/-/g, '')
    XLSX.writeFile(wb, `订单列表_${dateStr}.xlsx`)
    ElMessage.success(`已导出 ${rows.length} 条订单(${exportData.length} 行菜品)`)
  } catch {
    /* 错误已由拦截器统一提示 */
  } finally {
    exporting.value = false
  }
}

// 详情抽屉
const drawerVisible = ref(false)

/** 未订餐就餐快捷筛选:点击切换,激活时仅显示 orderSource=1 的订单 */
const toggleUnsolicitedFilter = () => {
  if (noStoreSelected.value) {
    ElMessage.warning('请先选择食堂')
    return
  }
  filters.orderSource = filters.orderSource === 1 ? undefined : 1
  handleSearch()
}
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

onMounted(fetchOrders)

// ===== 表格上方横向滚动条(与表格底部滚动条双向同步) =====
// 列较多时表格自带滚动条在最底部,拖动需先滚到底部,不便操作;
// 在表格上方放一条独立滚动条,拖任一条另一条同步移动。
// ElTable 是泛型函数组件,InstanceType 不适用;仅需要 $el 拿 DOM
const tableRef = ref<{ $el: HTMLElement }>()
const topScrollbarRef = ref<HTMLElement>()
const tableScrollWidth = ref(0)
const showTopScrollbar = ref(false)

/** 获取表格内部横向滚动容器(EP 2.x 为 .el-scrollbar__wrap) */
const getTableWrap = (): HTMLElement | null => {
  const el = tableRef.value?.$el as HTMLElement | undefined
  if (!el) return null
  return (
    el.querySelector('.el-scrollbar__wrap') ||
    el.querySelector('.el-table__body-wrapper')
  )
}

/** 上方滚动条拖动 → 同步表格 */
const syncFromTop = () => {
  const wrap = getTableWrap()
  if (wrap && topScrollbarRef.value) wrap.scrollLeft = topScrollbarRef.value.scrollLeft
}

/** 表格底部滚动条拖动 → 同步上方 */
const syncFromTable = () => {
  const wrap = getTableWrap()
  if (wrap && topScrollbarRef.value) topScrollbarRef.value.scrollLeft = wrap.scrollLeft
}

/** 测量表格内容宽度:超出才显示上方滚动条,并挂载底部滚动监听(仅一次) */
const setupTopScrollbar = async () => {
  await nextTick()
  const wrap = getTableWrap()
  const top = topScrollbarRef.value
  if (!wrap || !top) return
  tableScrollWidth.value = wrap.scrollWidth
  showTopScrollbar.value = wrap.scrollWidth > wrap.clientWidth
  if (!wrap.dataset.topbarSync) {
    wrap.addEventListener('scroll', syncFromTable)
    wrap.dataset.topbarSync = '1'
  }
}

watch(orders, () => { setupTopScrollbar() }, { flush: 'post' })
const onWinResize = () => { setupTopScrollbar() }
window.addEventListener('resize', onWinResize)
onUnmounted(() => {
  window.removeEventListener('resize', onWinResize)
  const wrap = getTableWrap()
  if (wrap) wrap.removeEventListener('scroll', syncFromTable)
})

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
        <ElSelect v-model="filters.status" placeholder="订单状态" clearable style="width: 120px" aria-label="筛选订单状态" @change="handleSearch">
          <ElOption v-for="o in statusOptions" :key="o.value" :label="o.label" :value="o.value" />
        </ElSelect>
        <ElSelect v-model="filters.mealType" placeholder="餐次" clearable style="width: 100px" aria-label="筛选餐次" @change="handleSearch">
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
          <!-- 未订餐就餐快捷筛选:点击切换,激活时高亮仅显示未订餐用餐订单(手续费设置入口在菜单管理页) -->
          <ElButton
            :type="filters.orderSource === 1 ? 'warning' : 'default'"
            :icon="Coins"
            @click="toggleUnsolicitedFilter"
          >
            {{ filters.orderSource === 1 ? '已筛选未订餐就餐' : '未订餐就餐' }}
          </ElButton>
          <ElButton :icon="Download" :loading="exporting" @click="handleExport">导出Excel</ElButton>
        </template>
      </SearchBar>

      <div class="card overflow-hidden">
        <!-- 表格上方横向滚动条:与表格底部滚动条双向同步,列多时免拖到底部 -->
        <div
          v-show="showTopScrollbar"
          ref="topScrollbarRef"
          class="table-top-scrollbar"
          @scroll="syncFromTop"
        >
          <div :style="{ width: `${tableScrollWidth}px`, height: '1px' }" />
        </div>
        <ElTable
          ref="tableRef"
          v-loading="loading"
          :data="flatRows"
          style="width: 100%"
          :show-overflow-tooltip="true"
          highlight-current-row
          row-key="key"
          aria-label="订单列表"
          @row-click="(row: any) => openDetail(row._order as OrderRow)"
        >
          <ElTableColumn prop="orderNo" label="订单号" min-width="160" />
          <ElTableColumn prop="date" label="日期" width="120" />
          <ElTableColumn label="姓名" min-width="100">
            <template #default="{ row }">{{ row.employeeName }}</template>
          </ElTableColumn>
          <ElTableColumn label="部门" min-width="110">
            <template #default="{ row }">{{ row.departmentName }}</template>
          </ElTableColumn>
          <ElTableColumn label="会员号" min-width="130">
            <template #default="{ row }">{{ row.cardNo }}</template>
          </ElTableColumn>
          <ElTableColumn label="餐次" width="80" align="center">
            <template #default="{ row }">{{ mealLabel(row.mealType) }}</template>
          </ElTableColumn>
          <ElTableColumn prop="dishName" label="菜名" min-width="140" />
          <ElTableColumn label="单价" width="90" align="right">
            <template #default="{ row }">¥{{ row.price }}</template>
          </ElTableColumn>
          <ElTableColumn label="数量" width="70" align="center">
            <template #default="{ row }">{{ row.quantity }}</template>
          </ElTableColumn>
          <ElTableColumn label="合计价格" width="110" align="right">
            <template #default="{ row }">
              <span class="font-medium tabular-nums text-text">¥{{ (Number(row.price) * Number(row.quantity)).toFixed(2) }}</span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="订单总额" width="100" align="right">
            <template #default="{ row }">
              <span v-if="row.isFirstRow" class="font-medium tabular-nums text-text">¥{{ row.totalAmount }}</span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="结帐时间" width="170" align="center">
            <template #default="{ row }">
              <span class="text-xs tabular-nums">{{ formatCheckoutTime(row) }}</span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="订单状态" width="100" align="center">
            <template #default="{ row }">
              <StatusTag :value="row.status" :map="ORDER_STATUS" />
            </template>
          </ElTableColumn>
          <ElTableColumn label="订单来源" width="110" align="center">
            <template #default="{ row }">
              <StatusTag :value="row.orderSource ?? 0" :map="ORDER_SOURCE" />
            </template>
          </ElTableColumn>
          <ElTableColumn label="操作" width="260" fixed="right" :show-overflow-tooltip="false">
            <template #default="{ row }">
              <template v-if="row.isFirstRow">
                <ElButton size="small" :icon="Eye" @click.stop="openDetail(row._order as OrderRow)">详情</ElButton>
                <ElButton
                  v-if="row.status === 1"
                  size="small"
                  type="success"
                  :icon="CheckCircle2"
                  @click.stop="handleComplete(row._order as OrderRow)"
                >
                  完成
                </ElButton>
                <ElButton
                  v-if="row.status === 1"
                  size="small"
                  type="danger"
                  :icon="XCircle"
                  @click.stop="handleCancel(row._order as OrderRow)"
                >
                  取消
                </ElButton>
              </template>
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
              <ElDescriptionsItem label="状态">
                <StatusTag :value="detail.order.status" :map="ORDER_STATUS" />
              </ElDescriptionsItem>
              <ElDescriptionsItem label="订单来源">
                <StatusTag :value="detail.order.orderSource ?? 0" :map="ORDER_SOURCE" />
              </ElDescriptionsItem>
              <ElDescriptionsItem label="手续费">
                ¥{{ detail.order.serviceFee ?? 0 }}
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
    </PageContainer>
  </Layout>
</template>

<style scoped>
/* 紧凑行样式:减少单元格内边距,避免过多空位 */
:deep(.el-table .el-table__cell) {
  padding: 4px 0;
}
:deep(.el-table .cell) {
  padding: 0 8px;
  line-height: 1.6;
}
/* 操作按钮不换行 */
:deep(.el-table .cell .el-button + .el-button) {
  margin-left: 6px;
}

/* 表格上方横向滚动条(与表格底部滚动条双向同步) */
.table-top-scrollbar {
  overflow-x: auto;
  overflow-y: hidden;
  height: 12px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.table-top-scrollbar::-webkit-scrollbar {
  height: 8px;
}
.table-top-scrollbar::-webkit-scrollbar-thumb {
  background: var(--el-border-color-darker);
  border-radius: 4px;
}
.table-top-scrollbar::-webkit-scrollbar-thumb:hover {
  background: var(--el-text-color-secondary);
}
.table-top-scrollbar::-webkit-scrollbar-track {
  background: transparent;
}
</style>
