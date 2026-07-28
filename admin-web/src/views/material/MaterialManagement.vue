<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import {
  ElButton,
  ElDialog,
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
} from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { Plus, Pencil, Trash2, Package, ArrowDownCircle, ArrowUpCircle } from 'lucide-vue-next'
import Layout from '@/components/Layout.vue'
import PageContainer from '@/components/PageContainer.vue'
import SearchBar from '@/components/SearchBar.vue'
import EmptyState from '@/components/EmptyState.vue'
import { useAuthStore } from '@/stores/auth'
import { materialApi } from '@/api'
import type { Material, PageResult } from '@/api/types'

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
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
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
    await materialApi.delete(id)
    ElMessage.success('删除成功')
    fetchList()
  } catch {
    /* 错误已由拦截器统一提示 */
  }
}

// 入库/出库弹窗
const stockDialogVisible = ref(false)
const stockDialogLoading = ref(false)
const stockMode = ref<'inbound' | 'outbound'>('inbound')
const stockTarget = ref<Material | null>(null)
const stockQty = ref<number>(1)
const stockRemark = ref('')

const openStock = (row: Material, mode: 'inbound' | 'outbound') => {
  stockTarget.value = row
  stockMode.value = mode
  stockQty.value = 1
  stockRemark.value = ''
  stockDialogVisible.value = true
}

const handleStockSubmit = async () => {
  if (!stockTarget.value?.id) return
  if (!stockQty.value || stockQty.value <= 0) {
    ElMessage.warning('数量必须大于 0')
    return
  }
  stockDialogLoading.value = true
  try {
    if (stockMode.value === 'inbound') {
      await materialApi.inbound(stockTarget.value.id, stockQty.value, stockRemark.value || undefined)
      ElMessage.success('入库成功')
    } else {
      await materialApi.outbound(stockTarget.value.id, stockQty.value, stockRemark.value || undefined)
      ElMessage.success('出库成功')
    }
    stockDialogVisible.value = false
    fetchList()
  } catch {
    /* 错误已由拦截器统一提示 */
  } finally {
    stockDialogLoading.value = false
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
    <PageContainer title="库存食材管理" description="维护食材档案、库存数量与预警线,支持入库/出库操作">
      <template #actions>
        <ElButton type="primary" :icon="Plus" @click="openAdd">新增食材</ElButton>
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
              <ElButton size="small" type="success" :icon="ArrowDownCircle" @click="openStock(row as Material, 'inbound')">入库</ElButton>
              <ElButton size="small" type="warning" :icon="ArrowUpCircle" @click="openStock(row as Material, 'outbound')">出库</ElButton>
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
          <ElFormItem label="初始库存">
            <ElInputNumber v-model="form.stockQty" :min="0" :precision="2" :step="1" controls-position="right" class="w-full" />
            <p class="mt-1 text-xs text-text-muted">新建食材的初始库存数量</p>
          </ElFormItem>
          <ElFormItem label="预警线">
            <ElInputNumber v-model="form.minStock" :min="0" :precision="2" :step="1" controls-position="right" class="w-full" />
            <p class="mt-1 text-xs text-text-muted">库存低于此值时显示红色预警标签</p>
          </ElFormItem>
        </ElForm>
        <template #footer>
          <ElButton @click="dialogVisible = false">取消</ElButton>
          <ElButton type="primary" :loading="dialogLoading" @click="handleSave">保存</ElButton>
        </template>
      </ElDialog>

      <!-- 入库/出库弹窗 -->
      <ElDialog
        v-model="stockDialogVisible"
        :title="stockMode === 'inbound' ? '入库' : '出库'"
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
          <ElFormItem label="数量">
            <ElInputNumber v-model="stockQty" :min="0.01" :precision="2" :step="1" controls-position="right" class="w-full" />
          </ElFormItem>
          <ElFormItem label="备注">
            <ElInput v-model="stockRemark" type="textarea" :rows="2" placeholder="备注信息(选填)" maxlength="200" />
          </ElFormItem>
        </ElForm>
        <template #footer>
          <ElButton @click="stockDialogVisible = false">取消</ElButton>
          <ElButton
            :type="stockMode === 'inbound' ? 'success' : 'warning'"
            :loading="stockDialogLoading"
            @click="handleStockSubmit"
          >
            确定
          </ElButton>
        </template>
      </ElDialog>
    </PageContainer>
  </Layout>
</template>
