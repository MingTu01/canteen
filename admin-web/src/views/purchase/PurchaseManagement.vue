<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import {
  ElButton,
  ElDatePicker,
  ElDialog,
  ElDrawer,
  ElForm,
  ElFormItem,
  ElInput,
  ElInputNumber,
  ElOption,
  ElPagination,
  ElSelect,
  ElTable,
  ElTableColumn,
  ElMessage,
  ElMessageBox,
} from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { Plus, Eye, Trash2, CheckCircle2, XCircle, ShoppingCart } from 'lucide-vue-next'
import Layout from '@/components/Layout.vue'
import PageContainer from '@/components/PageContainer.vue'
import SearchBar from '@/components/SearchBar.vue'
import StatusTag from '@/components/StatusTag.vue'
import EmptyState from '@/components/EmptyState.vue'
import { useAuthStore } from '@/stores/auth'
import { purchaseApi, supplierApi, materialApi } from '@/api'
import type { Purchase, PurchaseItem, Supplier, PurchaseDetail, Material, PageResult } from '@/api/types'

const authStore = useAuthStore()
const sid = computed(() => authStore.storeId || null)

// 采购单状态字典
const PURCHASE_STATUS: Record<number, { label: string; type: 'warning' | 'success' | 'info' }> = {
  1: { label: '待入库', type: 'warning' },
  2: { label: '已入库', type: 'success' },
  3: { label: '已取消', type: 'info' },
}

// 筛选
const statusFilter = ref<number | undefined>(undefined)
const dateRange = ref<[string, string] | null>(null)
const page = ref(1)
const size = ref(10)
const total = ref(0)
const loading = ref(false)
const list = ref<Purchase[]>([])

const fetchList = async () => {
  const sidVal = sid.value
  if (!sidVal) {
    list.value = []
    total.value = 0
    return
  }
  loading.value = true
  try {
    const res = await purchaseApi.list({
      storeId: sidVal,
      page: page.value,
      size: size.value,
      status: statusFilter.value,
      startDate: dateRange.value?.[0] || undefined,
      endDate: dateRange.value?.[1] || undefined,
    })
    const data = res as unknown as PageResult<Purchase>
    list.value = data.records ?? []
    total.value = data.total ?? 0
  } catch {
    /* 错误已由拦截器统一提示 */
  } finally {
    loading.value = false
  }
}

// 供应商列表(用于创建采购单下拉)
const suppliers = ref<Supplier[]>([])
const loadSuppliers = async () => {
  const sidVal = sid.value
  if (!sidVal) {
    suppliers.value = []
    return
  }
  try {
    suppliers.value = await supplierApi.activeList(sidVal)
  } catch {
    suppliers.value = []
  }
}

// 食材列表(用于采购明细下拉,入库时自动增加对应食材库存)
const materials = ref<Material[]>([])
const loadMaterials = async () => {
  const sidVal = sid.value
  if (!sidVal) {
    materials.value = []
    return
  }
  try {
    const res = await materialApi.list({ storeId: sidVal, page: 1, size: 500 })
    const data = res as unknown as PageResult<Material>
    materials.value = data.records ?? []
  } catch {
    materials.value = []
  }
}

/** 选择/输入食材时:已有食材填充 materialId+name+unit;新食材清空 materialId 只保留 name */
const onMaterialSelect = (row: PurchaseItem, val: number | string) => {
  if (typeof val === 'number') {
    const m = materials.value.find((it) => it.id === val)
    if (m) {
      row.materialId = m.id
      row.materialName = m.name
      row.unit = m.unit || '公斤'
      return
    }
  }
  // 新食材:val 是用户输入的名称字符串
  row.materialId = undefined
  row.materialName = String(val)
}

// 创建弹窗
const createDialogVisible = ref(false)
const createLoading = ref(false)
const formRef = ref<FormInstance>()
const defaultPurchase = (): Purchase => ({
  storeId: sid.value ?? 0,
  supplierId: 0,
  purchaseDate: new Date().toISOString().slice(0, 10),
  remark: '',
})
const form = ref<Purchase>(defaultPurchase())
const items = ref<PurchaseItem[]>([])

