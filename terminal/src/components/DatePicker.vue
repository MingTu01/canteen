<script setup lang="ts">
/**
 * 日期选择器(点击触发弹出月历)
 *
 * 用于 DateSidebar 头部:显示当前日期 + 日历图标,点击弹出月历模态。
 * - 触发按钮:竖向布局(日历图标 + MM-DD + 相对标签),适配窄宽度 sidebar
 * - 弹出月历:月份切换 + 7 列网格 + 日一二三四五六表头
 * - 可选日期(在 dates 范围内且 available):高亮可点击
 * - 不可选日期:暗色不可点击,点击时网格闪烁红光提示
 * - 选择后关闭弹窗,触发 select 事件
 *
 * Props:
 *   dates: 日期字符串数组(yyyy-MM-dd),决定哪些日期在范围内
 *   selectedDate: 当前选中的日期
 *   availableSet: 可选日期集合(Set<string>)
 *   markedSet: 已订餐日期集合(Set<string>),这些日期下方显示蓝色圆点标记;
 *              未传时回退到 availableSet(向后兼容)
 */
import { ref, computed, watch, nextTick } from 'vue'
import { parseDateKey, relativeLabel, pad2, toDateKey } from '@/utils'
import { Calendar, ChevronLeft, ChevronRight, X } from 'lucide-vue-next'

interface DateCell {
  key: string
  md: string
  rel: string
  available: boolean
  inDates: boolean
  marked: boolean
  isPlaceholder: boolean
}

const props = withDefaults(defineProps<{
  dates: string[]
  selectedDate: string
  availableSet: Set<string>
  markedSet?: Set<string>
}>(), {})

const emit = defineEmits<{ (e: 'select', key: string): void }>()

const WEEK_HEADER = ['日', '一', '二', '三', '四', '五', '六']

const showPicker = ref(false)
const datesSet = computed(() => new Set(props.dates))

/** 取日期字符串的月份部分 yyyy-MM */
const getMonthKey = (dateStr: string): string => {
  if (!dateStr) return ''
  const [y, m] = dateStr.split('-')
  return `${y}-${m}`
}

/** 当前显示的月份,默认跟随选中日期 */
const currentMonth = ref(
  getMonthKey(props.selectedDate || props.dates[0] || toDateKey(new Date()))
)

watch(() => props.selectedDate, (v) => {
  if (v) currentMonth.value = getMonthKey(v)
})

const monthLabel = computed(() => {
  const [y, m] = currentMonth.value.split('-').map(Number)
  return `${y}年${m}月`
})

/** 当前月的网格 */
const grid = computed<DateCell[]>(() => {
  if (!currentMonth.value) return []
  const [y, m] = currentMonth.value.split('-').map(Number)
  const firstOfMonth = new Date(y, m - 1, 1)
  const leadPlaceholders = firstOfMonth.getDay()
  const daysInMonth = new Date(y, m, 0).getDate()
  const cells: DateCell[] = []

  for (let i = 0; i < leadPlaceholders; i++) {
    cells.push({ key: `ph-${i}`, md: '', rel: '', available: false, inDates: false, marked: false, isPlaceholder: true })
  }
  // markedSet 未传时回退到 availableSet(向后兼容:订单查询页 availableSet 即已订餐日期)
  const markedSet = props.markedSet ?? props.availableSet
  for (let day = 1; day <= daysInMonth; day++) {
    const key = `${y}-${pad2(m)}-${pad2(day)}`
    cells.push({
      key,
      md: `${pad2(m)}-${pad2(day)}`,
      rel: relativeLabel(key),
      available: props.availableSet.has(key),
      inDates: datesSet.value.has(key),
      marked: markedSet.has(key),
      isPlaceholder: false,
    })
  }
  while (cells.length % 7 !== 0) {
    cells.push({ key: `ph-end-${cells.length}`, md: '', rel: '', available: false, inDates: false, marked: false, isPlaceholder: true })
  }
  return cells
})

const prevMonth = () => {
  const [y, m] = currentMonth.value.split('-').map(Number)
  const d = new Date(y, m - 2, 1)
  currentMonth.value = `${d.getFullYear()}-${pad2(d.getMonth() + 1)}`
}
const nextMonth = () => {
  const [y, m] = currentMonth.value.split('-').map(Number)
  const d = new Date(y, m, 1)
  currentMonth.value = `${d.getFullYear()}-${pad2(d.getMonth() + 1)}`
}

/** 触发按钮显示的文字 */
const triggerMd = computed(() => {
  if (!props.selectedDate) return '日历'
  const d = parseDateKey(props.selectedDate)
  return `${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`
})
const triggerRel = computed(() => {
  if (!props.selectedDate) return ''
  return relativeLabel(props.selectedDate)
})

