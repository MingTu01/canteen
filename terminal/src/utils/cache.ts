/**
 * 菜品/菜单本地缓存管理器
 *
 * 职责:
 * 1. 启动时拉取本门店所有菜品 → 存 IndexedDB → 拉取菜品图片 Blob → 存 IndexedDB
 * 2. 建立 SSE 长连接,接收变更事件,增量更新本地缓存
 * 3. 对外提供同步 API:getDish(id) / getDishImageUrl(url),命中本地缓存
 *
 * 容错策略:
 * - IndexedDB 不可用:所有 get* 返回 null,调用方降级直查后端
 * - SSE 断开:自动重连(指数退避 + 随机抖动),断开期间用轮询兜底(每 5 分钟全量校验)
 * - 图片下载失败:跳过,渲染时降级走后端 URL
 *
 * 安全说明:
 * - SSE 不再通过 URL query 传递 token(会进浏览器历史/Referer/代理日志)
 * - 改用一次性 ticket:先用 Bearer token 调用 /api/sse/ticket 获取 30s 一次性 ticket,
 *   再用 ticket 建立 EventSource,ticket 校验后立即销毁
 */
import { ref } from 'vue'
import api, { loadConfig } from '@/api'
import {
  dbPutDishes,
  dbGetAllDishes,
  dbPutImage,
  dbGetImage,
  dbPutMenu,
  dbGetMenu,
  dbDeleteMenu,
  dbClearDishesByStore,
  dbClearImages,
  dbClearUnusedImages,
  dbClearMenus,
  getDishImageBlob,
  clearObjectUrlCache,
  type CachedDish,
} from '@/utils/db'

const MENU_CACHE_PREFIX = 'menu_'
const SSE_RECONNECT_BASE = 5000    // 初始重连延迟
const SSE_RECONNECT_MAX = 60000    // 最大重连延迟(60 秒封顶)
const SSE_MAX_RETRIES = 10         // 最大重连次数
const FALLBACK_POLL_INTERVAL = 5 * 60 * 1000 // 5 分钟轮询兜底

/**
 * 菜单失效事件总线:SSE 收到 menu_changed 事件时更新此 ref。
 * OrderSelect/OrderQuery 等组件 watch 此 ref,匹配当前选中日期时清除内存缓存并重新拉取。
 * 解决"SSE 菜单事件无法触达 UI"问题(组件内存缓存不读取 IndexedDB)。
 */
export const menuInvalidated = ref<{ storeId: number; date: string; ts: number } | null>(null)

interface DishDTO {
  id: number
  storeId: number
  name: string
  price: number | string
  image: string | null
  category: string | null
  mealTypes: string | null
  isNew: number
  status: number
  updatedAt?: string
}

let sseSource: EventSource | null = null
let sseReconnectTimer: ReturnType<typeof setTimeout> | null = null
let sseRetryCount = 0
let fallbackPollTimer: ReturnType<typeof setInterval> | null = null
let currentStoreId: number | null = null
let initialized = false

/** refreshDishes 并发互斥锁:同时多个调用复用同一个 Promise,避免 clear+put 交错 */
let refreshInFlight: Promise<void> | null = null
/** SSE 事件防抖定时器:500ms 内多次事件只触发一次刷新 */
let sseDebounceTimer: ReturnType<typeof setTimeout> | null = null

/** 启动时拉取菜品 + 图片缓存 */
export async function initLocalCache(storeId: number): Promise<void> {
  if (initialized && currentStoreId === storeId) return
  // 如果 storeId 变化(切换门店),先销毁旧缓存
  if (initialized && currentStoreId !== storeId) {
    destroyLocalCache()
  }
  currentStoreId = storeId
  initialized = true

  // 先尝试加载本地缓存(秒开)
  const localDishes = await dbGetAllDishes()
  if (localDishes.length > 0) {
    console.log(`[cache] 本地已有 ${localDishes.length} 个菜品,先秒开`)
  }

  // 后台异步拉取最新菜品 + 图片
  refreshDishes(storeId).catch((e) =>
    console.warn('[cache] 菜品缓存刷新失败:', e),
  )

  // 启动 SSE
  startSse(storeId)

  // 启动轮询兜底(SSE 断开期间的保护)
  startFallbackPoll(storeId)
}

