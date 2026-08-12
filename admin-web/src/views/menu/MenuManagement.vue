<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import Layout from '@/components/Layout.vue'
import PageContainer from '@/components/PageContainer.vue'
import EmptyState from '@/components/EmptyState.vue'
import { useAuthStore } from '@/stores/auth'
import { menuApi, dishApi, systemApi } from '@/api'
import type { Dish, MenuWithItems } from '@/api'
import { MEAL_TYPE } from '@/constants/dict'
import {
  ElButton,
  ElDialog,
  ElForm,
  ElFormItem,
  ElDatePicker,
  ElSelect,
  ElOption,
  ElInput,
  ElMessage,
  ElMessageBox,
  ElSwitch,
  ElInputNumber,
  ElTimePicker,
  ElTag,
  ElRadioGroup,
  ElRadioButton,
} from 'element-plus'
import { Plus, CalendarDays, Trash2, Sun, Coffee, Moon, Copy, ChevronLeft, ChevronRight, Settings, Send, Search, X } from 'lucide-vue-next'
import { todayStr } from '@/utils/date'
import { normalizeList } from '@/utils/list'

const authStore = useAuthStore()
// 超管未选择食堂时返回 null,不再静默回退到 storeId=1
const storeId = computed(() => authStore.storeId || null)

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
/** 日期 -> {published: boolean} 用于月历标记已发布/未发布 */
const menuDateMap = ref<Map<string, boolean>>(new Map())

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
    menuDateMap.value = new Map()
    return
  }
  try {
    const raw = await menuApi.getDatesByMonth(sid, viewYear.value, viewMonth.value)
    const arr = normalizeList<{ date: string; published: boolean }>(raw)
    const map = new Map<string, boolean>()
    for (const item of arr) {
      if (item && item.date) {
        map.set(item.date, !!item.published)
      }
    }
    menuDateMap.value = map
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

/** 解析菜品适用餐次(逗号分隔,缺失时默认早中晚都适用) */
const dishMealTypes = (d: Dish): number[] => {
  if (!d.mealTypes) return [1, 2, 3]
  return d.mealTypes.split(',').map((x) => Number(x)).filter((x) => !isNaN(x))
}

// ===== 添加菜品弹窗:双栏点击式选择 =====
const dishSearchKey = ref('')
/** 可选菜品:过滤上架+适用餐次+排除已选+搜索关键词 */
const availableDishes = computed(() =>
  dishes.value
    .filter((d) => d.status === 1 && dishMealTypes(d).includes(form.value.mealType))
    .filter((d) => !form.value.dishIds.includes(d.id!))
    .filter((d) => !dishSearchKey.value || d.name.toLowerCase().includes(dishSearchKey.value.toLowerCase()))
)
/** 已选菜品详情列表 */
const selectedDishes = computed(() =>
  form.value.dishIds
    .map((id) => dishes.value.find((d) => d.id === id))
    .filter((d): d is Dish => !!d)
)
const addDish = (id: number) => {
  if (!form.value.dishIds.includes(id)) {
    form.value.dishIds.push(id)
  }
}
const removeDish = (id: number) => {
  form.value.dishIds = form.value.dishIds.filter((d) => d !== id)
}

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
    ElMessage.success('菜品添加成功')
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

// ===== 发布当天菜单(二次确认) =====
const publishing = ref(false)

/** 当天是否已发布(所有餐次菜单 published=1) */
const isDayPublished = computed(() => {
  if (dayMenus.value.length === 0) return false
  return dayMenus.value.every((mwi) => (mwi.menu.published ?? 0) === 1)
})

const handlePublish = async () => {
  const sid = storeId.value
  if (!sid || !selectedDate.value) {
    ElMessage.warning('请先选择日期')
    return
  }
  if (dayMenus.value.length === 0) {
    ElMessage.warning('当天无菜单可发布,请先添加菜单')
    return
  }
  try {
    await ElMessageBox.confirm(
      `确定要发布 ${selectedDate.value} 的菜单吗？发布后员工端将可以看到并点菜。`,
      '发布确认',
      {
        type: 'warning',
        confirmButtonText: '确认发布',
        cancelButtonText: '取消',
      }
    )
  } catch {
    return
  }
  publishing.value = true
  try {
    const res = await menuApi.publish(sid, selectedDate.value)
    ElMessage.success(`发布成功,共发布 ${res?.published ?? 0} 个餐次`)
    await fetchDayMenus()
    await fetchMenuDates()
  } catch {
    /* 拦截器提示 */
  } finally {
    publishing.value = false
  }
}

// ===== 清空当天菜单(二次确认,有订单时额外提示) =====
const clearing = ref(false)

const MEAL_TYPE_LABELS: Record<string, string> = { '1': '早餐', '2': '午餐', '3': '晚餐' }

const handleClearDay = async () => {
  const sid = storeId.value
  if (!sid || !selectedDate.value) {
    ElMessage.warning('请先选择日期')
    return
  }
  if (dayMenus.value.length === 0) {
    ElMessage.warning('当天无菜单可清空')
    return
  }
  // 先查询订单情况
  let orderInfo: { mealOrders: Record<string, number>; total: number } | null = null
  try {
    orderInfo = await menuApi.checkOrders(sid, selectedDate.value)
  } catch {
    /* 查询失败不阻断,后续正常清空 */
  }
  let promptMsg = `确定要清空 ${selectedDate.value} 的所有菜单吗？此操作不可恢复。`
  if (orderInfo && orderInfo.total > 0) {
    const mealDetails = Object.entries(orderInfo.mealOrders)
      .map(([mt, cnt]) => `${MEAL_TYPE_LABELS[mt] || mt}餐 ${cnt} 单`)
      .join('，')
    promptMsg = `⚠️ ${selectedDate.value} 已有 ${orderInfo.total} 个订单（${mealDetails}）。\n\n清空菜单不会删除已有订单（订单保存了菜品快照），但员工将无法再下单该日菜品。\n\n确定要继续清空吗？`
  }
  try {
    await ElMessageBox.confirm(promptMsg, '清空菜单确认', {
      type: orderInfo && orderInfo.total > 0 ? 'error' : 'warning',
      confirmButtonText: '确认清空',
      cancelButtonText: '取消',
      dangerouslyUseHTMLString: false,
    })
  } catch {
    return
  }
  clearing.value = true
  try {
    const res = await menuApi.clearByDate(sid, selectedDate.value)
    ElMessage.success(`已清空 ${res?.cleared ?? 0} 个餐次菜单`)
    await fetchDayMenus()
    await fetchMenuDates()
  } catch {
    /* 拦截器提示 */
  } finally {
    clearing.value = false
  }
}

// ===== 批量发布菜单 =====
const openBatchPublish = async () => {
  const sid = storeId.value
  if (!sid) {
    ElMessage.warning('请先选择食堂')
    return
  }
  try {
    await ElMessageBox.confirm(
      '确定要发布所有未发布的菜单吗？发布后员工端将可以看到并点菜。',
      '批量发布确认',
      { type: 'warning', confirmButtonText: '确认发布', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    const res = await menuApi.batchPublish(sid)
    ElMessage.success(`批量发布完成,共发布 ${res?.published ?? 0} 个餐次,涉及 ${res?.daysPublished ?? 0} 天`)
    await fetchDayMenus()
    await fetchMenuDates()
  } catch {
    /* 拦截器提示 */
  }
}

// ===== 批量复制菜单 =====
const batchSourceRange = ref<[string, string] | null>(null)
const batchTargetMode = ref<'single' | 'range'>('single')
const batchTargetStart = ref<string>('')
const batchTargetRange = ref<[string, string] | null>(null)
const batchSaving = ref(false)

// 冲突处理
interface ConflictItem {
  date: string
  mealTypes: number[]
  action: 'overwrite' | 'skip'
}
const conflictVisible = ref(false)
const conflictList = ref<ConflictItem[]>([])
const conflictResolving = ref(false)
// 缓存待执行的批量复制参数(冲突对话框确认后使用)
const pendingBatch = ref<{ sourceDates: string[]; targetDates: string[] } | null>(null)

const mealLabel = (t: number) => MEAL_TYPE[t as 1 | 2 | 3]?.label || '?'

/** 计算日期范围内所有日期(含首尾) */
const getDatesInRange = (start: string, end: string): string[] => {
  if (!start || !end) return []
  const s = new Date(start + 'T00:00:00')
  const e = new Date(end + 'T00:00:00')
  if (s > e) return []
  const dates: string[] = []
  const cur = new Date(s)
  while (cur <= e) {
    dates.push(`${cur.getFullYear()}-${pad2(cur.getMonth() + 1)}-${pad2(cur.getDate())}`)
    cur.setDate(cur.getDate() + 1)
  }
  return dates
}

/** 从起始日期生成 count 个连续日期 */
const getDatesFromStart = (start: string, count: number): string[] => {
  if (!start || count <= 0) return []
  const dates: string[] = []
  const cur = new Date(start + 'T00:00:00')
  for (let i = 0; i < count; i++) {
    dates.push(`${cur.getFullYear()}-${pad2(cur.getMonth() + 1)}-${pad2(cur.getDate())}`)
    cur.setDate(cur.getDate() + 1)
  }
  return dates
}

/** 执行批量复制(跳过 skipDates 中的日期) */
const executeBatchCopy = async (
  sid: number,
  sourceDates: string[],
  targetDates: string[],
  skipDates: Set<string>
) => {
  let totalCopied = 0
  let totalSkipped = 0
  for (let i = 0; i < sourceDates.length; i++) {
    const src = sourceDates[i]
    const tgt = targetDates[i]
    if (skipDates.has(tgt)) {
      totalSkipped++
      continue
    }
    try {
      const res = await menuApi.copy({
        storeId: sid,
        sourceDate: src,
        targetDate: tgt,
        overwrite: true,
      })
      totalCopied += res?.copied ?? 0
    } catch {
      /* 继续复制其他日期 */
    }
  }
  const msg =
    totalSkipped > 0
      ? `批量复制完成,共复制 ${totalCopied} 个餐次,跳过 ${totalSkipped} 个日期`
      : `批量复制完成,共复制 ${totalCopied} 个餐次`
  ElMessage.success(msg)
  await fetchMenuDates()
  if (targetDates.length > 0) {
    selectedDate.value = targetDates[0]
    await fetchDayMenus()
  }
}

/** 触发批量复制:校验 + 检测冲突 */
const handleBatchCopy = async () => {
  const sid = storeId.value
  if (!sid) {
    ElMessage.warning('请先选择食堂')
    return
  }
  if (!batchSourceRange.value || !batchSourceRange.value[0] || !batchSourceRange.value[1]) {
    ElMessage.warning('请选择源日期范围')
    return
  }

  const sourceDates = getDatesInRange(batchSourceRange.value[0], batchSourceRange.value[1])
  if (sourceDates.length === 0) {
    ElMessage.warning('源日期范围无效')
    return
  }

  // 计算目标日期
  let targetDates: string[] = []
  if (batchTargetMode.value === 'range') {
    if (!batchTargetRange.value || !batchTargetRange.value[0] || !batchTargetRange.value[1]) {
      ElMessage.warning('请选择目标日期范围')
      return
    }
    targetDates = getDatesInRange(batchTargetRange.value[0], batchTargetRange.value[1])
    if (targetDates.length !== sourceDates.length) {
      ElMessage.warning(
        `目标日期数量(${targetDates.length})与源日期数量(${sourceDates.length})不一致,请保持一致`
      )
      return
    }
  } else {
    if (!batchTargetStart.value) {
      ElMessage.warning('请选择目标起始日期')
      return
    }
    targetDates = getDatesFromStart(batchTargetStart.value, sourceDates.length)
  }

  // 校验源/目标不能重叠
  const sourceSet = new Set(sourceDates)
  for (const td of targetDates) {
    if (sourceSet.has(td)) {
      ElMessage.warning('目标日期与源日期重叠,请重新选择')
      return
    }
  }

  batchSaving.value = true
  try {
    // 检查目标日期是否有现有菜单(冲突检测)
    const conflicts: ConflictItem[] = []
    for (const date of targetDates) {
      try {
        const raw = await menuApi.getByDate(sid, date)
        const mealTypes = normalizeList<MenuWithItems>(raw)
          .map((m) => m.menu.mealType)
          .filter(Boolean) as number[]
        if (mealTypes.length > 0) {
          conflicts.push({ date, mealTypes, action: 'skip' })
        }
      } catch {
        /* ignore */
      }
    }

    if (conflicts.length > 0) {
      // 显示冲突对话框,等待用户处理
      conflictList.value = conflicts
      pendingBatch.value = { sourceDates, targetDates }
      conflictVisible.value = true
      return
    }

    // 无冲突,直接复制
    await executeBatchCopy(sid, sourceDates, targetDates, new Set())
  } catch {
    /* 拦截器提示 */
  } finally {
    batchSaving.value = false
  }
}

/** 冲突对话框:全部覆盖 */
const conflictAllOverwrite = () => {
  conflictList.value.forEach((c) => (c.action = 'overwrite'))
}

/** 冲突对话框:全部跳过 */
const conflictAllSkip = () => {
  conflictList.value.forEach((c) => (c.action = 'skip'))
}

/** 冲突对话框:确认处理 */
const confirmConflicts = async () => {
  const sid = storeId.value
  const batch = pendingBatch.value
  if (!sid || !batch) return

  const skipDates = new Set(
    conflictList.value.filter((c) => c.action === 'skip').map((c) => c.date)
  )

  conflictResolving.value = true
  try {
    await executeBatchCopy(sid, batch.sourceDates, batch.targetDates, skipDates)
    conflictVisible.value = false
    pendingBatch.value = null
  } catch {
    /* 拦截器提示 */
  } finally {
    conflictResolving.value = false
    batchSaving.value = false
  }
}

// ===== 订餐配置弹窗(按门店) =====
const orderConfigVisible = ref(false)
const orderConfigSaving = ref(false)
const orderForm = ref({
  order_advance_days: 7,
  order_deadline_time: '15:00',
  cancel_deadline_time: '15:00',
  max_order_quantity: 10,
  allow_cross_day_order: true,
})

const fetchOrderConfig = async () => {
  const sidVal = storeId.value
  if (!sidVal) return
  try {
    const cfg = await systemApi.getOrderConfig(sidVal)
    orderForm.value = {
      order_advance_days: Number(cfg.order_advance_days) || 7,
      order_deadline_time: cfg.order_deadline_time || '15:00',
      cancel_deadline_time: cfg.cancel_deadline_time || '15:00',
      max_order_quantity: Number(cfg.max_order_quantity) || 10,
      allow_cross_day_order: cfg.allow_cross_day_order === true || cfg.allow_cross_day_order === 'true',
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
  const sidVal = storeId.value
  if (!sidVal) {
    ElMessage.warning('请先选择食堂')
    return
  }
  orderConfigSaving.value = true
  try {
    await systemApi.updateOrderConfig(sidVal, [
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
  published: boolean
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
    cells.push({ key: `b${i}`, day: '', dateStr: '', clickable: false, hasMenu: false, published: false, isToday: false, isSelected: false, inMonth: false })
  }
  for (let d = 1; d <= daysInMonth; d++) {
    const ds = `${y}-${pad2(m)}-${pad2(d)}`
    cells.push({
      key: ds,
      day: d,
      dateStr: ds,
      clickable: true,
      hasMenu: menuDateMap.value.has(ds),
      published: menuDateMap.value.get(ds) === true,
      isToday: ds === todayDateStr,
      isSelected: ds === selectedDate.value,
      inMonth: true,
    })
  }
  // 补齐到 7 的倍数
  while (cells.length % 7 !== 0) {
    cells.push({ key: `a${cells.length}`, day: '', dateStr: '', clickable: false, hasMenu: false, published: false, isToday: false, isSelected: false, inMonth: false })
  }
  return cells
})

const cellClass = (cell: CalendarCell) => {
  if (!cell.inMonth) return 'h-9 text-text-muted/30'
  const base = 'relative h-9 rounded-md text-sm transition-colors cursor-pointer flex items-center justify-center '
  if (cell.isSelected) return base + 'bg-primary text-white font-semibold'
  if (cell.isToday) return base + 'border border-primary text-primary'
  // 已发布:绿色背景;有菜单未发布:橙色背景
  if (cell.hasMenu && cell.published) return base + 'bg-success/15 text-text hover:bg-success/25'
  if (cell.hasMenu && !cell.published) return base + 'bg-warning/15 text-text hover:bg-warning/25'
  return base + 'text-text-secondary hover:bg-bg-tertiary'
}

const selectDate = (ds: string) => {
  selectedDate.value = ds
  fetchDayMenus()
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

watch([viewYear, viewMonth], fetchMenuDates)
onMounted(() => {
  fetchDishes()
  fetchDayMenus()
  fetchMenuDates()
})
</script>

<template>
  <Layout>
    <PageContainer title="菜单管理" description="按日期编排每日早、中、晚三餐菜品,支持月历快速切换与菜单复制。">
      <template #actions>
        <ElButton v-if="storeId" :icon="Settings" @click="openOrderConfig">订餐配置</ElButton>
        <ElButton type="primary" :icon="Send" @click="openBatchPublish">批量发布</ElButton>
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
              <ElTag v-if="dayMenus.length > 0 && isDayPublished" type="success" size="small">已发布</ElTag>
              <ElTag v-else-if="dayMenus.length > 0" type="warning" size="small">未发布</ElTag>
              <ElTag v-else type="info" size="small">无菜单</ElTag>
            </div>
            <div class="flex items-center gap-3">
              <span class="text-sm text-text-muted">共 {{ totalDishes }} 道菜 · {{ dayMenus.length }} 个餐次</span>
              <ElButton
                v-if="dayMenus.length > 0 && !isDayPublished"
                type="primary"
                :icon="Send"
                :loading="publishing"
                size="small"
                @click="handlePublish"
              >发布</ElButton>
              <ElButton
                v-if="dayMenus.length > 0 && !isDayPublished"
                type="danger"
                :icon="Trash2"
                :loading="clearing"
                size="small"
                plain
                @click="handleClearDay"
              >清空</ElButton>
            </div>
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

        <!-- 右侧:月历 + 保存菜单 + 批量复制 -->
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
                  class="absolute bottom-1 left-1/2 h-1.5 w-1.5 -translate-x-1/2 rounded-full"
                  :class="cell.published ? 'bg-success' : 'bg-warning'"
                ></span>
              </button>
            </div>
            <!-- 图例 -->
            <div class="mt-3 flex items-center gap-4 border-t border-border-light pt-3 text-xs text-text-muted">
              <span class="flex items-center gap-1.5">
                <span class="h-1.5 w-1.5 rounded-full bg-success"></span>已发布
              </span>
              <span class="flex items-center gap-1.5">
                <span class="h-1.5 w-1.5 rounded-full bg-warning"></span>未发布
              </span>
              <span class="flex items-center gap-1.5">
                <span class="h-3 w-3 rounded border border-primary"></span>今日
              </span>
            </div>
          </div>

          <!-- 批量复制菜单卡片 -->
          <div class="rounded-xl border border-border bg-card p-4 shadow-sm">
            <div class="mb-3 text-sm font-medium text-text">批量复制菜单</div>
            <div class="space-y-3">
              <!-- 源日期范围 -->
              <div>
                <div class="mb-1 text-xs text-text-muted">源日期范围（被复制的日期）</div>
                <ElDatePicker
                  v-model="batchSourceRange"
                  type="daterange"
                  value-format="YYYY-MM-DD"
                  range-separator="至"
                  start-placeholder="开始日期"
                  end-placeholder="结束日期"
                  style="width: 100%"
                  size="small"
                />
              </div>

              <!-- 目标日期模式切换 -->
              <div>
                <div class="mb-1 text-xs text-text-muted">目标日期</div>
                <ElRadioGroup v-model="batchTargetMode" size="small" class="mb-2">
                  <ElRadioButton value="single">按起始日期自动顺序粘贴</ElRadioButton>
                  <ElRadioButton value="range">按日期范围</ElRadioButton>
                </ElRadioGroup>
                <!-- 单日期模式 -->
                <ElDatePicker
                  v-if="batchTargetMode === 'single'"
                  v-model="batchTargetStart"
                  type="date"
                  value-format="YYYY-MM-DD"
                  placeholder="选择起始日期（自动按顺序粘贴）"
                  style="width: 100%"
                  size="small"
                />
                <!-- 范围模式 -->
                <ElDatePicker
                  v-else
                  v-model="batchTargetRange"
                  type="daterange"
                  value-format="YYYY-MM-DD"
                  range-separator="至"
                  start-placeholder="开始日期"
                  end-placeholder="结束日期"
                  style="width: 100%"
                  size="small"
                />
              </div>

              <ElButton
                type="primary"
                :icon="Copy"
                :loading="batchSaving"
                class="w-full"
                @click="handleBatchCopy"
              >
                批量复制
              </ElButton>
              <div class="text-xs text-text-muted">
                将源日期范围的早/中/晚三餐菜单批量复制到目标日期。若目标日期已存在菜单,将按餐别提示覆盖或跳过。
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- 菜单冲突处理弹窗 -->
      <ElDialog
        v-model="conflictVisible"
        title="菜单冲突处理"
        width="520px"
        :close-on-click-modal="false"
        append-to-body
      >
        <div class="space-y-3">
          <p class="text-sm text-text-secondary">
            以下目标日期已存在菜单,请选择处理方式（覆盖或跳过）:
          </p>
          <div
            v-for="c in conflictList"
            :key="c.date"
            class="rounded-lg border border-border-light p-3"
          >
            <div class="mb-2 flex items-center justify-between">
              <span class="text-sm font-medium text-text">{{ c.date }}</span>
              <span class="text-xs text-text-muted">
                已存在: {{ c.mealTypes.map(mealLabel).join('、') }}
              </span>
            </div>
            <ElRadioGroup v-model="c.action" size="small">
              <ElRadioButton value="overwrite">覆盖</ElRadioButton>
              <ElRadioButton value="skip">跳过</ElRadioButton>
            </ElRadioGroup>
          </div>
        </div>
        <template #footer>
          <div class="flex items-center justify-between">
            <div class="flex gap-2">
              <ElButton size="small" @click="conflictAllOverwrite">全部覆盖</ElButton>
              <ElButton size="small" @click="conflictAllSkip">全部跳过</ElButton>
            </div>
            <div class="flex gap-3">
              <ElButton @click="conflictVisible = false">取消</ElButton>
              <ElButton type="primary" :loading="conflictResolving" @click="confirmConflicts">
                确认复制
              </ElButton>
            </div>
          </div>
        </template>
      </ElDialog>

      <!-- 添加菜品弹窗 -->
      <ElDialog
        v-model="dialogVisible"
        title="添加菜品"
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
            <div class="flex gap-3 w-full" style="height: 360px;">
              <!-- 左栏:可选菜品 -->
              <div class="flex-1 flex flex-col border rounded-lg overflow-hidden">
                <div class="p-2 border-b bg-gray-50">
                  <ElInput v-model="dishSearchKey" placeholder="搜索菜品名称" :prefix-icon="Search" size="small" clearable />
                </div>
                <div class="flex-1 overflow-y-auto">
                  <div v-if="availableDishes.length === 0" class="p-4 text-center text-gray-400 text-sm">
                    {{ dishSearchKey ? '未找到匹配菜品' : '暂无可选菜品' }}
                  </div>
                  <div
                    v-for="d in availableDishes"
                    :key="d.id"
                    class="flex items-center justify-between px-3 py-2 hover:bg-blue-50 cursor-pointer border-b border-gray-100 transition-colors"
                    @click="addDish(d.id!)"
                  >
                    <div class="flex-1 min-w-0">
                      <span class="text-sm font-medium">{{ d.name }}</span>
                      <span class="ml-2 text-xs text-gray-500">¥{{ Number(d.price).toFixed(2) }}</span>
                    </div>
                    <ElButton type="primary" size="small" circle :icon="Plus" @click.stop="addDish(d.id!)" />
                  </div>
                </div>
              </div>
              <!-- 右栏:已选菜品 -->
              <div class="flex-1 flex flex-col border rounded-lg overflow-hidden">
                <div class="px-3 py-2 border-b bg-gray-50 flex items-center justify-between">
                  <span class="text-sm font-medium">已选 {{ selectedDishes.length }} 道</span>
                  <ElButton v-if="selectedDishes.length > 0" type="danger" size="small" text @click="form.dishIds = []">清空</ElButton>
                </div>
                <div class="flex-1 overflow-y-auto">
                  <div v-if="selectedDishes.length === 0" class="p-4 text-center text-gray-400 text-sm">
                    点击左侧菜品添加
                  </div>
                  <div
                    v-for="d in selectedDishes"
                    :key="d.id"
                    class="flex items-center justify-between px-3 py-2 hover:bg-red-50 cursor-pointer border-b border-gray-100 transition-colors"
                    @click="removeDish(d.id!)"
                  >
                    <div class="flex-1 min-w-0">
                      <span class="text-sm font-medium">{{ d.name }}</span>
                      <span class="ml-2 text-xs text-gray-500">¥{{ Number(d.price).toFixed(2) }}</span>
                    </div>
                    <ElButton type="danger" size="small" circle :icon="X" @click.stop="removeDish(d.id!)" />
                  </div>
                </div>
              </div>
            </div>
          </ElFormItem>
        </ElForm>
        <template #footer>
          <div class="flex justify-end gap-3">
            <ElButton @click="dialogVisible = false">取消</ElButton>
            <ElButton type="primary" :loading="saving" @click="handleSave">添加</ElButton>
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
