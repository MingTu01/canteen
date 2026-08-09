/**
 * 品牌图片本地缓存模块（IndexedDB）
 *
 * 终端品牌图片（Logo + 终端背景图）缓存到本地，软件启动时从本地秒开，
 * 避免每次从网络加载导致背景闪烁/黑屏。
 *
 * 缓存结构：
 * - DB: canteen_terminal_branding（独立库，避免与菜品图片缓存清理逻辑冲突）
 * - store: images
 * - key: 图片纯路径（去掉 ?v= 查询参数）
 * - value: { blob: Blob, version: string, timestamp: number }
 *
 * 失效策略：
 * - URL 中的 ?v=mtime 作为版本号，版本号变化时重新缓存
 * - 超过 30 天的缓存自动清理
 *
 * 使用流程：
 * 1. 软件启动：initBrandingImageCache 从 IndexedDB 读取缓存的 blob URL，背景立即就位
 * 2. fetchBranding 拉取最新数据后：preloadBrandingImage 异步下载新图片到 IndexedDB
 * 3. brandingState 中存储的 URL 优先用本地 blob URL，加载极快（本地内存）
 */

const DB_NAME = 'canteen_terminal_branding'
const DB_VERSION = 1
const STORE_NAME = 'images'
const CACHE_TTL = 30 * 24 * 60 * 60 * 1000 // 30 天

interface CacheEntry {
  blob: Blob
  version: string
  timestamp: number
}

let dbInstance: IDBDatabase | null = null

function openDB(): Promise<IDBDatabase> {
  if (dbInstance) return Promise.resolve(dbInstance)
  return new Promise((resolve, reject) => {
    if (typeof indexedDB === 'undefined') {
      reject(new Error('IndexedDB 不可用'))
      return
    }
    const req = indexedDB.open(DB_NAME, DB_VERSION)
    req.onupgradeneeded = () => {
      const db = req.result
      if (!db.objectStoreNames.contains(STORE_NAME)) {
        db.createObjectStore(STORE_NAME)
      }
    }
    req.onsuccess = () => {
      dbInstance = req.result
      resolve(dbInstance)
    }
    req.onerror = () => reject(req.error)
  })
}

/** 解析 URL：分离纯路径和版本号 */
function parseUrl(url: string): { path: string; version: string } {
  try {
    const u = new URL(url, window.location.origin)
    return { path: u.pathname, version: u.searchParams.get('v') || '' }
  } catch {
    return { path: url, version: '' }
  }
}

/** 读取缓存 */
async function getCache(path: string, version: string): Promise<CacheEntry | null> {
  try {
    const db = await openDB()
    return new Promise((resolve, reject) => {
      const tx = db.transaction(STORE_NAME, 'readonly')
      const store = tx.objectStore(STORE_NAME)
      const req = store.get(path)
      req.onsuccess = () => {
        const entry = req.result as CacheEntry | undefined
        if (!entry) { resolve(null); return }
        // 版本号不一致则视为缓存失效
        if (version && entry.version && entry.version !== version) {
          resolve(null); return
        }
        // 超过 TTL 则视为过期
        if (Date.now() - entry.timestamp > CACHE_TTL) {
          resolve(null); return
        }
        resolve(entry)
      }
      req.onerror = () => reject(req.error)
    })
  } catch {
    return null
  }
}

/** 写入缓存 */
async function setCache(path: string, version: string, blob: Blob): Promise<void> {
  try {
    const db = await openDB()
    return new Promise((resolve, reject) => {
      const tx = db.transaction(STORE_NAME, 'readwrite')
      const store = tx.objectStore(STORE_NAME)
      const entry: CacheEntry = { blob, version, timestamp: Date.now() }
      store.put(entry, path)
      tx.oncomplete = () => resolve()
      tx.onerror = () => reject(tx.error)
    })
  } catch {
    /* 缓存写入失败静默处理 */
  }
}

/**
 * 获取品牌图片的本地 blob URL（如果缓存命中且版本匹配）。
 * 未命中缓存时返回 null（调用方决定是否降级用原始 URL）。
 *
 * 注意：返回的 blob URL 由本模块管理生命周期，调用方不应 revoke。
 * 进程退出时 blob URL 自动释放，长期驻留内存仅一张图片可忽略。
 */
export async function getCachedBrandingImageUrl(url: string): Promise<string | null> {
  if (!url) return null
  // data: URL 和 blob: URL 直接返回
  if (url.startsWith('data:') || url.startsWith('blob:')) return url

  const { path, version } = parseUrl(url)
  const entry = await getCache(path, version)
  if (!entry) return null
  return URL.createObjectURL(entry.blob)
}

/**
 * 预加载品牌图片到本地缓存（fetch + 存 IndexedDB）。
 * 已缓存且版本一致时跳过下载。
 * 返回本地 blob URL（命中或新下载成功时），或原始 URL（降级）。
 *
 * @param url 图片绝对 URL
 */
export async function preloadBrandingImage(url: string): Promise<string> {
  if (!url) return url
  if (url.startsWith('data:') || url.startsWith('blob:')) return url

  const { path, version } = parseUrl(url)

  // 先查缓存
  const cached = await getCache(path, version)
  if (cached) return URL.createObjectURL(cached.blob)

  // 未命中：fetch 下载
  try {
    const resp = await fetch(url)
    if (!resp.ok) return url
    const blob = await resp.blob()
    if (!blob.type.startsWith('image/')) return url
    await setCache(path, version, blob)
    return URL.createObjectURL(blob)
  } catch {
    // fetch 失败（网络/CORS）：返回原始 URL 让 <img>/background-image 直查后端
    return url
  }
}

/** 清空所有品牌图片缓存（解绑/切换食堂时调用） */
export async function clearAllBrandingImages(): Promise<void> {
  try {
    const db = await openDB()
    return new Promise((resolve, reject) => {
      const tx = db.transaction(STORE_NAME, 'readwrite')
      tx.objectStore(STORE_NAME).clear()
      tx.oncomplete = () => resolve()
      tx.onerror = () => reject(tx.error)
    })
  } catch {
    /* 静默 */
  }
}