const rules: FormRules = {
  supplierId: [{ required: true, message: '请选择供应商', trigger: 'change' }],
  purchaseDate: [{ required: true, message: '请选择采购日期', trigger: 'change' }],
}

const defaultItem = (): PurchaseItem => ({
  materialId: undefined,
  materialName: '',
  unit: '公斤',
  quantity: 1,
  price: 0,
})

const addItem = () => {
  items.value.push(defaultItem())
}

const removeItem = (index: number) => {
  items.value.splice(index, 1)
}

const totalAmount = computed(() =>
  items.value.reduce((sum, it) => sum + (Number(it.quantity) || 0) * (Number(it.price) || 0), 0)
)

const openCreate = () => {
  form.value = defaultPurchase()
  items.value = [defaultItem()]
  createDialogVisible.value = true
  loadSuppliers()
  loadMaterials()
}

const handleCreate = async () => {
  if (!sid.value) {
    ElMessage.warning('请先选择食堂')
    return
  }
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  if (!form.value.supplierId || Number(form.value.supplierId) <= 0) {
    ElMessage.warning('请选择供应商')
    return
  }
  const validItems = items.value.filter((it) => it.materialId || it.materialName)
  if (validItems.length === 0) {
    ElMessage.warning('请至少添加一条采购明细并选择或输入食材')
    return
  }
  for (const it of validItems) {
    if (!it.quantity || it.quantity <= 0) {
      ElMessage.warning('数量必须大于 0')
      return
    }
    if (!it.price || it.price < 0) {
      ElMessage.warning('单价不能为负')
      return
    }
  }
  createLoading.value = true
  try {
    await purchaseApi.create({
      purchase: { ...form.value, supplierId: Number(form.value.supplierId) },
      items: validItems.map((it) => ({
        ...it,
        quantity: Number(it.quantity),
        price: Number(it.price),
      })),
    })
    ElMessage.success('采购单创建成功')
    createDialogVisible.value = false
    fetchList()
  } catch {
    /* 错误已由拦截器统一提示 */
  } finally {
    createLoading.value = false
  }
}

// 详情抽屉
const detailDrawerVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<PurchaseDetail | null>(null)

const openDetail = async (row: Purchase) => {
  if (!row.id) return
  detailDrawerVisible.value = true
  detailLoading.value = true
  try {
    detail.value = await purchaseApi.detail(row.id)
  } catch {
    detail.value = null
  } finally {
    detailLoading.value = false
  }
}

