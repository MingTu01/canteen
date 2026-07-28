<script setup lang="ts">
/**
 * 订单卡片(订单查询页用)
 *
 * 三态展示:
 * - 已订餐待取餐(status=1):餐别色标题条 + 菜品列表 + 取消按钮
 * - 已取餐(status=2):餐别色标题条 + "已取餐"标签 + 菜品列表(无取消按钮)
 * - 未订餐:灰色餐别条 + "未订餐" 提示
 *
 * 餐别配色对齐 H5 useMealConfig(早橙/午绿/晚紫)。
 * 后端返回订单菜品在 order.items 字段(批量查询填充)。
 */
import { computed } from 'vue'
import { formatMoney } from '@/composables/useFormat'
import { mealTypeLabel } from '@/utils'
import { useMealConfig } from '@/composables/useMealConfig'
import { CheckCircle2 } from 'lucide-vue-next'

interface OrderItem {
  dishName: string
  price: number | string
  quantity?: number
}
interface Order {
  id: number
  mealType: number
  status?: number
  totalAmount?: number | string
  /** 后端返回的菜品列表(字段名 items) */
  items?: OrderItem[]
}

const props = defineProps<{
  mealType: number
  /** 订单数据,null 时渲染"未订餐"态 */
  order?: Order | null
  /** 是否正在取消该订单(禁用按钮) */
  canceling?: boolean
}>()

const emit = defineEmits<{ (e: 'cancel', order: Order): void }>()

const { mealPillStyle, mealIconColor, mealIconMap } = useMealConfig()

const mealName = computed(() => mealTypeLabel(props.mealType))

/** 后端返回的菜品列表(字段名 items) */
const orderItems = computed<OrderItem[]>(() => {
  return props.order?.items ?? []
})

const itemCount = computed(() =>
  orderItems.value.reduce((s, it) => s + Number(it.quantity || 1), 0),
)

const lineTotal = (it: OrderItem) => Number(it.price) * Number(it.quantity || 1)
const orderTotal = computed(() => {
  if (props.order?.totalAmount != null) return Number(props.order.totalAmount)
  return orderItems.value.reduce((s, it) => s + lineTotal(it), 0)
})

/** 是否已取餐(status=2),已取餐不显示取消按钮 */
const isCompleted = computed(() => Number(props.order?.status) === 2)
</script>

<template>
  <!-- 已订餐 -->
  <div v-if="order" class="order-card" :class="isCompleted ? 'order-card--completed' : 'order-card--active'">
    <!-- 餐别标题条(餐别配色胶囊) -->
    <div class="order-card__head">
      <div class="order-card__pill" :style="mealPillStyle(mealType)">
        <component
          :is="mealIconMap[mealType]"
          :size="20"
          :stroke-width="2.5"
          :color="mealIconColor(mealType)"
        />
        <span class="order-card__name">{{ mealName }}</span>
      </div>
      <div class="order-card__head-right">
        <!-- 已取餐标签 -->
        <span v-if="isCompleted" class="order-card__status order-card__status--done">
          <CheckCircle2 :size="14" stroke-width="2.5" />
          已取餐
        </span>
        <span class="order-card__sub">{{ itemCount }}道 · ¥{{ formatMoney(orderTotal) }}</span>
      </div>
    </div>

    <!-- 菜品列表 -->
    <div v-if="orderItems.length" class="order-card__body">
      <div
        v-for="(it, idx) in orderItems"
        :key="idx"
        class="order-card__item"
      >
        <span class="order-card__dish">
          {{ it.dishName }}<span v-if="Number(it.quantity || 1) > 1"> ×{{ it.quantity }}</span>
        </span>
        <span class="order-card__price">¥{{ formatMoney(lineTotal(it)) }}</span>
      </div>
    </div>
    <!-- 无菜品兜底 -->
    <div v-else class="order-card__body">
      <div class="order-card__no-items">暂无菜品明细</div>
    </div>

    <!-- 取消按钮(仅待取餐 status=1 显示) -->
    <div v-if="!isCompleted" class="order-card__footer">
      <button
        class="order-card__cancel btn-press"
        :disabled="canceling"
        @click="emit('cancel', order as Order)"
      >
        {{ canceling ? '取消中...' : '取消订餐' }}
      </button>
    </div>
  </div>

  <!-- 未订餐 -->
  <div v-else class="order-card order-card--empty">
    <div class="order-card__pill order-card__pill--muted">
      <component
        :is="mealIconMap[mealType]"
        :size="20"
        :stroke-width="2"
      />
      <span class="order-card__name order-card__name--muted">{{ mealName }}</span>
    </div>
    <span class="order-card__empty-text">未订餐</span>
  </div>
