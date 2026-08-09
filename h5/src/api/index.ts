import axios from 'axios'
import type { AxiosRequestConfig, AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import { showToast } from 'vant'
import router from '@/router'

/**
 * 全局 axios 实例(H5 订餐端)。
 *
 * 鉴权策略(与 admin-web / terminal 一致):
 * - auth_token 由后端写入 HttpOnly Cookie,浏览器自动随请求带上。
 * - withCredentials=true 是必需的,允许跨域携带 Cookie。
 * - 本地不存储 token,登录态仅由 Pinia(auth store)缓存 employee 信息 + isLoggedIn 标志。
 *
 * 响应约定(对应后端 ApiResponse):
 * - HTTP 200 + code=200 → 业务成功,拦截器返回 data 字段(业务数据)
 * - HTTP 200 + code=401 → 未登录/登录态失效,清登录态并跳 /login
 * - HTTP 200 + 其他 code → 业务错误,toast 提示并 reject
 * - HTTP 401 → 清登录态跳 /login
 * - HTTP 403 → 提示无权限
 * - blob 响应 → 直接返回,不走 code 校验
 * - 自定义 config._raw=true → 返回完整 AxiosResponse(供 branding 等需要 headers/304 的场景)
 */

/** 扩展 axios 配置:标记需要原始响应 */
declare module 'axios' {
  interface AxiosRequestConfig {
    /** 是否返回完整 AxiosResponse(默认 false,返回业务数据) */
    _raw?: boolean
    /** 是否静默失败(不弹 toast,不影响 401 跳转)。用于 SSE ticket 等辅助请求,
     *  失败不应干扰用户(如后端未部署新版本时 404 不应反复弹提示) */
    _silent?: boolean
  }
}

const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE || '/api',
  timeout: 15000,
  withCredentials: true, // 跨域请求携带 Cookie(auth_token)
})

/** 判断是否为登录类请求:登录失败不应清状态/跳转(用户已在登录页) */
const isLoginRequest = (url?: string): boolean => {
  if (!url) return false
  return url.includes('/employee/login') || url.includes('/employee/phone-login')
}

/**
 * 401 并发去重标志:多个并发请求同时返回 401 时,只允许第一个触发清理+跳转,
 * 后续的直接 reject,避免重复清理 store / 重复 router.push 造成闪烁与竞态。
 */
let isRedirecting = false

/** 清除前端登录态并跳转登录页 */
const clearAuthAndRedirect = async (): Promise<void> => {
  if (isRedirecting) return
  isRedirecting = true
  try {
    // 先同步清空内存态(auth/branding/cart),再跳转登录页。
    // 必须等待 store 清理完成后再 router.push,否则路由守卫读到 isLoggedIn=true
    // 会把用户从 /login 反弹回 /,造成 401 后短暂跳回首页再跳登录页的闪烁。
    // 动态 import 规避与 auth store 的循环依赖。
    const [{ useAuthStore }, { useBrandingStore }, { useCartStore }] = await Promise.all([
      import('@/stores/auth'),
      import('@/stores/branding'),
      import('@/stores/cart'),
    ])
    try {
      useAuthStore().setEmployee(null)
    } catch {
      /* store 未初始化时降级 */
    }
    try {
      useBrandingStore().clearBranding()
    } catch {
      /* ignore */
    }
    try {
      useCartStore().clearAll()
    } catch {
      /* ignore */
    }
    // 避免在登录页重复跳转
    if (router.currentRoute.value.path !== '/login') {
      await router
        .push({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } })
        .catch(() => {
          /* 忽略导航异常 */
        })
    }
  } finally {
    isRedirecting = false
  }
}

// 请求拦截:Cookie 自动携带,无需附加 Authorization 头
api.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => config,
  (error) => Promise.reject(error),
)

// 响应拦截:统一处理 ApiResponse.code
api.interceptors.response.use(
  (res: AxiosResponse) => {
    // blob 响应(文件下载)直接放行,不走 code 校验
    if (res.config.responseType === 'blob' || res.data instanceof Blob) {
      return res.data
    }

    // 需要完整响应(如 branding 的 ETag/304):由调用方自行处理
    if (res.config._raw) {
      return res
    }

    const body = res.data
    // 非标准结构(如直接返回字符串/数组),原样返回
    if (body == null || typeof body.code === 'undefined') {
      return body
    }

    // 自动给响应中的 /uploads/ 图片 URL 加签名(sig + exp)
    if (body.data) {
      import('@/utils/imageSign')
        .then(({ signImageUrls }) => signImageUrls(body.data))
        .catch(() => {})
    }

    // 业务成功:返回 data 字段
    if (body.code === 200) {
      return body.data
    }

    // 401:未登录/登录态失效
    if (body.code === 401) {
      if (!isLoginRequest(res.config.url)) {
        clearAuthAndRedirect()
      } else {
        // 登录请求失败:显示后端返回的错误消息(如"手机号或密码错误")
        showToast(body.message || '登录失败')
      }
      return Promise.reject(body)
    }

    // 其他业务错误:toast 提示(_silent 请求静默失败不弹 toast)
    if (!res.config._silent) {
      const msg = body.message || '请求失败'
      showToast(msg)
    }
    return Promise.reject(body)
  },
  (err) => {
    const status = err.response?.status
    const msg = err.response?.data?.message || err.message || '网络错误'
    const silent = err.config?._silent

    if (status === 401) {
      if (!isLoginRequest(err.config?.url)) {
        clearAuthAndRedirect()
      } else {
        // 登录请求 HTTP 401:显示错误消息
        showToast(msg || '登录失败')
      }
    } else if (status === 403) {
      if (!silent) showToast('无权限访问')
    } else if (!silent) {
      showToast(msg)
    }
    return Promise.reject(err)
  },
)

/** 统一封装 GET 请求,返回业务数据 T */
export function get<T = unknown>(url: string, config?: AxiosRequestConfig): Promise<T> {
  return api.get<T, T>(url, config)
}

/** 统一封装 POST 请求,返回业务数据 T */
export function post<T = unknown>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
  return api.post<T, T>(url, data, config)
}

/** 统一封装 PUT 请求,返回业务数据 T */
export function put<T = unknown>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
  return api.put<T, T>(url, data, config)
}

/** 统一封装 DELETE 请求,返回业务数据 T */
export function del<T = unknown>(url: string, config?: AxiosRequestConfig): Promise<T> {
  return api.delete<T, T>(url, config)
}

/** 暴露原始实例(供需要完整响应的特殊场景使用,如 branding ETag 缓存) */
export const rawAxios = api

export default api
