import axios, { type AxiosInstance } from 'axios'
import { clearBranding } from '@/store/branding'
import { detectShell } from '@/api/shellApi'

/** 终端绑定配置(存 localStorage,绑定后只读展示,改模式需重新绑定) */
export interface TerminalConfig {
  /** 服务器地址,如 https://canteen.xxx.com(不含 /api) */
  serverUrl: string
  /** 绑定后服务端返回的 token(role=3,storeId 锁定) */
  token: string
  /** 当前绑定的食堂 ID */
  storeId: number
  /** 当前绑定的食堂名称 */
  storeName: string
  /** 绑定时填的设备标识(便于运维识别,如 "前台订餐机") */
  deviceLabel: string
  /** 运行模式:order=订餐机 / pickup=取餐机 */
  mode: 'order' | 'pickup'
  /** 绑定时间(ISO 字符串,用于判断是否需要刷新 token) */
  boundAt: string
}

const STORAGE_KEY = 'terminal_config_v2'
/** token 刷新阈值:绑定超过 15 天自动刷新(终端 token 默认 30 天,提前一半刷新留足余量) */
const TOKEN_REFRESH_THRESHOLD_DAYS = 15

/** 读取本地绑定的配置,未绑定时返回 null */
export function loadConfig(): TerminalConfig | null {
  const raw = localStorage.getItem(STORAGE_KEY)
  if (!raw) return null
  try {
    return JSON.parse(raw) as TerminalConfig
  } catch {
    return null
  }
}

/** 保存绑定配置到 localStorage */
export function saveConfig(cfg: TerminalConfig): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(cfg))
  } catch (e) {
    console.error('[api] 保存配置失败,localStorage 可能被禁用:', e)
    throw new Error('无法保存配置,请检查浏览器是否禁用了 localStorage')
  }
}

/** 清除绑定配置(解绑) */
export function clearConfig(): void {
  localStorage.removeItem(STORAGE_KEY)
  // 同步清除 shell 侧 DPAPI 加密存储的 token(空串即清;浏览器环境静默跳过)
  saveTokenToShell('')
}

// ===== 终端 token 双存储(shell DPAPI 加密文件为主,localStorage 明文兜底) =====
// 写入点(绑定成功/token 刷新成功):localStorage 照旧写(兜底)+ 异步同步到 shell;
// 读取点(应用启动早期,initTokenFromShell):先同步读 localStorage 保底,
//   再异步从 shell 恢复——shell 返回非空且与当前不同则更新 localStorage;
// 清空点(解绑/401 失效,clearConfig):shell 删除 + localStorage 清除。
// shell 不在(浏览器开发模式)时全部静默失败,自然降级 localStorage。

/**
 * 把 token 异步保存到 Python shell(DPAPI 加密后写 token.bin)。
 * 同源 127.0.0.1:15118 无跨域问题;POST 会带 Origin 且与 Host 一致可通过校验。
 * 全部静默失败(shell 不在时降级 localStorage),不阻塞调用方。
 */
export function saveTokenToShell(token: string): void {
  if (detectShell() !== 'python') return
  try {
    void fetch('/__api__/token_save', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ token }),
    }).catch(() => {
      /* shell 不在时静默降级 localStorage */
    })
  } catch {
    /* shell 不在时静默降级 localStorage */
  }
}

/**
 * 从 Python shell 读取 DPAPI 加密保存的 token。
 * 文件不存在/解密失败/shell 不在时返回 null(不抛错)。
 */
export async function loadTokenFromShell(): Promise<string | null> {
  if (detectShell() !== 'python') return null
  try {
    const res = await fetch('/__api__/token_load')
    const data = await res.json()
    if (data?.ok && typeof data.token === 'string' && data.token) return data.token
    return null
  } catch {
    return null
  }
}

/**
 * 应用启动早期调用一次(不阻塞首屏):从 shell 恢复 token。
 * shell 为主存储,返回非空且与 localStorage 不同时,以 shell 为准更新
 * localStorage 与内存 axios 实例(下次请求即用新 token)。
 */
export function initTokenFromShell(): void {
  if (detectShell() !== 'python') return
  void (async () => {
    try {
      const shellToken = await loadTokenFromShell()
      if (!shellToken) return
      const cfg = loadConfig()
      if (cfg && cfg.token !== shellToken) {
        saveConfig({ ...cfg, token: shellToken })
        // 请求拦截器每次从 localStorage 读最新 token,此处重置实例仅作保险
        currentApi = null
        currentBase = ''
        console.log('[api] 已从 shell 恢复终端 token')
      }
    } catch {
      /* 静默 */
    }
  })()
}

/** 创建 axios 实例(动态 baseURL,绑定后所有请求带 token) */
let currentApi: AxiosInstance | null = null
let currentBase = ''
let isHandling401 = false

/**
 * 异步清理本地缓存(IndexedDB + SSE)。
 * 用动态 import 避免与 cache.ts 的循环依赖。
 */
function asyncPurgeLocalCache(): void {
  import('@/utils/cache')
    .then(({ purgeLocalCache }) => {
      purgeLocalCache().catch(() => {})
    })
    .catch(() => {})
}

/**
 * 定期刷新终端 token(滚动续期)。
 * 绑定超过 15 天时,用当前 token 换取新 token,避免接近 30 天过期时失绑。
 * 异步执行,不阻塞当前请求。
 */
