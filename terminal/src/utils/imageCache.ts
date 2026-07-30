/**
 * 头像图片本地缓存模块（IndexedDB）
 *
 * 终端缓存策略：
 * - 菜品图片：由 utils/cache.ts 统一管理（SSE + 全量预加载 + 增量更新）
 * - 员工头像：本模块负责，懒加载（刷卡识别到员工时才 fetch + 缓存）
 *
 * 缓存结构：
 * - DB: canteen_terminal_avatar
 * - store: avatars
 * - key: 头像纯路径（去掉 ?v= 查询参数）
 * - value: { blob: Blob, version: string, timestamp: number }
 *
 * 失效策略：
 * - URL 中的 ?v=mtime 作为版本号
 * - 版本号变化时重新缓存
 * - 超过 30 天的缓存自动清理
 */

const DB_NAME = 'canteen_terminal_avatar'
const DB_VERSION = 1
const STORE_NAME = 'avatars'
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
function parseUrl(url: string, baseUrl: string): { path: string; version: string } {
  try {
    const u = new URL(url, baseUrl || window.location.origin)
    return { path: u.pathname, version: u.searchParams.get('v') || '' }
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
        if (version && entry.version && entry.version !== version) {
          resolve(null); return
        }
        if (Date.now() - entry.timestamp > CACHE_TTL) {
          resolve(null); return
        }
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
 * 获取头像 URL（优先返回缓存的 blob URL）
 * 懒加载：首次调用时 fetch 并缓存，后续命中缓存直接返回 blob URL
 * @param url 原始头像 URL（相对路径或绝对路径）
 * @param baseUrl 后端服务器地址（终端场景下需要）
 */
export async function getCachedAvatar(url: string, baseUrl = ''): Promise<string> {
  if (!url) return url
  // data: URL 和 blob: URL 直接返回
  if (url.startsWith('data:') || url.startsWith('blob:')) return url

  const { path, version } = parseUrl(url, baseUrl)
  // 只缓存 /uploads/ 路径
  if (!path.startsWith('/uploads/')) return url

  // 尝试读缓存
  const cached = await getCache(path, version)
  if (cached) return cached

  // 未命中：fetch 并缓存
  // fullUrl 是带 baseUrl 的完整 URL,用于 fetch 和降级
  const fullUrl = url.startsWith('http') ? url : baseUrl + url
  try {
    const resp = await fetch(fullUrl)
    if (!resp.ok) return fullUrl
    const blob = await resp.blob()
    if (!blob.type.startsWith('image/')) return fullUrl
    await setCache(path, version, blob)
    return URL.createObjectURL(blob)
  } catch {
    // fetch 失败(CORS/网络):返回 fullUrl 让 <img> 标签直查后端
    // img 标签不受 CORS 限制,仍能正常显示
    // 注意:不能返回原始相对 url,否则终端会请求 http://127.0.0.1:1287/uploads/... 导致 404
    return fullUrl
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
