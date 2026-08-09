<script setup lang="ts">
/**
 * 品牌顶栏:Logo + 食堂名称(左上角)
 *
 * 用于需要显示品牌背景的页面(订餐/取餐模式的首页、查询页、选择页、取餐菜品页),
 * 在品牌背景上叠加显示食堂标识。
 * - 透明背景,叠加在全局 BrandingBg 之上
 * - 白色文字 + 白色描边logo,适配深色品牌背景
 * - 无 logo 时仅显示名称
 */
import { computed } from 'vue'
import { brandingState } from '@/store/branding'

const branding = computed(() => brandingState.data)
const storeName = computed(() => branding.value?.name || '企业智慧食堂')

const onLogoError = (e: Event) => {
  ;(e.target as HTMLImageElement).style.display = 'none'
}
</script>

<template>
  <header class="branding-header">
    <div class="branding-header__brand">
      <img
        v-if="branding?.logoUrl"
        :src="branding.logoUrl"
        :alt="storeName"
        class="branding-header__logo"
        @error="onLogoError"
      />
      <span class="branding-header__name">{{ storeName }}</span>
    </div>
  </header>
</template>

<style scoped>
.branding-header {
  position: relative;
  z-index: 1;
  padding: 20px 32px;
}
.branding-header__brand {
  display: flex;
  align-items: center;
  gap: 12px;
}
.branding-header__logo {
  width: 48px;
  height: 48px;
  border-radius: 50%;
  object-fit: cover;
  /* 白色描边,适配深色背景 */
  box-shadow: 0 0 0 2px rgba(255, 255, 255, 0.3);
}
.branding-header__name {
  font-size: var(--fs-xl);
  font-weight: 700;
  color: #ffffff;
  text-shadow: 0 2px 8px rgba(0, 0, 0, 0.4);
}
</style>