let refreshInFlight: Promise<void> | null = null
function maybeRefreshToken(cfg: TerminalConfig): void {
  if (refreshInFlight) return
  const boundAt = new Date(cfg.boundAt).getTime()
  if (isNaN(boundAt)) return
  const daysSinceBound = (Date.now() - boundAt) / (24 * 60 * 60 * 1000)
  if (daysSinceBound < TOKEN_REFRESH_THRESHOLD_DAYS) return

  refreshInFlight = (async () => {
    try {
      const base = cfg.serverUrl.replace(/\/$/, '')
      const tmp = axios.create({ baseURL: base + '/api', timeout: 10000 })
      tmp.defaults.headers.common['Authorization'] = `Bearer ${cfg.token}`
      const resp = await tmp.get('/terminal/refresh')
      if (resp.data?.code === 200 && resp.data.data?.token) {
        const newCfg: TerminalConfig = {
          ...cfg,
          token: resp.data.data.token,
          boundAt: new Date().toISOString(),
        }
        saveConfig(newCfg)
        // 重置 axios 实例,下次 getApi 会用新 token
        currentApi = null
        currentBase = ''
        console.log('[api] 终端 token 已刷新')
      }
    } catch {
      // 刷新失败(如网络错误),不影响当前 token 使用(30 天内仍有效)
    } finally {
      refreshInFlight = null
    }
  })()
}

/** 获取当前生效的 axios 实例;未绑定时抛错 */
export function getApi(): AxiosInstance {
  const cfg = loadConfig()
  if (!cfg) {
    throw new Error('终端未绑定,请先在配置页绑定食堂')
  }
  // 异步刷新 token(不阻塞当前请求)
  maybeRefreshToken(cfg)
  if (!currentApi || currentBase !== cfg.serverUrl) {
    currentBase = cfg.serverUrl
    currentApi = axios.create({
      baseURL: cfg.serverUrl.replace(/\/$/, '') + '/api',
      timeout: 15000,
    })
    currentApi.interceptors.request.use((config) => {
      // 每次请求都从 localStorage 读取最新 token(可能被 maybeRefreshToken 更新)
      const latestCfg = loadConfig()
      if (latestCfg?.token) {
        config.headers.Authorization = `Bearer ${latestCfg.token}`
      }
      return config
    })
    // 401/403 响应:token 失效或门店权限变更,清除本地绑定与缓存,回到配置页重新绑定
    // 使用防重入标志位(5 秒冷却),避免并发请求时多次触发
    currentApi.interceptors.response.use(
      (res) => {
        // 自动给响应中的 /uploads/ 图片 URL 加签名(sig + exp)
        if (res.data?.data) {
          import('@/utils/imageSign')
            .then(({ signImageUrls }) => signImageUrls(res.data.data))
            .catch(() => {})
        }
        return res
      },
      (err) => {
        const status = err.response?.status
        if ((status === 401 || status === 403) && !isHandling401) {
          isHandling401 = true
          clearConfig()
          clearBranding()
          // 异步清理 IndexedDB + SSE 缓存,避免跨门店数据残留
          asyncPurgeLocalCache()
          // hash 模式下用 location.hash 跳转,避免整页刷新丢失状态
          if (!window.location.hash.includes('/settings')) {
            window.location.hash = '#/settings'
          }
          // 5 秒冷却期,确保后续请求不再重复触发
          setTimeout(() => { isHandling401 = false }, 5000)
        }
        return Promise.reject(err)
      }
    )
  }
  return currentApi
}

/**
 * 终端绑定:管理员账号密码 + 食堂安全码 → 终端 token。
 * 不依赖已绑定的配置,使用临时 axios 实例。
 */
export async function bindTerminal(params: {
  serverUrl: string
  username: string
  password: string
  securityCode: string
  deviceLabel?: string
  mode: 'order' | 'pickup'
}): Promise<TerminalConfig> {
  const base = params.serverUrl.replace(/\/$/, '')
  const tmp = axios.create({ baseURL: base + '/api', timeout: 10000 })
  const res = await tmp.post('/terminal/bind', {
    username: params.username,
    password: params.password,
    securityCode: params.securityCode,
    deviceLabel: params.deviceLabel || '',
  })
  const data = res.data?.data
  if (!data || !data.token) {
    throw new Error(res.data?.message || '绑定失败,服务器未返回 token')
  }
  const cfg: TerminalConfig = {
    serverUrl: base,
    token: data.token,
    storeId: Number(data.storeId),
    storeName: String(data.storeName || ''),
    deviceLabel: params.deviceLabel || '',
    mode: params.mode,
    boundAt: new Date().toISOString(),
  }
  saveConfig(cfg)
  // 异步同步到 shell 主存储(DPAPI 加密,失败静默降级 localStorage)
  saveTokenToShell(cfg.token)
  // 重置实例,下次 getApi 会按新配置创建
  currentApi = null
  currentBase = ''
  return cfg
}

/**
 * 默认导出:兼容老代码 `import api from '@/api'` 用法。
 * 每次访问都返回当前绑定的 axios 实例;未绑定时抛错(调用方应保证已绑定)。
 */
export default {
  get: (url: string, config?: any) => getApi().get(url, config),
  post: (url: string, data?: any, config?: any) => getApi().post(url, data, config),
  put: (url: string, data?: any, config?: any) => getApi().put(url, data, config),
  delete: (url: string, config?: any) => getApi().delete(url, config),
}
