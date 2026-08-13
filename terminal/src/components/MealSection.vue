<script setup lang="ts">
/**
 * 餐别区块(选菜页用)
 *
 * 对齐 H5 Order.vue 的横条卡片结构:
 * - 餐别胶囊标题(按餐别配色:早橙/午绿/晚紫)
 * - 菜品列表(单列横条:左侧菜名+价格,右侧操作区,整卡可点 +1)
 * - 辣度角标(卡片右上角,"辣"字 + 按级别 1-3 个辣椒)
 * - 支持"已订餐锁定":锁定时整块不可加菜,已订菜品框出 + 显示固定数量(像购物车那样,但不可调)
 *
 * 选中态由父组件通过 getQuantity 函数判断(>0 即已选),切换通过 inc/dec 事件上报。
 * 已订菜品通过 orderedItems Map 传入(dishId -> quantity),锁定状态下显示固定数量。
 */
import { useMealConfig } from '@/composables/useMealConfig'
import { formatMoney } from '@/composables/useFormat'
import { Plus, Minus, Lock } from 'lucide-vue-next'
import ChiliIcon from '@/components/ChiliIcon.vue'

interface MenuItem {
  dishId: number
  dishName: string
  price: number | string
  category?: string
  image?: string
  imageUrl?: string
  /** 辣度: 0-不辣, 1-微辣, 2-中辣, 3-重辣 */
  spiceLevel?: number
}

const props = defineProps<{
  mealType: number
  items: MenuItem[]
  /** 取某菜品当前数量(>0 视为已选) */
  getQuantity: (dishId: number, mealType: number) => number
  /** 该餐别是否已订餐(整块锁定,不可再加菜,已订菜品框出) */
  locked?: boolean
  /** 该餐别下已订餐的菜品映射:dishId -> quantity(用于框出"已订"菜品并显示固定数量) */
  orderedItems?: Map<number, number>
}>()

const emit = defineEmits<{
  (e: 'inc', item: MenuItem): void
  (e: 'dec', item: MenuItem): void
}>()

const { mealPillStyle, mealIconColor, mealIconMap } = useMealConfig()

const mealName = (t: number) =>
  ({ 1: '早餐', 2: '午餐', 3: '晚餐' } as Record<number, string>)[t] || '未知'

/** 菜品是否已订餐(锁定状态下,在 orderedItems 中) */
const isOrdered = (dishId: number) =>
  props.locked && props.orderedItems?.has(dishId) === true

/** 已订菜品的固定数量(从 orderedItems 取) */
const orderedQty = (dishId: number) => props.orderedItems?.get(dishId) ?? 0

/** 辣度(0-3),未配置视为不辣 */
const spiceOf = (item: MenuItem) => item.spiceLevel ?? 0

/**
 * 点击菜品卡片:整卡 +1(锁定/已订状态不响应)。
 * 数量调整由右侧步进器(@click.stop)独立处理,避免与卡片点击重复触发。
 */
const onClick = (item: MenuItem) => {
  if (props.locked) return
  emit('inc', item)
}
</script>

<template>
  <section class="meal-section" :class="{ 'meal-section--locked': locked }">
    <!-- 餐别胶囊标题 -->
    <div class="meal-section__pill">
      <component
        :is="mealIconMap[mealType]"
        :size="20"
        :stroke-width="2.5"
        :color="mealIconColor(mealType)"
      />
      <span class="meal-section__name" :style="mealPillStyle(mealType)">
        {{ mealName(mealType) }}
      </span>
      <span v-if="locked" class="meal-section__locked-tag">
        <Lock :size="12" stroke-width="2.5" />
        已订餐
      </span>
    </div>

    <!-- 菜品横条列表(单列) -->
    <div class="meal-section__list">
      <div
        v-for="item in items"
        :key="item.dishId"
        class="dish"
        :class="{
          'dish--selected': getQuantity(item.dishId, mealType) > 0,
          'dish--ordered': isOrdered(item.dishId),
          'dish--locked': locked,
        }"
        @click="onClick(item)"
      >
        <!-- 辣度角标(卡片右上角,"辣"字 + 按级别 1-3 个辣椒) -->
        <div
          v-if="spiceOf(item) > 0"
          class="dish__spice-badge"
        >
          <span class="dish__spice-label">辣</span>
          <ChiliIcon
            v-for="n in spiceOf(item)"
            :key="n"
            :size="12"
          />
        </div>

        <!-- 左侧:菜名 + 价格 -->
        <div class="dish__info">
          <span class="dish__name">{{ item.dishName }}</span>
          <span class="dish__price">¥{{ formatMoney(item.price) }}</span>
        </div>

        <!-- 右侧:操作区 -->
        <!-- 已订菜品:显示固定数量(像购物车那样框出,不可调) -->
        <div v-if="isOrdered(item.dishId)" class="dish__ordered-qty">
          <span class="dish__ordered-num">×{{ orderedQty(item.dishId) }}</span>
          <span class="dish__ordered-label">已订</span>
        </div>
        <!-- 未锁定时:数量调整(整卡可点 +1,步进器用 @click.stop 防止重复触发) -->
        <div v-else-if="!locked" class="dish__action" @click.stop>
          <template v-if="getQuantity(item.dishId, mealType) > 0">
            <button
              type="button"
              class="dish__step-btn dish__step-btn--dec"
              aria-label="减少"
              @click.stop="emit('dec', item)"
            >
              <Minus :size="16" stroke-width="2.5" />
            </button>
            <span class="dish__step-num">{{ getQuantity(item.dishId, mealType) }}</span>
            <button
              type="button"
              class="dish__step-btn dish__step-btn--inc"
              aria-label="增加"
              @click.stop="emit('inc', item)"
            >
              <Plus :size="16" stroke-width="2.5" />
            </button>
          </template>
          <!-- 数量为 0:整卡可点 +1,这里放一个 + 号作为视觉提示 -->
          <button
            v-else
            type="button"
            class="dish__add-btn"
            aria-label="加入购物车"
            @click.stop="emit('inc', item)"
          >
            <Plus :size="18" stroke-width="2.5" />
          </button>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.meal-section {
  margin-bottom: 24px;
}
.meal-section--locked {
  opacity: 0.9;
}
.meal-section__pill {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}
.meal-section__name {
  font-size: var(--fs-lg);
  font-weight: 700;
  padding: 4px 14px;
  border-radius: 999px;
  border: 1px solid;
}
.meal-section__locked-tag {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 10px;
  border-radius: 999px;
  background: var(--doubao-muted);
  color: var(--doubao-muted-foreground);
  font-size: var(--fs-xs);
  font-weight: 700;
}

