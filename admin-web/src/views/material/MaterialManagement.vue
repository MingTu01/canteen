<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import {
  ElButton,
  ElDialog,
  ElDrawer,
  ElForm,
  ElFormItem,
  ElInput,
  ElInputNumber,
  ElOption,
  ElPagination,
  ElSelect,
  ElSwitch,
  ElTable,
  ElTableColumn,
  ElTag,
  ElMessage,
  ElMessageBox,
} from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { Plus, Pencil, Trash2, Package, ArrowUpCircle, ClipboardCheck, RotateCcw } from 'lucide-vue-next'
import Layout from '@/components/Layout.vue'
import PageContainer from '@/components/PageContainer.vue'
import SearchBar from '@/components/SearchBar.vue'
import EmptyState from '@/components/EmptyState.vue'
import { useAuthStore } from '@/stores/auth'
import { materialApi } from '@/api'
import type { Material, PageResult, StockCount } from '@/api/types'

const authStore = useAuthStore()
const sid = computed(() => authStore.storeId || null)

const keyword = ref('')
const lowStockOnly = ref(false)
const page = ref(1)
const size = ref(10)
const total = ref(0)
const loading = ref(false)
const list = ref<Material[]>([])

const fetchList = async () => {
  const sidVal = sid.value
  if (!sidVal) {
    list.value = []
    total.value = 0
    return
  }
  loading.value = true
  try {
    const res = await materialApi.list({
      storeId: sidVal,
      page: page.value,
      size: size.value,
      keyword: keyword.value || undefined,
      lowStock: lowStockOnly.value || undefined,
    })
    const data = res as unknown as PageResult<Material>
    list.value = data.records ?? []
    total.value = data.total ?? 0
  } catch {
    /* 错误已由拦截器统一提示 */
  } finally {
    loading.value = false
  }
}

const isLowStock = (row: Material) =>
  (row.stockQty ?? 0) < (row.minStock ?? 0)

// 新增/编辑弹窗
const dialogVisible = ref(false)
const dialogLoading = ref(false)
const isEdit = ref(false)
const formRef = ref<FormInstance>()
const defaultMaterial = (): Material => ({
  storeId: sid.value ?? 0,
  name: '',
  unit: '公斤',
  stockQty: 0,
  minStock: 0,
  category: '',
})
const form = ref<Material>(defaultMaterial())

const rules: FormRules = {
  name: [{ required: true, message: '请输入食材名称', trigger: 'blur' }],
  unit: [{ required: true, message: '请输入单位', trigger: 'blur' }],
}

const openAdd = () => {
  isEdit.value = false
  form.value = defaultMaterial()
  dialogVisible.value = true
}

const openEdit = (row: Material) => {
  isEdit.value = true
  form.value = { ...row }
  dialogVisible.value = true
}

const handleSave = async () => {
  if (!sid.value) {
    ElMessage.warning('请先选择食堂')
    return
  }
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  // 新建时库存始终为 0(库存统一通过采购入库)
  if (!isEdit.value) {
    form.value.stockQty = 0
  } else {
    // 编辑时不修改库存
    form.value.stockQty = undefined
  }
  dialogLoading.value = true
  try {
    if (isEdit.value && form.value.id) {
      await materialApi.update(form.value.id, form.value)
      ElMessage.success('更新成功')
    } else {
      await materialApi.create(form.value)
      ElMessage.success('创建成功')
    }
    dialogVisible.value = false
    fetchList()
  } catch {
    /* 错误已由拦截器统一提示 */
  } finally {
    dialogLoading.value = false
  }
}

