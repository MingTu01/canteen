import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '@/router'
import { useAuthStore } from '@/stores/auth'

/**
 * 全局 axios 实例(admin-web 管理后台)。
 *
 * 鉴权策略升级(2026-07-18):
 * - cookie 模式:auth_token 由后端写入 HttpOnly Cookie,浏览器自动随请求带上。
 *   withCredentials=true 是必需的,允许跨域携带 Cookie。
 * - localStorage['auth'] 仅缓存 admin 信息与"已登录"标志,token 字段保留但不再发送。
 * - 401 拦截:清除 auth 状态并跳转登录页。
 */
const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE || '/api',
  timeout: 15000,
  withCredentials: true // 跨域请求携带 Cookie(auth_token)
})

const clearAuth = () => {
  localStorage.removeItem('auth')
  try {
    const authStore = useAuthStore()
    authStore.clearState()
  } catch {
    /* Pinia 未初始化时忽略 */
  }
  // 调用后端 logout 清除所有端的 Cookie(admin_token/employee_token/terminal_token/auth_token)
  // 避免 admin_token 丢失后 TokenExtractor 回退到 employee_token 导致持续 403
  // logout 接口在白名单中,无需有效 token 即可调用
  try {
    fetch('/api/auth/logout', { method: 'POST', credentials: 'same-origin' }).catch(() => {})
  } catch {
    /* 忽略网络错误 */
  }
}

/** 判断是否为登录请求:登录失败不应清状态/跳转(用户已在登录页) */
const isLoginRequest = (url?: string) => !!url && url.includes('/admin/login')

/**
 * 401/403 并发去重标志:多个并发请求同时返回 401 时,
 * 只允许第一个触发清理+跳转,其余直接 reject,避免重复跳转与重复清状态。
 */
let isRedirecting = false

// 请求拦截:不再附加 Authorization 头,完全依赖 HttpOnly Cookie
api.interceptors.request.use((config) => config)

// 响应拦截:统一处理 code
api.interceptors.response.use(
  (res) => {
    // 文件流下载(blob)直接放行,不走 code 校验
    if (res.config.responseType === 'blob' || res.data instanceof Blob) {
      return res.data
    }
    const data = res.data
    // 自动给响应中的 /uploads/ 图片 URL 加签名(sig + exp)
    if (data?.data) {
      import('@/utils/imageSign')
        .then(({ signImageUrls }) => signImageUrls(data.data))
        .catch(() => {})
    }
    if (data.code === 200) return data
    // 401/403 不弹重复消息(下面会跳转登录页)
    if (data.code !== 401 && data.code !== 403) {
      ElMessage.error(data.message || '请求失败')
    }
    // 401 未登录 / 403 无权限(token 失效或被覆盖):清登录态并跳转登录页
    if ((data.code === 401 || data.code === 403) && !isLoginRequest(res.config.url)) {
      if (!isRedirecting) {
        isRedirecting = true
        clearAuth()
        router
          .push('/login?redirect=' + encodeURIComponent(router.currentRoute.value.fullPath))
          .finally(() => {
            isRedirecting = false
          })
      }
    }
    return Promise.reject(data)
  },
  (err) => {
    const status = err.response?.status
    // 401 未登录 / 403 无权限:清登录态并跳转登录页
    if ((status === 401 || status === 403) && !isLoginRequest(err.config?.url)) {
      if (!isRedirecting) {
        isRedirecting = true
        clearAuth()
        router
          .push('/login?redirect=' + encodeURIComponent(router.currentRoute.value.fullPath))
          .finally(() => {
            isRedirecting = false
          })
      }
      return Promise.reject(err)
    }
    const msg = err.response?.data?.message || err.message || '网络错误'
    ElMessage.error(msg)
    return Promise.reject(err)
  }
)

// 模块化 re-export
export * from './types'
export * from './admin'
export * from './dish'
export * from './dishCategory'
export * from './order'
export * from './employee'
export * from './department'
export * from './menu'
export * from './notification'
export * from './recharge'
export * from './report'
export * from './store'
export * from './timer'
export * from './backup'
export * from './system'
export * from './operationLog'
export * from './supplier'
export * from './purchase'
export * from './material'
export * from './feedback'
export * from './groupOrder'
export * from './dailyClose'
export * from './settlement'

export default api
