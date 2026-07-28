<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import Layout from '@/components/Layout.vue'
import PageContainer from '@/components/PageContainer.vue'
import EmptyState from '@/components/EmptyState.vue'
import { useAuthStore } from '@/stores/auth'
import { timerApi } from '@/api'
import type { DiningTimeSlot } from '@/api'
import { MEAL_TYPE } from '@/constants/dict'
import {
  ElButton,
  ElDialog,
  ElForm,
  ElFormItem,
  ElSelect,
  ElOption,
  ElTimePicker,
  ElTag,
  ElMessage,
  ElMessageBox,
} from 'element-plus'
import { Plus, Pencil, Trash2, Clock, Sun, Coffee, Moon } from 'lucide-vue-next'
import { normalizeList } from '@/utils/list'

type MealKey = keyof typeof MEAL_TYPE
const MEAL_MAP: Record<number, { label: string; color: string }> = { ...MEAL_TYPE }
const mealOptions: { value: MealKey; label: string }[] = [1, 2, 3].map((k) => ({
  value: k as MealKey,
  label: MEAL_TYPE[k as MealKey].label,
}))
const mealMeta = (m: number) => MEAL_MAP[m]

const authStore = useAuthStore()
// 超管未选择食堂时返回 null,不再静默回退到 storeId=1
const storeId = computed(() => authStore.storeId || null)

const list = ref<DiningTimeSlot[]>([])
const loading = ref(false)
const dialogVisible = ref(false)
const dialogLoading = ref(false)
const isEdit = ref(false)
const form = ref<DiningTimeSlot>({ storeId: 1, mealType: 1, startTime: '', endTime: '' })

const fetchList = async () => {
  const sid = storeId.value
  if (!sid) {
    list.value = []
    return
  }
  loading.value = true
  try {
    const raw = await timerApi.list(sid)
    list.value = normalizeList<DiningTimeSlot>(raw)
  } catch {
    /* 拦截器提示 */
  } finally {
    loading.value = false
  }
}

/* 实时时间,用于显示时段当前状态 */
const now = ref(new Date())
let tickTimer: number | undefined

const toMinutes = (t: string): number => {
  if (!t) return -1
  const parts = t.split(':').map(Number)
  const h = parts[0] || 0
  const m = parts[1] || 0
  const s = parts[2] || 0
  return h * 60 + m + s / 60
}

const nowMinutes = computed(() => {
  const d = now.value
  return d.getHours() * 60 + d.getMinutes() + d.getSeconds() / 60
})

type SlotStatus = 'ongoing' | 'upcoming' | 'ended'
const statusMeta: Record<SlotStatus, { label: string; type: 'success' | 'info' | 'warning' }> = {
  ongoing: { label: '进行中', type: 'success' },
  upcoming: { label: '未开始', type: 'info' },
  ended: { label: '已结束', type: 'warning' },
}

const statusOf = (row: DiningTimeSlot): SlotStatus => {
  const start = toMinutes(row.startTime)
  const end = toMinutes(row.endTime)
  const cur = nowMinutes.value
  if (cur < start) return 'upcoming'
  if (cur >= end) return 'ended'
  return 'ongoing'
}

const hasConflict = (current: DiningTimeSlot): boolean => {
  const newStart = toMinutes(current.startTime)
  const newEnd = toMinutes(current.endTime)
  return list.value.some((s) => {
    if (s.mealType !== current.mealType) return false
    if (isEdit.value && s.id === current.id) return false
    const es = toMinutes(s.startTime)
    const ee = toMinutes(s.endTime)
    return newStart < ee && newEnd > es
  })
}

const saving = ref(false)
const handleSave = async () => {
  const f = form.value
  if (!f.mealType) {
    ElMessage.warning('请选择餐次')
    return
  }
  if (!f.startTime || !f.endTime) {
    ElMessage.warning('请选择开始时间和结束时间')
    return
  }
  if (toMinutes(f.endTime) <= toMinutes(f.startTime)) {
    ElMessage.warning('结束时间必须晚于开始时间')
    return
  }
  if (hasConflict(f)) {
    ElMessage.warning('该餐次时段与已有时段重叠,请调整时间')
    return
  }
  saving.value = true
  try {
    const payload: DiningTimeSlot = {
      ...f,
      storeId: storeId.value ?? 0,
      mealType: f.mealType,
      startTime: f.startTime,
      endTime: f.endTime,
    }
    if (isEdit.value && f.id) {
      await timerApi.update(f.id, payload)
      ElMessage.success('更新成功')
    } else {
      await timerApi.create(payload)
      ElMessage.success('创建成功')
    }
    dialogVisible.value = false
    await fetchList()
  } catch {
    /* 错误已由拦截器提示 */
  } finally {
    saving.value = false
  }
}

const openCreate = () => {
  isEdit.value = false
  form.value = { storeId: storeId.value ?? 0, mealType: 1, startTime: '', endTime: '' }
  dialogVisible.value = true
}

const openEdit = (row: DiningTimeSlot) => {
  isEdit.value = true
  form.value = { ...row }
  dialogVisible.value = true
}

const handleDelete = async (row: DiningTimeSlot) => {
  try {
    await ElMessageBox.confirm(
      `确定要删除「${mealMeta(row.mealType)?.label}」时段吗？`,
      '删除确认',
      { type: 'warning', confirmButtonText: '确定删除', cancelButtonText: '取消' }
    )
  } catch {
    return /* 用户取消 */
  }
  try {
    await timerApi.delete(row.id as number)
    ElMessage.success('删除成功')
    fetchList()
  } catch {
    /* 拦截器提示 */
  }
}

