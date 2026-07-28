import { reactive, ref } from 'vue'
import api from '@/api'
import { loadConfig } from '@/api'

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
  // 若已加载且 storeId 一致,使用后台模式静默校验
  const sameStore = loadedStoreId.value === storeId && brandingState.data
  const isBackground = options.background || sameStore

  const cache = readCache(storeId)
  // 有缓存则先秒开(仅在前台请求时)
  if (cache?.data && !isBackground) {
    brandingState.data = cache.data
  }

  if (!isBackground) brandingState.loading = true

  try {
    const headers: Record<string, string> = {}
    if (cache?.etag) headers['If-None-Match'] = cache.etag

    const res = await api.get(`/store/${storeId}/branding`, {
      headers,
      // 接受 304 作为成功状态(axios 默认只接受 2xx)
      validateStatus: (s: number) => (s >= 200 && s < 300) || s === 304,
    })

    if (res.status === 304) {
      // 数据未变,保留缓存(如果还没展示则补上)
      if (!brandingState.data && cache?.data) brandingState.data = cache.data
      loadedStoreId.value = storeId
      currentEtag.value = cache?.etag || null
      return
    }

    const data = res.data?.data as StoreBranding | undefined
    const etag = res.headers['etag'] as string | undefined
    if (data) {
      brandingState.data = data
      loadedStoreId.value = storeId
      currentEtag.value = etag || cache?.etag || null
      writeCache(storeId, etag || cache?.etag || null, data)
    }
  } catch {
    // 网络错误时回退到缓存(若有)
    if (!brandingState.data && cache?.data) {
      brandingState.data = cache.data
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
