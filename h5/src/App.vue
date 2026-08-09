<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import router from '@/router'
import TabBar from '@/components/TabBar.vue'
import ForceChangePassword from '@/components/ForceChangePassword.vue'
import { useAuthStore } from '@/stores/auth'

/**
 * 根组件:
 * - RouterView 渲染路由页面
 * - 底部 TabBar(首页/订餐/订单/我的),登录页与详情/表单页隐藏
 * - keep-alive 缓存首页和订餐页(meta.keepAlive = true)
 * - ForceChangePassword:首次登录强制改密弹窗(全局挂载)
 * - SSE 长连接恢复:页面刷新后若已登录,恢复员工 SSE 连接(支付码核销实时刷新)
 */
const route = useRoute()
const authStore = useAuthStore()

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

onMounted(() => {
  // 页面刷新后恢复 SSE 连接(登录流程中已启动,刷新会丢失,需在此恢复)
  authStore.ensureSseRunning()
})
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

    <!-- 首次登录强制修改密码弹窗(全局) -->
    <ForceChangePassword />
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
