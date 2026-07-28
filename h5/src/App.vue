<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import router from '@/router'
import TabBar from '@/components/TabBar.vue'

/**
 * 根组件:
 * - RouterView 渲染路由页面
 * - 底部 TabBar(首页/订餐/订单/我的),登录页与详情/表单页隐藏
 * - keep-alive 缓存首页和订餐页(meta.keepAlive = true)
 */
const route = useRoute()

/** 需要缓存的组件 name 列表(对应 route.name) */
const cachedViews = computed<string[]>(() =>
  router
    .getRoutes()
    .filter((r) => r.meta.keepAlive)
    .map((r) => r.name)
    .filter((n): n is string => typeof n === 'string'),
)

/** 是否隐藏底部 TabBar */
const hideTabbar = computed<boolean>(() => !!route.meta.hideTabbar)
</script>

<template>
  <div class="app-container" :class="{ 'has-tabbar': !hideTabbar }">
    <router-view v-slot="{ Component, route: currentRoute }">
      <transition name="fade" mode="out-in">
        <keep-alive :include="cachedViews">
          <component :is="Component" :key="currentRoute.fullPath" />
        </keep-alive>
      </transition>
    </router-view>

    <TabBar v-if="!hideTabbar" />
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables' as *;

.app-container {
  min-height: 100vh;
  background: $brand-card;

  &.has-tabbar {
    // TabBar 高度 64px + 安全区
    padding-bottom: calc(64px + env(safe-area-inset-bottom));
  }
}
</style>
