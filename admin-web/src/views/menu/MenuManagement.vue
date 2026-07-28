<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import Layout from '@/components/Layout.vue'
import PageContainer from '@/components/PageContainer.vue'
import EmptyState from '@/components/EmptyState.vue'
import { useAuthStore } from '@/stores/auth'
import { menuApi, dishApi, systemApi } from '@/api'
import type { Dish, MenuWithItems, SystemConfig } from '@/api'
import { MEAL_TYPE } from '@/constants/dict'
import {
  ElButton,
  ElDialog,
  ElForm,
  ElFormItem,
  ElDatePicker,
  ElSelect,
  ElOption,
  ElTransfer,
  ElMessage,
  ElMessageBox,
  ElSwitch,
  ElInputNumber,
  ElTimePicker,
} from 'element-plus'
import { Plus, CalendarDays, Trash2, Sun, Coffee, Moon, Copy, ChevronLeft, ChevronRight, Settings } from 'lucide-vue-next'
import { todayStr } from '@/utils/date'
import { normalizeList } from '@/utils/list'

const authStore = useAuthStore()
// 超管未选择食堂时返回 null,不再静默回退到 storeId=1
const storeId = computed(() => authStore.storeId || null)
const isSuperAdmin = computed(() => authStore.isSuperAdmin)

const pad2 = (n: number) => String(n).padStart(2, '0')

const fmtDate = (s: string) => {
  if (!s) return '—'
  const d = new Date(s + 'T00:00:00')
  const week = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'][d.getDay()]
  return `${s} ${week}`
}

const selectedDate = ref(todayStr())
const loading = ref(false)
const dayMenus = ref<MenuWithItems[]>([])
const dishes = ref<Dish[]>([])

// 月历状态
const today = new Date()
const viewYear = ref(today.getFullYear())
const viewMonth = ref(today.getMonth() + 1) // 1-12
const menuDates = ref<Set<string>>(new Set())

const fetchDayMenus = async () => {
  if (!selectedDate.value) return
  const sid = storeId.value
  if (!sid) {
    dayMenus.value = []
    return
  }
  loading.value = true
  try {
    const raw = await menuApi.getByDate(sid, selectedDate.value)
    dayMenus.value = normalizeList<MenuWithItems>(raw)
    // 同步月历到所选日期
    const d = new Date(selectedDate.value + 'T00:00:00')
    viewYear.value = d.getFullYear()
    viewMonth.value = d.getMonth() + 1
  } catch {
    /* 拦截器提示 */
  } finally {
    loading.value = false
  }
}

const fetchDishes = async () => {
  const sid = storeId.value
  if (!sid) {
    dishes.value = []
    return
  }
  try {
    // 拉取较大页,避免穿梭框只显示前 10 条
    const raw = await dishApi.list({ storeId: sid, page: 1, size: 1000 })
    dishes.value = normalizeList<Dish>(raw)
  } catch {
    /* 拦截器提示 */
  }
}

const fetchMenuDates = async () => {
  const sid = storeId.value
  if (!sid) {
    menuDates.value = new Set()
    return
  }
  try {
    const raw = await menuApi.getDatesByMonth(sid, viewYear.value, viewMonth.value)
    const arr = normalizeList<string>(raw)
    menuDates.value = new Set(arr)
  } catch {
    /* 拦截器提示 */
  }
}

const mealColumns = [
  { type: 1 as const, icon: Sun, label: '早餐', empty: '今日暂无早餐菜单' },
  { type: 2 as const, icon: Coffee, label: '午餐', empty: '今日暂无午餐菜单' },
  { type: 3 as const, icon: Moon, label: '晚餐', empty: '今日暂无晚餐菜单' },
]

const menuOf = (mealType: number) =>
  dayMenus.value.find((m) => m.menu?.mealType === mealType)

const totalDishes = computed(() =>
  dayMenus.value.reduce((sum, m) => sum + (m.items?.length || 0), 0)
)

const transferData = computed(() =>
  dishes.value
    .filter((d) => d.status === 1)
    .map((d) => ({
      key: d.id as number,
      label: `${d.name}  ¥${Number(d.price).toFixed(2)}`,
    }))
)

// ===== 新增/编辑菜单弹窗 =====
const dialogVisible = ref(false)
const saving = ref(false)
const form = ref({
  date: todayStr(),
  mealType: 2,
  dishIds: [] as number[],
})

const openCreate = (mealType?: number) => {
  // 预选已存在的菜品
  const mt = mealType ?? 2
  const existing = menuOf(mt)
  const presetIds = existing?.items?.map((it) => it.item?.dishId).filter(Boolean) as number[] || []
  form.value = {
    date: selectedDate.value || todayStr(),
    mealType: mt,
    dishIds: presetIds,
  }
  dialogVisible.value = true
}