// 状态操作
const handleInbound = async (row: Purchase) => {
  if (!row.id) return
  try {
    await ElMessageBox.confirm(
      `确认将采购单 ${row.purchaseNo} 标记为已入库吗?入库后无法再修改。`,
      '入库确认',
      { type: 'warning', confirmButtonText: '确认入库', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await purchaseApi.updateStatus(row.id, 2)
    ElMessage.success('已入库')
    fetchList()
  } catch {
    /* 错误已由拦截器统一提示 */
  }
}

const handleCancel = async (row: Purchase) => {
  if (!row.id) return
  try {
    await ElMessageBox.confirm(
      `确认取消采购单 ${row.purchaseNo} 吗?`,
      '取消确认',
      { type: 'warning', confirmButtonText: '确认取消', cancelButtonText: '返回' }
    )
  } catch {
    return
  }
  try {
    await purchaseApi.updateStatus(row.id, 3)
    ElMessage.success('已取消')
    fetchList()
  } catch {
    /* 错误已由拦截器统一提示 */
  }
}

const handleDelete = async (row: Purchase) => {
  if (!row.id) return
  try {
    await ElMessageBox.confirm(
      `确认删除采购单 ${row.purchaseNo} 吗?仅待入库状态可删除。`,
      '删除确认',
      { type: 'warning', confirmButtonText: '确定删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await purchaseApi.delete(row.id)
    ElMessage.success('删除成功')
    fetchList()
  } catch {
    /* 错误已由拦截器统一提示 */
  }
}

const handleSearch = () => {
  page.value = 1
  fetchList()
}

const handleReset = () => {
  statusFilter.value = undefined
  dateRange.value = null
  page.value = 1
  fetchList()
}

watch(sid, () => {
  page.value = 1
  fetchList()
})

onMounted(fetchList)
</script>

<template>
  <Layout>
    <PageContainer title="采购管理" description="记录采购单、供应商与明细,支持入库/取消操作">
      <template #actions>
        <ElButton
          type="primary"
          :icon="Plus"
          :disabled="!sid"
          @click="openCreate"
        >新增采购单</ElButton>
      </template>

      <div
        v-if="!sid"
        class="mb-4 rounded-lg border border-amber-300 bg-amber-50 px-4 py-3 text-sm text-amber-700"
      >
        请先选择食堂后再查看数据。
      </div>

      <SearchBar @search="handleSearch" @reset="handleReset">
        <ElSelect
          v-model="statusFilter"
          placeholder="全部状态"
          clearable
          style="width: 140px"
          @change="handleSearch"
        >
          <ElOption label="待入库" :value="1" />
          <ElOption label="已入库" :value="2" />
          <ElOption label="已取消" :value="3" />
        </ElSelect>
        <ElDatePicker
          v-model="dateRange"
          type="daterange"
          range-separator="至"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          value-format="YYYY-MM-DD"
          style="width: 260px"
          @change="handleSearch"
        />
      </SearchBar>

      <div class="card overflow-hidden">
        <ElTable
          v-loading="loading"
          :data="list"
          style="width: 100%"
          :show-overflow-tooltip="true"
          row-key="id"
        >
          <ElTableColumn label="采购单号" min-width="180">
            <template #default="{ row }">
              <div class="flex items-center gap-2">
                <ShoppingCart class="h-4 w-4 text-primary" />
                <span class="font-mono font-medium text-text">{{ row.purchaseNo }}</span>
              </div>
            </template>
          </ElTableColumn>
          <ElTableColumn label="供应商" min-width="160">
            <template #default="{ row }">
              <span class="text-text">{{ row.supplierName || '—' }}</span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="采购日期" width="120" align="center">
            <template #default="{ row }">
              <span class="tabular-nums text-text-secondary">{{ row.purchaseDate || '—' }}</span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="总金额" width="120" align="right">
            <template #default="{ row }">
              <span class="font-medium tabular-nums text-text">¥{{ row.totalAmount ?? 0 }}</span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="操作人" width="120">
            <template #default="{ row }">
              <span class="text-text-secondary">{{ row.operatorName || '—' }}</span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="状态" width="100" align="center">
            <template #default="{ row }">
              <StatusTag :value="row.status ?? 1" :map="PURCHASE_STATUS" />
            </template>
          </ElTableColumn>
          <ElTableColumn label="操作" width="280" fixed="right">
            <template #default="{ row }">
              <ElButton size="small" :icon="Eye" @click="openDetail(row as Purchase)">详情</ElButton>
              <ElButton
                v-if="row.status === 1"
                size="small"
                type="success"
                :icon="CheckCircle2"
                @click="handleInbound(row as Purchase)"
              >入库</ElButton>
              <ElButton
                v-if="row.status === 1"
                size="small"
                type="warning"
                :icon="XCircle"
                @click="handleCancel(row as Purchase)"
              >取消</ElButton>
              <ElButton
                v-if="row.status === 1"
                size="small"
                type="danger"
                :icon="Trash2"
                @click="handleDelete(row as Purchase)"
              />
            </template>
          </ElTableColumn>
          <template #empty>
            <EmptyState description="暂无采购单数据" />
          </template>
        </ElTable>

        <div class="flex flex-wrap items-center justify-between gap-2 border-t border-border px-4 py-3">
          <span class="text-xs text-text-muted">共 {{ total }} 条</span>
          <ElPagination
            v-model:current-page="page"
            v-model:page-size="size"
            :page-sizes="[10, 20, 50, 100]"
            :total="total"
            layout="total, sizes, prev, pager, next, jumper"
            background
            @current-change="fetchList"
            @size-change="(s: number) => { size = s; page = 1; fetchList() }"
          />
        </div>
      </div>

      <!-- 新增采购单弹窗 -->
      <ElDialog
        v-model="createDialogVisible"
        title="新增采购单"
        width="900px"
        :close-on-click-modal="false"
        append-to-body
        destroy-on-close
      >
        <ElForm ref="formRef" :model="form" :rules="rules" label-width="100px">
          <div class="grid grid-cols-1 gap-x-4 sm:grid-cols-2">
            <ElFormItem label="供应商" prop="supplierId">
              <ElSelect v-model="form.supplierId" placeholder="请选择供应商" filterable class="w-full">
                <ElOption
                  v-for="s in suppliers"
                  :key="s.id"
                  :label="s.name"
                  :value="s.id as number"
                />
              </ElSelect>
            </ElFormItem>
            <ElFormItem label="采购日期" prop="purchaseDate">
              <ElDatePicker
                v-model="form.purchaseDate"
                type="date"
                placeholder="请选择日期"
                value-format="YYYY-MM-DD"
                class="w-full"
              />
            </ElFormItem>
          </div>
          <ElFormItem label="备注">
            <ElInput v-model="form.remark" type="textarea" :rows="2" placeholder="备注信息(选填)" maxlength="500" />
          </ElFormItem>

          <!-- 明细表格 -->
          <div class="mb-2 flex items-center justify-between">
            <span class="text-sm font-medium text-text">采购明细</span>
            <ElButton size="small" type="primary" :icon="Plus" @click="addItem">添加行</ElButton>
          </div>
          <ElTable :data="items" border style="width: 100%" size="small">
            <ElTableColumn label="序号" type="index" width="55" align="center" />
            <ElTableColumn label="食材" min-width="200">
              <template #default="{ row }">
                <ElSelect
                  v-model="row.materialId"
                  placeholder="选择或输入新食材"
                  filterable
                  allow-create
                  default-first-option
                  size="small"
                  class="w-full"
                  @change="(val: number | string) => onMaterialSelect(row as PurchaseItem, val)"
                >
                  <ElOption
                    v-for="m in materials"
                    :key="m.id"
                    :label="m.name + (m.category ? ' (' + m.category + ')' : '')"
                    :value="m.id as number"
                  />
                </ElSelect>
              </template>
            </ElTableColumn>
            <ElTableColumn label="单位" width="110">
              <template #default="{ row }">
                <ElSelect v-model="row.unit" size="small" filterable allow-create>
                  <ElOption label="公斤" value="公斤" />
                  <ElOption label="斤" value="斤" />
                  <ElOption label="桶" value="桶" />
                  <ElOption label="袋" value="袋" />
                  <ElOption label="瓶" value="瓶" />
                  <ElOption label="个" value="个" />
                  <ElOption label="升" value="升" />
                </ElSelect>
              </template>
            </ElTableColumn>
            <ElTableColumn label="数量" width="120">
              <template #default="{ row }">
                <ElInputNumber v-model="row.quantity" :min="0.01" :precision="2" :step="1" :controls="false" size="small" class="w-full" />
              </template>
            </ElTableColumn>
            <ElTableColumn label="单价(¥)" width="120">
              <template #default="{ row }">
                <ElInputNumber v-model="row.price" :min="0" :precision="2" :step="0.5" :controls="false" size="small" class="w-full" />
              </template>
            </ElTableColumn>
            <ElTableColumn label="小计(¥)" width="110" align="right">
              <template #default="{ row }">
                <span class="tabular-nums">{{ ((Number(row.quantity) || 0) * (Number(row.price) || 0)).toFixed(2) }}</span>
              </template>
            </ElTableColumn>
            <ElTableColumn label="操作" width="80" align="center">
              <template #default="{ $index }">
                <ElButton size="small" type="danger" :icon="Trash2" link @click="removeItem($index)" />
              </template>
            </ElTableColumn>
          </ElTable>
          <div class="mt-3 flex justify-end">
            <span class="text-sm text-text-muted">合计:</span>
            <span class="ml-2 text-base font-bold tabular-nums text-primary">¥{{ totalAmount.toFixed(2) }}</span>
          </div>
        </ElForm>
        <template #footer>
          <ElButton @click="createDialogVisible = false">取消</ElButton>
          <ElButton type="primary" :loading="createLoading" @click="handleCreate">提交</ElButton>
        </template>
      </ElDialog>

      <!-- 详情抽屉 -->
      <ElDrawer
        v-model="detailDrawerVisible"
        title="采购单详情"
        direction="rtl"
        size="600px"
      >
        <div v-loading="detailLoading">
          <template v-if="detail">
            <div class="mb-6 rounded-lg border border-border bg-bg-secondary p-4">
              <div class="mb-3 flex items-center justify-between">
                <span class="font-mono text-base font-bold text-text">{{ detail.purchase.purchaseNo }}</span>
                <StatusTag :value="detail.purchase.status ?? 1" :map="PURCHASE_STATUS" />
              </div>
              <div class="grid grid-cols-2 gap-3 text-sm">
                <div>
                  <span class="text-text-muted">供应商:</span>
                  <span class="ml-2 text-text">{{ detail.purchase.supplierName || '—' }}</span>
                </div>
                <div>
                  <span class="text-text-muted">采购日期:</span>
                  <span class="ml-2 text-text">{{ detail.purchase.purchaseDate || '—' }}</span>
                </div>
                <div>
                  <span class="text-text-muted">操作人:</span>
                  <span class="ml-2 text-text">{{ detail.purchase.operatorName || '—' }}</span>
                </div>
                <div>
                  <span class="text-text-muted">创建时间:</span>
                  <span class="ml-2 text-text">{{ detail.purchase.createdAt || '—' }}</span>
                </div>
                <div class="col-span-2">
                  <span class="text-text-muted">备注:</span>
                  <span class="ml-2 text-text">{{ detail.purchase.remark || '—' }}</span>
                </div>
              </div>
            </div>

            <div class="mb-2 text-sm font-medium text-text">采购明细</div>
            <ElTable :data="detail.items" border style="width: 100%" size="small">
              <ElTableColumn label="序号" type="index" width="55" align="center" />
              <ElTableColumn prop="materialName" label="食材名称" min-width="140" />
              <ElTableColumn prop="unit" label="单位" width="80" align="center" />
              <ElTableColumn label="数量" width="90" align="right">
                <template #default="{ row }">
                  <span class="tabular-nums">{{ row.quantity }}</span>
                </template>
              </ElTableColumn>
              <ElTableColumn label="单价" width="100" align="right">
                <template #default="{ row }">
                  <span class="tabular-nums">¥{{ row.price }}</span>
                </template>
              </ElTableColumn>
              <ElTableColumn label="小计" width="110" align="right">
                <template #default="{ row }">
                  <span class="tabular-nums">¥{{ row.amount }}</span>
                </template>
              </ElTableColumn>
            </ElTable>
            <div class="mt-3 flex justify-end">
              <span class="text-sm text-text-muted">合计:</span>
              <span class="ml-2 text-base font-bold tabular-nums text-primary">¥{{ detail.purchase.totalAmount ?? 0 }}</span>
            </div>
          </template>
          <EmptyState v-else description="暂无详情数据" />
        </div>
      </ElDrawer>
    </PageContainer>
  </Layout>
</template>
