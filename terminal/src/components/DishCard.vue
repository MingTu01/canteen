<script setup lang="ts">
/**
 * 菜品卡片(对齐 H5 Order.vue 内联 .dish-card 样式)
 * - 横向布局:图片 + 名称/价格 + 选中按钮
 * - 选中态:2px 主色边框
 * - 售罄态:半透明遮罩
 * - 图片加载失败回退到 Lucide 占位图标
 */
import { ref, computed } from 'vue'
import { Plus, Check, ImageOff } from 'lucide-vue-next'
import { formatMoney } from '@/composables/useFormat'
import { dishIcon } from '@/utils'

const props = withDefaults(defineProps<{
  dish: {
    dishId?: number
    id?: number
    dishName?: string
    name?: string
    price: number | string
    category?: string
    image?: string
    imageUrl?: string
    stock?: number
    maxPerOrder?: number
  }
  selected?: boolean
  disabled?: boolean
  showAddButton?: boolean
}>(), {
  selected: false,
  disabled: false,
  showAddButton: true,
})

const emit = defineEmits<{ (e: 'toggle'): void }>()

const errored = ref(false)
const imgUrl = computed(() => props.dish.image || props.dish.imageUrl || '')
const dishName = computed(() => props.dish.dishName || props.dish.name || '未知菜品')
const soldOut = computed(() => props.dish.stock !== undefined && props.dish.stock <= 0)
const PlaceholderIcon = computed(() => dishIcon(props.dish.category || ''))

const onClick = () => {
  if (props.disabled || soldOut.value) return
  emit('toggle')
}
</script>

<template>
  <button
    :class="['dish-card', { 'dish-card--selected': selected, 'dish-card--disabled': disabled || soldOut }]"
    @click="onClick"
  >
    <!-- 图片区 80×80(对齐 H5) -->
    <div class="dish-card__img-wrap">
      <img
        v-if="imgUrl && !errored"
        :src="imgUrl"
        :alt="dishName"
        class="dish-card__img"
        @error="errored = true"
      />
      <component v-else :is="soldOut ? ImageOff : PlaceholderIcon" :size="32" class="dish-card__placeholder" />
      <!-- 售罄遮罩 -->
      <div v-if="soldOut" class="dish-card__soldout">售罄</div>
    </div>

    <!-- 信息区 -->
    <div class="dish-card__info">
      <div class="dish-card__name text-ellipsis">{{ dishName }}</div>
      <div class="dish-card__price">¥{{ formatMoney(dish.price) }}</div>
    </div>

    <!-- 选中按钮(右侧) -->
    <div v-if="showAddButton" class="dish-card__btn-wrap">
      <div v-if="selected" class="dish-card__check">
        <Check :size="18" stroke-width="2.5" />
      </div>
      <div v-else-if="!soldOut" class="dish-card__add">
        <Plus :size="18" stroke-width="2.5" />
      </div>
    </div>
  </button>
</template>

<style scoped>
.dish-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px;
  background: var(--doubao-card);
  border: 1px solid var(--doubao-border);
  border-radius: var(--doubao-radius-sm);
  cursor: pointer;
  transition: border-color 0.15s ease, transform 0.12s ease;
  font-family: inherit;
  text-align: left;
  width: 100%;
}
.dish-card:active { transform: scale(0.98); }
.dish-card--selected {
  border: 2px solid var(--doubao-primary);
  padding: 9px; /* 抵消 2px 边框 */
}
.dish-card--disabled { opacity: 0.55; cursor: not-allowed; }
.dish-card--disabled:active { transform: none; }

.dish-card__img-wrap {
  position: relative;
  width: 80px;
  height: 80px;
  border-radius: var(--doubao-radius-xs);
  background: var(--doubao-muted);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  overflow: hidden;
}
.dish-card__img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.dish-card__placeholder {
  color: var(--doubao-muted-foreground);
}
.dish-card__soldout {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(255, 255, 255, 0.85);
  color: var(--doubao-muted-foreground);
  font-size: 12px;
  font-weight: 700;
}

.dish-card__info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.dish-card__name {
  font-size: var(--fs-base);
  font-weight: 400;
  color: var(--doubao-card-foreground);
}
.dish-card__price {
  font-size: var(--fs-base);
  font-weight: 700;
  color: var(--doubao-primary);
}

.dish-card__btn-wrap {
  flex-shrink: 0;
}
.dish-card__add,
.dish-card__check {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: white;
}
.dish-card__add { background: var(--doubao-primary); }
.dish-card__check { background: var(--doubao-primary); }
</style>