const handleSave = async () => {
  if (!form.value.date) {
    ElMessage.warning('请选择日期')
    return
  }
  if (form.value.dishIds.length === 0) {
    ElMessage.warning('请至少选择一道菜品')
    return
  }
  saving.value = true
  try {
    await menuApi.create({
      storeId: storeId.value ?? 0,
      date: form.value.date,
      mealType: form.value.mealType,
      dishIds: form.value.dishIds,
    })
    ElMessage.success('菜单发布成功')
    dialogVisible.value = false
    await fetchDayMenus()
    await fetchMenuDates()
  } catch {
    /* 拦截器提示 */
  } finally {
    saving.value = false
  }
}

const handleDelete = async (mwi: MenuWithItems) => {
  try {
    await ElMessageBox.confirm(
      `确定要删除「${MEAL_TYPE[mwi.menu.mealType as 1 | 2 | 3]?.label}」菜单吗？`,
      '删除确认',
      { type: 'warning', confirmButtonText: '确定删除', cancelButtonText: '取消' }
    )
  } catch {
    return /* 用户取消 */
  }
  try {
    await menuApi.delete(mwi.menu.id as number)
    ElMessage.success('删除成功')
    await fetchDayMenus()
    await fetchMenuDates()
  } catch {
    /* 拦截器提示 */
  }
}

// ===== 快速复制(内联卡片) =====
// 目标日期:默认为上方选中的 selectedDate(即"当天")
// 源日期:被复制的日期
// 按钮点击 = 把源日期菜单复制到目标日期
const copyTargetDate = ref(todayStr())
const copySourceDate = ref(todayStr())
const copySaving = ref(false)

const handleQuickCopy = async () => {
  if (!copySourceDate.value || !copyTargetDate.value) {
    ElMessage.warning('请选择源日期和目标日期')
    return
  }
  if (copySourceDate.value === copyTargetDate.value) {
    ElMessage.warning('源日期与目标日期不能相同')
    return
  }
  copySaving.value = true
  try {
    const res = await menuApi.copy({
      storeId: storeId.value ?? 0,
      sourceDate: copySourceDate.value,
      targetDate: copyTargetDate.value,
      overwrite: true,
    })
    ElMessage.success(`复制成功,共复制 ${res?.copied ?? 0} 个餐次`)
    // 跳转到目标日期查看结果
    selectedDate.value = copyTargetDate.value
    await fetchDayMenus()
    await fetchMenuDates()
  } catch {
    /* 拦截器提示 */
  } finally {
    copySaving.value = false
  }
}

// ===== 订餐配置弹窗 =====
const orderConfigVisible = ref(false)
const orderConfigSaving = ref(false)
const orderForm = ref({
  order_advance_days: 7,
  order_deadline_time: '15:00',
  cancel_deadline_time: '15:00',
  max_order_quantity: 10,
  allow_cross_day_order: true,
})

const getStr = (list: SystemConfig[], key: string, def = ''): string => {
  const item = list.find((c) => c.config_key === key)
  return item?.config_value ?? def
}
const getNum = (list: SystemConfig[], key: string, def = 0): number => {
  const v = getStr(list, key)
  const n = Number(v)
  return isNaN(n) ? def : n
}
const getBool = (list: SystemConfig[], key: string, def = false): boolean => {
  const v = getStr(list, key).toLowerCase()
  if (v === 'true' || v === '1') return true
  if (v === 'false' || v === '0') return false
  return def
}

const fetchOrderConfig = async () => {
  try {
    const list = await systemApi.config()
    orderForm.value = {
      order_advance_days: getNum(list, 'order_advance_days', 7),
      order_deadline_time: getStr(list, 'order_deadline_time', '15:00'),
      cancel_deadline_time: getStr(list, 'cancel_deadline_time', '15:00'),
      max_order_quantity: getNum(list, 'max_order_quantity', 10),
      allow_cross_day_order: getBool(list, 'allow_cross_day_order', true),
    }
  } catch {
    /* 拦截器提示 */
  }
}

const openOrderConfig = () => {
  fetchOrderConfig()
  orderConfigVisible.value = true
}

