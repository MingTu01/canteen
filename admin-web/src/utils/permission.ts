/**
 * 前端权限工具(admin-web)。
 *
 * 配合后端 PermissionUtils 设计:
 * - role=0: 员工(H5/小程序)
 * - role=1: 超级管理员(可跨门店)
 * - role=2: 门店管理员(仅本门店)
 * - role=3: 终端(扫码设备)
 * - role=4: 财务岗(报表+充值)
 * - role=5: 厨师长(订餐汇总+菜品)
 * - role=6: 店长(全店管理,不可删数据)
 *
 * 使用方式:
 *   import { hasPermission, canAccessStore, ROLE_SUPER_ADMIN } from '@/utils/permission'
 *   if (hasPermission([ROLE_SUPER_ADMIN])) { ... }
 *   if (canAccessStore(targetStoreId)) { ... }
 */
import { useAuthStore } from '@/stores/auth'

export const ROLE_EMPLOYEE = 0
export const ROLE_SUPER_ADMIN = 1
export const ROLE_STORE_ADMIN = 2
export const ROLE_TERMINAL = 3
export const ROLE_FINANCE = 4
export const ROLE_CHEF = 5
export const ROLE_STORE_MANAGER = 6

/** 当前登录管理员 */
const currentAdmin = () => useAuthStore().admin

/** 当前角色 */
export const currentRole = (): number | null => currentAdmin()?.role ?? null

/** 当前门店 ID */
export const currentStoreId = (): number | null => currentAdmin()?.storeId ?? null

/** 是否超级管理员 */
export const isSuperAdmin = (): boolean => currentRole() === ROLE_SUPER_ADMIN

/** 是否门店管理员 */
export const isStoreAdmin = (): boolean => currentRole() === ROLE_STORE_ADMIN

/** 是否财务岗 */
export const isFinance = (): boolean => currentRole() === ROLE_FINANCE

/** 是否厨师长 */
export const isChef = (): boolean => currentRole() === ROLE_CHEF

/** 是否店长 */
export const isStoreManager = (): boolean => currentRole() === ROLE_STORE_MANAGER

/** 是否任意管理级别角色(1/2/4/5/6),排除员工(0)和终端(3) */
export const isAdmin = (): boolean => {
  const r = currentRole()
  return r !== null && r !== 3 && r >= ROLE_SUPER_ADMIN && r <= ROLE_STORE_MANAGER
}

/**
 * 是否拥有指定角色之一。
 * @param roles 允许的角色数组,如 [ROLE_SUPER_ADMIN] 表示仅超管
 */
export const hasPermission = (roles: number[]): boolean => {
  const r = currentRole()
  return r !== null && roles.includes(r)
}

/**
 * 是否可访问目标门店。
 * 超管可访问任意门店;其他管理角色仅可访问本门店。
 */
export const canAccessStore = (targetStoreId: number | null | undefined): boolean => {
  if (targetStoreId == null) return false
  if (isSuperAdmin()) return true
  const current = currentStoreId()
  return current !== null && current === targetStoreId
}

/**
 * 路由守卫用:是否可访问带 meta.roles 的路由。
 * 未声明 roles 默认所有管理员可访问。
 */
export const canAccessRoute = (routeMetaRoles?: number[]): boolean => {
  if (!routeMetaRoles || routeMetaRoles.length === 0) return true
  return hasPermission(routeMetaRoles)
}
