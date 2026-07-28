<script setup lang="ts">
/**
 * 取餐验证页
 *
 * 刷卡后拉取员工今日待取餐订单,选第一条 status===1 的,
 * 设置 store 后跳转取餐信息页。至少展示 1.2s "验证中" 动画。
 */
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import api, { loadConfig } from '@/api'
import { pickupStore, type PickupOrder } from '@/store/pickup'
import { brandingState, fetchBranding } from '@/store/branding'
import { toDateKey } from '@/utils'
import { User } from 'lucide-vue-next'
import BrandingBg from '@/components/BrandingBg.vue'
import Modal from '@/components/Modal.vue'

const router = useRouter()
const employee = computed(() => pickupStore.employee)
const branding = computed(() => brandingState.data)
const startedAt = ref(0)
let done = false
let advanceTimer: ReturnType<typeof setTimeout> | null = null

/* 错误弹窗 */
const showError = ref(false)
const errorMsg = ref('')
const errorVariant = ref<'warning' | 'info'>('warning')
const errorTitle = ref('')

/** 拉取员工今日待取餐订单,选第一条 status===1 的,设置 store 后跳转取餐信息页 */
const fetchAndAdvance = async () => {
  if (!employee.value) {
    router.replace('/pickup')
    return
  }
  try {
    const resp = await api.get(`/order/employee/${employee.value.id}`)
    const list: any[] = resp.data?.code === 200 ? (resp.data.data || []) : []
    const today = toDateKey(new Date())
    const pending = list
      .filter((o) => o.date === today && o.status === 1)
      .sort((a, b) => Number(a.mealType) - Number(b.mealType))
    if (pending.length === 0) {
      // 测试阶段:无真实订单时,从今日菜单随机生成模拟订单便于测试取餐展示
      await buildMockOrderFromTodayMenu(today)
      return
    }
    const o = pending[0]
    const order: PickupOrder = {
      id: Number(o.id),
      mealType: Number(o.mealType),
      date: String(o.date),
      totalAmount: Number(o.totalAmount ?? 0),
      // 订单来源:0-正常订餐,1-未订餐用餐(用于取餐页标识)
      orderSource: Number(o.orderSource ?? 0),
      // 兼容后端 items 字段(批量查询填充),映射菜品图片用于取餐页大图展示
      orderItems: (o.items ?? []).map((it: any) => ({
        dishName: String(it.dishName || ''),
        price: Number(it.price ?? 0),
        quantity: Number(it.quantity ?? 1),
        dishImage: String(it.dishImage || it.dish_image || ''),
      })),
    }
    pickupStore.order = order
    // 至少展示 1.2s 的"验证中"动画
    const elapsed = Date.now() - startedAt.value
    const wait = Math.max(0, 1200 - elapsed)
    advanceTimer = setTimeout(() => {
      if (done) return
      done = true
      router.replace('/pickup/info')
    }, wait)
  } catch (e: any) {
    errorTitle.value = '查询失败'
    errorMsg.value = e?.response?.data?.message || '查询订餐信息失败,请重试'
    errorVariant.value = 'warning'
    showError.value = true
  }
}

/**
 * 测试阶段辅助:无真实订单时,从今日菜单随机生成模拟订单。
 * 随机选一个餐次,从该餐次菜品中随机选 2-4 道,展示取餐页面。
 * 模拟订单 id 为负数,避免与真实订单混淆。
 */
