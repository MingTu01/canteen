<script setup lang="ts">
/**
 * 订餐主菜单(选择订餐和查询页面)
 *
 * 刷卡成功后的入口页:
 * - 顶部用户信息条(头像 + 姓名 + 部门 + 余额)
 * - 中央大时钟(居中显示,与待机页一致)
 * - 三大入口:我要订餐 / 查询订餐 / 返回待机
 * - 无操作 10 秒自动返回待机
 */
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { orderStore, resetOrderFlow } from '@/store/order'
import { useIdleTimer } from '@/composables/useIdleTimer'
import { fullDateLabel, pad2 } from '@/utils'
import { Utensils, Search, Home } from 'lucide-vue-next'
import UserInfoBar from '@/components/UserInfoBar.vue'
import BrandingBg from '@/components/BrandingBg.vue'
import { brandingState, fetchBranding } from '@/store/branding'

const router = useRouter()
const emp = computed(() => orderStore.employee)
const branding = computed(() => brandingState.data)

const clock = ref('')
const dateLabel = ref('')
let timer = 0

const updateClock = () => {
  const now = new Date()
  clock.value = `${pad2(now.getHours())}:${pad2(now.getMinutes())}`
  dateLabel.value = fullDateLabel(now)
}

useIdleTimer(
  () => {
    resetOrderFlow()
    router.replace('/order')
  },
  10_000,
  1_000,
)

const goSelect = () => router.push('/order/select')
const goQuery = () => router.push('/order/query')
const goHome = () => {
  // 返回主页(待机页),清除登录状态,要求重新刷卡
  resetOrderFlow()
  router.replace('/order')
}

onMounted(() => {
  // 登录态校验:无员工信息视为登录失效,直接返回待机页重新刷卡
  if (!emp.value) {
    router.replace('/order')
    return
  }
  updateClock()
  timer = window.setInterval(updateClock, 1000)
  fetchBranding()
})
onUnmounted(() => {
  if (timer) clearInterval(timer)
})
</script>

<template>
  <main class="menu">
    <BrandingBg :bg-url="branding?.terminalBackgroundUrl" :overlay-opacity="0.15" />
    <UserInfoBar :employee="emp" />

    <div class="menu__content">
      <!-- 中央大时钟(居中显示) -->
      <div class="menu__clock-section">
        <div class="menu__clock">{{ clock }}</div>
        <div class="menu__date">{{ dateLabel }}</div>
      </div>

      <!-- 三大入口 -->
      <div class="menu__grid">
        <button class="menu__card btn-press" @click="goSelect">
          <div class="menu__icon menu__icon--primary">
            <Utensils :size="32" />
          </div>
          <span class="menu__label">我要订餐</span>
        </button>

        <button class="menu__card btn-press" @click="goQuery">
          <div class="menu__icon menu__icon--primary">
            <Search :size="32" />
          </div>
          <span class="menu__label">查询订餐</span>
        </button>

        <button class="menu__card btn-press" @click="goHome">
          <div class="menu__icon menu__icon--muted">
            <Home :size="32" />
          </div>
          <span class="menu__label">返回主页</span>
        </button>
      </div>
    </div>
  </main>
</template>

<style scoped>
.menu {
  position: relative;
  display: flex;
  flex-direction: column;
  height: 100vh;
  overflow: hidden;
  /* 深色背景 + 白色文字(与待机页统一) */
  background: var(--doubao-foreground);
}
.menu > *:not(.branding-bg) {
  position: relative;
  z-index: 1;
}
.menu__content {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 48px;
  padding: 24px 24px 40px;
  overflow-y: auto;
  min-height: 0;
}

/* 中央大时钟(居中,白色) */
.menu__clock-section {
  text-align: center;
  color: #ffffff;
  text-shadow: 0 2px 8px rgba(0, 0, 0, 0.3);
}
.menu__clock {
  font-size: var(--fs-clock);
  font-weight: 700;
  line-height: 1;
  letter-spacing: -2px;
  font-variant-numeric: tabular-nums;
}
.menu__date {
  margin-top: 16px;
  font-size: var(--fs-xl);
  color: rgba(255, 255, 255, 0.85);
}

.menu__grid {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: center;
  gap: 32px;
}
.menu__card {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16px;
  width: 200px;
  height: 200px;
  flex-shrink: 0;
  border-radius: var(--doubao-radius);
  background: rgba(255, 255, 255, 0.08);
  border: 1.5px solid rgba(255, 255, 255, 0.15);
  cursor: pointer;
  font-family: inherit;
  backdrop-filter: blur(8px);
  transition: transform 0.12s ease, background 0.15s ease;
}
.menu__card:hover { background: rgba(255, 255, 255, 0.15); }
.menu__card:active { transform: scale(0.97); }
.menu__icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 64px;
  height: 64px;
  border-radius: 50%;
}
.menu__icon--primary {
  background: var(--doubao-accent);
  color: var(--doubao-primary);
}
.menu__icon--muted {
  background: rgba(255, 255, 255, 0.15);
  color: rgba(255, 255, 255, 0.7);
}
.menu__label {
  font-size: var(--fs-xl);
  font-weight: 700;
  color: #ffffff;
}

/* 竖屏适配 */
@media (orientation: portrait) {
  .menu__content { gap: 48px; }
}
</style>
