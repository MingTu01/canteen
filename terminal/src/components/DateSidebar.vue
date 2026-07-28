<script setup lang="ts">
/**
 * 左侧日期竖列
 *
 * 提取自 OrderSelect / OrderQuery 中重复的日期栏。
 * - 顶部:日期选择器(header slot)
 * - 列表:7 天日期,选中态高亮,日期右侧显示 3 个餐别圆点(已点对应色高亮,未点灰色)
 *
 * header slot 默认显示当前日期文字,可被父组件覆盖(如嵌入 DatePicker)。
 */
import { shortDate, relativeLabel } from '@/utils'
import { MEAL_COLORS } from '@/composables/useMealConfig'

withDefaults(defineProps<{
  dates: string[]
  selectedDate: string
  /**
   * 由父组件传入"某日期已订餐的餐别列表"函数。
   * 返回 [1,3] 表示该日期已选早餐+晚餐,午餐未选。
   */
  mealTypesForDate?: (date: string) => number[]
}>(), {
  mealTypesForDate: () => [],
})

const emit = defineEmits<{
  (e: 'select', date: string): void
}>()

/** 三餐固定顺序:1早 2午 3晚 */
const MEAL_ORDER = [1, 2, 3]
</script>

<template>
  <aside class="date-sidebar">
    <!-- 顶部:日期选择器 -->
    <div class="date-sidebar__head">
      <slot name="header">
        <!-- 默认:显示当前日期文字 -->
        <span class="date-sidebar__cur">{{ shortDate(selectedDate) }}</span>
        <span class="date-sidebar__rel">{{ relativeLabel(selectedDate) }}</span>
      </slot>
    </div>

    <!-- 日期列表 -->
    <div class="date-sidebar__list no-scrollbar">
      <button
        v-for="d in dates"
        :key="d"
        type="button"
        class="date-sidebar__item"
        :class="{ 'date-sidebar__item--active': d === selectedDate }"
        @click="emit('select', d)"
      >
        <span class="date-sidebar__date">{{ shortDate(d) }}</span>
        <!-- 3 个餐别圆点(竖向排列在日期右侧):已点高亮(早橙/午绿/晚紫),未点灰色 -->
        <div class="date-sidebar__dots">
          <span
            v-for="mt in MEAL_ORDER"
            :key="mt"
            class="date-sidebar__dot"
            :class="{ 'date-sidebar__dot--on': mealTypesForDate(d).includes(mt) }"
            :style="mealTypesForDate(d).includes(mt)
              ? { background: MEAL_COLORS[mt].text }
              : undefined"
          />
        </div>
      </button>
    </div>
  </aside>
</template>

<style scoped>
.date-sidebar {
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
  width: 130px;
  border-right: 1px solid var(--doubao-border);
  background: var(--doubao-secondary);
}
.date-sidebar__head {
  padding: 12px;
  border-bottom: 1px solid var(--doubao-border);
}
.date-sidebar__cur {
  font-size: var(--fs-base);
  font-weight: 600;
  color: var(--doubao-foreground);
}
.date-sidebar__rel {
  font-size: var(--fs-xs);
  color: var(--doubao-primary);
  font-weight: 500;
}

.date-sidebar__list {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
}
.date-sidebar__item {
  display: flex;
  flex-direction: row;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 16px 12px;
  border: none;
  border-top: 1px solid var(--doubao-border);
  background: transparent;
  cursor: pointer;
  transition: background 0.15s ease;
}
.date-sidebar__item--active {
  background: var(--doubao-primary);
  border-top-color: transparent;
}
.date-sidebar__date {
  font-size: var(--fs-base);
  font-weight: 500;
  color: var(--doubao-muted-foreground);
}
.date-sidebar__item--active .date-sidebar__date {
  color: var(--doubao-primary-foreground);
  font-weight: 600;
}

/* 3 个餐别圆点(竖向排列在日期右侧:早橙/午绿/晚紫,已点亮,未点灰) */
.date-sidebar__dots {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 5px;
}
.date-sidebar__dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--doubao-muted);
  opacity: 0.5;
  transition: opacity 0.15s ease, transform 0.15s ease;
}
.date-sidebar__dot--on {
  opacity: 1;
  transform: scale(1.15);
  box-shadow: 0 0 0 2px var(--doubao-card);
}
.date-sidebar__item--active .date-sidebar__dot {
  background: rgba(255, 255, 255, 0.4);
}
/* 选中日期下已点圆点保持各自餐别色(内联 style 优先级高于此规则) */
</style>
