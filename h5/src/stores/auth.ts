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
const OPENID_STORAGE_KEY = 'canteen_h5_openid'
/** sessionStorage 标记:本次会话已主动退出,不再自动微信授权(避免退出后被弹回原账号) */
const AUTO_AUTH_SUPPRESS_KEY = 'canteen_h5_auto_auth_suppressed'
/** sessionStorage 标记:本会话已自动触发过微信静默授权(未登录),防止重挂载后反复跳转形成授权闪烁循环 */
const AUTO_WX_ATTEMPTED_KEY = 'canteen_h5_auto_wx_attempted'

/** 从 localStorage 读取 openid(微信用户标识,用于自动登录和手机号登录自动换绑) */
const readOpenid = (): string | null => {
  try {
    return localStorage.getItem(OPENID_STORAGE_KEY)
  } catch {
    return null
  }
}

/** 持久化 openid 到 localStorage */
const persistOpenid = (openid: string | null) => {
  try {
    if (openid) {
      localStorage.setItem(OPENID_STORAGE_KEY, openid)
    } else {
      localStorage.removeItem(OPENID_STORAGE_KEY)
    }
  } catch {
    /* 忽略 quota 异常 */
  }
}

/** 读取当前微信 openid(供登录页传参实现自动换绑) */
const getOpenid = (): string | null => readOpenid()

/** 本次会话是否主动退出过(是则登录页不再自动微信授权) */
const isAutoAuthSuppressed = (): boolean => {
  try {
    return sessionStorage.getItem(AUTO_AUTH_SUPPRESS_KEY) === '1'
  } catch {
    return false
  }
}

/** 设置主动退出标记(登出时调用) */
const suppressAutoAuth = (): void => {
  try {
    sessionStorage.setItem(AUTO_AUTH_SUPPRESS_KEY, '1')
  } catch {
    /* 忽略 */
  }
}

/** 清除主动退出标记 + 自动授权尝试标记(登录成功时调用,恢复后续自动授权能力) */
const clearAutoAuthSuppressed = (): void => {
  try {
    sessionStorage.removeItem(AUTO_AUTH_SUPPRESS_KEY)
    sessionStorage.removeItem(AUTO_WX_ATTEMPTED_KEY)
  } catch {
    /* 忽略 */
  }
}

/** 本会话是否已自动触发过微信静默授权(未登录)。是则不再自动跳转,避免授权闪烁循环 */
const isWechatAutoAttempted = (): boolean => {
  try {
    return sessionStorage.getItem(AUTO_WX_ATTEMPTED_KEY) === '1'
  } catch {
    return false
  }
}

/** 标记本会话已自动触发过微信静默授权 */
const markWechatAutoAttempted = (): void => {
  try {
    sessionStorage.setItem(AUTO_WX_ATTEMPTED_KEY, '1')
  } catch {
    /* 忽略 */
  }
}