const handleDelete = async (id: number) => {
  try {
    await ElMessageBox.confirm('确定要删除这条食材吗？', '删除确认', {
      type: 'warning',
      confirmButtonText: '确定删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await materialApi.delete(id)
    ElMessage.success('删除成功')
    fetchList()
  } catch {
    /* 错误已由拦截器统一提示 */
  }
}

// 出库弹窗
const stockDialogVisible = ref(false)
const stockDialogLoading = ref(false)
const stockTarget = ref<Material | null>(null)
const stockQty = ref<number>(1)
const stockRemark = ref('')

const openOutbound = (row: Material) => {
  stockTarget.value = row
  stockQty.value = 1
  stockRemark.value = ''
  stockDialogVisible.value = true
}

const handleOutboundSubmit = async () => {
  if (!stockTarget.value?.id) return
  if (!stockQty.value || stockQty.value <= 0) {
    ElMessage.warning('数量必须大于 0')
    return
  }
  stockDialogLoading.value = true
  try {
    await materialApi.outbound(stockTarget.value.id, stockQty.value, stockRemark.value || undefined)
    ElMessage.success('出库成功')
    stockDialogVisible.value = false
    fetchList()
  } catch {
    /* 错误已由拦截器统一提示 */
  } finally {
    stockDialogLoading.value = false
  }
}

// ==================== 库存盘点 ====================

// 盘点弹窗
const stocktakeDialogVisible = ref(false)
const stocktakeLoading = ref(false)
const stocktakeTarget = ref<Material | null>(null)
const stocktakeQty = ref<number>(0)
const stocktakeRemark = ref('')

const openStocktake = (row: Material) => {
  stocktakeTarget.value = row
  stocktakeQty.value = row.stockQty ?? 0
  stocktakeRemark.value = ''
  stocktakeDialogVisible.value = true
}

const stocktakeDiff = computed(() => {
  if (!stocktakeTarget.value) return 0
  return (stocktakeQty.value ?? 0) - (stocktakeTarget.value.stockQty ?? 0)
})

const handleStocktakeSubmit = async () => {
  if (!stocktakeTarget.value?.id) return
  if (stocktakeQty.value == null || stocktakeQty.value < 0) {
    ElMessage.warning('盘点数量不能为负')
    return
  }
  stocktakeLoading.value = true
  try {
    await materialApi.stocktake(stocktakeTarget.value.id, stocktakeQty.value, stocktakeRemark.value || undefined)
    if (stocktakeDiff.value === 0) {
      ElMessage.success('盘点完成,库存无差异')
    } else {
      ElMessage.success('盘点记录已创建,请在盘点记录中查看差异')
    }
    stocktakeDialogVisible.value = false
    fetchList()
  } catch {
    /* 错误已由拦截器统一提示 */
  } finally {
    stocktakeLoading.value = false
  }
}

// 盘点记录抽屉
const stocktakeDrawerVisible = ref(false)
const stocktakeRecords = ref<StockCount[]>([])
const stocktakeLoading2 = ref(false)
const stocktakeStatusFilter = ref<number | undefined>(undefined)
const stocktakePage = ref(1)
const stocktakeSize = ref(10)
const stocktakeTotal = ref(0)

const fetchStocktakeList = async () => {
  const sidVal = sid.value
  if (!sidVal) return
  stocktakeLoading2.value = true
  try {
    const res = await materialApi.stocktakeList(sidVal, stocktakePage.value, stocktakeSize.value, stocktakeStatusFilter.value)
    const data = res as unknown as PageResult<StockCount>
    stocktakeRecords.value = data.records ?? []
    stocktakeTotal.value = data.total ?? 0
  } catch {
    stocktakeRecords.value = []
  } finally {
    stocktakeLoading2.value = false
  }
}

const openStocktakeDrawer = () => {
  stocktakeDrawerVisible.value = true
  stocktakePage.value = 1
  stocktakeStatusFilter.value = undefined
  fetchStocktakeList()
}

const handleResolveStockCount = async (record: StockCount) => {
  if (!record.id) return
  try {
    await ElMessageBox.confirm(
      `确认恢复"${record.materialName}"的库存为盘点数量 ${record.countedQty} 吗?`,
      '恢复差异确认',
      { type: 'warning', confirmButtonText: '确认恢复', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await materialApi.resolveStockCount(record.id)
    ElMessage.success('差异已恢复')
    fetchStocktakeList()
    fetchList()
  } catch {
    /* 错误已由拦截器统一提示 */
  }
}

const handleResolveAll = async () => {
  const sidVal = sid.value
  if (!sidVal) return
  try {
    await ElMessageBox.confirm(
      '确认一次性恢复所有待处理盘点差异吗?此操作将把所有待处理记录的库存调整为盘点数量。',
      '批量恢复确认',
      { type: 'warning', confirmButtonText: '确认批量恢复', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    const res = await materialApi.resolveAllStockCount(sidVal)
    const data = res as unknown as { resolvedCount: number }
    ElMessage.success(`已恢复 ${data.resolvedCount ?? 0} 条差异`)
    fetchStocktakeList()
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
  keyword.value = ''
  lowStockOnly.value = false
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
    <PageContainer title="库存管理" description="维护食材档案与库存,支持出库与盘点操作">
      <template #actions>
        <ElButton type="primary" :icon="Plus" :disabled="!sid" @click="openAdd">新增食材</ElButton>
        <ElButton type="info" :icon="ClipboardCheck" :disabled="!sid" @click="openStocktakeDrawer">盘点记录</ElButton>
      </template>

      <div
        v-if="!sid"
        class="mb-4 rounded-lg border border-amber-300 bg-amber-50 px-4 py-3 text-sm text-amber-700"
      >
        请先选择食堂后再查看数据。
      </div>

      <SearchBar @search="handleSearch" @reset="handleReset">
        <ElInput
          v-model="keyword"
          placeholder="搜索食材名称/分类"
          clearable
          style="width: 240px"
          @keyup.enter="handleSearch"
        />
        <ElSwitch
          v-model="lowStockOnly"
          active-text="仅显示预警"
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
          <ElTableColumn label="食材" min-width="180">
            <template #default="{ row }">
              <div class="flex items-center gap-2">
                <Package class="h-4 w-4 text-primary" />
                <span class="font-medium text-text">{{ row.name }}</span>
              </div>
            </template>
          </ElTableColumn>
          <ElTableColumn label="分类" width="120">
            <template #default="{ row }">
              <ElTag v-if="row.category" size="small" type="info">{{ row.category }}</ElTag>
              <span v-else class="text-text-muted">—</span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="单位" width="90" align="center">
            <template #default="{ row }">
              <span class="text-text-secondary">{{ row.unit || '—' }}</span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="当前库存" width="120" align="right">
            <template #default="{ row }">
              <span class="tabular-nums font-medium" :class="isLowStock(row as Material) ? 'text-danger' : 'text-text'">
                {{ row.stockQty ?? 0 }}
              </span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="预警线" width="100" align="right">
            <template #default="{ row }">
              <span class="tabular-nums text-text-muted">{{ row.minStock ?? 0 }}</span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="状态" width="110" align="center">
            <template #default="{ row }">
              <ElTag v-if="isLowStock(row as Material)" type="danger" size="small" effect="light" round>库存预警</ElTag>
              <ElTag v-else type="success" size="small" effect="light" round>正常</ElTag>
            </template>
          </ElTableColumn>
          <ElTableColumn label="操作" width="280" fixed="right">
            <template #default="{ row }">
              <ElButton size="small" type="warning" :icon="ArrowUpCircle" @click="openOutbound(row as Material)">出库</ElButton>
              <ElButton size="small" type="info" :icon="ClipboardCheck" @click="openStocktake(row as Material)">盘点</ElButton>
              <ElButton size="small" :icon="Pencil" @click="openEdit(row as Material)">编辑</ElButton>
              <ElButton size="small" type="danger" :icon="Trash2" @click="handleDelete(row.id)" />
            </template>
          </ElTableColumn>
          <template #empty>
            <EmptyState description="暂无食材数据" />
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

      <!-- 新增/编辑弹窗 -->
      <ElDialog
        v-model="dialogVisible"
        :title="isEdit ? '编辑食材' : '新增食材'"
        width="520px"
        :close-on-click-modal="false"
        append-to-body
        destroy-on-close
      >
        <ElForm ref="formRef" :model="form" :rules="rules" label-width="100px">
          <ElFormItem label="食材名称" prop="name">
            <ElInput v-model="form.name" placeholder="请输入食材名称" maxlength="100" />
          </ElFormItem>
          <ElFormItem label="分类">
            <ElSelect v-model="form.category" placeholder="请选择或输入分类" clearable filterable allow-create class="w-full">
              <ElOption label="米面粮油" value="米面粮油" />
              <ElOption label="蔬菜" value="蔬菜" />
              <ElOption label="肉类" value="肉类" />
              <ElOption label="禽蛋" value="禽蛋" />
              <ElOption label="水产" value="水产" />
              <ElOption label="调料" value="调料" />
              <ElOption label="干货" value="干货" />
              <ElOption label="其他" value="其他" />
            </ElSelect>
          </ElFormItem>
          <ElFormItem label="单位" prop="unit">
            <ElSelect v-model="form.unit" placeholder="请选择或输入单位" filterable allow-create class="w-full">
              <ElOption label="公斤" value="公斤" />
              <ElOption label="斤" value="斤" />
              <ElOption label="桶" value="桶" />
              <ElOption label="袋" value="袋" />
              <ElOption label="瓶" value="瓶" />
              <ElOption label="个" value="个" />
              <ElOption label="升" value="升" />
            </ElSelect>
          </ElFormItem>
          <ElFormItem label="预警线">
            <ElInputNumber v-model="form.minStock" :min="0" :precision="2" :step="1" controls-position="right" class="w-full" />
            <p class="mt-1 text-xs text-text-muted">库存低于此值时显示红色预警标签</p>
          </ElFormItem>
          <div v-if="!isEdit" class="rounded-lg bg-blue-50 px-3 py-2 text-xs text-blue-600">
            新建食材库存初始为 0,库存通过采购管理入库自动增加
          </div>
        </ElForm>
        <template #footer>
          <ElButton @click="dialogVisible = false">取消</ElButton>
          <ElButton type="primary" :loading="dialogLoading" @click="handleSave">保存</ElButton>
        </template>
      </ElDialog>

      <!-- 出库弹窗 -->
      <ElDialog
        v-model="stockDialogVisible"
        title="出库"
        width="420px"
        :close-on-click-modal="false"
        append-to-body
        destroy-on-close
      >
        <ElForm label-width="90px">
          <ElFormItem label="食材">
            <span class="font-medium text-text">{{ stockTarget?.name }}</span>
            <span class="ml-2 text-xs text-text-muted">当前库存 {{ stockTarget?.stockQty ?? 0 }} {{ stockTarget?.unit || '' }}</span>
          </ElFormItem>
          <ElFormItem label="出库数量">
            <ElInputNumber v-model="stockQty" :min="0.01" :precision="2" :step="1" controls-position="right" class="w-full" />
          </ElFormItem>
          <ElFormItem label="备注">
            <ElInput v-model="stockRemark" type="textarea" :rows="2" placeholder="备注信息(选填)" maxlength="200" />
          </ElFormItem>
        </ElForm>
        <template #footer>
          <ElButton @click="stockDialogVisible = false">取消</ElButton>
          <ElButton type="warning" :loading="stockDialogLoading" @click="handleOutboundSubmit">确定</ElButton>
        </template>
      </ElDialog>

      <!-- 盘点弹窗 -->
      <ElDialog
        v-model="stocktakeDialogVisible"
        title="库存盘点"
        width="480px"
        :close-on-click-modal="false"
        append-to-body
        destroy-on-close
      >
        <ElForm label-width="100px">
          <ElFormItem label="食材">
            <span class="font-medium text-text">{{ stocktakeTarget?.name }}</span>
          </ElFormItem>
          <ElFormItem label="系统库存">
            <span class="tabular-nums text-text-secondary">{{ stocktakeTarget?.stockQty ?? 0 }} {{ stocktakeTarget?.unit || '' }}</span>
          </ElFormItem>
          <ElFormItem label="实际盘点数">
            <ElInputNumber v-model="stocktakeQty" :min="0" :precision="2" :step="1" controls-position="right" class="w-full" />
          </ElFormItem>
          <ElFormItem label="差异">
            <span
              class="tabular-nums font-medium"
              :class="stocktakeDiff > 0 ? 'text-success' : stocktakeDiff < 0 ? 'text-danger' : 'text-text-muted'"
            >
              {{ stocktakeDiff > 0 ? '+' : '' }}{{ stocktakeDiff }} {{ stocktakeTarget?.unit || '' }}
              <span v-if="stocktakeDiff > 0" class="ml-1 text-xs">(盘盈)</span>
              <span v-else-if="stocktakeDiff < 0" class="ml-1 text-xs">(盘亏)</span>
              <span v-else class="ml-1 text-xs">(一致)</span>
            </span>
          </ElFormItem>
          <ElFormItem label="备注">
            <ElInput v-model="stocktakeRemark" type="textarea" :rows="2" placeholder="备注信息(选填)" maxlength="200" />
          </ElFormItem>
        </ElForm>
        <template #footer>
          <ElButton @click="stocktakeDialogVisible = false">取消</ElButton>
          <ElButton type="primary" :loading="stocktakeLoading" @click="handleStocktakeSubmit">确认盘点</ElButton>
        </template>
      </ElDialog>

      <!-- 盘点记录抽屉 -->
      <ElDrawer
        v-model="stocktakeDrawerVisible"
        title="盘点记录"
        size="800px"
        :close-on-click-modal="false"
        append-to-body
        destroy-on-close
      >
        <div class="mb-4 flex items-center justify-between">
          <ElSelect
            v-model="stocktakeStatusFilter"
            placeholder="全部状态"
            clearable
            style="width: 140px"
            @change="() => { stocktakePage = 1; fetchStocktakeList() }"
          >
            <ElOption label="待处理" :value="1" />
            <ElOption label="已处理" :value="2" />
          </ElSelect>
          <ElButton type="warning" :icon="RotateCcw" @click="handleResolveAll">批量恢复差异</ElButton>
        </div>

        <ElTable
          v-loading="stocktakeLoading2"
          :data="stocktakeRecords"
          style="width: 100%"
          :show-overflow-tooltip="true"
          row-key="id"
        >
          <ElTableColumn label="食材" min-width="140">
            <template #default="{ row }">
              <span class="font-medium text-text">{{ row.materialName || '—' }}</span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="系统库存" width="110" align="right">
            <template #default="{ row }">
              <span class="tabular-nums text-text-secondary">{{ row.systemQty ?? 0 }}</span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="盘点数量" width="110" align="right">
            <template #default="{ row }">
              <span class="tabular-nums font-medium text-text">{{ row.countedQty ?? 0 }}</span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="差异" width="120" align="right">
            <template #default="{ row }">
              <span
                class="tabular-nums font-medium"
                :class="(row.difference ?? 0) > 0 ? 'text-success' : (row.difference ?? 0) < 0 ? 'text-danger' : 'text-text-muted'"
              >
                {{ (row.difference ?? 0) > 0 ? '+' : '' }}{{ row.difference ?? 0 }}
              </span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="状态" width="100" align="center">
            <template #default="{ row }">
              <ElTag v-if="row.status === 1" type="warning" size="small" effect="light" round>待处理</ElTag>
              <ElTag v-else type="success" size="small" effect="light" round>已处理</ElTag>
            </template>
          </ElTableColumn>
          <ElTableColumn label="时间" width="170">
            <template #default="{ row }">
              <span class="text-xs text-text-muted">{{ row.createdAt || '—' }}</span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="操作" width="110" fixed="right">
            <template #default="{ row }">
              <ElButton
                v-if="row.status === 1"
                size="small"
                type="warning"
                :icon="RotateCcw"
                @click="handleResolveStockCount(row as StockCount)"
              >恢复</ElButton>
              <span v-else class="text-xs text-text-muted">—</span>
            </template>
          </ElTableColumn>
          <template #empty>
            <EmptyState description="暂无盘点记录" />
          </template>
        </ElTable>

        <div class="mt-4 flex flex-wrap items-center justify-between gap-2">
          <span class="text-xs text-text-muted">共 {{ stocktakeTotal }} 条</span>
          <ElPagination
            v-model:current-page="stocktakePage"
            v-model:page-size="stocktakeSize"
            :page-sizes="[10, 20, 50]"
            :total="stocktakeTotal"
            layout="total, sizes, prev, pager, next"
            background
            @current-change="fetchStocktakeList"
            @size-change="(s: number) => { stocktakeSize = s; stocktakePage = 1; fetchStocktakeList() }"
          />
        </div>
      </ElDrawer>
    </PageContainer>
  </Layout>
</template>
