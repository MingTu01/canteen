<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import {
  ElButton,
  ElDrawer,
  ElInput,
  ElOption,
  ElPagination,
  ElRate,
  ElSelect,
  ElTable,
  ElTableColumn,
  ElTag,
  ElMessage,
  ElMessageBox,
} from 'element-plus'
import { Eye, Trash2, CheckCircle2, MinusCircle, MessageSquare, Star, Clock, ListChecks } from 'lucide-vue-next'
import Layout from '@/components/Layout.vue'
import PageContainer from '@/components/PageContainer.vue'
import SearchBar from '@/components/SearchBar.vue'
import StatusTag from '@/components/StatusTag.vue'
import EmptyState from '@/components/EmptyState.vue'
import StatCard from '@/components/StatCard.vue'
import { useAuthStore } from '@/stores/auth'
import { feedbackApi } from '@/api'
import type { Feedback, FeedbackStats, PageResult } from '@/api/types'

const authStore = useAuthStore()
const sid = computed(() => authStore.storeId || null)

// 反馈状态字典
const FEEDBACK_STATUS: Record<number, { label: string; type: 'warning' | 'success' | 'info' }> = {
  1: { label: '待处理', type: 'warning' },
  2: { label: '已处理', type: 'success' },
  3: { label: '已忽略', type: 'info' },
}

// 反馈分类字典
const FEEDBACK_CATEGORY: Record<number, { label: string; type: 'primary' | 'danger' | 'success' | 'info' }> = {
  1: { label: '菜品评价', type: 'primary' },
  2: { label: '服务投诉', type: 'danger' },
  3: { label: '建议', type: 'success' },
  4: { label: '其他', type: 'info' },
}

const categoryLabel = (c?: number) => FEEDBACK_CATEGORY[c ?? 1]?.label ?? '—'

// 统计
const stats = ref<FeedbackStats | null>(null)
const fetchStats = async () => {
  const sidVal = sid.value
  if (!sidVal) {
    stats.value = null
    return
  }
  try {
    stats.value = await feedbackApi.stats(sidVal)
  } catch {
    stats.value = null
  }
}

// 筛选
const statusFilter = ref<number | undefined>(undefined)
const categoryFilter = ref<number | undefined>(undefined)
const keyword = ref('')
const page = ref(1)
const size = ref(10)
const total = ref(0)
const loading = ref(false)
const list = ref<Feedback[]>([])

const fetchList = async () => {
  const sidVal = sid.value
  if (!sidVal) {
    list.value = []
    total.value = 0
    return
  }
  loading.value = true
  try {
    const res = await feedbackApi.list({
      storeId: sidVal,
      page: page.value,
      size: size.value,
      status: statusFilter.value,
      category: categoryFilter.value,
      keyword: keyword.value || undefined,
    })
    const data = res as unknown as PageResult<Feedback>
    list.value = data.records ?? []
    total.value = data.total ?? 0
  } catch {
    /* 错误已由拦截器统一提示 */
  } finally {
    loading.value = false
  }
}

const handleSearch = () => {
  page.value = 1
  fetchList()
}

const handleReset = () => {
  statusFilter.value = undefined
  categoryFilter.value = undefined
  keyword.value = ''
  page.value = 1
  fetchList()
}

// 详情抽屉 + 回复
const detailDrawerVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<Feedback | null>(null)
const replyText = ref('')
const replyLoading = ref(false)

const openDetail = async (row: Feedback) => {
  if (!row.id) return
  detailDrawerVisible.value = true
  detailLoading.value = true
  replyText.value = ''
  try {
    detail.value = await feedbackApi.detail(row.id)
    replyText.value = detail.value.reply || ''
  } catch {
    detail.value = null
  } finally {
    detailLoading.value = false
  }
}

const handleReply = async () => {
  if (!detail.value?.id) return
  if (!replyText.value.trim()) {
    ElMessage.warning('请输入回复内容')
    return
  }
  replyLoading.value = true
  try {
    await feedbackApi.reply(detail.value.id, replyText.value.trim())
    ElMessage.success('回复成功')
    detailDrawerVisible.value = false
    fetchList()
    fetchStats()
  } catch {
    /* 错误已由拦截器统一提示 */
  } finally {
    replyLoading.value = false
  }
}