// 卡片视图按餐次分组
const mealColumns = [
  { type: 1 as const, icon: Sun, label: '早餐' },
  { type: 2 as const, icon: Coffee, label: '午餐' },
  { type: 3 as const, icon: Moon, label: '晚餐' },
]
const slotsOf = (mealType: number) => list.value.filter((s) => s.mealType === mealType)

// 格式化时段显示 "07:00:00" -> "07:00"
const fmtTime = (t: string) => {
  if (!t) return '—'
  const parts = t.split(':')
  return `${parts[0] || '00'}:${parts[1] || '00'}`
}

onMounted(() => {
  fetchList()
  tickTimer = window.setInterval(() => {
    now.value = new Date()
  }, 30000)
})

onBeforeUnmount(() => {
  if (tickTimer) window.clearInterval(tickTimer)
})
</script>

<template>
  <Layout>
    <PageContainer title="就餐时段配置" description="管理各餐次的就餐时间段,支持时段冲突校验与实时状态展示。">
      <template #actions>
        <ElButton type="primary" :icon="Plus" @click="openCreate">新增时段</ElButton>
      </template>

      <div
        v-if="!storeId"
        class="mb-4 rounded-lg border border-amber-300 bg-amber-50 px-4 py-3 text-sm text-amber-700"
      >
        请先选择食堂后再查看数据。
      </div>

      <div v-loading="loading" class="grid grid-cols-1 gap-5 md:grid-cols-2 xl:grid-cols-3">
        <div
          v-for="col in mealColumns"
          :key="col.type"
          class="flex flex-col rounded-2xl border border-border bg-card shadow-sm overflow-hidden"
        >
          <div
            class="flex items-center justify-between gap-2 px-5 py-4"
            :style="{ background: `${MEAL_TYPE[col.type].color}14` }"
          >
            <div class="flex min-w-0 items-center gap-2">
              <component :is="col.icon" class="h-5 w-5 shrink-0" :style="{ color: MEAL_TYPE[col.type].color }" />
              <span class="truncate font-semibold text-text">{{ col.label }}</span>
            </div>
            <span class="shrink-0 text-xs text-text-muted">{{ slotsOf(col.type).length }} 个时段</span>
          </div>

          <div class="flex-1 space-y-3 p-4">
            <template v-if="slotsOf(col.type).length">
              <div
                v-for="row in slotsOf(col.type)"
                :key="row.id"
                class="group flex flex-wrap items-center justify-between gap-2 rounded-xl border border-border-light bg-bg-secondary px-4 py-3 transition-colors hover:border-primary"
              >
                <div class="flex min-w-0 flex-1 flex-col gap-1">
                  <div class="flex items-center gap-2">
                    <Clock class="h-4 w-4 text-text-muted" />
                    <span class="font-medium tabular-nums text-text">
                      {{ fmtTime(row.startTime) }} — {{ fmtTime(row.endTime) }}
                    </span>
                  </div>
                  <div class="flex items-center gap-2">
                    <ElTag
                      :type="statusMeta[statusOf(row)].type"
                      effect="light"
                      round
                      size="small"
                    >
                      {{ statusMeta[statusOf(row)].label }}
                    </ElTag>
                  </div>
                </div>
                <div class="flex shrink-0 items-center gap-1 opacity-0 transition-opacity group-hover:opacity-100">
                  <ElButton size="small" :icon="Pencil" @click="openEdit(row)">编辑</ElButton>
                  <ElButton size="small" type="danger" :icon="Trash2" @click="handleDelete(row)" />
                </div>
              </div>
            </template>
            <div v-else>
              <EmptyState :description="`暂无${col.label}时段`" />
            </div>
          </div>
        </div>
      </div>

      <ElDialog
        v-model="dialogVisible"
        :title="isEdit ? '编辑时段' : '新增时段'"
        width="460px"
        :close-on-click-modal="false"
        append-to-body
        destroy-on-close
      >
        <ElForm :model="form" label-width="90px" label-position="right" class="pr-2">
          <ElFormItem label="餐次" required>
            <ElSelect v-model="form.mealType" placeholder="请选择餐次" style="width: 100%">
              <ElOption
                v-for="item in mealOptions"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </ElSelect>
          </ElFormItem>
          <ElFormItem label="开始时间" required>
            <ElTimePicker
              v-model="form.startTime"
              format="HH:mm"
              value-format="HH:mm:ss"
              placeholder="选择开始时间"
              style="width: 100%"
            />
          </ElFormItem>
          <ElFormItem label="结束时间" required>
            <ElTimePicker
              v-model="form.endTime"
              format="HH:mm"
              value-format="HH:mm:ss"
              placeholder="选择结束时间"
              style="width: 100%"
            />
          </ElFormItem>
        </ElForm>
        <template #footer>
          <div class="flex justify-end gap-3">
            <ElButton @click="dialogVisible = false">取消</ElButton>
            <ElButton type="primary" :loading="saving || dialogLoading" @click="handleSave">
              保存
            </ElButton>
          </div>
        </template>
      </ElDialog>
    </PageContainer>
  </Layout>
</template>
