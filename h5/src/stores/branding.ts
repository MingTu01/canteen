import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getBranding } from '@/api/store'
import type { Branding } from '@/api/types'

/**
 * 食堂品牌信息 Store(H5 端)。
 *
 * 缓存策略(参考 terminal,零带宽优先):
 * - branding + etag 持久化到 localStorage,刷新页面秒开。
 * - fetchBranding 带 If-None-Match:后端数据未变返回 304(无 body,零带宽)。
 * - 数据有更新时自动刷新内存与缓存。
 *
 * localStorage key 按门店隔离:不同食堂品牌互不污染。
 */
const cacheKeyOf = (storeId: number) => `canteen_h5_branding_${storeId}`

interface BrandingCache {
  etag: string | null
  data: Branding | null
}

const readCache = (storeId: number): BrandingCache | null => {
  try {
    const raw = localStorage.getItem(cacheKeyOf(storeId))
    return raw ? (JSON.parse(raw) as BrandingCache) : null
  } catch {
    return null
  }
}

const writeCache = (storeId: number, etag: string | null, data: Branding | null): void => {
  try {
    localStorage.setItem(cacheKeyOf(storeId), JSON.stringify({ etag, data }))
  } catch {
    /* 忽略 quota 异常 */
  }
}

export const useBrandingStore = defineStore('branding', () => {
  const branding = ref<Branding | null>(null)
  const etag = ref<string | null>(null)

  /**
   * 拉取指定食堂的品牌信息(带 ETag 304 缓存)。
   * - 有缓存则先用缓存秒开,后台异步校验。
   * - 304:数据未变,保留缓存。
   * - 200:更新内存与缓存。
   * - 网络/接口错误:回退到缓存(若有)。
   */
  const fetchBranding = async (storeId: number): Promise<void> => {
    if (!storeId) {
      branding.value = null
      etag.value = null
      return
    }

    const cache = readCache(storeId)
    // 有缓存则先秒开
    if (cache?.data) {
      branding.value = cache.data
      etag.value = cache.etag
    }

    try {
      const res = await getBranding(storeId, cache?.etag)
      if (res.status === 304) {
        // 数据未变,保留缓存(如尚未展示则补上)
        if (!branding.value && cache?.data) {
          branding.value = cache.data
        }
        etag.value = cache?.etag || null
        return
      }

      const body = res.data
      const newEtag = (res.headers['etag'] as string | undefined) ?? null
      if (body?.data) {
        branding.value = body.data
        etag.value = newEtag
        writeCache(storeId, newEtag, body.data)
      }
    } catch {
      // 网络错误时回退到缓存(若有,已在前面秒开)
    }
  }

  /** 清除品牌缓存(切换门店/登出时调用) */
  const clearBranding = (): void => {
    try {
      Object.keys(localStorage)
        .filter((k) => k.startsWith('canteen_h5_branding_'))
        .forEach((k) => localStorage.removeItem(k))
    } catch {
      /* 忽略 */
    }
    branding.value = null
    etag.value = null
  }

  return {
    // state
    branding,
    etag,
    // actions
    fetchBranding,
    clearBranding,
  }
})