</template>

<style scoped>
.order-card {
  margin: 12px 32px;
  border-radius: var(--doubao-radius);
}
/* 已订餐卡片:柔和背景,无蓝色边框 */
.order-card--active {
  background: var(--doubao-card);
  border: 1px solid var(--doubao-border);
  overflow: hidden;
}
/* 已取餐卡片:浅灰背景,表示已完成 */
.order-card--completed {
  background: var(--doubao-muted);
  border: 1px solid var(--doubao-border);
  overflow: hidden;
  opacity: 0.85;
}
/* 未订餐卡片:横向布局 */
.order-card--empty {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 16px 20px;
  background: var(--doubao-card);
  border: 1px solid var(--doubao-border);
}

/* 餐别标题条 */
.order-card__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 20px;
  border-bottom: 1px solid var(--doubao-border);
}
.order-card__head-right {
  display: flex;
  align-items: center;
  gap: 10px;
}
.order-card__pill {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 6px 14px;
  border-radius: 999px;
  border: 1px solid;
  font-size: var(--fs-base);
  font-weight: 600;
}
.order-card__pill--muted {
  background: var(--doubao-muted);
  border-color: var(--doubao-border);
  color: var(--doubao-muted-foreground);
}
.order-card__name {
  font-size: var(--fs-lg);
  font-weight: 600;
}
.order-card__name--muted {
  color: var(--doubao-muted-foreground);
  font-weight: 500;
}
.order-card__sub {
  font-size: var(--fs-sm);
  color: var(--doubao-muted-foreground);
  font-weight: 500;
  font-variant-numeric: tabular-nums;
}
.order-card__empty-text {
  font-size: var(--fs-sm);
  color: var(--doubao-muted-foreground);
}

/* 已取餐状态标签 */
.order-card__status {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 10px;
  border-radius: 999px;
  font-size: var(--fs-xs);
  font-weight: 600;
}
.order-card__status--done {
  background: var(--doubao-success, #07c160);
  color: #fff;
}

/* 菜品列表 */
.order-card__body {
  padding: 8px 20px 12px;
}
.order-card__item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 0;
  border-bottom: 1px dashed var(--doubao-border);
  font-size: var(--fs-base);
  color: var(--doubao-secondary-foreground);
}
.order-card__item:last-child {
  border-bottom: none;
}
.order-card__dish {
  color: var(--doubao-foreground);
}
.order-card__price {
  font-weight: 600;
  color: var(--doubao-primary);
  font-variant-numeric: tabular-nums;
}
.order-card__no-items {
  padding: 12px 0;
  text-align: center;
  font-size: var(--fs-sm);
  color: var(--doubao-muted-foreground);
}

/* 取消按钮 */
.order-card__footer {
  display: flex;
  justify-content: flex-end;
  padding: 0 20px 14px;
}
.order-card__cancel {
  height: 44px;
  padding: 0 20px;
  border-radius: var(--doubao-radius-sm);
  background: transparent;
  border: 1.5px solid var(--doubao-destructive);
  color: var(--doubao-destructive);
  font-size: var(--fs-sm);
  font-weight: 600;
  cursor: pointer;
  font-family: inherit;
  transition: background 0.15s ease, transform 0.12s ease;
}
.order-card__cancel:active {
  transform: scale(0.97);
  background: rgba(239, 68, 68, 0.06);
}
.order-card__cancel:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
</style>