/**
 * 全量刷新本门店菜品 + 图片缓存。
 *
 * 并发互斥:同时多个调用(如 SSE 连续事件)复用同一个 in-flight Promise,
 * 避免 clear+put 交错导致空窗或数据回退。
 */
export async function refreshDishes(storeId: number): Promise<void> {
  if (refreshInFlight) return refreshInFlight
  refreshInFlight = doRefreshDishes(storeId).finally(() => {
    refreshInFlight = null
  })
  return refreshInFlight
}

/** SSE 事件触发的防抖刷新(500ms 内多次事件只刷新一次,避免并发风暴) */
function sseTriggeredRefresh(storeId: number): void {
  if (sseDebounceTimer) clearTimeout(sseDebounceTimer)
  sseDebounceTimer = setTimeout(() => {
    sseDebounceTimer = null
    refreshDishes(storeId).catch((e) => console.warn('[sse] refresh failed:', e))
  }, 500)
}

/** 实际执行刷新(由 refreshDishes 调用,已加互斥锁) */
async function doRefreshDishes(storeId: number): Promise<void> {
  // 拉取门店所有菜品(不分页)
  const resp = await api.get(`/dish/store/${storeId}/all`)
  if (resp.data?.code !== 200) return
  // 网络往返期间门店可能已切换,写入前校验避免数据错配
  if (currentStoreId !== storeId) {
    console.log('[cache] 门店已切换,放弃写入旧门店菜品')
    return
  }
  const dishes: DishDTO[] = resp.data.data || []
  if (dishes.length === 0) {
    // 空列表也要清理本地脏数据(门店临时全部下架场景)
    await dbClearDishesByStore(storeId).catch(() => {})
    console.log('[cache] 门店无菜品,已清空本地缓存')
    return
  }

  // 转换为 CachedDish
  const cached: CachedDish[] = dishes.map((d) => ({
    id: d.id,
    storeId: d.storeId,
    name: d.name,
    price: Number(d.price),
    image: d.image,
    category: d.category,
    mealTypes: d.mealTypes,
    isNew: d.isNew ?? 0,
    status: d.status ?? 1,
    updatedAt: d.updatedAt ?? new Date().toISOString(),
  }))

  // 二次校验:dbClearDishesByStore + dbPutDishes 之间门店可能再次切换
  if (currentStoreId !== storeId) {
    console.log('[cache] 门店已切换,放弃写入')
    return
  }
  await dbClearDishesByStore(storeId)
  await dbPutDishes(cached)
  console.log(`[cache] 已缓存 ${cached.length} 个菜品`)

  // 并发下载图片(限并发 5)
  const imageUrls = cached
    .map((d) => d.image)
    .filter((u): u is string => !!u && u.startsWith('/uploads/'))
  await downloadImages(imageUrls)

  // 清理不再使用的图片 Blob(菜品下架或图片 URL 变更后,旧 Blob 残留)
  const usedUrls = new Set(cached.map((d) => d.image).filter(Boolean) as string[])
  await dbClearUnusedImages(usedUrls).catch(() => {})
}

/** 批量下载图片到 IndexedDB,限并发 5 */
async function downloadImageUrls(urls: string[], baseUrl: string): Promise<void> {
  const CONCURRENCY = 5
  const queue = [...new Set(urls)]
  let completed = 0
  const total = queue.length

  async function worker() {
    while (queue.length > 0) {
      const url = queue.shift()!
      try {
        // 已缓存则跳过
        const existing = await dbGetImage(url)
        if (existing) {
          completed++
          continue
        }
        // 下载
        const fullUrl = url.startsWith('http') ? url : baseUrl + url
        const resp = await fetch(fullUrl)
        if (!resp.ok) continue
        const blob = await resp.blob()
        await dbPutImage(url, blob)
        completed++
      } catch {
        /* 单张图片失败不影响整体 */
      }
    }
  }

  await Promise.all(
    Array.from({ length: CONCURRENCY }, () => worker()),
  )
  console.log(`[cache] 图片下载完成:${completed}/${total}`)
}

