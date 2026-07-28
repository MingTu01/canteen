<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import Layout from '@/components/Layout.vue'
import PageContainer from '@/components/PageContainer.vue'
import SearchBar from '@/components/SearchBar.vue'
import StatusTag from '@/components/StatusTag.vue'
import EmptyState from '@/components/EmptyState.vue'
import { useAuthStore } from '@/stores/auth'
import { notificationApi } from '@/api'
import type { Notification } from '@/api'
import { NOTIFICATION_TYPE, NOTIFICATION_DISPLAY_STATUS } from '@/constants/dict'
import {
  ElTable,
  ElTableColumn,
  ElButton,
  ElDialog,
  ElForm,
  ElFormItem,
  ElInput,
  ElSelect,
  ElOption,
  ElSwitch,
  ElDatePicker,
  ElImage,
  ElPagination,
  ElMessage,
  ElMessageBox,
} from 'element-plus'
import { Plus, Pencil, Trash2, Megaphone, Image as ImageIcon } from 'lucide-vue-next'
import ImageUploader from '@/components/ImageUploader.vue'
import { normalizeList } from '@/utils/list'

const authStore = useAuthStore()
// 超管未选择食堂时返回 null,不再静默回退到 storeId=1
const storeId = computed(() => authStore.storeId || null)

const list = ref<Notification[]>([])
const loading = ref(false)
const dialogVisible = ref(false)
const isEdit = ref(false)

const typeFilter = ref<number | undefined>(undefined)
const displayStatusFilter = ref<string | undefined>(undefined)
const keyword = ref('')
const page = ref(1)
const size = ref(10)

const fetchList = async () => {
  const sid = storeId.value
  if (!sid) {
    list.value = []
    return
  }
  loading.value = true
  try {
    const raw = await notificationApi.list({ storeId: sid, type: typeFilter.value })
    list.value = normalizeList<Notification>(raw)
  } catch {
    /* 拦截器提示 */
  } finally {
    loading.value = false
  }
}

const filteredList = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  const ds = displayStatusFilter.value
  return list.value.filter((n) => {
    if (ds && n.displayStatus !== ds) return false
    if (!kw) return true
    return n.title?.toLowerCase().includes(kw) || n.content?.toLowerCase().includes(kw)
  })
})
const total = computed(() => filteredList.value.length)
const pagedList = computed(() => {
  const start = (page.value - 1) * size.value
  return filteredList.value.slice(start, start + size.value)
})

const handleSearch = () => {
  page.value = 1
  fetchList()
}
const handleReset = () => {
  typeFilter.value = undefined
  displayStatusFilter.value = undefined
  keyword.value = ''
  page.value = 1
  fetchList()
}

const preview = (text: string) => {
  if (!text) return '—'
  return text.length > 40 ? text.slice(0, 40) + '…' : text
}

// 时间显示:LocalDateTime 后端返回 "2026-07-17T10:30:00"
const fmtTime = (t?: string | null) => {
  if (!t) return '—'
  return t.replace('T', ' ').slice(0, 16)
}

// ===== 默认上下架时间:上架=当前,下架=一个月后 =====
const defaultPublishAt = (): string => {
  const d = new Date()
  return d.toISOString().slice(0, 19)
}
const defaultExpireAt = (): string => {
  const d = new Date()
  d.setMonth(d.getMonth() + 1)
  return d.toISOString().slice(0, 19)
}

const form = ref<Notification>({
  storeId: 1,
  title: '',
  content: '',
  type: 1,
  status: 1,
  publishAt: defaultPublishAt(),
  expireAt: defaultExpireAt(),
})

const openCreate = () => {
  isEdit.value = false
  form.value = {
    storeId: storeId.value ?? 0,
    title: '',
    content: '',
    imageUrl: '',
    type: 1,
    status: 1,
    publishAt: defaultPublishAt(),
    expireAt: defaultExpireAt(),
  }
  dialogVisible.value = true
}

const openEdit = (row: Notification) => {
  isEdit.value = true
  form.value = { ...row }
  // 编辑时如果 publishAt/expireAt 为 null,保持 null(允许立即上架/不下架)
  dialogVisible.value = true
}