/** 闪烁动画 */
const flashRef = ref<HTMLElement | null>(null)
let flashTimer = 0
const triggerFlash = () => {
  nextTick(() => {
    const el = flashRef.value
    if (!el) return
    el.classList.remove('date-picker__grid--flash')
    void el.offsetWidth
    el.classList.add('date-picker__grid--flash')
    if (flashTimer) clearTimeout(flashTimer)
    flashTimer = window.setTimeout(() => {
      el?.classList.remove('date-picker__grid--flash')
    }, 600)
  })
}

const onItemClick = (it: DateCell) => {
  if (it.isPlaceholder) return
  if (!it.inDates || !it.available) {
    triggerFlash()
    return
  }
  emit('select', it.key)
  showPicker.value = false
}

watch(showPicker, (open) => {
  if (open && props.selectedDate && !props.availableSet.has(props.selectedDate)) {
    triggerFlash()
  }
})
</script>

<template>
  <div class="date-picker">
    <!-- 触发按钮(竖向:日历图标 + 日期 + 相对标签) -->
    <button
      type="button"
      class="date-picker__trigger btn-press"
      @click="showPicker = true"
    >
      <Calendar :size="28" />
      <span class="date-picker__trigger-md">{{ triggerMd }}</span>
      <!-- 相对标签始终占位,无内容时用空 span 保持高度一致,避免列表跳动 -->
      <span class="date-picker__trigger-rel">{{ triggerRel || '\u00A0' }}</span>
    </button>

    <!-- 弹出月历模态 -->
    <div
      v-if="showPicker"
      class="date-picker__overlay"
      @click.self="showPicker = false"
    >
      <div class="date-picker__panel">
        <!-- 顶部:月份导航 + 关闭 -->
        <div class="date-picker__head">
          <button class="date-picker__nav btn-press" aria-label="上一月" @click="prevMonth">
            <ChevronLeft :size="22" />
          </button>
          <span class="date-picker__month">{{ monthLabel }}</span>
          <button class="date-picker__nav btn-press" aria-label="下一月" @click="nextMonth">
            <ChevronRight :size="22" />
          </button>
          <button class="date-picker__close btn-press" aria-label="关闭" @click="showPicker = false">
            <X :size="18" />
          </button>
        </div>

        <!-- 表头 -->
        <div class="date-picker__week-header">
          <span v-for="w in WEEK_HEADER" :key="w" class="date-picker__week-cell">{{ w }}</span>
        </div>

        <!-- 月历网格 -->
        <div ref="flashRef" class="date-picker__grid">
          <button
            v-for="it in grid"
            :key="it.key"
            type="button"
            :class="[
              'date-cell',
              {
                'date-cell--placeholder': it.isPlaceholder,
                'date-cell--on': !it.isPlaceholder && it.inDates && it.available,
                'date-cell--off': !it.isPlaceholder && (!it.inDates || !it.available),
                'date-cell--active': !it.isPlaceholder && it.key === selectedDate,
              },
            ]"
            :disabled="it.isPlaceholder"
            @click="onItemClick(it)"
          >
            <template v-if="!it.isPlaceholder">
              <span class="date-cell__md">{{ it.md }}</span>
              <span
                class="date-cell__label"
                :class="{ 'date-cell__label--rel': !!it.rel }"
              >{{ it.rel }}</span>
              <!-- 已订餐蓝色圆点标记 -->
              <span v-if="it.marked" class="date-cell__dot"></span>
            </template>
          </button>
        </div>

        <!-- 底部提示 -->
        <div class="date-picker__footer">
          <span>暗色不可选</span>
          <span class="date-picker__footer-sub">可选范围 {{ dates.length }} 天</span>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* 触发按钮(竖向,放大适配 DateSidebar 130px 宽度) */
.date-picker__trigger {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  width: 100%;
  padding: 14px 8px;
  border: 1.5px solid var(--doubao-border);
  border-radius: var(--doubao-radius-sm);
  background: var(--doubao-card);
  color: var(--doubao-primary);
  font-family: inherit;
  cursor: pointer;
  transition: border-color 0.15s ease, background 0.15s ease;
}
.date-picker__trigger:hover {
  border-color: var(--doubao-primary);
  background: var(--doubao-accent);
}
.date-picker__trigger :deep(svg) {
  width: 28px;
  height: 28px;
}
.date-picker__trigger-md {
  font-size: var(--fs-lg);
  font-weight: 700;
  color: var(--doubao-foreground);
  font-variant-numeric: tabular-nums;
  line-height: 1.1;
}
.date-picker__trigger-rel {
  font-size: var(--fs-sm);
  font-weight: 700;
  color: var(--doubao-primary);
  line-height: 1;
}