/** 兼容方法:从终端配置中拿 serverUrl 拼接绝对路径 */
async function downloadImages(urls: string[]): Promise<void> {
  const config = loadConfig()
  const baseUrl = config?.serverUrl || ''
  await downloadImageUrls(urls, baseUrl)
}

/**
 * SSE 长连接:接收菜品/菜单变更事件。
 *
 * 认证流程(避免 token 出现在 URL query 中):
 * 1. 用 Bearer token 调用 GET /api/sse/ticket 获取一次性 ticket(30s 有效)
 * 2. 用 ticket 建立 EventSource: /api/sse/subscribe?ticket=xxx
 * 3. ticket 校验后立即销毁,不会进浏览器历史/Referer
 */
async function startSse(storeId: number): Promise<void> {
  stopSse()
  const config = loadConfig()
  if (!config?.serverUrl || !config?.token) return

  try {
    // 1. 用 Bearer token 获取一次性 ticket(30s 有效)
    const ticketResp = await api.get('/sse/ticket')
    const ticket = ticketResp.data?.code === 200 ? ticketResp.data.data?.ticket : null
    if (!ticket) {
      console.warn('[sse] 获取 ticket 失败,稍后重试')
      scheduleReconnect(storeId)
      return
    }
    // 2. 用 ticket 建立 EventSource(ticket 一次性,30s 过期)
    const url = `${config.serverUrl}/api/sse/subscribe?ticket=${encodeURIComponent(ticket)}`
    sseSource = new EventSource(url)

    sseSource.addEventListener('open', () => {
      console.log('[sse] 连接已建立')
      sseRetryCount = 0
    })

    sseSource.addEventListener('dish_changed', () => {
      // 防抖刷新:500ms 内多次事件只刷新一次
      sseTriggeredRefresh(storeId)
    })

    sseSource.addEventListener('dish_batch_changed', () => {
      sseTriggeredRefresh(storeId)
    })

    sseSource.addEventListener('menu_changed', (e) => {
      try {
        const data = JSON.parse(e.data)
        if (data.date) {
          // 精确失效该日期菜单缓存(不影响其他日期)
          dbClearMenuByDate(storeId, data.date)
          // 触发事件总线,让组件内存缓存也失效(OrderSelect 等 watch 此 ref)
          menuInvalidated.value = { storeId, date: data.date, ts: Date.now() }
        }
      } catch { /* 忽略 */ }
    })

    sseSource.onerror = () => {
      stopSse()
      scheduleReconnect(storeId)
    }
  } catch (e) {
    console.warn('[sse] 启动失败:', e)
    scheduleReconnect(storeId)
  }
}

/** 调度 SSE 重连(指数退避 + 随机抖动,避免多终端同步重连风暴) */
function scheduleReconnect(storeId: number): void {
  if (sseRetryCount >= SSE_MAX_RETRIES) {
    // 达到上限后不永久放弃,降级为低频重试(每 5 分钟尝试重启)
    // 避免 7×24 终端因临时网络抖动永久丧失实时性
    console.warn(`[sse] 重连已达上限(${SSE_MAX_RETRIES}次),降级为低频重试(每 ${FALLBACK_POLL_INTERVAL / 1000} 秒)`)
    sseReconnectTimer = setTimeout(() => {
      sseRetryCount = 0  // 重置计数器,给重新连接的机会
      startSse(storeId).catch(() => {})
    }, FALLBACK_POLL_INTERVAL)
    return
  }
  // 指数退避 + 随机抖动(jitter):5s → 10s → 20s → 40s → 60s(封顶)
  // jitter 避免多终端同步重连风暴(后端重启时所有终端同时重连)
  const base = Math.min(SSE_RECONNECT_BASE * Math.pow(2, sseRetryCount), SSE_RECONNECT_MAX)
  const delay = Math.round(base * (0.5 + Math.random() * 0.5))
  sseRetryCount++
  console.warn(`[sse] 连接断开,${delay / 1000} 秒后重连(第 ${sseRetryCount} 次)`)
  sseReconnectTimer = setTimeout(() => startSse(storeId).catch(() => {}), delay)
}

function stopSse(): void {
  if (sseSource) {
    sseSource.close()
    sseSource = null
  }
  if (sseReconnectTimer) {
    clearTimeout(sseReconnectTimer)
    sseReconnectTimer = null
  }
  if (sseDebounceTimer) {
    clearTimeout(sseDebounceTimer)
    sseDebounceTimer = null
  }
}

