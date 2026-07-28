import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import * as authApi from '@/api/auth'
import { getEmployee } from '@/api/employee'
import type { Employee } from '@/api/types'

/**
 * 认证 Store(H5 员工端)。
 *
 * 鉴权策略(与 admin-web / terminal 一致):
 * - token 由后端写入 HttpOnly Cookie,前端不存储。
 * - localStorage 缓存 employee 信息(刷新页面后恢复用户信息)。
 * - sessionStorage 缓存 isLoggedIn 标志(关闭浏览器标签后失效,需重新登录)。
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

/** 从 sessionStorage 读取登录标志 */
const readLoggedIn = (): boolean => {
  try {
    return sessionStorage.getItem(LOGGED_IN_STORAGE_KEY) === '1'
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
        sessionStorage.setItem(LOGGED_IN_STORAGE_KEY, '1')
      } else {
        sessionStorage.removeItem(LOGGED_IN_STORAGE_KEY)
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

  /** 刷新员工信息(从后端拉取最新余额等) */
  const refreshEmployee = async (): Promise<void> => {
    if (!employee.value?.id) return
    try {
      const latest = await getEmployee(employee.value.id)
      employee.value = latest
      persistEmployee(latest)
    } catch {
      /* 忽略刷新失败 */
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
    // actions
    phoneLogin,
    login,
    logout,
    refreshEmployee,
    setEmployee,
  }
})