// 状态操作
const handleUpdateStatus = async (row: Feedback, status: number, label: string) => {
  if (!row.id) return
  try {
    await ElMessageBox.confirm(`确认将此反馈标记为「${label}」吗?`, '操作确认', {
      type: 'warning',
      confirmButtonText: '确认',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await feedbackApi.updateStatus(row.id, status)
    ElMessage.success('操作成功')
    fetchList()
    fetchStats()
  } catch {
    /* 错误已由拦截器统一提示 */
  }
}

const handleDelete = async (row: Feedback) => {
  if (!row.id) return
  try {
    await ElMessageBox.confirm('确认删除此反馈吗?删除后不可恢复。', '删除确认', {
      type: 'warning',
      confirmButtonText: '确定删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await feedbackApi.delete(row.id)
    ElMessage.success('删除成功')
    fetchList()
    fetchStats()
  } catch {
    /* 错误已由拦截器统一提示 */
  }
}

const formatTime = (t?: string | null) => {
  if (!t) return '—'
  return t.replace('T', ' ').slice(0, 16)
}

watch(sid, () => {
  page.value = 1
  fetchList()
  fetchStats()
})

onMounted(() => {
  fetchList()
  fetchStats()
})
</script>

<template>
  <Layout>
    <PageContainer title="反馈评价管理" description="查看员工反馈与评价,支持回复、状态处理与统计">
      <!-- 统计卡片 -->
      <div class="mb-5 grid grid-cols-1 gap-4 sm:grid-cols-3">
        <StatCard
          title="反馈总数"
          :value="stats?.total ?? 0"
          :icon="ListChecks"
          color="primary"
        />
        <StatCard
          title="待处理"
          :value="stats?.pending ?? 0"
          :icon="Clock"
          color="warning"
        />
        <StatCard
          title="平均评分"
          :value="stats?.avgRating ?? 0"
          :icon="Star"
          color="accent"
        />
      </div>

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
          <ElOption label="待处理" :value="1" />
          <ElOption label="已处理" :value="2" />
          <ElOption label="已忽略" :value="3" />
        </ElSelect>
        <ElSelect
          v-model="categoryFilter"
          placeholder="全部分类"
          clearable
          style="width: 140px"
          @change="handleSearch"
        >
          <ElOption label="菜品评价" :value="1" />
          <ElOption label="服务投诉" :value="2" />
          <ElOption label="建议" :value="3" />
          <ElOption label="其他" :value="4" />
        </ElSelect>
        <ElInput
          v-model="keyword"
          placeholder="搜索反馈内容"
          clearable
          style="width: 220px"
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
          <ElTableColumn label="员工" min-width="120">
            <template #default="{ row }">
              <span class="text-text">{{ row.employeeName || '—' }}</span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="分类" width="110" align="center">
            <template #default="{ row }">
              <ElTag :type="FEEDBACK_CATEGORY[row.category ?? 1]?.type" effect="light" size="small">
                {{ categoryLabel(row.category) }}
              </ElTag>
            </template>
          </ElTableColumn>
          <ElTableColumn label="评分" width="150" align="center">
            <template #default="{ row }">
              <ElRate :model-value="row.rating ?? 0" disabled size="small" />
            </template>
          </ElTableColumn>
          <ElTableColumn label="内容" min-width="200">
            <template #default="{ row }">
              <span class="text-text-secondary">{{ row.content || '—' }}</span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="状态" width="100" align="center">
            <template #default="{ row }">
              <StatusTag :value="row.status ?? 1" :map="FEEDBACK_STATUS" />
            </template>
          </ElTableColumn>
          <ElTableColumn label="时间" width="160" align="center">
            <template #default="{ row }">
              <span class="tabular-nums text-text-muted">{{ formatTime(row.createdAt) }}</span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="操作" width="280" fixed="right">
            <template #default="{ row }">
              <ElButton size="small" :icon="Eye" @click="openDetail(row as Feedback)">详情</ElButton>
              <ElButton
                v-if="row.status === 1"
                size="small"
                type="success"
                :icon="MessageSquare"
                @click="openDetail(row as Feedback)"
              >回复</ElButton>
              <ElButton
                v-if="row.status === 1"
                size="small"
                type="warning"
                :icon="MinusCircle"
                @click="handleUpdateStatus(row as Feedback, 3, '已忽略')"
              >忽略</ElButton>
              <ElButton
                size="small"
                type="danger"
                :icon="Trash2"
                @click="handleDelete(row as Feedback)"
              />
            </template>
          </ElTableColumn>
          <template #empty>
            <EmptyState description="暂无反馈数据" />
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

      <!-- 详情抽屉 -->
      <ElDrawer
        v-model="detailDrawerVisible"
        title="反馈详情"
        direction="rtl"
        size="560px"
      >
        <div v-loading="detailLoading">
          <template v-if="detail">
            <div class="mb-6 rounded-lg border border-border bg-bg-secondary p-4">
              <div class="mb-3 flex items-center justify-between">
                <span class="text-base font-bold text-text">{{ detail.employeeName || '匿名员工' }}</span>
                <StatusTag :value="detail.status ?? 1" :map="FEEDBACK_STATUS" />
              </div>
              <div class="grid grid-cols-2 gap-3 text-sm">
                <div>
                  <span class="text-text-muted">分类:</span>
                  <ElTag class="ml-2" :type="FEEDBACK_CATEGORY[detail.category ?? 1]?.type" effect="light" size="small">
                    {{ categoryLabel(detail.category) }}
                  </ElTag>
                </div>
                <div>
                  <span class="text-text-muted">评分:</span>
                  <ElRate class="ml-2 align-middle" :model-value="detail.rating ?? 0" disabled size="small" />
                </div>
                <div>
                  <span class="text-text-muted">关联菜品:</span>
                  <span class="ml-2 text-text">{{ detail.dishName || '—' }}</span>
                </div>
                <div>
                  <span class="text-text-muted">关联订单:</span>
                  <span class="ml-2 text-text">{{ detail.orderId || '—' }}</span>
                </div>
                <div class="col-span-2">
                  <span class="text-text-muted">提交时间:</span>
                  <span class="ml-2 text-text">{{ formatTime(detail.createdAt) }}</span>
                </div>
              </div>
              <div class="mt-3 border-t border-border pt-3">
                <div class="mb-1 text-xs text-text-muted">反馈内容</div>
                <div class="text-sm text-text">{{ detail.content || '—' }}</div>
              </div>
              <div v-if="detail.reply" class="mt-3 border-t border-border pt-3">
                <div class="mb-1 text-xs text-text-muted">管理员回复 · {{ formatTime(detail.repliedAt) }}</div>
                <div class="text-sm text-text">{{ detail.reply }}</div>
              </div>
            </div>

            <!-- 回复输入 -->
            <div class="mb-2 text-sm font-medium text-text">回复内容</div>
            <ElInput
              v-model="replyText"
              type="textarea"
              :rows="4"
              placeholder="请输入回复内容"
              maxlength="500"
              show-word-limit
              :disabled="detail?.status === 2 || detail?.status === 3"
            />
            <div class="mt-3 flex justify-end gap-2">
              <ElButton
                v-if="detail.status === 1"
                :icon="CheckCircle2"
                @click="handleUpdateStatus(detail, 2, '已处理')"
              >标记已处理</ElButton>
              <ElButton
                type="primary"
                :loading="replyLoading"
                :disabled="detail?.status === 2 || detail?.status === 3"
                @click="handleReply"
              >
                {{ detail.reply ? '更新回复' : '提交回复' }}
              </ElButton>
            </div>
          </template>
          <EmptyState v-else description="暂无详情数据" />
        </div>
      </ElDrawer>
    </PageContainer>
  </Layout>
</template>
