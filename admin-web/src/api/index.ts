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
}

/** 判断是否为登录请求:登录失败不应清状态/跳转(用户已在登录页) */
const isLoginRequest = (url?: string) => !!url && url.includes('/admin/login')

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
    if (data.code === 200) return data
    // 401/403 不弹重复消息(下面会跳转登录页,错误提示对用户无意义)
    if (data.code !== 401 && data.code !== 403) {
      ElMessage.error(data.message || '请求失败')
    }
    // 401 未登录 / 403 无权限(会话过期或越权):清登录态并跳转登录页
    if ((data.code === 401 || data.code === 403) && !isLoginRequest(res.config.url)) {
      clearAuth()
      router.push('/login')
    }
    return Promise.reject(data)
  },
  (err) => {
    const status = err.response?.status
    // 401 未登录 / 403 无权限(会话过期或越权):清登录态并跳转登录页,不弹错误消息
    if ((status === 401 || status === 403) && !isLoginRequest(err.config?.url)) {
      clearAuth()
      router.push('/login')
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
