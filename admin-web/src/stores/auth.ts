import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import api, { adminApi } from '@/api'

export interface AdminInfo {
  id: number
  username: string
  name: string
  storeId: number
  role: number
}

/** 角色常量:0=员工(H5),1=超管,2=门店管理员,3=终端,4=财务,5=厨师长,6=店长 */
export const ROLE_EMPLOYEE = 0
export const ROLE_SUPER_ADMIN = 1
export const ROLE_STORE_ADMIN = 2
export const ROLE_TERMINAL = 3
export const ROLE_FINANCE = 4
export const ROLE_CHEF = 5
export const ROLE_STORE_MANAGER = 6

/**
 * 管理员认证 Store。
 *
 * 鉴权策略升级(2026-07-18):
 * - token 不再由前端管理,改由后端 HttpOnly Cookie 携带。
 * - localStorage['auth'](通过 pinia-plugin-persistedstate 持久化)仅缓存 admin 信息与
 *   "已登录"标志。
 * - logout 调用后端 /api/auth/logout,将 token 加入黑名单并清除 Cookie。
 */
export const useAuthStore = defineStore('auth', () => {
  const admin = ref<AdminInfo | null>(null)

  const isLoggedIn = computed(() => admin.value !== null)
  const isSuperAdmin = computed(() => admin.value?.role === ROLE_SUPER_ADMIN)
  const storeId = computed(() => admin.value?.storeId)

  const login = async (username: string, password: string) => {
    const res = await adminApi.login({ username, password })
    admin.value = res.data.admin
    return res
  }

  /** 注销:调用后端将 token 加入黑名单 + 清除 Cookie,再清前端状态 */
  const logout = async () => {
    try {
      await api.post('/auth/logout')
    } catch {
      /* 静默:即使后端清理失败也继续清前端状态 */
    }
    admin.value = null
  }

  /** 清空内存状态(供 401 拦截器调用,避免与路由守卫状态不一致)
   *  不再手动 removeItem:persist 插件会自动同步空状态到 localStorage */
  const clearState = () => {
    admin.value = null
  }

  /** 是否拥有指定角色之一(支持多角色:hasRole(1,2,5)) */
  const hasRole = (...roles: number[]) => {
    return admin.value ? roles.includes(admin.value.role) : false
  }

  /** 兼容数组形式:hasRole([1,2]) */
  const hasRoleArray = (roles: number[]) => {
    return admin.value ? roles.includes(admin.value.role) : false
  }

  /** 是否有报表/财务访问权限(超管/店管/财务/店长) */
  const canViewFinance = () =>
    hasRole(ROLE_SUPER_ADMIN, ROLE_STORE_ADMIN, ROLE_FINANCE, ROLE_STORE_MANAGER)

  /** 是否有菜品管理权限(超管/店管/厨师/店长) */
  const canManageDish = () =>
    hasRole(ROLE_SUPER_ADMIN, ROLE_STORE_ADMIN, ROLE_CHEF, ROLE_STORE_MANAGER)

  /** 是否有采购/库存管理权限(超管/店管/厨师/店长) */
  const canManageProcurement = () =>
    hasRole(ROLE_SUPER_ADMIN, ROLE_STORE_ADMIN, ROLE_CHEF, ROLE_STORE_MANAGER)

  /** 是否有系统管理权限(仅超管/店管) */
  const canManageSystem = () => hasRole(ROLE_SUPER_ADMIN, ROLE_STORE_ADMIN)

  /** 是否有对账/关店权限(超管/店管/财务/店长) */
  const canSettle = () =>
    hasRole(ROLE_SUPER_ADMIN, ROLE_STORE_ADMIN, ROLE_FINANCE, ROLE_STORE_MANAGER)

  return {
    admin, isLoggedIn, isSuperAdmin, storeId,
    login, logout, clearState,
    hasRole, hasRoleArray,
    canViewFinance, canManageDish, canManageProcurement, canManageSystem, canSettle,
  }
}, {
  persist: true
})
