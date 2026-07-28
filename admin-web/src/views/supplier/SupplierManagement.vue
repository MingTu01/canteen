<script setup lang="ts">
import { computed, onMounted, watch } from 'vue'
import {
  ElButton,
  ElDialog,
  ElForm,
  ElFormItem,
  ElInput,
  ElOption,
  ElPagination,
  ElSelect,
  ElSwitch,
  ElTable,
  ElTableColumn,
  ElTag,
} from 'element-plus'
import type { FormRules } from 'element-plus'
import { Plus, Pencil, Trash2, Truck } from 'lucide-vue-next'
import Layout from '@/components/Layout.vue'
import PageContainer from '@/components/PageContainer.vue'
import SearchBar from '@/components/SearchBar.vue'
import StatusTag from '@/components/StatusTag.vue'
import EmptyState from '@/components/EmptyState.vue'
import { useAuthStore } from '@/stores/auth'
import { supplierApi } from '@/api'
import type { Supplier } from '@/api/types'
import { COMMON_STATUS } from '@/constants/dict'
import { useCrud } from '@/composables/useCrud'

const authStore = useAuthStore()
const sid = computed(() => authStore.storeId || null)

/** 供应商搜索参数 */
interface SupplierSearch {
  keyword?: string
}

const rules: FormRules = {
  name: [{ required: true, message: '请输入供应商名称', trigger: 'blur' }],
}

const {
  list,
  loading,
  page,
  size,
  total,
  searchParams,
  dialogVisible,
  dialogLoading,
  isEdit,
  form,
  formRef,
  fetchList,
  onPageChange,
  onSizeChange,
  handleSearch,
  handleReset,
  handleCreate,
  handleEdit,
  handleSave,
  handleDelete,
} = useCrud<Supplier, SupplierSearch>({
  entityName: '供应商',
  rules,
  // 原实现为直接删除(无二次确认),保留该行为
  confirmDelete: false,
  defaultSize: 10,
  initialSearch: { keyword: '' },
  defaultForm: (): Supplier => ({
    storeId: sid.value ?? 0,
    name: '',
    contactPerson: '',
    phone: '',
    address: '',
    category: '',
    status: 1,
    remark: '',
  }),
  list: async ({ page, size, search }) => {
    const sidVal = sid.value
    if (!sidVal) return []
    // supplierApi.list 返回 PageResult<Supplier>
    return supplierApi.list({
      storeId: sidVal,
      page,
      size,
      keyword: search.keyword || undefined,
    })
  },
  create: (data) => supplierApi.create(data),
  update: (id, data) => supplierApi.update(id, data),
  remove: (id) => supplierApi.delete(id),
})

/* 门店切换:重置分页并重新拉取 */
watch(sid, () => {
  page.value = 1
  fetchList()
})

onMounted(fetchList)
</script>

<template>
  <Layout>
    <PageContainer title="供应商管理" description="维护食堂供应商档案、联系人及合作状态">
      <template #actions>
        <ElButton type="primary" :icon="Plus" @click="handleCreate">新增供应商</ElButton>
      </template>

      <div
        v-if="!sid"
        class="mb-4 rounded-lg border border-amber-300 bg-amber-50 px-4 py-3 text-sm text-amber-700"
      >
        请先选择食堂后再查看数据。
      </div>

      <SearchBar @search="handleSearch" @reset="handleReset">
        <ElInput
          v-model="searchParams.keyword"
          placeholder="搜索供应商名称/联系人/电话"
          clearable
          style="width: 260px"
          @keyup.enter="handleSearch"
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
          <ElTableColumn label="供应商" min-width="180">
            <template #default="{ row }">
              <div class="flex items-center gap-2">
                <Truck class="h-4 w-4 text-primary" />
                <span class="font-medium text-text">{{ row.name }}</span>
              </div>
            </template>
          </ElTableColumn>
          <ElTableColumn label="联系人" width="120">
            <template #default="{ row }">
              <span class="text-text-secondary">{{ row.contactPerson || '—' }}</span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="联系电话" width="150">
            <template #default="{ row }">
              <span class="tabular-nums text-text-secondary">{{ row.phone || '—' }}</span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="供货品类" width="140">
            <template #default="{ row }">
              <ElTag v-if="row.category" size="small" type="info">{{ row.category }}</ElTag>
              <span v-else class="text-text-muted">—</span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="地址" min-width="200" show-overflow-tooltip>
            <template #default="{ row }">
              <span class="text-text-secondary">{{ row.address || '—' }}</span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="状态" width="100" align="center">
            <template #default="{ row }">
              <StatusTag :value="row.status ?? 1" :map="COMMON_STATUS" />
            </template>
          </ElTableColumn>
          <ElTableColumn label="操作" width="160" fixed="right">
            <template #default="{ row }">
              <ElButton size="small" :icon="Pencil" @click="handleEdit(row as Supplier)">编辑</ElButton>
              <ElButton size="small" type="danger" :icon="Trash2" @click="handleDelete(row.id)" />
            </template>
          </ElTableColumn>
          <template #empty>
            <EmptyState description="暂无供应商数据" />
          </template>
        </ElTable>

        <div class="flex flex-wrap items-center justify-between gap-2 border-t border-border px-4 py-3">
          <span class="text-xs text-text-muted">共 {{ total }} 条</span>
          <ElPagination
            :current-page="page"
            :page-size="size"
            :page-sizes="[10, 20, 50, 100]"
            :total="total"
            layout="total, sizes, prev, pager, next, jumper"
            background
            @current-change="onPageChange"
            @size-change="onSizeChange"
          />
        </div>
      </div>

      <!-- 新增/编辑弹窗 -->
      <ElDialog
        v-model="dialogVisible"
        :title="isEdit ? '编辑供应商' : '新增供应商'"
        width="560px"
        :close-on-click-modal="false"
        append-to-body
        destroy-on-close
      >
        <ElForm ref="formRef" :model="form" :rules="rules" label-width="100px">
          <ElFormItem label="供应商名称" prop="name">
            <ElInput v-model="form.name" placeholder="请输入供应商名称" maxlength="100" />
          </ElFormItem>
          <ElFormItem label="联系人">
            <ElInput v-model="form.contactPerson" placeholder="请输入联系人" maxlength="50" />
          </ElFormItem>
          <ElFormItem label="联系电话">
            <ElInput v-model="form.phone" placeholder="请输入联系电话" maxlength="30" />
          </ElFormItem>
          <ElFormItem label="供货品类">
            <ElSelect v-model="form.category" placeholder="请选择或输入品类" clearable filterable allow-create class="w-full">
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
          <ElFormItem label="地址">
            <ElInput v-model="form.address" type="textarea" :rows="2" placeholder="请输入地址" maxlength="200" />
          </ElFormItem>
          <ElFormItem label="备注">
            <ElInput v-model="form.remark" type="textarea" :rows="2" placeholder="备注信息" maxlength="500" />
          </ElFormItem>
          <ElFormItem label="状态">
            <ElSwitch v-model="form.status" :active-value="1" :inactive-value="0" active-text="合作中" inactive-text="已停用" />
          </ElFormItem>
        </ElForm>
        <template #footer>
          <ElButton @click="dialogVisible = false">取消</ElButton>
          <ElButton type="primary" :loading="dialogLoading" @click="handleSave">保存</ElButton>
        </template>
      </ElDialog>
    </PageContainer>
  </Layout>
</template>