const saving = ref(false)
const handleSave = async () => {
  if (!form.value.title?.trim()) {
    ElMessage.warning('请输入通知标题')
    return
  }
  if (!form.value.content?.trim()) {
    ElMessage.warning('请输入通知内容')
    return
  }
  // 校验时间
  if (form.value.publishAt && form.value.expireAt
      && new Date(form.value.publishAt) >= new Date(form.value.expireAt)) {
    ElMessage.warning('下架时间必须晚于上架时间')
    return
  }
  saving.value = true
  try {
    const payload: Notification = {
      ...form.value,
      title: form.value.title.trim(),
      content: form.value.content.trim(),
      storeId: storeId.value ?? 0,
      type: form.value.type ?? 1,
      status: form.value.status ?? 1,
    }
    if (isEdit.value && form.value.id) {
      await notificationApi.update(form.value.id, payload)
      ElMessage.success('更新成功')
    } else {
      await notificationApi.create(payload)
      ElMessage.success('创建成功')
    }
    dialogVisible.value = false
    await fetchList()
  } catch {
    /* 拦截器提示 */
  } finally {
    saving.value = false
  }
}

// ===== 图片上传 =====
// 改用 ImageUploader 组件走后端上传 API,统一图片存储路径(/uploads/),
// 避免把 base64 dataURL 直接写进 MySQL 导致数据库膨胀、响应变大、图片无法独立缓存。

// ===== 删除 =====
const handleDelete = async (row: Notification) => {
  try {
    await ElMessageBox.confirm(`确定要删除通知「${row.title}」吗？`, '删除确认', {
      type: 'warning',
      confirmButtonText: '确定删除',
      cancelButtonText: '取消',
    })
  } catch {
    return /* 用户取消 */
  }
  try {
    await notificationApi.delete(row.id as number)
    ElMessage.success('删除成功')
    fetchList()
  } catch {
    /* 拦截器提示 */
  }
}

onMounted(fetchList)
</script>