/** 轮询兜底:SSE 断开期间每 5 分钟全量校验一次,并尝试重启 SSE */
function startFallbackPoll(storeId: number): void {
  stopFallbackPoll()
  fallbackPollTimer = setInterval(() => {
    if (!sseSource || sseSource.readyState === EventSource.CLOSED) {
      console.log('[cache] SSE 断开,触发兜底轮询')
      refreshDishes(storeId).catch((e) => console.warn('[cache] 兜底轮询失败:', e))
      // 尝试重启 SSE(如果之前因 10 次上限停止且没有 pending 重连)
      if (!sseSource && !sseReconnectTimer) {
        sseRetryCount = 0
        startSse(storeId).catch(() => {})
      }
    }
  }, FALLBACK_POLL_INTERVAL)
}

function stopFallbackPoll(): void {
  if (fallbackPollTimer) {
    clearInterval(fallbackPollTimer)
    fallbackPollTimer = null
  }
}

/** 精确清除指定日期菜单缓存(不影响其他日期) */
async function dbClearMenuByDate(storeId: number, date: string): Promise<void> {
  const key = `${MENU_CACHE_PREFIX}${storeId}_${date}`
  try {
    await dbDeleteMenu(key)
    console.log(`[cache] 菜单缓存已失效:${date}`)
  } catch { /* 静默 */ }
}

/** 缓存菜单查询结果 */
export async function cacheMenu(storeId: number, date: string, menu: unknown): Promise<void> {
  const key = `${MENU_CACHE_PREFIX}${storeId}_${date}`
  await dbPutMenu(key, menu)
}

/** 读取本地菜单缓存 */
export async function getCachedMenu<T = unknown>(storeId: number, date: string): Promise<T | null> {
  const key = `${MENU_CACHE_PREFIX}${storeId}_${date}`
  return dbGetMenu<T>(key)
}

/** 获取本地缓存的菜品(同步,可能为空) */
export async function getLocalDishes(): Promise<CachedDish[]> {
  return dbGetAllDishes()
}

/** 按 ID 获取本地菜品 */
export async function getLocalDish(id: number): Promise<CachedDish | null> {
  const all = await dbGetAllDishes()
  return all.find((d) => d.id === id) || null
}

/**
 * 获取菜品图片 URL(优先本地 Blob,降级直查后端)。
 *
 * 返回的 blob: URL 由调用方独占管理,在组件卸载/数据更新时 revokeObjectURL。
 * 每次调用都创建新的 ObjectURL(不缓存),避免双重所有权导致裂图。
 * 同一图片被多个组件使用时各持独立 ObjectURL,revoke 互不影响。
 */
export async function getDishImgUrl(imageUrl: string | null | undefined): Promise<string | null> {
  if (!imageUrl) return null
  // 1. 查本地 Blob 缓存,命中则创建新的 ObjectURL(由调用方独占管理)
  const blob = await getDishImageBlob(imageUrl)
  if (blob) return URL.createObjectURL(blob)
  // 2. 降级:返回后端原始 URL(非 blob:,无需 revoke)
  const config = loadConfig()
  const baseUrl = config?.serverUrl || ''
  return imageUrl.startsWith('http') ? imageUrl : baseUrl + imageUrl
}

/** 销毁缓存管理器(切换门店/解绑时调用) */
export function destroyLocalCache(): void {
  stopSse()
  stopFallbackPoll()
  clearObjectUrlCache()
  sseRetryCount = 0
  refreshInFlight = null
  currentStoreId = null
  initialized = false
  // 不清除 IndexedDB 数据,下次启动仍可秒开
}

/** 全清本地缓存(完全重置,解绑时调用) */
export async function purgeLocalCache(): Promise<void> {
  stopSse()
  stopFallbackPoll()
  clearObjectUrlCache()
  sseRetryCount = 0
  refreshInFlight = null
  await dbClearDishesByStore(0).catch(() => {})
  await dbClearImages().catch(() => {})
  await dbClearMenus().catch(() => {})
  currentStoreId = null
  initialized = false
}
