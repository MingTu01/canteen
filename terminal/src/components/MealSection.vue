<script setup lang="ts">
/**
 * 餐别区块(选菜页用)
 *
 * 对齐 H5 Order.vue 的 meal-section 结构:
 * - 餐别胶囊标题(按餐别配色:早橙/午绿/晚紫)
 * - 菜品网格(3列,垂直卡片:图片在上 + 名称价格 + 选中按钮)
 * - 支持"已订餐锁定":锁定时整块不可加菜,已订菜品框出 + 显示固定数量(像购物车那样,但不可调)
 *
 * 选中态由父组件通过 getQuantity 函数判断(>0 即已选),切换通过 inc/dec 事件上报。
 * 已订菜品通过 orderedItems Map 传入(dishId -> quantity),锁定状态下显示固定数量。
 */
import { reactive, watch, onMounted, onUnmounted } from 'vue'
import { useMealConfig } from '@/composables/useMealConfig'
import { formatMoney } from '@/composables/useFormat'
import { dishIcon } from '@/utils'
import { getDishImgUrl } from '@/utils/cache'
import { Plus, Minus, Lock } from 'lucide-vue-next'

interface MenuItem {
  dishId: number
  dishName: string
  price: number | string
  category?: string
  image?: string
  imageUrl?: string
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

/** 图片加载失败的 dishId 集合(响应式,触发回退到占位图标) */
const erroredDishIds = reactive(new Set<number>())
const onImgError = (dishId: number) => erroredDishIds.add(dishId)

/** 本地图片 URL 缓存:dishId -> ObjectURL(走 IndexedDB Blob) */
const localImgUrls = reactive<Record<number, string>>({})

/** 释放所有 ObjectURL(切换日期/卸载前调用,避免内存泄漏) */
const revokeLocalImgUrls = () => {
  for (const key of Object.keys(localImgUrls)) {
    const url = localImgUrls[Number(key)]
    if (url && url.startsWith('blob:')) URL.revokeObjectURL(url)
  }
}

/** 加载本地图片 URL(命中 IndexedDB 则用 Blob,否则降级后端 URL) */
const loadLocalImg = async (item: MenuItem) => {
  const url = item.image || item.imageUrl
  if (!url) return
  try {
    const localUrl = await getDishImgUrl(url)
    if (localUrl) {
      // 释放旧的 ObjectURL(如果存在)
      const old = localImgUrls[item.dishId]
      if (old && old.startsWith('blob:')) URL.revokeObjectURL(old)
      localImgUrls[item.dishId] = localUrl
    }
  } catch { /* 降级直查后端 */ }
}

onMounted(() => {
  props.items.forEach(loadLocalImg)
})
// items 变化时重新加载(避免切换日期时图片不更新)
watch(() => props.items, () => {
  // 切换日期前释放旧 ObjectURL
  revokeLocalImgUrls()
  props.items.forEach(loadLocalImg)
}, { deep: false })

onUnmounted(() => {
  revokeLocalImgUrls()
})

const mealName = (t: number) =>
  ({ 1: '早餐', 2: '午餐', 3: '晚餐' } as Record<number, string>)[t] || '未知'

/** 菜品是否已订餐(锁定状态下,在 orderedItems 中) */
const isOrdered = (dishId: number) =>
  props.locked && props.orderedItems?.has(dishId) === true

/** 已订菜品的固定数量(从 orderedItems 取) */
const orderedQty = (dishId: number) => props.orderedItems?.get(dishId) ?? 0

/** 点击菜品卡片:
 * - 锁定状态:整块禁用,不响应
 * - 已选(数量 > 0):减一
 * - 未选:加一
 */
const onClick = (item: MenuItem) => {
  if (props.locked) return
  const q = props.getQuantity(item.dishId, props.mealType)
  if (q > 0) emit('dec', item)
  else emit('inc', item)
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

    <!-- 菜品网格 -->
    <div class="meal-section__grid">
      <button
        v-for="item in items"
        :key="item.dishId"
        type="button"
        class="dish"
        :class="{
          'dish--selected': getQuantity(item.dishId, mealType) > 0,
          'dish--ordered': isOrdered(item.dishId),
          'dish--locked': locked,
        }"
        :disabled="locked"
        @click="onClick(item)"
      >
        <!-- 图片区 80×80 -->
        <div class="dish__img">
          <img
            v-if="(item.image || item.imageUrl) && !erroredDishIds.has(item.dishId)"
            :src="localImgUrls[item.dishId] || item.image || item.imageUrl"
            :alt="item.dishName"
            @error="onImgError(item.dishId)"
          />
          <component
            v-else
            :is="dishIcon(item.category || '')"
            :size="28"
            class="dish__placeholder"
          />
        </div>

