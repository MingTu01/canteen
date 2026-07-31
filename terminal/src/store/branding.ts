import { reactive, ref } from 'vue'
import api from '@/api'
import { loadConfig } from '@/api'
import { getServerUrl as getShellServerUrl } from '@/api/shellApi'

/**
 * 获取服务器地址,优先从终端绑定配置读取,兜底从 Python Shell config.json 读取。
 * 这样即使终端未绑定(首次启动配置页),也能预填服务器地址。
 */
async function getServerUrl(): Promise<string> {
  const cfg = loadConfig()
  if (cfg?.serverUrl) return cfg.serverUrl
  // 兜底:从 Python Shell config.json 读取(浏览器环境返回空)
  return getShellServerUrl()
}

/**
 * 食堂品牌信息 Store(终端版)。
 *
 * 终端在绑定后即锁定 storeId,品牌信息从该食堂拉取:
 * - 使用 ETag + If-None-Match,后端数据未变时返回 304(零带宽)
 * - 品牌数据 + ETag 持久化到 localStorage,终端重启或刷新时优先用缓存秒开,
 *   同时后台异步校验是否有更新
 * - terminalBackgroundUrl 用作待机页背景图(订餐机/取餐机)
 * - logoUrl + name 显示在待机页顶栏
 *
 * 静态资源(logo/背景图 URL)自身带 ?v=mtime 版本号 + 后端 365d immutable 缓存,
 * 浏览器命中磁盘缓存,不会重复下载图片。
 *
 * 注意:Python Shell 的 origin 是 http://127.0.0.1:port,后端返回的相对路径
 * (/uploads/xxx.jpg)需要拼接服务器地址为绝对 URL,否则图片加载不了。
 */
export interface StoreBranding {
  id: number
  name: string
  logoUrl?: string
  imageUrl?: string
  terminalBackgroundUrl?: string
  h5BannerUrl?: string
  description?: string
  updatedAt?: string
}

interface BrandingCache {
  etag: string | null
  data: StoreBranding | null
}

const cacheKeyOf = (storeId: number) => `terminal_branding_${storeId}`

/**
 * 将后端返回的相对路径图片 URL 拼接为服务器绝对 URL。
 * Python Shell 的 origin 是 http://127.0.0.1:port,相对路径 /uploads/xxx.jpg 无法访问,
 * 必须拼接为 http://server:port/uploads/xxx.jpg。
 *
 * 已经是绝对 URL(http/https 开头)或 data URI 的不做处理。
 */
function absolutizeUrl(url: string | undefined | null, serverUrl: string): string | undefined {
  if (!url) return undefined
  // 已经是绝对 URL 或 data URI,直接返回
  if (/^(https?:|data:|blob:)/i.test(url)) return url
  // 相对路径:拼接服务器地址
  const base = serverUrl.replace(/\/$/, '')
  return base + (url.startsWith('/') ? url : '/' + url)
}

/**
 * 将 branding 数据中的所有图片 URL 转为绝对路径。
 */
function absolutizeBranding(data: StoreBranding, serverUrl: string): StoreBranding {
  return {
    ...data,
    logoUrl: absolutizeUrl(data.logoUrl, serverUrl),
    imageUrl: absolutizeUrl(data.imageUrl, serverUrl),
    terminalBackgroundUrl: absolutizeUrl(data.terminalBackgroundUrl, serverUrl),
    h5BannerUrl: absolutizeUrl(data.h5BannerUrl, serverUrl),
  }
}

const readCache = (storeId: number): BrandingCache | null => {
  try {
    const raw = localStorage.getItem(cacheKeyOf(storeId))
    return raw ? JSON.parse(raw) : null
  } catch {
    return null
  }
}

const writeCache = (storeId: number, etag: string | null, data: StoreBranding | null) => {
  try {
    localStorage.setItem(cacheKeyOf(storeId), JSON.stringify({ etag, data }))
  } catch {
    /* 忽略 quota 错误 */
  }
}

export const brandingState = reactive<{
  data: StoreBranding | null
  loading: boolean
}>({
  data: null,
  loading: false,
})