const saveOrderConfig = async () => {
  orderConfigSaving.value = true
  try {
    await systemApi.batchUpdateConfig([
      { key: 'order_advance_days', value: String(orderForm.value.order_advance_days) },
      { key: 'order_deadline_time', value: orderForm.value.order_deadline_time },
      { key: 'cancel_deadline_time', value: orderForm.value.cancel_deadline_time },
      { key: 'max_order_quantity', value: String(orderForm.value.max_order_quantity) },
      { key: 'allow_cross_day_order', value: String(orderForm.value.allow_cross_day_order) },
    ])
    ElMessage.success('订餐配置已保存')
    orderConfigVisible.value = false
  } catch {
    /* 拦截器提示 */
  } finally {
    orderConfigSaving.value = false
  }
}

// ===== 月历构建 =====
const weekHeaders = ['一', '二', '三', '四', '五', '六', '日']

interface CalendarCell {
  key: string
  day: number | ''
  dateStr: string
  clickable: boolean
  hasMenu: boolean
  isToday: boolean
  isSelected: boolean
  inMonth: boolean
}

const calendarCells = computed<CalendarCell[]>(() => {
  const y = viewYear.value
  const m = viewMonth.value
  const first = new Date(y, m - 1, 1)
  // 周一为一周第一天:0=周日 -> 调整为周一开头
  let startWeekday = first.getDay() - 1
  if (startWeekday < 0) startWeekday = 6
  const daysInMonth = new Date(y, m, 0).getDate()
  const todayDateStr = todayStr()
  const cells: CalendarCell[] = []
  // 前置空白
  for (let i = 0; i < startWeekday; i++) {
    cells.push({ key: `b${i}`, day: '', dateStr: '', clickable: false, hasMenu: false, isToday: false, isSelected: false, inMonth: false })
  }
  for (let d = 1; d <= daysInMonth; d++) {
    const ds = `${y}-${pad2(m)}-${pad2(d)}`
    cells.push({
      key: ds,
      day: d,
      dateStr: ds,
      clickable: true,
      hasMenu: menuDates.value.has(ds),
      isToday: ds === todayDateStr,
      isSelected: ds === selectedDate.value,
      inMonth: true,
    })
  }
  // 补齐到 7 的倍数
  while (cells.length % 7 !== 0) {
    cells.push({ key: `a${cells.length}`, day: '', dateStr: '', clickable: false, hasMenu: false, isToday: false, isSelected: false, inMonth: false })
  }
  return cells
})

const cellClass = (cell: CalendarCell) => {
  if (!cell.inMonth) return 'h-9 text-text-muted/30'
  const base = 'relative h-9 rounded-md text-sm transition-colors cursor-pointer flex items-center justify-center '
  if (cell.isSelected) return base + 'bg-primary text-white font-semibold'
  if (cell.isToday) return base + 'border border-primary text-primary'
  if (cell.hasMenu) return base + 'bg-primary/10 text-text hover:bg-primary/20'
  return base + 'text-text-secondary hover:bg-bg-tertiary'
}

const selectDate = (ds: string) => {
  selectedDate.value = ds
  // 同步快速复制的目标日期为选中的日期
  copyTargetDate.value = ds
}

const prevMonth = () => {
  if (viewMonth.value === 1) {
    viewMonth.value = 12
    viewYear.value -= 1
  } else {
    viewMonth.value -= 1
  }
}
const nextMonth = () => {
  if (viewMonth.value === 12) {
    viewMonth.value = 1
    viewYear.value += 1
  } else {
    viewMonth.value += 1
  }
}

// 选中日期变化时,同步快速复制目标日期
watch(selectedDate, (v) => {
  copyTargetDate.value = v
})

watch([viewYear, viewMonth], fetchMenuDates)
onMounted(() => {
  copyTargetDate.value = selectedDate.value
  fetchDishes()
  fetchDayMenus()
  fetchMenuDates()
})
</script>

