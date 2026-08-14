/**
 * 图片 URL 签名工具(终端版)。
 *
 * 后端 /uploads/** 已加签名校验拦截器,所有图片访问必须带 sig + exp 参数。
 * 本模块在 axios 响应拦截器中自动扫描数据中的 /uploads/ 路径,
 * 批量调 /file/sign 接口获取签名 URL,替换原值。
 *
 * 签名结果缓存在内存(Map),有效期 6 天(后端签名 7 天,留 1 天余量)。
 *
 * 注意:签名接口 /file/sign 本身不带 /uploads/ 路径,不会触发拦截器,无循环风险。
 */

/**
 * 需要自动签名的图片字段名(覆盖后端返回的所有图片字段)。
 * 注:菜品图片字段 'image' / 'dishImage' 已不再使用(终端菜品图移除),
 * 移除后菜单响应不再触发签名请求,大幅降低拦截器递归扫描开销。
 * 保留:avatar(员工头像)、logoUrl(品牌logo)等仍需签名的字段。
 */
const IMAGE_FIELDS = [
  'imageUrl', 'avatar', 'logoUrl',
  'coverImage', 'iconUrl', 'photo', 'thumbnail', 'picUrl',
]

/** 签名缓存:originalUrl → { signedUrl, expireAt } */
const signCache = new Map<string, { signedUrl: string; expireAt: number }>()

/** 缓存有效期 6 天(毫秒) */
const CACHE_TTL = 6 * 24 * 60 * 60 * 1000

/** 待签名的 URL 收集 + 批量去重 */
const pendingUrls = new Set<string>()
let batchTimer: ReturnType<typeof setTimeout> | null = null

/** 从缓存获取签名 URL,未命中或过期返回 null */
function getCached(originalUrl: string): string | null {
  const cached = signCache.get(originalUrl)
  if (!cached) return null
  if (Date.now() > cached.expireAt) {
    signCache.delete(originalUrl)
    return null
  }
  return cached.signedUrl
}

/** 批量调 sign 接口,填充缓存 */
async function flushSignBatch(urls: string[]): Promise<void> {
  if (urls.length === 0) return
  try {
    const { getApi } = await import('@/api')
    const api = getApi()
    const res = await api.post('/file/sign', { paths: urls })
    const signedUrls: string[] = res.data?.data?.urls || []
    const now = Date.now()
    urls.forEach((orig, i) => {
      const signed = signedUrls[i] || orig
      signCache.set(orig, { signedUrl: signed, expireAt: now + CACHE_TTL })
    })
  } catch {
    // 签名失败(网络/未登录等):URL 保持原值,图片可能 403 但不影响业务流程
  }
}

/** 调度批量签名(50ms 内收集的 URL 合并为一次请求) */
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
 * 先用缓存命中的 URL 替换;未命中的收集到批量队列异步签名(不阻塞响应)。
 *
 * @param data 响应数据(会原地修改)
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
      // 已带签名的跳过
      if (val.includes('sig=') && val.includes('exp=')) continue
      const cached = getCached(val)
      if (cached) {
        obj[field] = cached
      } else {
        // 异步签名,不阻塞当前响应(下次请求时缓存已填充)
        scheduleSign(val)
      }
    }
  }

  // 递归子对象
  for (const key of Object.keys(obj)) {
    if (typeof obj[key] === 'object' && obj[key] !== null) {
      signImageUrls(obj[key])
    }
  }
}

/**
 * 同步获取单个图片的签名 URL(优先用缓存)。
 * 用于终端需要立即拿到签名 URL 的场景(如 fetch 下载图片到 IndexedDB)。
 * 缓存未命中时返回原 URL(可异步触发签名,下次调用即命中)。
 */
export function getSignedUrlSync(url: string): string {
  if (!url || !url.includes('/uploads/')) return url
  if (url.includes('sig=') && url.includes('exp=')) return url
  const cached = getCached(url)
  if (cached) return cached
  // 异步触发签名(不阻塞)
  scheduleSign(url)
  return url
}

/**
 * 异步获取单个图片的签名 URL(确保签名完成)。
 * 用于终端下载图片到 IndexedDB 前必须拿到签名 URL 的场景。
 */
export async function getSignedUrlAsync(url: string): Promise<string> {
  if (!url || !url.includes('/uploads/')) return url
  if (url.includes('sig=') && url.includes('exp=')) return url
  const cached = getCached(url)
  if (cached) return cached
  // 同步等待签名完成
  await flushSignBatch([url])
  return getCached(url) || url
}

/** 清空签名缓存(解绑/切换食堂时调用) */
export function clearSignCache(): void {
  signCache.clear()
  pendingUrls.clear()
  if (batchTimer) {
    clearTimeout(batchTimer)
    batchTimer = null
  }
}
