<script setup lang="ts">
/**
 * 自定义底部 TabBar(对齐 meal-miniapp 模板)
 * - 使用 Lucide SVG 图标替代 Vant 字体图标
 * - 纯白背景 + 顶部 border,激活项 primary 色,非激活 muted-foreground
 * - 高度 64px(含安全区),图标 20px + 文字 10px
 */
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Home, ShoppingBag, ClipboardList, User } from 'lucide-vue-next'

const route = useRoute()
const router = useRouter()

interface TabItem {
  path: string
  label: string
  icon: typeof Home
}

const tabs: TabItem[] = [
  { path: '/', label: '首页', icon: Home },
  { path: '/order', label: '订餐', icon: ShoppingBag },
  { path: '/orders', label: '订单', icon: ClipboardList },
  { path: '/profile', label: '我的', icon: User },
]

/** 当前激活的 tab path(精确匹配优先,前缀匹配按最长优先,避免 /orders 误匹配 /order) */
const activePath = computed<string>(() => {
  const current = route.path
  // 精确匹配(含根路径)
  const exact = tabs.find((t) => t.path === current)
  if (exact) return exact.path
  // 前缀匹配:按 path 长度倒序,保证 /orders 优先匹配 /orders 而非 /order
  const matched = tabs
    .filter((t) => t.path !== '/' && current.startsWith(t.path))
    .sort((a, b) => b.path.length - a.path.length)[0]
  return matched?.path ?? '/'
})

const onTabClick = (path: string): void => {
  if (path === activePath.value) return
  router.replace(path)
}
</script>

<template>
  <nav class="tabbar safe-area-bottom">
    <button
      v-for="tab in tabs"
      :key="tab.path"
      type="button"
      class="tabbar__item"
      :class="{ 'tabbar__item--active': activePath === tab.path }"
      @click="onTabClick(tab.path)"
    >
      <component :is="tab.icon" :size="20" :stroke-width="2" class="tabbar__icon" />
      <span class="tabbar__label">{{ tab.label }}</span>
    </button>
  </nav>
</template>

<style scoped lang="scss">
@use '@/styles/variables' as *;

.tabbar {
  position: fixed;
  bottom: 0;
  left: 0;
  right: 0;
  height: 64px;
  display: flex;
  align-items: stretch;
  justify-content: space-around;
  background: $brand-card;
  border-top: 1px solid $brand-border;
  z-index: 100;

  // 安全区底部填充(iPhone X+)
  &.safe-area-bottom {
    padding-bottom: env(safe-area-inset-bottom);
    box-sizing: content-box;
  }
}

.tabbar__item {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  background: transparent;
  border: none;
  cursor: pointer;
  color: $brand-muted-foreground;
  transition: color 0.15s ease;
  padding: 0;

  &:active {
    background: $brand-secondary;
  }

  &--active {
    color: $brand-primary;
  }
}

.tabbar__icon {
  flex-shrink: 0;
}

.tabbar__label {
  font-size: 10px;
  font-weight: 500;
  line-height: 1;
}
</style>
