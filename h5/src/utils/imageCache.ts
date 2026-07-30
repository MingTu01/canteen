/**
 * 图片本地缓存模块（IndexedDB）
 *
 * 缓存策略（懒加载）：
 * - 用到时才 fetch，fetch 后缓存，下次命中缓存直接返回 blob URL
 * - H5：菜品图片 + 登录用户头像都缓存（懒加载）
 *
 * 缓存结构：
 * - store: images
 * - key: 图片纯路径（去掉 ?v= 查询参数）
 * - value: { blob: Blob, version: string, timestamp: number }
 *
 * 失效策略：
 * - URL 中的 ?v=mtime 作为版本号
 * - 版本号变化时重新缓存
 * - 超过 30 天的缓存自动清理
 */

const DB_NAME = 'canteen_image_cache'
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
    const path = u.pathname
    const version = u.searchParams.get('v') || ''
    return { path, version }
  } catch {
    return { path: url, version: '' }
  }
}

/** 获取缓存的 blob URL（如果缓存命中且版本匹配） */
async function getCache(path: string, version: string): Promise<string | null> {
  try {
    const db = await openDB()
    return new Promise((resolve, reject) => {
      const tx = db.transaction(STORE_NAME, 'readonly')
      const store = tx.objectStore(STORE_NAME)
      const req = store.get(path)
      req.onsuccess = () => {
        const entry = req.result as CacheEntry | undefined
        if (!entry) { resolve(null); return }
        // 版本号变化则失效
        if (version && entry.version && entry.version !== version) {
          resolve(null); return
        }
        // 超过 TTL 则失效
        if (Date.now() - entry.timestamp > CACHE_TTL) {
          resolve(null); return
        }
        // 创建 blob URL
        resolve(URL.createObjectURL(entry.blob))
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
    // 缓存写入失败静默处理
  }
}

/**
 * 获取图片 URL（优先返回缓存的 blob URL）
 * 懒加载：首次调用时 fetch 并缓存，后续命中缓存直接返回 blob URL
 * @param url 原始图片 URL
 */
export async function getCachedImage(url: string): Promise<string> {
  if (!url) return url
  // data: URL 和 blob: URL 直接返回
  if (url.startsWith('data:') || url.startsWith('blob:')) return url
  // 非本站 URL 不缓存
  if (url.startsWith('http') && !url.includes(window.location.host)) {
    // 相对路径 /uploads/ 可以缓存，绝对外部 URL 不缓存
    if (!url.includes('/uploads/')) return url
  }

  const { path, version } = parseUrl(url)

  // 只缓存 /uploads/ 路径，外部 URL 不缓存
  if (!path.startsWith('/uploads/')) return url

  // 尝试读缓存
  const cached = await getCache(path, version)
  if (cached) return cached

  // 未命中：fetch 并缓存
  try {
    const resp = await fetch(url)
    if (!resp.ok) return url
    const blob = await resp.blob()
    // 只缓存图片类型
    if (!blob.type.startsWith('image/')) return url
    await setCache(path, version, blob)
    return URL.createObjectURL(blob)
  } catch {
    return url
  }
}

/** 清理过期缓存 */
export async function cleanExpiredCache(): Promise<void> {
  try {
    const db = await openDB()
    return new Promise((resolve, reject) => {
      const tx = db.transaction(STORE_NAME, 'readwrite')
      const store = tx.objectStore(STORE_NAME)
      const req = store.openCursor()
      const now = Date.now()
      const toDelete: string[] = []
      req.onsuccess = () => {
        const cursor = req.result
        if (cursor) {
          const entry = cursor.value as CacheEntry
          if (now - entry.timestamp > CACHE_TTL) {
            toDelete.push(cursor.key as string)
          }
          cursor.continue()
        } else {
          // 删除过期项
          for (const key of toDelete) {
            store.delete(key)
          }
        }
      }
      tx.oncomplete = () => resolve()
      tx.onerror = () => reject(tx.error)
    })
  } catch {
    // 静默处理
  }
}

/** 清空所有缓存 */
export async function clearAllCache(): Promise<void> {
  try {
    const db = await openDB()
    return new Promise((resolve, reject) => {
      const tx = db.transaction(STORE_NAME, 'readwrite')
      tx.objectStore(STORE_NAME).clear()
      tx.oncomplete = () => resolve()
      tx.onerror = () => reject(tx.error)
    })
  } catch {
    // 静默处理
  }
}