<template>
  <Layout>
    <PageContainer title="通知管理" description="管理系统通知、公告与活动信息,支持配图、定时上下架与到期自动下架。">
      <template #actions>
        <ElButton type="primary" :icon="Plus" @click="openCreate">新增通知</ElButton>
      </template>

      <div
        v-if="!storeId"
        class="mb-4 rounded-lg border border-amber-300 bg-amber-50 px-4 py-3 text-sm text-amber-700"
      >
        请先选择食堂后再查看数据。
      </div>

      <SearchBar @search="handleSearch" @reset="handleReset">
        <ElSelect v-model="typeFilter" placeholder="通知类型" clearable style="width: 140px">
          <ElOption
            v-for="(meta, key) in NOTIFICATION_TYPE"
            :key="key"
            :label="meta.label"
            :value="Number(key)"
          />
        </ElSelect>
        <ElSelect v-model="displayStatusFilter" placeholder="展示状态" clearable style="width: 140px">
          <ElOption
            v-for="(meta, key) in NOTIFICATION_DISPLAY_STATUS"
            :key="key"
            :label="meta.label"
            :value="String(key)"
          />
        </ElSelect>
        <ElInput
          v-model="keyword"
          placeholder="搜索标题或内容"
          clearable
          style="width: 240px"
          @keyup.enter="handleSearch"
        />
      </SearchBar>

      <div class="card overflow-hidden" v-loading="loading">
        <ElTable :data="pagedList" style="width: 100%" :show-overflow-tooltip="true" row-key="id">
          <ElTableColumn label="标题" min-width="220">
            <template #default="{ row }">
              <div class="flex items-center gap-2">
                <Megaphone class="h-4 w-4 shrink-0 text-primary" />
                <span class="font-medium text-text">{{ row.title }}</span>
              </div>
            </template>
          </ElTableColumn>
          <ElTableColumn label="配图" width="80" align="center">
            <template #default="{ row }">
              <ElImage
                v-if="row.imageUrl"
                :src="row.imageUrl"
                :preview-src-list="[row.imageUrl]"
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
          <ElTableColumn label="类型" width="100" align="center">
            <template #default="{ row }">
              <StatusTag :value="row.type" :map="NOTIFICATION_TYPE" />
            </template>
          </ElTableColumn>
          <ElTableColumn label="内容预览" min-width="220">
            <template #default="{ row }">
              <span class="text-text-secondary">{{ preview(row.content) }}</span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="上架时间" width="150">
            <template #default="{ row }">
              <span class="tabular-nums text-text-muted">{{ fmtTime(row.publishAt) }}</span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="下架时间" width="150">
            <template #default="{ row }">
              <span class="tabular-nums text-text-muted">{{ fmtTime(row.expireAt) }}</span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="展示状态" width="100" align="center">
            <template #default="{ row }">
              <StatusTag
                v-if="row.displayStatus"
                :value="row.displayStatus"
                :map="NOTIFICATION_DISPLAY_STATUS"
              />
              <span v-else>—</span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="操作" width="160" fixed="right">
            <template #default="{ row }">
              <ElButton link type="primary" :icon="Pencil" @click="openEdit(row as Notification)">编辑</ElButton>
              <ElButton link type="danger" :icon="Trash2" @click="handleDelete(row as Notification)">删除</ElButton>
            </template>
          </ElTableColumn>
          <template #empty>
            <EmptyState description="暂无通知数据" />
          </template>
        </ElTable>

        <div class="flex flex-wrap items-center justify-between gap-2 border-t border-border px-4 py-3">
          <span class="text-xs text-text-muted">共 {{ total }} 条</span>
          <ElPagination
            v-model:current-page="page"
            v-model:page-size="size"
            :total="total"
            :page-sizes="[10, 20, 50, 100]"
            layout="total, sizes, prev, pager, next, jumper"
            background
          />
        </div>
      </div>

      <ElDialog
        v-model="dialogVisible"
        :title="isEdit ? '编辑通知' : '新增通知'"
        width="680px"
        :close-on-click-modal="false"
        append-to-body
        destroy-on-close
      >
        <ElForm :model="form" label-width="100px" label-position="right">
          <ElFormItem label="标题" required>
            <ElInput v-model="form.title" placeholder="请输入通知标题" maxlength="60" />
          </ElFormItem>
          <ElFormItem label="类型" required>
            <ElSelect v-model="form.type" style="width: 100%">
              <ElOption
                v-for="(meta, key) in NOTIFICATION_TYPE"
                :key="key"
                :label="meta.label"
                :value="Number(key)"
              />
            </ElSelect>
          </ElFormItem>
          <ElFormItem label="配图">
            <ImageUploader
              v-model="form.imageUrl"
              label="通知配图"
              :preview-size="120"
              hint="支持 JPG/PNG/WebP,自动压缩到 200KB 以内"
            />
          </ElFormItem>
          <ElFormItem label="内容" required>
            <ElInput
              v-model="form.content"
              type="textarea"
              :rows="6"
              :maxlength="1000"
              show-word-limit
              placeholder="请输入通知内容(支持公告、活动详情等)"
            />
          </ElFormItem>
          <ElFormItem label="上架时间">
            <ElDatePicker
              v-model="form.publishAt"
              type="datetime"
              value-format="YYYY-MM-DDTHH:mm:ss"
              placeholder="留空表示立即上架"
              style="width: 100%"
              clearable
            />
            <span class="ml-2 text-xs text-text-muted whitespace-nowrap">为空=立即上架</span>
          </ElFormItem>
          <ElFormItem label="下架时间">
            <ElDatePicker
              v-model="form.expireAt"
              type="datetime"
              value-format="YYYY-MM-DDTHH:mm:ss"
              placeholder="留空表示不下架"
              style="width: 100%"
              clearable
            />
            <span class="ml-2 text-xs text-text-muted whitespace-nowrap">到期自动下架</span>
          </ElFormItem>
          <ElFormItem label="启用">
            <ElSwitch v-model="form.status" :active-value="1" :inactive-value="0" active-text="上架" inactive-text="下架" />
            <span class="ml-2 text-xs text-text-muted">关闭后员工端不可见</span>
          </ElFormItem>
        </ElForm>
        <template #footer>
          <div class="flex justify-end gap-3">
            <ElButton @click="dialogVisible = false">取消</ElButton>
            <ElButton type="primary" :loading="saving" @click="handleSave">保存</ElButton>
          </div>
        </template>
      </ElDialog>
    </PageContainer>
  </Layout>
</template>
