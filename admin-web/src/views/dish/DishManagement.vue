<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import {
  ElButton,
  ElCheckboxGroup,
  ElCheckbox,
  ElDialog,
  ElForm,
  ElFormItem,
  ElImage,
  ElInput,
  ElInputNumber,
  ElMessageBox,
  ElOption,
  ElPagination,
  ElSelect,
  ElSwitch,
  ElTable,
  ElTableColumn,
  ElTag,
  ElMessage,
} from 'element-plus'
import type { FormInstance, FormRules, TableInstance } from 'element-plus'
import {
  Plus,
  Image as ImageIcon,
  Pencil,
  Trash2,
  ArrowUpCircle,
  ArrowDownCircle,
  Archive,
  RotateCcw,
} from 'lucide-vue-next'
import Layout from '@/components/Layout.vue'
import PageContainer from '@/components/PageContainer.vue'
import SearchBar from '@/components/SearchBar.vue'
import StatusTag from '@/components/StatusTag.vue'
import EmptyState from '@/components/EmptyState.vue'
import ImageUploader from '@/components/ImageUploader.vue'
import { useCrud } from '@/composables/useCrud'
import { useAuthStore } from '@/stores/auth'
import { dishApi } from '@/api'
import type { Dish } from '@/api/types'
import { COMMON_STATUS, MEAL_TYPE } from '@/constants/dict'

const authStore = useAuthStore()
// 超管未选择食堂时返回 null,不再静默回退到 storeId=1
const sid = computed(() => authStore.storeId || null)

// 餐次标记
const mealTypeOptions = Object.entries(MEAL_TYPE).map(([k, v]) => ({
  value: Number(k),
  label: v.label,
}))

const parseMealTypes = (s?: string): number[] => {
  if (!s) return [1, 2, 3]
  return s.split(',').map((x) => Number(x)).filter((x) => !isNaN(x))
}

const mealTypeTags = (s?: string) => {
  const arr = parseMealTypes(s)
  return arr.map((t) => mealTypeOptions.find((o) => o.value === t)?.label || '').filter(Boolean)
}

const keyword = ref('')
const mealTypeFilter = ref<number | ''>('')
const page = ref(1)
const size = ref(10)
const total = ref(0)

const { list: dishes, loading, fetchList, handleDelete, dialogVisible, dialogLoading, isEdit } = useCrud<Dish>({
  list: async () => {
    const sidVal = sid.value
    if (!sidVal) return []
    const res = await dishApi.list({
      storeId: sidVal,
      page: page.value,
      size: size.value,
      keyword: keyword.value,
      mealType: mealTypeFilter.value || undefined,
    })
    total.value = res.total ?? res.records.length
    return res.records
  },
  create: (d) => dishApi.create(d),
  update: (id, d) => dishApi.update(id, d),
  remove: (id) => dishApi.delete(id),
  entityName: '菜品',
})

const formRef = ref<FormInstance>()
const defaultDish = (): Dish => ({
  storeId: sid.value ?? 0,
  name: '',
  price: 0,
  mealTypes: '1,2,3',
  image: '',
  stock: null,
  maxPerOrder: null,
  isNew: 0,
  status: 1,
})
const form = ref<Dish>(defaultDish())
const formMealTypes = ref<number[]>([1, 2, 3])

// ElInputNumber 期望 number | undefined,而 Dish.stock/maxPerOrder 允许 null(表示不限)。
// 通过 computed 代理在 null 与 undefined 之间转换,避免类型冲突。
const formStock = computed<number | undefined>({
  get: () => (form.value.stock ?? undefined),
  set: (v) => {
    form.value.stock = v ?? null
  },
})
const formMaxPerOrder = computed<number | undefined>({
  get: () => (form.value.maxPerOrder ?? undefined),
  set: (v) => {
    form.value.maxPerOrder = v ?? null
  },
})

const rules: FormRules = {
  name: [{ required: true, message: '请输入菜品名称', trigger: 'blur' }],
  price: [{ required: true, message: '请输入价格', trigger: 'blur' }],
}

const openAdd = () => {
  isEdit.value = false
  form.value = defaultDish()
  formMealTypes.value = parseMealTypes(form.value.mealTypes)
  dialogVisible.value = true
}

const openEdit = (row: Dish) => {
  isEdit.value = true
  form.value = { ...row }
  formMealTypes.value = parseMealTypes(row.mealTypes)
  dialogVisible.value = true
}