<template>
  <Layout>
    <PageContainer title="菜单管理" description="按日期编排每日早、中、晚三餐菜品,支持月历快速切换与菜单复制。">
      <template #actions>
        <ElButton v-if="isSuperAdmin" :icon="Settings" @click="openOrderConfig">订餐配置</ElButton>
        <ElButton type="primary" :icon="Plus" @click="openCreate()">新增菜单</ElButton>
      </template>

      <div
        v-if="!storeId"
        class="mb-4 rounded-lg border border-amber-300 bg-amber-50 px-4 py-3 text-sm text-amber-700"
      >
        请先选择食堂后再查看数据。
      </div>

      <div class="grid grid-cols-1 gap-5 lg:grid-cols-3">
        <!-- 左侧:当日三餐 -->
        <div class="space-y-5 lg:col-span-2">
          <!-- 日期信息条 -->
          <div class="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-border bg-card p-4 shadow-sm">
            <div class="flex items-center gap-2">
              <CalendarDays class="h-5 w-5 text-primary" />
              <span class="text-base font-semibold text-text">{{ fmtDate(selectedDate) }}</span>
            </div>
            <span class="text-sm text-text-muted">共 {{ totalDishes }} 道菜 · {{ dayMenus.length }} 个餐次</span>
          </div>

          <!-- 三餐区块 -->
          <div v-loading="loading" class="grid grid-cols-1 gap-5 md:grid-cols-3">
            <div
              v-for="col in mealColumns"
              :key="col.type"
              class="flex flex-col rounded-2xl border border-border bg-card shadow-sm overflow-hidden"
            >
              <div
                class="flex items-center justify-between px-5 py-4"
                :style="{ background: `${MEAL_TYPE[col.type].color}14` }"
              >
                <div class="flex items-center gap-2">
                  <component :is="col.icon" class="h-5 w-5" :style="{ color: MEAL_TYPE[col.type].color }" />
                  <span class="font-semibold text-text">{{ col.label }}</span>
                </div>
                <ElButton size="small" type="primary" plain :icon="Plus" @click="openCreate(col.type)">
                  {{ menuOf(col.type) ? '编辑' : '添加' }}
                </ElButton>
              </div>

              <div class="flex-1 p-4">
                <template v-if="menuOf(col.type)">
                  <div class="space-y-3">
                    <div
                      v-for="it in menuOf(col.type)?.items"
                      :key="it.item?.id"
                      class="group flex items-center justify-between rounded-xl border border-border-light bg-bg-secondary px-4 py-3 transition-colors hover:border-primary"
                    >
                      <div class="min-w-0">
                        <div class="truncate text-sm font-medium text-text">
                          {{ it.dish?.name || '未知菜品' }}
                        </div>
                        <div class="mt-0.5 text-xs tabular-nums text-text-muted">
                          ¥{{ Number(it.dish?.price ?? 0).toFixed(2) }}
                        </div>
                      </div>
                      <ElButton
                        link
                        type="danger"
                        :icon="Trash2"
                        class="opacity-0 transition-opacity group-hover:opacity-100"
                        @click="handleDelete(menuOf(col.type)!)"
                      >
                      </ElButton>
                    </div>
                  </div>
                </template>
                <div v-else>
                  <EmptyState :description="col.empty" />
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- 右侧:月历 + 快速复制 -->
        <div class="space-y-5">
          <div class="rounded-xl border border-border bg-card p-4 shadow-sm">
            <!-- 月份导航 -->
            <div class="mb-3 flex items-center justify-between">
              <ElButton :icon="ChevronLeft" circle size="small" @click="prevMonth" />
              <span class="font-semibold text-text">{{ viewYear }}年{{ viewMonth }}月</span>
              <ElButton :icon="ChevronRight" circle size="small" @click="nextMonth" />
            </div>
            <!-- 星期表头 -->
            <div class="mb-1 grid grid-cols-7 gap-1 text-center text-xs text-text-muted">
              <div v-for="w in weekHeaders" :key="w" class="py-1">{{ w }}</div>
            </div>
            <!-- 日期格子 -->
            <div class="grid grid-cols-7 gap-1">
              <button
                v-for="cell in calendarCells"
                :key="cell.key"
                type="button"
                :class="cellClass(cell)"
                :disabled="!cell.clickable"
                @click="cell.clickable && selectDate(cell.dateStr)"
              >
                <span>{{ cell.day }}</span>
                <span
                  v-if="cell.hasMenu && !cell.isSelected"
                  class="absolute bottom-1 left-1/2 h-1 w-1 -translate-x-1/2 rounded-full bg-primary"
                ></span>
              </button>
            </div>
            <!-- 图例 -->
            <div class="mt-3 flex items-center gap-4 border-t border-border-light pt-3 text-xs text-text-muted">
              <span class="flex items-center gap-1.5">
                <span class="h-1.5 w-1.5 rounded-full bg-primary"></span>已配置
              </span>
              <span class="flex items-center gap-1.5">
                <span class="h-3 w-3 rounded border border-primary"></span>今日
              </span>
            </div>
          </div>

          <!-- 快速复制卡片 -->
          <div class="rounded-xl border border-border bg-card p-4 shadow-sm">
            <div class="mb-3 text-sm font-medium text-text">快速复制</div>
            <div class="space-y-3">
              <div>
                <div class="mb-1 text-xs text-text-muted">目标日期(复制到当天)</div>
                <ElDatePicker
                  v-model="copyTargetDate"
                  type="date"
                  value-format="YYYY-MM-DD"
                  :clearable="false"
                  placeholder="目标日期"
                  style="width: 100%"
                  size="small"
                />
              </div>
              <div>
                <div class="mb-1 text-xs text-text-muted">源日期(被复制的日期)</div>
                <ElDatePicker
                  v-model="copySourceDate"
                  type="date"
                  value-format="YYYY-MM-DD"
                  :clearable="false"
                  placeholder="源日期"
                  style="width: 100%"
                  size="small"
                />
              </div>
              <ElButton
                type="primary"
                :icon="Copy"
                :loading="copySaving"
                class="w-full"
                @click="handleQuickCopy"
              >
                复制 {{ copySourceDate }} 到 {{ copyTargetDate }}
              </ElButton>
              <div class="text-xs text-text-muted">
                将源日期的早/中/晚三餐菜单复制到目标日期(覆盖目标日期已有菜单)
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- 新增/编辑菜单弹窗 -->
      <ElDialog
        v-model="dialogVisible"
        title="新增/编辑菜单"
        width="680px"
        :close-on-click-modal="false"
        append-to-body
        destroy-on-close
      >
        <ElForm :model="form" label-width="80px" label-position="right">
          <ElFormItem label="日期" required>
            <ElDatePicker
              v-model="form.date"
              type="date"
              value-format="YYYY-MM-DD"
              placeholder="选择日期"
              style="width: 100%"
            />
          </ElFormItem>
          <ElFormItem label="餐次" required>
            <ElSelect v-model="form.mealType" style="width: 100%">
              <ElOption
                v-for="(meta, key) in MEAL_TYPE"
                :key="key"
                :label="meta.label"
                :value="Number(key)"
              />
            </ElSelect>
          </ElFormItem>
          <ElFormItem label="菜品" required>
            <ElTransfer
              v-model="form.dishIds"
              :data="transferData"
              :titles="['可选菜品', '已选菜品']"
              filterable
              filter-placeholder="搜索菜品"
              :props="{ key: 'key', label: 'label' }"
              style="width: 100%"
            />
          </ElFormItem>
        </ElForm>
        <template #footer>
          <div class="flex justify-end gap-3">
            <ElButton @click="dialogVisible = false">取消</ElButton>
            <ElButton type="primary" :loading="saving" @click="handleSave">发布菜单</ElButton>
          </div>
        </template>
      </ElDialog>

      <!-- 订餐配置弹窗 -->
      <ElDialog
        v-model="orderConfigVisible"
        title="订餐配置"
        width="560px"
        :close-on-click-modal="false"
        append-to-body
        destroy-on-close
      >
        <ElForm :model="orderForm" label-width="140px" label-position="right">
          <ElFormItem label="可提前预订天数">
            <ElInputNumber v-model="orderForm.order_advance_days" :min="0" :max="60" />
            <span class="ml-2 text-xs text-text-muted">0=仅当天</span>
          </ElFormItem>
          <ElFormItem label="订餐截止时间">
            <ElTimePicker
              v-model="orderForm.order_deadline_time"
              value-format="HH:mm"
              format="HH:mm"
              placeholder="如 15:00"
              style="width: 160px"
            />
            <span class="ml-3 text-xs text-text-muted">前一天此时间后不可订次日</span>
          </ElFormItem>
          <ElFormItem label="取消截止时间">
            <ElTimePicker
              v-model="orderForm.cancel_deadline_time"
              value-format="HH:mm"
              format="HH:mm"
              placeholder="如 15:00"
              style="width: 160px"
            />
            <span class="ml-3 text-xs text-text-muted">前一天此时间后不可取消次日</span>
          </ElFormItem>
          <ElFormItem label="单次最大订餐数">
            <ElInputNumber v-model="orderForm.max_order_quantity" :min="1" :max="100" />
          </ElFormItem>
          <ElFormItem label="允许跨日订餐">
            <ElSwitch v-model="orderForm.allow_cross_day_order" />
            <span class="ml-3 text-xs text-text-muted">关闭后仅可订当日菜品</span>
          </ElFormItem>
        </ElForm>
        <div class="mb-4 rounded-lg bg-bg-secondary px-4 py-3 text-xs text-text-muted">
          <div class="font-medium text-text">规则说明</div>
          <ul class="mt-1 list-disc space-y-1 pl-5">
            <li>次日订单须在前一天截止时间之前下单/取消,过后不允许。</li>
            <li>当天订单和历史订单不受此限制。</li>
          </ul>
        </div>
        <template #footer>
          <div class="flex justify-end gap-3">
            <ElButton @click="orderConfigVisible = false">取消</ElButton>
            <ElButton type="primary" :loading="orderConfigSaving" @click="saveOrderConfig">保存配置</ElButton>
          </div>
        </template>
      </ElDialog>
    </PageContainer>
  </Layout>
</template>
