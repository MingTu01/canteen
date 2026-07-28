import { createRouter, createWebHashHistory } from 'vue-router'
import { loadConfig } from '@/api'

const routes = [
  { path: '/', name: 'Home', component: () => import('@/views/Home.vue') },
  { path: '/settings', name: 'Settings', component: () => import('@/views/Settings.vue') },
  // ===== 订餐流程 =====
  { path: '/order', name: 'OrderStandby', component: () => import('@/views/OrderStandby.vue') },
  { path: '/order/menu', name: 'OrderMenu', component: () => import('@/views/OrderMenu.vue') },
  { path: '/order/select', name: 'OrderSelect', component: () => import('@/views/OrderSelect.vue') },
  { path: '/order/confirm', name: 'OrderConfirm', component: () => import('@/views/OrderConfirm.vue') },
  { path: '/order/success', name: 'OrderSuccess', component: () => import('@/views/OrderSuccess.vue') },
  { path: '/order/query', name: 'OrderQuery', component: () => import('@/views/OrderQuery.vue') },
  // ===== 取餐流程 =====
  { path: '/pickup', name: 'PickupStandby', component: () => import('@/views/PickupStandby.vue') },
  { path: '/pickup/verify', name: 'PickupVerify', component: () => import('@/views/PickupVerify.vue') },
  { path: '/pickup/info', name: 'PickupInfo', component: () => import('@/views/PickupInfo.vue') },
]

const router = createRouter({
  history: createWebHashHistory(),
  routes,
})

// 路由守卫:未绑定终端时强制跳转 /settings;已绑定时 /settings 也允许访问(操作需管理员密码)
router.beforeEach((to, _from, next) => {
  const cfg = loadConfig()
  if (!cfg && to.path !== '/settings') {
    next('/settings')
  } else {
    next()
  }
})

export default router
