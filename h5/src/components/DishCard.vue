<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import type { Dish } from '@/api/types'
import { getCachedImage } from '@/utils/imageCache'

/**
 * 菜品卡片组件。
 * 展示菜品图片、名称、价格,以及数量加减(Stepper)。
 * quantity=0 时仅显示加号;quantity>0 时显示完整 Stepper。
 */
const props = withDefaults(
  defineProps<{
    dish: Dish
    /** 当前数量(默认 0) */
    quantity?: number
    /** 是否禁用操作(如已售罄) */
    disabled?: boolean
  }>(),
  {
    quantity: 0,
    disabled: false,
  },
)

const emit = defineEmits<{
  (e: 'add', dish: Dish): void
  (e: 'remove', dish: Dish): void
}>()

/** 菜品图片原始地址(后端返回 /uploads/xxx 相对路径,直接使用;为空则用占位) */
const rawImageUrl = computed<string>(() => {
  const img = props.dish.image || props.dish.imageUrl
  if (!img) return ''
  // 已是完整 URL 或 data URL 直接使用
  if (/^(https?:)?\/\//.test(img) || img.startsWith('data:')) return img
  // 相对路径直接使用(开发环境由 vite 代理或后端直出)
  return img
})

/** 经过本地缓存处理的图片 URL（命中缓存返回 blob URL，否则原 URL） */
const imageUrl = ref('')
watch(
  rawImageUrl,
  async (raw) => {
    if (!raw) {
      imageUrl.value = ''
      return
    }
    imageUrl.value = await getCachedImage(raw)
  },
  { immediate: true },
)

const isSoldOut = computed<boolean>(() => props.dish.status === 0)

/** 加号点击 */
const onAdd = (): void => {
  if (props.disabled || isSoldOut.value) return
  emit('add', props.dish)
}

/** 减号点击 */
const onRemove = (): void => {
  if (props.disabled) return
  emit('remove', props.dish)
}
</script>

<template>
  <div class="dish-card" :class="{ 'is-sold-out': isSoldOut }">
    <div class="dish-card__image">
      <img v-if="imageUrl" :src="imageUrl" :alt="dish.name" loading="lazy" />
      <div v-else class="dish-card__image-placeholder">
        <van-icon name="photo-o" size="32" />
      </div>
      <span v-if="dish.isSpecial === 1" class="dish-card__badge dish-card__badge--special">推荐</span>
    </div>

    <div class="dish-card__body">
      <div class="dish-card__name text-ellipsis">{{ dish.name }}</div>
      <div v-if="dish.description" class="dish-card__desc text-ellipsis-2">{{ dish.description }}</div>
      <div class="dish-card__footer">
        <span class="price">{{ dish.price?.toFixed(2) }}</span>
        <div class="dish-card__stepper">
          <van-stepper
            :model-value="quantity"
            :min="0"
            :disabled="disabled || isSoldOut"
            :show-minus="quantity > 0"
            :show-input="quantity > 0"
            :disable-plus="disabled || isSoldOut"
            button-size="24"
            @plus="onAdd"
            @minus="onRemove"
          />
        </div>
      </div>
      <div v-if="isSoldOut" class="dish-card__sold-out">已售罄</div>
    </div>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables' as *;

.dish-card {
  display: flex;
  background: #fff;
  border-radius: 8px;
  padding: 10px;
  gap: 10px;
  position: relative;

  &.is-sold-out {
    opacity: 0.6;
  }

  &__image {
    position: relative;
    flex-shrink: 0;
    width: 90px;
    height: 90px;
    border-radius: 6px;
    overflow: hidden;
    background: $bg-gray;

    img {
      width: 100%;
      height: 100%;
      object-fit: cover;
    }
  }

  &__image-placeholder {
    width: 100%;
    height: 100%;
    display: flex;
    align-items: center;
    justify-content: center;
    color: $text-placeholder;
  }

  &__badge {
    position: absolute;
    top: 0;
    left: 0;
    padding: 2px 6px;
    font-size: 10px;
    line-height: 1.4;
    color: #fff;
    border-radius: 6px 0 6px 0;

    &--new {
      background: $brand-orange;
    }

    &--special {
      background: $brand-primary;
    }
  }

  &__body {
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
  }

  &__name {
    font-size: 15px;
    font-weight: 500;
    color: $text-primary;
    line-height: 1.4;
  }

  &__desc {
    margin-top: 4px;
    font-size: 12px;
    color: $text-secondary;
    line-height: 1.4;
  }

  &__footer {
    margin-top: auto;
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding-top: 6px;
  }

  &__stepper {
    :deep(.van-stepper) {
      --van-stepper-button-round-theme-color: #{$brand-primary};
    }
  }

  &__sold-out {
    position: absolute;
    top: 50%;
    left: 50%;
    transform: translate(-50%, -50%);
    background: rgba(0, 0, 0, 0.5);
    color: #fff;
    padding: 4px 12px;
    border-radius: 4px;
    font-size: 14px;
    pointer-events: none;
  }
}
</style>
