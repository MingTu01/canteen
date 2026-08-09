/**
 * 图片 URL 签名工具(H5 版)。
 *
 * 后端 /uploads/** 已加签名校验拦截器,所有图片访问必须带 sig + exp 参数。
 * 本模块在 axios 响应拦截器中自动扫描数据中的 /uploads/ 路径,
 * 批量调 /file/sign 接口获取签名 URL,替换原值。
 */

/** 需要自动签名的图片字段名 */
const IMAGE_FIELDS = [
  'imageUrl', 'avatar', 'logoUrl', 'image', 'dishImage',
  'coverImage', 'iconUrl', 'photo', 'thumbnail', 'picUrl',
]

/** 签名缓存:originalUrl → { signedUrl, expireAt } */
const signCache = new Map<string, { signedUrl: string; expireAt: number }>()

/** 缓存有效期 6 天(毫秒) */
const CACHE_TTL = 6 * 24 * 60 * 60 * 1000

/** 待签名的 URL 收集 + 批量去重 */
const pendingUrls = new Set<string>()
let batchTimer: ReturnType<typeof setTimeout> | null = null

function getCached(originalUrl: string): string | null {
  const cached = signCache.get(originalUrl)
  if (!cached) return null
  if (Date.now() > cached.expireAt) {
    signCache.delete(originalUrl)
    return null
  }
  return cached.signedUrl
}

async function flushSignBatch(urls: string[]): Promise<void> {
  if (urls.length === 0) return
  try {
    const res = await fetch('/api/file/sign', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      credentials: 'same-origin',
      body: JSON.stringify({ paths: urls }),
    })
    if (!res.ok) return
    const json = await res.json()
    const signedUrls: string[] = json?.data?.urls || []
    const now = Date.now()
    urls.forEach((orig, i) => {
      const signed = signedUrls[i] || orig
      signCache.set(orig, { signedUrl: signed, expireAt: now + CACHE_TTL })
    })
  } catch {
    // 签名失败:URL 保持原值
  }
}

function scheduleSign(originalUrl: string): void {
  pendingUrls.add(originalUrl)
  if (batchTimer) clearTimeout(batchTimer)
  batchTimer = setTimeout(() => {
    const batch = [...pendingUrls]
    pendingUrls.clear()
    batchTimer = null
    flushSignBatch(batch)
  }, 50)
}

/**
 * 递归扫描对象,给所有图片字段的 /uploads/ URL 填充签名。
 */
export function signImageUrls(data: unknown): void {
  if (!data || typeof data !== 'object') return

  if (Array.isArray(data)) {
    for (const item of data) signImageUrls(item)
    return
  }

  const obj = data as Record<string, unknown>
  for (const field of IMAGE_FIELDS) {
    const val = obj[field]
    if (typeof val === 'string' && val.includes('/uploads/')) {
      if (val.includes('sig=') && val.includes('exp=')) continue
      const cached = getCached(val)
      if (cached) {
        obj[field] = cached
      } else {
        scheduleSign(val)
      }
    }
  }

  for (const key of Object.keys(obj)) {
    if (typeof obj[key] === 'object' && obj[key] !== null) {
      signImageUrls(obj[key])
    }
  }
}

/** 清空签名缓存 */
export function clearSignCache(): void {
  signCache.clear()
  pendingUrls.clear()
  if (batchTimer) {
    clearTimeout(batchTimer)
    batchTimer = null
  }
}