const buildMockOrderFromTodayMenu = async (today: string) => {
  try {
    const cfg = loadConfig()
    if (!cfg || !cfg.storeId) {
      throw new Error('未绑定门店')
    }
    const resp = await api.get(`/menu/store/${cfg.storeId}/date/${today}`)
    const menus: any[] = resp.data?.code === 200 ? (resp.data.data || []) : []
    if (menus.length === 0) {
      throw new Error('今日菜单未配置')
    }
    // 随机选一个餐次
    const randomMenu = menus[Math.floor(Math.random() * menus.length)]
    const dishes: any[] = randomMenu.items || randomMenu.dishes || []
    if (dishes.length === 0) {
      throw new Error('今日菜单无菜品')
    }
    // 随机选 2-4 道菜
    const pickCount = Math.min(dishes.length, 2 + Math.floor(Math.random() * 3))
    const shuffled = [...dishes].sort(() => Math.random() - 0.5)
    const picked = shuffled.slice(0, pickCount)
    const totalAmount = picked.reduce((sum, d) => sum + Number(d.price || 0), 0)
    const order: PickupOrder = {
      id: -1, // 模拟订单 id 为负数,标识测试数据
      mealType: Number(randomMenu.mealType),
      date: today,
      totalAmount,
      orderItems: picked.map((d) => ({
        dishName: String(d.dishName || d.name || ''),
        price: Number(d.price ?? 0),
        quantity: 1,
        dishImage: String(d.dishImage || d.image || ''),
      })),
    }
    pickupStore.order = order
    const elapsed = Date.now() - startedAt.value
    const wait = Math.max(0, 1200 - elapsed)
    advanceTimer = setTimeout(() => {
      if (done) return
      done = true
      router.replace('/pickup/info')
    }, wait)
  } catch (e: any) {
    errorTitle.value = '暂无待取餐订单'
    errorMsg.value = '今日暂无待取餐订单(测试阶段也未配置菜单)'
    errorVariant.value = 'info'
    showError.value = true
  }
}

/** 错误弹窗确认后返回待机 */
const onErrorConfirm = () => {
  showError.value = false
  router.replace('/pickup')
}

onMounted(() => {
  startedAt.value = Date.now()
  fetchBranding({ background: true })
  fetchAndAdvance()
})
onUnmounted(() => {
  done = true
  if (advanceTimer) clearTimeout(advanceTimer)
})
</script>

<template>
  <main class="verify">
    <BrandingBg :bg-url="branding?.terminalBackgroundUrl" :overlay-opacity="0.5" />

    <div class="verify__inner">
      <!-- Spinner -->
      <div class="verify__spinner spinner"></div>

      <!-- 验证中 -->
      <div class="verify__title">验证中...</div>

      <!-- 用户信息卡片 -->
      <div v-if="employee" class="verify__card">
        <div class="verify__avatar">
          <User :size="32" />
        </div>
        <div class="verify__user">
          {{ employee.name }} · {{ employee.departmentName || '未分配部门' }} · 工号 {{ employee.cardNo }}
        </div>
        <div class="verify__hint">正在查询您的订餐信息...</div>
      </div>
    </div>

    <!-- 错误弹窗:无待取餐订单 / 查询失败 -->
    <Modal
      v-model="showError"
      :title="errorTitle"
      :message="errorMsg"
      :variant="errorVariant"
      :cancel-text="''"
      confirm-text="知道了"
      @confirm="onErrorConfirm"
      @cancel="onErrorConfirm"
    />
  </main>
</template>

<style scoped>
.verify {
  position: relative;
  min-height: 100vh;
  /* 取餐端统一深色背景 + 白色文字 */
  background: var(--doubao-foreground);
}
.verify__inner {
  position: relative;
  z-index: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  gap: 32px;
  padding: 40px 24px;
}
.verify__spinner {
  width: 56px;
  height: 56px;
  border: 4px solid rgba(255, 255, 255, 0.2);
  border-top-color: var(--doubao-primary);
  border-radius: 50%;
}
.verify__title {
  font-size: var(--fs-xl);
  font-weight: 600;
  color: #ffffff;
}
.verify__card {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
  padding: 32px 48px;
  background: rgba(255, 255, 255, 0.08);
  border: 1px solid rgba(255, 255, 255, 0.15);
  border-radius: var(--doubao-radius);
  min-width: 360px;
  backdrop-filter: blur(8px);
}
.verify__avatar {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 64px;
  height: 64px;
  border-radius: 50%;
  background: var(--doubao-accent);
  color: var(--doubao-primary);
}
.verify__user {
  font-size: var(--fs-lg);
  font-weight: 600;
  color: #ffffff;
  text-align: center;
}
.verify__hint {
  font-size: var(--fs-base);
  color: rgba(255, 255, 255, 0.7);
  margin-top: 4px;
}
</style>