const handleSave = async () => {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  if (formMealTypes.value.length === 0) {
    ElMessage.warning('请至少选择一个适用餐次')
    return
  }
  form.value.mealTypes = formMealTypes.value.join(',')
  dialogLoading.value = true
  try {
    if (isEdit.value && form.value.id) {
      await dishApi.update(form.value.id, form.value)
      ElMessage.success('更新成功')
    } else {
      await dishApi.create(form.value)
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

const handleToggleStatus = async (row: Dish) => {
  if (!row.id) return
  try {
    await dishApi.toggleStatus(row.id)
    ElMessage.success(row.status === 1 ? '已下架' : '已上架')
    fetchList()
  } catch {
    /* 错误已由拦截器统一提示 */
  }
}

// ==================== 批量操作 ====================
const tableRef = ref<TableInstance>()
const selectedDishes = ref<Dish[]>([])
const selectedIds = computed(() =>
  selectedDishes.value.map((d) => d.id).filter((id): id is number => id != null)
)

const handleSelectionChange = (selection: Dish[]) => {
  selectedDishes.value = selection
}

const clearSelection = () => {
  tableRef.value?.clearSelection()
}

const handleBatchStatus = async (status: number) => {
  const sidVal = sid.value
  if (!sidVal || selectedIds.value.length === 0) return
  try {
    await dishApi.batchUpdateStatus({
      dishIds: selectedIds.value,
      status,
      storeId: sidVal,
    })
    ElMessage.success(status === 1 ? '批量上架成功' : '批量下架成功')
    clearSelection()
    fetchList()
  } catch {
    /* 错误已由拦截器统一提示 */
  }
}

const handleBatchDelete = async () => {
  const sidVal = sid.value
  if (!sidVal || selectedIds.value.length === 0) return
  try {
    await ElMessageBox.confirm(
      `确定要删除选中的 ${selectedIds.value.length} 个菜品吗？删除后可在回收站恢复。`,
      '批量删除确认',
      { type: 'warning', confirmButtonText: '确定删除', cancelButtonText: '取消' }
    )
  } catch {
    return /* 用户取消 */
  }
  try {
    await dishApi.batchDelete({
      dishIds: selectedIds.value,
      storeId: sidVal,
    })
    ElMessage.success('批量删除成功')
    clearSelection()
    fetchList()
  } catch {
    /* 错误已由拦截器统一提示 */
  }
}

// ==================== 回收站 ====================
const trashDialogVisible = ref(false)
const trashList = ref<Dish[]>([])
const trashLoading = ref(false)
const trashPage = ref(1)
const trashSize = ref(10)
const trashTotal = ref(0)

const loadTrash = async () => {
  const sidVal = sid.value
  if (!sidVal) return
  trashLoading.value = true
  try {
    const res = await dishApi.trash({
      storeId: sidVal,
      page: trashPage.value,
      size: trashSize.value,
    })
    trashList.value = res.records
    trashTotal.value = res.total
  } catch {
    /* 错误已由拦截器统一提示 */
  } finally {
    trashLoading.value = false
  }
}

const openTrash = () => {
  trashDialogVisible.value = true
  trashPage.value = 1
  loadTrash()
}

const handleTrashPageChange = (p: number) => {
  trashPage.value = p
  loadTrash()
}

const handleTrashSizeChange = (s: number) => {
  trashSize.value = s
  trashPage.value = 1
  loadTrash()
}

const handleRestore = async (row: Dish) => {
  if (!row.id) return
  try {
    await dishApi.restore(row.id)
    ElMessage.success('已恢复')
    loadTrash()
    fetchList()
  } catch {
    /* 错误已由拦截器统一提示 */
  }
}

const handlePurge = async (row: Dish) => {
  if (!row.id) return
  try {
    await ElMessageBox.confirm(
      `确定要彻底删除「${row.name}」吗？此操作不可恢复！`,
      '彻底删除确认',
      { type: 'error', confirmButtonText: '彻底删除', cancelButtonText: '取消' }
    )
  } catch {
    return /* 用户取消 */
  }
  try {
    await dishApi.purge(row.id)
    ElMessage.success('已彻底删除')
    loadTrash()
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
  mealTypeFilter.value = ''
  page.value = 1
  fetchList()
}

const handlePageChange = (p: number) => {
  page.value = p
  fetchList()
}

// 食堂切换时重新加载菜品列表
watch(sid, () => {
  page.value = 1
  fetchList()
})

onMounted(() => {
  fetchList()
})
</script>

<template>
  <Layout>
    <PageContainer title="菜品管理" description="维护食堂菜品信息、适用餐次与上下架状态">
      <template #actions>
        <ElButton type="primary" :icon="Plus" @click="openAdd">添加菜品</ElButton>
        <ElButton :icon="Archive" @click="openTrash">回收站</ElButton>
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
          placeholder="搜索菜品名称"
          clearable
          style="width: 200px"
          aria-label="搜索菜品名称"
          @keyup.enter="handleSearch"
        />
        <ElSelect
          v-model="mealTypeFilter"
          placeholder="全部餐次"
          clearable
          style="width: 180px"
          aria-label="筛选餐次"
          @change="handleSearch"
        >
          <ElOption
            v-for="opt in mealTypeOptions"
            :key="opt.value"
            :label="opt.label"
            :value="opt.value"
          />
        </ElSelect>
      </SearchBar>

      <div class="card overflow-hidden">
        <div
          v-if="selectedDishes.length > 0"
          class="flex flex-wrap items-center gap-2 border-b border-border bg-blue-50 px-4 py-2"
        >
          <span class="text-sm text-blue-700">已选 {{ selectedDishes.length }} 项</span>
          <ElButton size="small" type="success" :icon="ArrowUpCircle" @click="handleBatchStatus(1)">批量上架</ElButton>
          <ElButton size="small" type="warning" :icon="ArrowDownCircle" @click="handleBatchStatus(0)">批量下架</ElButton>
          <ElButton size="small" type="danger" :icon="Trash2" @click="handleBatchDelete">批量删除</ElButton>
          <ElButton size="small" text @click="clearSelection">取消选择</ElButton>
        </div>
        <ElTable
          ref="tableRef"
          v-loading="loading"
          :data="dishes"
          style="width: 100%"
          :show-overflow-tooltip="true"
          row-key="id"
          aria-label="菜品列表"
          @selection-change="handleSelectionChange"
        >
          <ElTableColumn type="selection" width="55" fixed="left" />
          <ElTableColumn label="图片" width="80" align="center">
            <template #default="{ row }">
              <ElImage
                v-if="row.image"
                :src="row.image"
                :preview-src-list="[row.image]"
                preview-teleported
                fit="cover"
                class="h-10 w-10 rounded-lg"
              />
              <div
                v-else
                class="mx-auto flex h-10 w-10 items-center justify-center rounded-lg bg-bg-tertiary text-text-muted"
              >
                <ImageIcon class="h-4 w-4" />
              </div>
            </template>
          </ElTableColumn>
          <ElTableColumn prop="name" label="名称" min-width="160" />
          <ElTableColumn label="适用餐次" width="200" align="center">
            <template #default="{ row }">
              <div class="flex flex-wrap justify-center gap-1">
                <ElTag
                  v-for="t in mealTypeTags(row.mealTypes)"
                  :key="t"
                  size="small"
                  type="warning"
                >
                  {{ t }}
                </ElTag>
              </div>
            </template>
          </ElTableColumn>
          <ElTableColumn label="价格" width="120" align="right">
            <template #default="{ row }">
              <span class="font-medium tabular-nums text-text">¥{{ row.price }}</span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="库存" width="100" align="right">
            <template #default="{ row }">
              <span v-if="row.stock === null || row.stock === 0 || row.stock === undefined" class="text-text-muted">不限</span>
              <span v-else class="tabular-nums">{{ row.stock }}</span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="单次限购" width="100" align="right">
            <template #default="{ row }">
              <span v-if="row.maxPerOrder === null || row.maxPerOrder === 0 || row.maxPerOrder === undefined" class="text-text-muted">不限</span>
              <span v-else class="tabular-nums">{{ row.maxPerOrder }}</span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="状态" width="100" align="center">
            <template #default="{ row }">
              <StatusTag :value="row.status" :map="COMMON_STATUS" />
            </template>
          </ElTableColumn>
          <ElTableColumn label="操作" width="220" fixed="right">
            <template #default="{ row }">
              <ElButton size="small" :icon="Pencil" @click="openEdit(row as Dish)">编辑</ElButton>
              <ElButton
                size="small"
                :type="row.status === 1 ? 'warning' : 'success'"
                :icon="row.status === 1 ? ArrowDownCircle : ArrowUpCircle"
                @click="handleToggleStatus(row as Dish)"
              >
                {{ row.status === 1 ? '下架' : '上架' }}
              </ElButton>
              <ElButton size="small" type="danger" :icon="Trash2" aria-label="删除菜品" @click="handleDelete(row.id)" />
            </template>
          </ElTableColumn>
          <template #empty>
            <EmptyState description="暂无菜品数据" />
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
            @current-change="handlePageChange"
            @size-change="(s: number) => { size = s; page = 1; fetchList() }"
          />
        </div>
      </div>

      <!-- 新增/编辑弹窗 -->
      <ElDialog
        v-model="dialogVisible"
        :title="isEdit ? '编辑菜品' : '新增菜品'"
        width="560px"
        :close-on-click-modal="false"
        append-to-body
        destroy-on-close
      >
        <ElForm ref="formRef" :model="form" :rules="rules" label-width="90px">
          <ElFormItem label="图片">
            <ImageUploader v-model="form.image" label="菜品图片" hint="菜品展示图,建议正方形,自动压缩到 200KB 以内" />
          </ElFormItem>
          <ElFormItem label="名称" prop="name">
            <ElInput v-model="form.name" placeholder="请输入菜品名称" aria-required="true" />
          </ElFormItem>
          <ElFormItem label="价格" prop="price">
            <ElInputNumber v-model="form.price" :min="0" :precision="2" :step="0.5" class="w-full" aria-required="true" />
          </ElFormItem>
          <ElFormItem label="库存">
            <div class="w-full">
              <ElInputNumber v-model="formStock" :min="0" :step="1" controls-position="right" class="w-full" />
              <p class="mt-1 text-xs text-text-muted">0 表示不限</p>
            </div>
          </ElFormItem>
          <ElFormItem label="单次限购">
            <div class="w-full">
              <ElInputNumber v-model="formMaxPerOrder" :min="1" :step="1" controls-position="right" class="w-full" />
              <p class="mt-1 text-xs text-text-muted">0 或不填表示不限</p>
            </div>
          </ElFormItem>
          <ElFormItem label="适用餐次">
            <ElCheckboxGroup v-model="formMealTypes">
              <ElCheckbox
                v-for="o in mealTypeOptions"
                :key="o.value"
                :value="o.value"
              >
                {{ o.label }}
              </ElCheckbox>
            </ElCheckboxGroup>
          </ElFormItem>
          <ElFormItem label="新品">
            <ElSwitch v-model="form.isNew" :active-value="1" :inactive-value="0" />
          </ElFormItem>
          <ElFormItem label="状态">
            <ElSwitch v-model="form.status" :active-value="1" :inactive-value="0" />
          </ElFormItem>
        </ElForm>
        <template #footer>
          <ElButton @click="dialogVisible = false">取消</ElButton>
          <ElButton type="primary" :loading="dialogLoading" @click="handleSave">保存</ElButton>
        </template>
      </ElDialog>

      <!-- 回收站弹窗 -->
      <ElDialog
        v-model="trashDialogVisible"
        title="回收站"
        width="800px"
        append-to-body
        destroy-on-close
      >
        <ElTable
          v-loading="trashLoading"
          :data="trashList"
          style="width: 100%"
          :show-overflow-tooltip="true"
          row-key="id"
          aria-label="回收站菜品列表"
        >
          <ElTableColumn label="图片" width="80" align="center">
            <template #default="{ row }">
              <ElImage
                v-if="row.image"
                :src="row.image"
                :preview-src-list="[row.image]"
                preview-teleported
                fit="cover"
                class="h-10 w-10 rounded-lg"
              />
              <div
                v-else
                class="mx-auto flex h-10 w-10 items-center justify-center rounded-lg bg-bg-tertiary text-text-muted"
              >
                <ImageIcon class="h-4 w-4" />
              </div>
            </template>
          </ElTableColumn>
          <ElTableColumn prop="name" label="名称" min-width="140" />
          <ElTableColumn label="价格" width="100" align="right">
            <template #default="{ row }">
              <span class="font-medium tabular-nums text-text">¥{{ row.price }}</span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="操作" width="180" fixed="right">
            <template #default="{ row }">
              <ElButton size="small" type="success" :icon="RotateCcw" @click="handleRestore(row as Dish)">恢复</ElButton>
              <ElButton size="small" type="danger" :icon="Trash2" @click="handlePurge(row as Dish)">彻底删除</ElButton>
            </template>
          </ElTableColumn>
          <template #empty>
            <EmptyState description="回收站为空" />
          </template>
        </ElTable>
        <div class="flex flex-wrap items-center justify-between gap-2 border-t border-border px-4 py-3">
          <span class="text-xs text-text-muted">共 {{ trashTotal }} 条</span>
          <ElPagination
            v-model:current-page="trashPage"
            v-model:page-size="trashSize"
            :page-sizes="[10, 20, 50]"
            :total="trashTotal"
            layout="total, sizes, prev, pager, next"
            background
            @current-change="handleTrashPageChange"
            @size-change="handleTrashSizeChange"
          />
        </div>
      </ElDialog>
    </PageContainer>
  </Layout>
</template>
