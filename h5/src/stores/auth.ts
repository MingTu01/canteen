import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import * as authApi from '@/api/auth'
import type { Employee } from '@/api/types'

/**
 * 认证 Store(H5 员工端)。
 *
 * 鉴权策略(与 admin-web / terminal 一致):
 * - token 由后端写入 HttpOnly Cookie,前端不存储。
 * - localStorage 缓存 employee 信息(刷新页面后恢复用户信息)。
 * - localStorage 缓存 isLoggedIn 标志(只要不手动退出就保持登录,配合后端滑动续期实现永不失效)。
 *
 * 注:手动持久化,不依赖 pinia-plugin-persistedstate。
 */

const EMPLOYEE_STORAGE_KEY = 'canteen_h5_employee'
const LOGGED_IN_STORAGE_KEY = 'canteen_h5_logged_in'

/** 从 localStorage 读取 employee */
const readEmployee = (): Employee | null => {
  try {
    const raw = localStorage.getItem(EMPLOYEE_STORAGE_KEY)
    return raw ? (JSON.parse(raw) as Employee) : null
  } catch {
    return null
  }
}

/** 从 localStorage 读取登录标志(只要不手动退出就保持登录) */
const readLoggedIn = (): boolean => {
  try {
    return localStorage.getItem(LOGGED_IN_STORAGE_KEY) === '1'
  } catch {
    return false
  }
}

export const useAuthStore = defineStore('auth', () => {
  // 初始化时从存储恢复
  const employee = ref<Employee | null>(readEmployee())
  const isLoggedIn = ref<boolean>(readLoggedIn())

  // ============ getters ============
  const balance = computed<number>(() => employee.value?.balance ?? 0)
  const employeeName = computed<string>(() => employee.value?.name ?? '')
  const storeId = computed<number | null>(() => employee.value?.storeId ?? null)
  const employeeId = computed<number | null>(() => employee.value?.id ?? null)
  /** 是否需要强制修改密码(首次登录使用默认密码) */
  const needChangePassword = computed<boolean>(() => employee.value?.mustChangePassword === 1)

  // ============ 持久化辅助 ============
  const persistEmployee = (emp: Employee | null) => {
    try {
      if (emp) {
        localStorage.setItem(EMPLOYEE_STORAGE_KEY, JSON.stringify(emp))
      } else {
        localStorage.removeItem(EMPLOYEE_STORAGE_KEY)
      }
    } catch {
      /* 忽略 quota 异常 */
    }
  }

  const persistLoggedIn = (flag: boolean) => {
    try {
      if (flag) {
        localStorage.setItem(LOGGED_IN_STORAGE_KEY, '1')
      } else {
        localStorage.removeItem(LOGGED_IN_STORAGE_KEY)
      }
    } catch {
      /* 忽略 */
    }
  }

  // ============ actions ============
  /** 手机号登录 */
  const phoneLogin = async (phone: string, password: string): Promise<Employee> => {
    const res = await authApi.phoneLogin(phone, password)
    employee.value = res.employee
    isLoggedIn.value = true
    persistEmployee(res.employee)
    persistLoggedIn(true)
    return res.employee
  }

  /** 卡号登录(需指定门店) */
  const login = async (cardNo: string, targetStoreId: number, password: string): Promise<Employee> => {
    const res = await authApi.login(cardNo, targetStoreId, password)
    employee.value = res.employee
    isLoggedIn.value = true
    persistEmployee(res.employee)
    persistLoggedIn(true)
    return res.employee
  }

  /**
   * 微信授权码登录:已绑定则直接登录成功,未绑定抛出 { needBind: true, bindToken } 供调用方处理。
   * token 由后端写入 HttpOnly Cookie,前端无需存储。
   */
  const wechatLogin = async (code: string): Promise<Employee> => {
    const res = await authApi.wechatLogin(code)
    if (res.status === 'login' && res.employee) {
      employee.value = res.employee
      isLoggedIn.value = true
      persistEmployee(res.employee)
      persistLoggedIn(true)
      return res.employee
    }
    if (res.status === 'need_bind' && res.bindToken) {
      const err = new Error('need_bind') as Error & { needBind?: boolean; bindToken?: string }
      err.needBind = true
      err.bindToken = res.bindToken
      throw err
    }
    throw new Error('微信登录失败')
  }

  /** 微信绑定:通过手机号+密码验证身份,绑定 openid 后自动登录 */
  const wechatBind = async (bindToken: string, phone: string, password: string): Promise<Employee> => {
    const res = await authApi.wechatBind(bindToken, phone, password)
    employee.value = res.employee
    isLoggedIn.value = true
    persistEmployee(res.employee)
    persistLoggedIn(true)
    return res.employee
  }

  /** 注销:调用后端清 Cookie + 加入黑名单,再清前端状态 */
  const logout = async (): Promise<void> => {
    try {
      await authApi.logout()
    } catch {
      /* 即使后端调用失败,也继续清前端状态 */
    }
    employee.value = null
    isLoggedIn.value = false
    persistEmployee(null)
    persistLoggedIn(false)
  }

  /** 刷新员工信息(从后端拉取最新余额等,使用 /employee/me 基于 token,不依赖 localStorage 中的 ID) */
  const refreshEmployee = async (): Promise<void> => {
    if (!isLoggedIn.value) return
    try {
      const latest = await authApi.getMe()
      employee.value = latest
      persistEmployee(latest)
    } catch {
      /* 忽略刷新失败,保留缓存的员工信息 */
    }
  }

  /** 直接设置员工信息(供外部同步用) */
  const setEmployee = (emp: Employee | null): void => {
    employee.value = emp
    isLoggedIn.value = emp !== null
    persistEmployee(emp)
    persistLoggedIn(emp !== null)
  }

  return {
    // state
    employee,
    isLoggedIn,
    // getters
    balance,
    employeeName,
    storeId,
    employeeId,
    needChangePassword,
    // actions
    phoneLogin,
    login,
    wechatLogin,
    wechatBind,
    logout,
    refreshEmployee,
    setEmployee,
  }
})
