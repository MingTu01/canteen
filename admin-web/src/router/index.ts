import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import nprogress from 'nprogress'

nprogress.configure({ showSpinner: false, speed: 300 })

/** 路由元信息扩展 */
declare module 'vue-router' {
  interface RouteMeta {
    requiresAuth?: boolean
    title?: string
    icon?: string
    roles?: number[]
  }
}

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/Login.vue'),
    meta: { requiresAuth: false, title: '登录' },
  },
  {
    path: '/',
    redirect: '/dashboard',
    meta: { requiresAuth: true },
  },
  {
    path: '/dashboard',
    name: 'Dashboard',
    component: () => import('@/views/dashboard/Dashboard.vue'),
    meta: { requiresAuth: true, title: '数据总览', icon: 'LayoutDashboard', roles: [1, 2, 4, 5, 6] },
  },
  {
    path: '/dish',
    name: 'DishManagement',
    component: () => import('@/views/dish/DishManagement.vue'),
    meta: { requiresAuth: true, title: '菜品管理', icon: 'ChefHat', roles: [1, 2, 5, 6] },
  },
  {
    path: '/menu',
    name: 'MenuManagement',
    component: () => import('@/views/menu/MenuManagement.vue'),
    meta: { requiresAuth: true, title: '菜单管理', icon: 'BookOpen', roles: [1, 2, 5, 6] },
  },
  {
    path: '/order',
    name: 'OrderManagement',
    component: () => import('@/views/order/OrderManagement.vue'),
    meta: { requiresAuth: true, title: '订单管理', icon: 'ClipboardList', roles: [1, 2, 6] },
  },
  {
    path: '/order-summary',
    name: 'OrderSummary',
    component: () => import('@/views/order/OrderSummary.vue'),
    meta: { requiresAuth: true, title: '订餐汇总', icon: 'FileSpreadsheet', roles: [1, 2, 5, 6] },
  },
  {
    path: '/employee',
    name: 'EmployeeManagement',
    component: () => import('@/views/employee/EmployeeManagement.vue'),
    meta: { requiresAuth: true, title: '员工管理', icon: 'Users', roles: [1, 2, 6] },
  },
  {
    path: '/department',
    name: 'DepartmentManagement',
    component: () => import('@/views/department/DepartmentManagement.vue'),
    meta: { requiresAuth: true, title: '部门管理', icon: 'Building2', roles: [1, 2, 6] },
  },
  {
    path: '/timer',
    name: 'TimerManagement',
    component: () => import('@/views/timer/TimerManagement.vue'),
    meta: { requiresAuth: true, title: '就餐时段', icon: 'Clock', roles: [1, 2, 6] },
  },
  {
    path: '/notification',
    name: 'NotificationManagement',
    component: () => import('@/views/notification/NotificationManagement.vue'),
    meta: { requiresAuth: true, title: '通知管理', icon: 'Megaphone', roles: [1, 2, 6] },
  },
  {
    path: '/report',
    name: 'Report',
    component: () => import('@/views/report/Report.vue'),
    meta: { requiresAuth: true, title: '报表统计', icon: 'BarChart3', roles: [1, 2, 4, 6] },
  },
  {
    path: '/daily-close',
    name: 'DailyCloseManagement',
    component: () => import('@/views/daily-close/DailyCloseManagement.vue'),
    meta: { requiresAuth: true, title: '日终对账', icon: 'ClipboardCheck', roles: [1, 2, 4, 6] },
  },
  {
    path: '/settlement',
    name: 'SettlementManagement',
    component: () => import('@/views/settlement/SettlementManagement.vue'),
    meta: { requiresAuth: true, title: '关店对账', icon: 'CalendarCheck', roles: [1, 2, 4, 6] },
  },
  {
    path: '/recharge',
    name: 'Recharge',
    component: () => import('@/views/recharge/Recharge.vue'),
    meta: { requiresAuth: true, title: '充值记录', icon: 'Wallet', roles: [1, 2, 4, 6] },
  },
  {
    path: '/settings',
    name: 'Settings',
    component: () => import('@/views/settings/Settings.vue'),
    meta: { requiresAuth: true, title: '系统设置', icon: 'Settings', roles: [1] },
  },
  {
    path: '/store',
    name: 'StoreManagement',
    component: () => import('@/views/store/StoreManagement.vue'),
    meta: { requiresAuth: true, title: '食堂管理', icon: 'Store', roles: [1] },
  },
  {
    path: '/admin',
    name: 'AdminManagement',
    component: () => import('@/views/admin/AdminManagement.vue'),
    meta: { requiresAuth: true, title: '账号管理', icon: 'UserCog', roles: [1, 2] },
  },
  {
    path: '/backup',
    name: 'BackupManagement',
    component: () => import('@/views/backup/BackupManagement.vue'),
    meta: { requiresAuth: true, title: '备份恢复', icon: 'DatabaseBackup', roles: [1, 2] },
  },
  {
    path: '/operation-log',
    name: 'OperationLogManagement',
    component: () => import('@/views/system/OperationLogManagement.vue'),
    meta: { requiresAuth: true, title: '操作日志', icon: 'FileText', roles: [1, 2] },
  },
  {
    path: '/supplier',
    name: 'SupplierManagement',
    component: () => import('@/views/supplier/SupplierManagement.vue'),
    meta: { requiresAuth: true, title: '供应商管理', icon: 'Truck', roles: [1, 2, 5, 6] },
  },
  {
    path: '/purchase',
    name: 'PurchaseManagement',
    component: () => import('@/views/purchase/PurchaseManagement.vue'),
    meta: { requiresAuth: true, title: '采购管理', icon: 'ShoppingCart', roles: [1, 2, 5, 6] },
  },
  {
    path: '/material',
    name: 'MaterialManagement',
    component: () => import('@/views/material/MaterialManagement.vue'),
    meta: { requiresAuth: true, title: '库存管理', icon: 'Package', roles: [1, 2, 5, 6] },
  },
  {
    path: '/feedback',
    name: 'FeedbackManagement',
    component: () => import('@/views/feedback/FeedbackManagement.vue'),
    meta: { requiresAuth: true, title: '反馈评价', icon: 'MessageSquare', roles: [1, 2, 6] },
  },
  {
    path: '/group-order',
    name: 'GroupOrderManagement',
    component: () => import('@/views/group-order/GroupOrderManagement.vue'),
    meta: { requiresAuth: true, title: '团体订餐', icon: 'Users', roles: [1, 2, 6] },
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'NotFound',
    component: () => import('@/views/NotFound.vue'),
    meta: { requiresAuth: false, title: '页面未找到' },
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior(_to, _from, savedPosition) {
    return savedPosition || { top: 0 }
  },
})

router.beforeEach((to, _from, next) => {
  nprogress.start()
  const authStore = useAuthStore()

  // 鉴权基于 admin 信息是否存在(token 仅作 localStorage 标志,不作为鉴权依据)
  if (to.meta.requiresAuth && !authStore.isLoggedIn) {
    next({ path: '/login', query: { redirect: to.fullPath } })
    return
  }

  if (to.path === '/login' && authStore.isLoggedIn) {
    next('/dashboard')
    return
  }

  if (to.meta.roles && to.meta.roles.length > 0) {
    if (!authStore.hasRole(...to.meta.roles)) {
      // 角色不匹配:跳转登录页,避免在 dashboard(可能无权限)和受保护页面间死循环
      authStore.clearState()
      next({ path: '/login', query: { redirect: to.fullPath } })
      return
    }
  }

  next()
})

router.afterEach((to) => {
  nprogress.done()
  const baseTitle = '企业智慧食堂'
  document.title = to.meta.title ? `${to.meta.title} - ${baseTitle}` : baseTitle
})

export default router