/* 模态遮罩 */
.date-picker__overlay {
  position: fixed;
  inset: 0;
  z-index: 100;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: rgba(14, 17, 21, 0.5);
  backdrop-filter: blur(4px);
}

/* 弹窗面板 */
.date-picker__panel {
  width: 100%;
  max-width: 560px;
  background: var(--doubao-card);
  border: 1px solid var(--doubao-border);
  border-radius: var(--doubao-radius);
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.2);
  padding: 20px;
}

/* 顶部:月份导航 + 关闭 */
.date-picker__head {
  display: flex;
  align-items: center;
  justify-content: center;
  position: relative;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--doubao-border);
}
.date-picker__nav {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  border-radius: 50%;
  border: none;
  background: var(--doubao-muted);
  color: var(--doubao-foreground);
  cursor: pointer;
  transition: background 0.15s ease;
}
.date-picker__nav:hover {
  background: var(--doubao-accent);
  color: var(--doubao-primary);
}
.date-picker__month {
  margin: 0 24px;
  font-size: var(--fs-xl);
  font-weight: 700;
  color: var(--doubao-foreground);
}
.date-picker__close {
  position: absolute;
  right: 0;
  top: 50%;
  transform: translateY(-50%);
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border-radius: 50%;
  border: none;
  background: transparent;
  color: var(--doubao-muted-foreground);
  cursor: pointer;
  transition: background 0.15s ease, color 0.15s ease;
}
.date-picker__close:hover {
  background: var(--doubao-muted);
  color: var(--doubao-foreground);
}

/* 表头 */
.date-picker__week-header {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  gap: 6px;
  margin: 16px 0 6px;
}
.date-picker__week-cell {
  text-align: center;
  padding: 6px 0;
  font-size: var(--fs-sm);
  font-weight: 700;
  color: var(--doubao-muted-foreground);
}

/* 月历网格 */
.date-picker__grid {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  gap: 6px;
}
.date-picker__grid--flash {
  animation: dp-flash 0.6s ease;
}
@keyframes dp-flash {
  0%, 100% { background: transparent; }
  30%      { background: rgba(239, 68, 68, 0.12); }
  60%      { background: transparent; }
}

.date-cell {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  min-height: 64px;
  padding: 8px 4px;
  border-radius: var(--doubao-radius-sm);
  border: 1.5px solid transparent;
  background: transparent;
  color: var(--doubao-foreground);
  cursor: pointer;
  font-family: inherit;
  transition: background 0.15s ease, border-color 0.15s ease, transform 0.1s ease;
}
.date-cell:active { transform: scale(0.96); }

.date-cell--placeholder {
  background: transparent;
  border: none;
  cursor: default;
  pointer-events: none;
}

.date-cell--on {
  background: var(--doubao-card);
  border-color: var(--doubao-border);
}

.date-cell--off {
  background: var(--doubao-muted);
  border-color: var(--doubao-border);
  color: var(--doubao-muted-foreground);
  opacity: 0.5;
  cursor: not-allowed;
}
.date-cell--off:active { transform: none; }

.date-cell--active {
  background: var(--doubao-primary);
  border-color: var(--doubao-primary);
  color: var(--doubao-primary-foreground);
}
.date-cell--active .date-cell__md,
.date-cell--active .date-cell__label {
  color: var(--doubao-primary-foreground);
}

.date-cell__md {
  font-size: var(--fs-base);
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  line-height: 1.1;
}

.date-cell__label {
  font-size: 11px;
  color: var(--doubao-muted-foreground);
  min-height: 14px;
  line-height: 1;
}
.date-cell__label--rel {
  color: var(--doubao-primary);
  font-weight: 700;
}
.date-cell--active .date-cell__label--rel {
  color: var(--doubao-primary-foreground);
}

/* 已订餐蓝色圆点标记 */
.date-cell__dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--doubao-primary);
  margin-top: 2px;
  flex-shrink: 0;
}
/* 选中态(高亮底色)下圆点改白色,保证对比度 */
.date-cell--active .date-cell__dot {
  background: var(--doubao-primary-foreground);
}

/* 底部提示 */
.date-picker__footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 16px;
  padding-top: 12px;
  border-top: 1px solid var(--doubao-border);
  font-size: var(--fs-xs);
  color: var(--doubao-muted-foreground);
}
.date-picker__footer-sub {
  opacity: 0.7;
}

@media (max-width: 1280px) {
  .date-cell {
    min-height: 52px;
    padding: 6px 2px;
  }
  .date-picker__panel {
    max-width: 480px;
    padding: 16px;
  }
}
</style>