/* 菜品横条列表(单列) */
.meal-section__list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

/* 单条菜品横条 */
.dish {
  position: relative;
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: 10px;
  padding: 12px 14px;
  /* 给右上角辣度角标预留空间 */
  padding-right: 70px;
  background: var(--doubao-card);
  border: 1px solid var(--doubao-border);
  border-radius: var(--doubao-radius);
  cursor: pointer;
  transition: border-color 0.15s ease, background 0.15s ease;
  min-width: 0;
}
.dish:active {
  opacity: 0.92;
}
.dish--selected {
  border: 2px solid var(--doubao-primary);
  padding: 11px 13px;
  padding-right: 69px;
}
/* 已订餐菜品:绿色框 + 浅绿背景,显示固定数量,不可再加 */
.dish--ordered {
  border: 2px solid var(--doubao-success, #07c160);
  padding: 11px 13px;
  padding-right: 69px;
  background: rgba(7, 193, 96, 0.08);
  cursor: not-allowed;
}
.dish--ordered:active {
  opacity: 1;
}
/* 锁定餐别下未订菜品:灰色不可选 */
.dish--locked:not(.dish--ordered) {
  opacity: 0.55;
  cursor: not-allowed;
}

/* 左侧:菜名 + 价格 */
.dish__info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.dish__name {
  font-size: 16px;
  font-weight: 700;
  color: var(--doubao-card-foreground);
  /* 最多 2 行,超出省略号 */
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  word-break: break-all;
  line-height: 1.35;
}
.dish__price {
  font-size: 16px;
  font-weight: 700;
  color: var(--doubao-primary);
  font-variant-numeric: tabular-nums;
}

/* 辣度角标(卡片右上角,半透明红底 + "辣"字 + 红色辣椒图标) */
.dish__spice-badge {
  position: absolute;
  top: 6px;
  right: 8px;
  z-index: 3;
  display: flex;
  align-items: center;
  gap: 2px;
  padding: 3px 6px;
  background: rgba(239, 68, 68, 0.08);
  border-radius: 8px;
  color: #ef4444;
  line-height: 1;
}
.dish__spice-label {
  font-size: 12px;
  font-weight: 700;
  line-height: 1;
}

/* 右侧操作区 */
.dish__action {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 8px;
}
.dish__step-num {
  min-width: 20px;
  text-align: center;
  font-size: 16px;
  font-weight: 700;
  color: var(--doubao-foreground);
  font-variant-numeric: tabular-nums;
}
.dish__step-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 50%;
  border: none;
  cursor: pointer;
  font-family: inherit;
  padding: 0;
  transition: opacity 0.15s ease;
}
.dish__step-btn:active {
  opacity: 0.85;
}
.dish__step-btn--inc {
  background: var(--doubao-primary);
  color: var(--doubao-primary-foreground);
}
.dish__step-btn--dec {
  background: var(--doubao-muted);
  color: var(--doubao-secondary-foreground);
  border: 1px solid var(--doubao-border);
}

/* "+" 按钮(数量为 0 时,整卡可点 +1,这里仅作视觉提示) */
.dish__add-btn {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: var(--doubao-primary);
  color: var(--doubao-primary-foreground);
  border: none;
  cursor: pointer;
  padding: 0;
  font-family: inherit;
  transition: opacity 0.15s ease;
}
.dish__add-btn:active {
  opacity: 0.85;
}

/* 已订菜品固定数量显示(像购物车那样,但不可调) */
.dish__ordered-qty {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  border-radius: 999px;
  background: var(--doubao-success, #07c160);
  color: #fff;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}
.dish__ordered-num {
  font-size: var(--fs-base);
}
.dish__ordered-label {
  font-size: var(--fs-xs);
  font-weight: 700;
  opacity: 0.9;
}
</style>