// ============ SSE 员工维度长连接(全局,不随页面切换断开) ============
// 模块级变量(不放入 store 响应式系统,避免 EventSource 被 Vue 代理)
// 生命周期:登录成功 → 启动;登出 → 关闭;页面刷新 → App.vue 恢复
let sseSource: EventSource | null = null
let sseReconnectTimer: ReturnType<typeof setTimeout> | null = null
let sseRetryCount = 0
/** SSE 是否应停止(true=未启动/已登出,false=应运行) */
let sseStopped = true
const SSE_MAX_RETRY = 10

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

  /**
   * 支付码核销时间戳:终端核销支付码后,SSE 推送 paycode_used 事件,
   * 更新此时间戳。Profile.vue watch 此值,变化时刷新二维码。
   * (SSE 在 store 全局管理,不随页面切换断开)
   */
  const payCodeUsedAt = ref<number>(0)

  /**
   * 菜单变更时间戳:管理端修改/发布菜单后,SSE 推送 menu_changed 事件,
   * 更新此时间戳 + 变更日期。Order.vue watch 此值,变化时清缓存并刷新菜单。
   */
  const menuChangedAt = ref<number>(0)
  /** 菜单变更影响的日期(yyyy-MM-dd),空字符串表示全部日期 */
  const menuChangedDate = ref<string>('')

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
  /** 手机号登录。openid 可选:微信内传当前 openid,后端自动绑定/换绑到该账号 */
  const phoneLogin = async (phone: string, password: string, openid?: string): Promise<Employee> => {
    const res = await authApi.phoneLogin(phone, password, openid)
    persistOpenid(openid || res.employee?.wxOpenid || readOpenid())
    employee.value = res.employee
    isLoggedIn.value = true
    persistEmployee(res.employee)
    persistLoggedIn(true)
    clearAutoAuthSuppressed()
    startSseOnLogin()
    return res.employee
  }

  /** 卡号登录(需指定门店) */
  const login = async (cardNo: string, targetStoreId: number, password: string): Promise<Employee> => {
    const res = await authApi.login(cardNo, targetStoreId, password)
    employee.value = res.employee
    isLoggedIn.value = true
    persistEmployee(res.employee)
    persistLoggedIn(true)
    startSseOnLogin()
    return res.employee
  }

  /**
   * 微信授权码登录:已绑定则直接登录成功,未绑定抛出 { needBind: true, bindToken } 供调用方处理。
   * token 由后端写入 HttpOnly Cookie,前端无需存储。
   */
  const wechatLogin = async (code: string): Promise<Employee> => {
    const res = await authApi.wechatLogin(code)
    if (res.openid) persistOpenid(res.openid)
    if (res.status === 'login' && res.employee) {
      employee.value = res.employee
      isLoggedIn.value = true
      persistEmployee(res.employee)
      persistLoggedIn(true)
      clearAutoAuthSuppressed()
      startSseOnLogin()
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
    // openid 已在 need_bind 阶段缓存;若返回中带 openid 则再补一次
    if (res.employee?.wxOpenid) persistOpenid(res.employee.wxOpenid)
    employee.value = res.employee
    isLoggedIn.value = true
    persistEmployee(res.employee)
    persistLoggedIn(true)
    clearAutoAuthSuppressed()
    startSseOnLogin()
    return res.employee
  }

  /** 注销:调用后端清 Cookie + 加入黑名单,再清前端状态 */
  const logout = async (): Promise<void> => {
    // 先关闭 SSE,避免登出后仍持有连接
    stopEmployeeSse()
    try {
      await authApi.logout()
    } catch {
      /* 即使后端调用失败,也继续清前端状态 */
    }
    // 标记本次已主动退出:当前标签页内不再自动微信授权,避免退出后又被弹回原账号
    // (用户可点「微信登录」手动重新登录,或改用手机号登录新账号自动换绑)
    suppressAutoAuth()
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

  // ============ SSE 员工维度长连接(全局管理) ============
  /**
   * 建立员工 SSE 长连接:终端核销支付码后实时推送 paycode_used 事件。
   * 全局管理(不随页面切换断开),登录期间一直保持。
   */
  const cleanupSseSource = (): void => {
    if (sseSource) {
      sseSource.close()
      sseSource = null
    }
  }

  const scheduleSseReconnect = (): void => {
    if (sseReconnectTimer) clearTimeout(sseReconnectTimer)
    if (sseRetryCount >= SSE_MAX_RETRY) return
    const delay = Math.min(1000 * Math.pow(2, sseRetryCount), 30000)
    sseRetryCount++
    sseReconnectTimer = setTimeout(() => {
      sseReconnectTimer = null
      startEmployeeSse()
    }, delay)
  }

  const startEmployeeSse = async (): Promise<void> => {
    if (!isLoggedIn.value) return
    // 已有连接不重复建立
    if (sseSource) return

    try {
      const { ticket } = await authApi.getEmployeeTicket()
      if (!ticket || sseStopped) return

      const url = `/api/sse/subscribe-employee?ticket=${encodeURIComponent(ticket)}`
      sseSource = new EventSource(url)

      sseSource.addEventListener('open', () => {
        sseRetryCount = 0
      })

      // 监听 paycode_used 事件:终端核销后更新时间戳,Profile.vue watch 刷新二维码
      sseSource.addEventListener('paycode_used', () => {
        payCodeUsedAt.value = Date.now()
      })

      // 监听 menu_changed 事件:管理端修改/发布菜单后,更新时间戳 + 日期,Order.vue watch 刷新菜单
      sseSource.addEventListener('menu_changed', (event: MessageEvent) => {
        try {
          const data = JSON.parse(event.data)
          menuChangedDate.value = data?.date || ''
        } catch {
          menuChangedDate.value = ''
        }
        menuChangedAt.value = Date.now()
      })

      sseSource.onerror = () => {
        cleanupSseSource()
        if (!sseStopped) {
          scheduleSseReconnect()
        }
      }
    } catch {
      cleanupSseSource()
      if (!sseStopped) {
        scheduleSseReconnect()
      }
    }
  }

  /** 登录成功后启动 SSE(由 login/phoneLogin/wechatLogin/wechatBind 调用) */
  const startSseOnLogin = (): void => {
    sseStopped = false
    sseRetryCount = 0
    startEmployeeSse()
  }

  /** 登出时关闭 SSE(由 logout 调用) */
  const stopEmployeeSse = (): void => {
    sseStopped = true
    if (sseReconnectTimer) {
      clearTimeout(sseReconnectTimer)
      sseReconnectTimer = null
    }
    cleanupSseSource()
  }

  /**
   * 页面刷新后恢复 SSE 连接(App.vue onMounted 调用)。
   * 如果已登录但 SSE 未启动,则启动。
   */
  const ensureSseRunning = (): void => {
    if (isLoggedIn.value && sseStopped) {
      sseStopped = false
      sseRetryCount = 0
      startEmployeeSse()
    }
  }

  return {
    // state
    employee,
    isLoggedIn,
    payCodeUsedAt,
    menuChangedAt,
    menuChangedDate,
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
    // 微信 openid / 自动登录辅助
    getOpenid,
    isAutoAuthSuppressed,
    isWechatAutoAttempted,
    markWechatAutoAttempted,
    // SSE
    startSseOnLogin,
    stopEmployeeSse,
    ensureSseRunning,
  }
})
