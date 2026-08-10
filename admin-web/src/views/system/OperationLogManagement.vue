<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import {
  ElInput,
  ElOption,
  ElPagination,
  ElSelect,
  ElTable,
  ElTableColumn,
} from 'element-plus'
import Layout from '@/components/Layout.vue'
import PageContainer from '@/components/PageContainer.vue'
import SearchBar from '@/components/SearchBar.vue'
import StatusTag from '@/components/StatusTag.vue'
import EmptyState from '@/components/EmptyState.vue'
import { useAuthStore } from '@/stores/auth'
import { operationLogApi } from '@/api'
import type { OperationLogItem, OperationLogQuery } from '@/api/operationLog'
import type { PageResult } from '@/api/types'

const authStore = useAuthStore()
// 超管未选择食堂时返回 null,不再静默回退到 storeId=1
const sid = computed(() => authStore.storeId || null)

const logs = ref<OperationLogItem[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)

const filters = reactive({
  operation: '',
  status: undefined as number | undefined,
})

/** 操作日志状态:1=成功,0=失败 */
const LOG_STATUS = {
  0: { label: '失败', type: 'danger' },
  1: { label: '成功', type: 'success' },
} as const

const statusOptions = [
  { value: 1, label: '成功' },
  { value: 0, label: '失败' },
]

const buildQuery = (overrides: Partial<OperationLogQuery> = {}): OperationLogQuery => ({
  storeId: sid.value ?? undefined,
  page: page.value,
  size: size.value,
  operation: filters.operation || undefined,
  status: filters.status,
  ...overrides,
})

const fetchLogs = async () => {
  // 未选择食堂:不请求,清空列表
  if (!sid.value) {
    logs.value = []
    total.value = 0
    return
  }
  loading.value = true
  try {
    const res = await operationLogApi.list(buildQuery())
    const data = res as unknown as PageResult<OperationLogItem> | OperationLogItem[]
    logs.value = Array.isArray(data) ? data : data.records ?? []
    total.value = Array.isArray(data) ? data.length : data.total ?? logs.value.length
  } catch {
    /* 错误已由拦截器统一提示 */
  } finally {
    loading.value = false
  }
}

const handleSearch = () => {
  page.value = 1
  fetchLogs()
}

const handleReset = () => {
  filters.operation = ''
  filters.status = undefined
  page.value = 1
  fetchLogs()
}

const handlePageChange = (p: number) => {
  page.value = p
  fetchLogs()
}

const handleSizeChange = (s: number) => {
  size.value = s
  page.value = 1
  fetchLogs()
}

onMounted(fetchLogs)

// 超管切换食堂后自动刷新日志列表
watch(() => authStore.storeId, () => {
  page.value = 1
  fetchLogs()
})
</script>

<template>
  <Layout>
    <PageContainer title="操作日志" description="查看管理员操作记录，支持按操作关键词与状态筛选">
      <div
        v-if="!sid"
        class="mb-4 rounded-lg border border-amber-300 bg-amber-50 px-4 py-3 text-sm text-amber-700"
      >
        请先选择食堂后再查看操作日志。
      </div>
      <SearchBar @search="handleSearch" @reset="handleReset">
        <ElInput
          v-model="filters.operation"
          placeholder="搜索操作关键词"
          clearable
          style="width: 200px"
          @keyup.enter="handleSearch"
        />
        <ElSelect v-model="filters.status" placeholder="全部状态" clearable style="width: 120px" @change="handleSearch">
          <ElOption v-for="o in statusOptions" :key="o.value" :label="o.label" :value="o.value" />
        </ElSelect>
      </SearchBar>

      <div class="card overflow-hidden">
        <ElTable
          v-loading="loading"
          :data="logs"
          style="width: 100%"
          :show-overflow-tooltip="true"
          row-key="id"
        >
          <ElTableColumn prop="id" label="ID" width="80" align="center" />
          <ElTableColumn label="操作人" width="140">
            <template #default="{ row }">
              <span class="font-medium text-text">{{ row.adminName || `#${row.adminId}` || '—' }}</span>
            </template>
          </ElTableColumn>
          <ElTableColumn prop="operation" label="操作" min-width="140" />
          <ElTableColumn label="操作详情" min-width="200">
            <template #default="{ row }">
              <span class="text-text-secondary">{{ row.params || '—' }}</span>
            </template>
          </ElTableColumn>
          <ElTableColumn prop="ip" label="IP" width="140" />
          <ElTableColumn label="状态" width="100" align="center">
            <template #default="{ row }">
              <StatusTag :value="row.status" :map="LOG_STATUS" />
            </template>
          </ElTableColumn>
          <ElTableColumn prop="errorMsg" label="错误信息" min-width="180" show-overflow-tooltip />
          <ElTableColumn prop="createdAt" label="时间" width="180" />
          <template #empty>
            <EmptyState description="暂无操作日志" />
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
    </PageContainer>
  </Layout>
</template>