        <!-- 信息区 -->
        <div class="dish__info">
          <span class="dish__name text-ellipsis">{{ item.dishName }}</span>
          <span class="dish__price">¥{{ formatMoney(item.price) }}</span>
        </div>

        <!-- 已订菜品:显示固定数量(像购物车那样框出,不可调) -->
        <div v-if="isOrdered(item.dishId)" class="dish__ordered-qty">
          <span class="dish__ordered-num">×{{ orderedQty(item.dishId) }}</span>
          <span class="dish__ordered-label">已订</span>
        </div>

        <!-- 数量调整按钮(未锁定时) -->
        <div v-else-if="!locked" class="dish__qty">
          <template v-if="getQuantity(item.dishId, mealType) > 0">
            <button
              type="button"
              class="dish__qty-btn dish__qty-btn--dec btn-press"
              aria-label="减少"
              @click.stop="emit('dec', item)"
            >
              <Minus :size="14" stroke-width="2.5" />
            </button>
            <span class="dish__qty-num">{{ getQuantity(item.dishId, mealType) }}</span>
            <button
              type="button"
              class="dish__qty-btn dish__qty-btn--inc btn-press"
              aria-label="增加"
              @click.stop="emit('inc', item)"
            >
              <Plus :size="14" stroke-width="2.5" />
            </button>
          </template>
          <div v-else class="dish__btn dish__btn--add">
            <Plus :size="16" stroke-width="2.5" />
          </div>
        </div>
      </button>
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
  font-weight: 600;
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
  font-weight: 600;
}

.meal-section__grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
}

.dish {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 12px;
  background: var(--doubao-card);
  border: 1px solid var(--doubao-border);
  border-radius: var(--doubao-radius);
  cursor: pointer;
  transition: border-color 0.15s ease, transform 0.12s ease;
  font-family: inherit;
  text-align: left;
}
.dish:active { transform: scale(0.98); }
.dish--selected {
  border: 2px solid var(--doubao-primary);
  padding: 11px;
}
/* 已订餐菜品:绿色框 + 浅绿背景,显示固定数量,不可再加 */
.dish--ordered {
  border: 2px solid var(--doubao-success, #07c160);
  padding: 11px;
  background: rgba(7, 193, 96, 0.08);
  cursor: not-allowed;
}
.dish--ordered:active { transform: none; }
.dish--locked:not(.dish--ordered) {
  opacity: 0.55;
  cursor: not-allowed;
}
.dish--locked:active { transform: none; }

.dish__img {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 80px;
  height: 80px;
  border-radius: var(--doubao-radius-xs);
  background: var(--doubao-muted);
  overflow: hidden;
}
.dish__img img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.dish__placeholder {
  color: var(--doubao-muted-foreground);
}

.dish__info {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.dish__name {
  font-size: var(--fs-base);
  font-weight: 500;
  color: var(--doubao-card-foreground);
}
.dish__price {
  font-size: var(--fs-base);
  font-weight: 700;
  color: var(--doubao-primary);
}

/* 已订菜品固定数量显示(像购物车那样,但不可调) */
.dish__ordered-qty {
  align-self: flex-end;
  display: flex;
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
  font-weight: 600;
  opacity: 0.9;
}

/* 数量调整按钮区 */
.dish__qty {
  align-self: flex-end;
  display: flex;
  align-items: center;
  gap: 6px;
}
.dish__qty-num {
  min-width: 18px;
  text-align: center;
  font-size: var(--fs-base);
  font-weight: 700;
  color: var(--doubao-foreground);
  font-variant-numeric: tabular-nums;
}
.dish__qty-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border-radius: 50%;
  border: none;
  cursor: pointer;
  font-family: inherit;
  transition: transform 0.12s ease, opacity 0.15s ease;
}
.dish__qty-btn:active { transform: scale(0.92); }
.dish__qty-btn--inc {
  background: var(--doubao-primary);
  color: var(--doubao-primary-foreground);
}
.dish__qty-btn--dec {
  background: var(--doubao-muted);
  color: var(--doubao-secondary-foreground);
  border: 1px solid var(--doubao-border);
}

.dish__btn {
  align-self: flex-end;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 50%;
  color: var(--doubao-primary-foreground);
  background: var(--doubao-primary);
}
</style>