/** 已加载的 storeId(用于判断是否需要重新拉取) */
const loadedStoreId = ref<number | null>(null)
/** 当前 ETag(用于 clearBranding 时同步状态) */
const currentEtag = ref<string | null>(null)

/**
 * 拉取当前绑定食堂的品牌信息(带 ETag 304 缓存)。
 * - 缓存命中时先秒开缓存,后台异步校验
 * - 数据有更新时自动刷新
 *
 * @param options.background true=后台静默校验(不显示 loading)
 */
export async function fetchBranding(options: { background?: boolean } = {}): Promise<void> {
  const cfg = loadConfig()
  if (!cfg || !cfg.storeId) {
    brandingState.data = null
    return
  }

  const storeId = cfg.storeId
  // 获取服务器地址:优先绑定配置,兜底 Python Shell config.json
  const serverUrl = await getServerUrl()

  // 若已加载且 storeId 一致,使用后台模式静默校验
  const sameStore = loadedStoreId.value === storeId && brandingState.data
  const isBackground = options.background || sameStore

  const cache = readCache(storeId)
  // 有缓存则先秒开(无论前台还是后台模式,确保图片立即显示)
  if (cache?.data) {
    brandingState.data = serverUrl ? absolutizeBranding(cache.data, serverUrl) : cache.data
  }

  if (!isBackground) brandingState.loading = true

  try {
    const headers: Record<string, string> = {}
    if (cache?.etag) headers['If-None-Match'] = cache.etag

    const res = await api.get(`/store/${storeId}/branding`, {
      headers,
      validateStatus: (s: number) => (s >= 200 && s < 300) || s === 304,
    })

    if (res.status === 304) {
      if (!brandingState.data && cache?.data) {
        brandingState.data = serverUrl ? absolutizeBranding(cache.data, serverUrl) : cache.data
      }
      loadedStoreId.value = storeId
      currentEtag.value = cache?.etag || null
      return
    }

    const data = res.data?.data as StoreBranding | undefined
    const etag = res.headers['etag'] as string | undefined
    if (data) {
      const absolutized = serverUrl ? absolutizeBranding(data, serverUrl) : data
      brandingState.data = absolutized
      loadedStoreId.value = storeId
      currentEtag.value = etag || cache?.etag || null
      writeCache(storeId, etag || cache?.etag || null, absolutized)
    }
  } catch {
    if (!brandingState.data && cache?.data) {
      brandingState.data = serverUrl ? absolutizeBranding(cache.data, serverUrl) : cache.data
      loadedStoreId.value = storeId
      currentEtag.value = cache?.etag || null
    }
  } finally {
    if (!isBackground) brandingState.loading = false
  }
}

/** 清除品牌缓存(解绑时调用):遍历清除所有 terminal_branding_ 前缀的 key,避免残留其他食堂缓存 */
export function clearBranding(): void {
  Object.keys(localStorage)
    .filter((k) => k.startsWith('terminal_branding_'))
    .forEach((k) => localStorage.removeItem(k))
  brandingState.data = null
  loadedStoreId.value = null
  currentEtag.value = null
}

/**
 * 清理旧版本缓存(含相对路径 URL 的 branding 数据)。
 * 在应用启动时调用一次,确保旧版本(未做 absolutize 的缓存)不会残留。
 */
export function purgeOldBrandingCache(): void {
  Object.keys(localStorage)
    .filter((k) => k.startsWith('terminal_branding_'))
    .forEach((k) => {
      try {
        const raw = localStorage.getItem(k)
        if (!raw) return
        const parsed = JSON.parse(raw)
        const data = parsed?.data
        if (!data) return
        // 如果缓存中的 URL 是相对路径(以 / 开头),说明是旧版本缓存,删除
        const hasRelativeUrl =
          (data.logoUrl && data.logoUrl.startsWith('/')) ||
          (data.terminalBackgroundUrl && data.terminalBackgroundUrl.startsWith('/'))
        if (hasRelativeUrl) {
          localStorage.removeItem(k)
        }
      } catch {
        // 解析失败也删除
        localStorage.removeItem(k)
      }
    })
}
