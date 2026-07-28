import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

/**
 * H5 订餐端路由。
 *
 * 路由元信息:
 * - requiresAuth: 是否需要登录(默认 true,/login 为 false)
 * - hideTabbar: 是否隐藏底部 Tabbar(登录/详情/表单页)
 * - keepAlive: 是否启用 keep-alive 缓存(首页/订餐页)
 * - title: 页面标题(用于 document.title)
 */
declare module 'vue-router' {
  interface RouteMeta {
    requiresAuth?: boolean
    hideTabbar?: boolean
    keepAlive?: boolean
    title?: string
  }
}

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/Login.vue'),
    meta: { requiresAuth: false, hideTabbar: true, title: '登录' },
  },
  {
    path: '/',
    name: 'Home',
    component: () => import('@/views/Home.vue'),
    meta: { requiresAuth: true, keepAlive: true, title: '首页' },
  },
  {
    path: '/order',
    name: 'Order',
    component: () => import('@/views/Order.vue'),
    meta: { requiresAuth: true, keepAlive: true, title: '订餐' },
  },
  {
    path: '/orders',
    name: 'Orders',
    component: () => import('@/views/Orders.vue'),
    meta: { requiresAuth: true, title: '我的订单' },
  },
  {
    path: '/orders/:id',
    name: 'OrderDetail',
    component: () => import('@/views/OrderDetail.vue'),
    meta: { requiresAuth: true, hideTabbar: true, title: '订单详情' },
  },
  {
    path: '/profile',
    name: 'Profile',
    component: () => import('@/views/Profile.vue'),
    meta: { requiresAuth: true, title: '我的' },
  },
  {
    path: '/feedback/create',
    name: 'FeedbackCreate',
    component: () => import('@/views/feedback/FeedbackCreate.vue'),
    meta: { requiresAuth: true, hideTabbar: true, title: '提交反馈' },
  },
  {
    path: '/feedback/list',
    name: 'FeedbackList',
    component: () => import('@/views/feedback/FeedbackList.vue'),
    meta: { requiresAuth: true, hideTabbar: true, title: '我的反馈' },
  },
  {
    path: '/group-order',
    name: 'GroupOrderList',
    component: () => import('@/views/group-order/GroupOrderList.vue'),
    meta: { requiresAuth: true, hideTabbar: true, title: '团餐' },
  },
  {
    path: '/group-order/:id',
    name: 'GroupOrderDetail',
    component: () => import('@/views/group-order/GroupOrderDetail.vue'),
    meta: { requiresAuth: true, hideTabbar: true, title: '团餐详情' },
  },
  {
    path: '/unsolicited-order',
    name: 'UnsolicitedOrder',
    component: () => import('@/views/UnsolicitedOrder.vue'),
    meta: { requiresAuth: true, hideTabbar: true, title: '未订餐用餐' },
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'NotFound',
    component: () => import('@/views/NotFound.vue'),
    meta: { requiresAuth: false, hideTabbar: true, title: '页面未找到' },
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior(_to, _from, savedPosition) {
    return savedPosition || { top: 0 }
  },
})

/** 路由守卫:检查登录态 */
router.beforeEach((to, _from, next) => {
  const authStore = useAuthStore()
  const baseTitle = '食堂订餐'
  document.title = to.meta.title ? `${to.meta.title} - ${baseTitle}` : baseTitle

  // 需要登录但未登录 → 跳登录页(带 redirect)
  if (to.meta.requiresAuth !== false && !authStore.isLoggedIn) {
    next({ path: '/login', query: { redirect: to.fullPath } })
    return
  }

  // 已登录访问登录页 → 跳首页
  if (to.path === '/login' && authStore.isLoggedIn) {
    next('/')
    return
  }

  next()
})

export default router
