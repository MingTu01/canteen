/**
 * 菜品/菜单本地缓存管理器
 *
 * 职责:
 * 1. 启动时拉取本门店所有菜品 → 存 IndexedDB(仅元数据,不含图片)
 * 2. 建立 SSE 长连接,接收变更事件,增量更新本地缓存
 * 3. 对外提供同步 API:getDish(id),命中本地缓存
 *
 * 注:菜品图片 Blob 缓存已移除(终端不再渲染菜品图),仅保留员工头像缓存。
 * images store 共用:菜品图已不再写入,残留的旧菜品 Blob 会在 refreshDishes 时
 * 通过 dbClearUnusedImages(仅员工头像 URL)清理。
 *
 * 容错策略:
 * - IndexedDB 不可用:所有 get* 返回 null,调用方降级直查后端
 * - SSE 断开:自动重连(指数退避 + 随机抖动),断开期间用轮询兜底
 *   (每 5 分钟按版本号校验一次,版本未变化则跳过全量拉取)
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
  dbPutMenu,
  dbGetMenu,
  dbDeleteMenu,
  dbClearDishesByStore,
  dbClearImages,
  dbClearUnusedImages,
  dbClearMenus,
  dbClearEmployees,
  dbGetAllEmployees,
  type CachedDish,
} from '@/utils/db'

const MENU_CACHE_PREFIX = 'menu_'
const SSE_RECONNECT_BASE = 5000    // 初始重连延迟
const SSE_RECONNECT_MAX = 60000    // 最大重连延迟(60 秒封顶)
const SSE_MAX_RETRIES = 10         // 最大重连次数
const FALLBACK_POLL_INTERVAL = 5 * 60 * 1000 // 5 分钟轮询兜底

/** 菜品数据版本号 localStorage key(按门店,值为后端 count:maxUpdated 版本串) */
function dishVersionKey(storeId: number): string {
  return `dish_ver_${storeId}`
}

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
  // 如果 storeId 变化(切换门店),先销毁旧缓存并清理旧店铺 IndexedDB 数据
  const switchedStore = initialized && currentStoreId !== null && currentStoreId !== storeId
  if (switchedStore) {
    console.log(`[cache] 店铺切换 ${currentStoreId} → ${storeId},清理旧店铺缓存`)
    const oldStoreId = currentStoreId
    destroyLocalCache()
    // 立即清理旧店铺的菜品/菜单/员工数据,避免短暂展示上一家的数据
    await dbClearDishesByStore(oldStoreId).catch(() => {})
    await dbClearMenus().catch(() => {})
    await dbClearEmployees().catch(() => {})
  }
  currentStoreId = storeId
  initialized = true

  // 先尝试加载本地缓存(秒开)
  const localDishes = await dbGetAllDishes()
  if (localDishes.length > 0) {
    console.log(`[cache] 本地已有 ${localDishes.length} 个菜品,先秒开`)
  }

  // 后台异步拉取最新菜品 + 图片
  // 切换门店属清缓存场景,强制全量刷新(不走版本号短路)
  refreshDishes(storeId, { force: switchedStore }).catch((e) =>
    console.warn('[cache] 菜品缓存刷新失败:', e),
  )

  // 启动 SSE
  startSse(storeId)

  // 启动轮询兜底(SSE 断开期间的保护)
  startFallbackPoll(storeId)
}

/**
 * 刷新本门店菜品缓存(带版本号短路)。
 *
 * 并发互斥:同时多个调用(如 SSE 连续事件)复用同一个 in-flight Promise,
 * 避免 clear+put 交错导致空窗或数据回退。
 *
 * 竞态保护:.finally 中检查是否仍是自己的 Promise,
 * 避免店铺切换后旧请求错误地把新请求的 refreshInFlight 重置为 null。
 */
export async function refreshDishes(storeId: number, opts?: { force?: boolean }): Promise<void> {
  if (refreshInFlight) return refreshInFlight
  const p = doRefreshDishes(storeId, opts).finally(() => {
    // 只有当 refreshInFlight 仍指向自己时才重置,
    // 避免店铺切换后旧请求把新请求的 Promise 错误置 null
    if (refreshInFlight === p) refreshInFlight = null
  })
  refreshInFlight = p
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

/**
 * 拉取服务端菜品数据版本号(GET /dish/store/{storeId}/version)。
 * 后端返回 {version: "count:maxUpdated"};接口不可用/网络失败返回 null
 * (此时视为无法短路,走全量刷新,兼容旧版后端)。
 */
async function getDishVersion(storeId: number): Promise<string | null> {
  try {
    const resp = await api.get(`/dish/store/${storeId}/version`)
    if (resp.data?.code !== 200) return null
    const data = resp.data.data
    if (typeof data === 'string' && data) return data
    if (data && typeof data.version === 'string' && data.version) return data.version
    return null
  } catch {
    return null
  }
}

/**
 * 实际执行刷新(由 refreshDishes 调用,已加互斥锁)。
 *
 * 版本号短路:非 force 时先取服务端版本号,与 localStorage `dish_ver_${storeId}`
 * 比对——相等且本地 IndexedDB 该店菜品非空则直接返回,跳过全量拉取、
 * dbClearDishesByStore/dbPutDishes 与 dbClearUnusedImages 全表扫描。
 * 版本不同或本地为空 → 走全量刷新,成功后写回版本号。
 * (存刷新前的版本号:若刷新期间数据又变更,下次比对会发现不同再多刷一次,
 * 宁可多刷不可漏刷)
 */
async function doRefreshDishes(storeId: number, opts?: { force?: boolean }): Promise<void> {
  // 先取服务端版本号(全量刷新成功后写回,作为下次短路比对依据)
  const remoteVer = await getDishVersion(storeId)
  if (!opts?.force && remoteVer) {
    const savedVer = localStorage.getItem(dishVersionKey(storeId))
    if (remoteVer === savedVer) {
      const localDishes = await dbGetAllDishes()
      if (localDishes.some((d) => d.storeId === storeId)) {
        console.log(`[cache] 菜品版本未变化(${remoteVer}),跳过全量刷新`)
        return
      }
    }
  }

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
    // 记录版本号,空门店不再反复全量拉取
    if (remoteVer) localStorage.setItem(dishVersionKey(storeId), remoteVer)
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
  // 全量刷新成功,写回版本号(下次版本未变时短路,跳过全量拉取与图片清理)
  if (remoteVer) localStorage.setItem(dishVersionKey(storeId), remoteVer)
  console.log(`[cache] 已缓存 ${cached.length} 个菜品(版本 ${remoteVer ?? '未知'})`)

  // 清理 IndexedDB 中残留的旧菜品图片 Blob(菜品图已不再下载/渲染)
  // 注意:images store 与员工头像共用,仅传员工头像 URL 作为白名单,
  // 这样旧的菜品图 Blob 会被清掉,头像保留
  // (仅真正全量刷新后执行,版本短路路径不触发全表扫描)
  const usedUrls = new Set<string>()
  try {
    const employees = await dbGetAllEmployees()
    for (const e of employees) {
      if (e.avatar) usedUrls.add(e.avatar)
    }
  } catch { /* 静默 */ }
  await dbClearUnusedImages(usedUrls).catch(() => {})
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

/** 销毁缓存管理器(切换门店/解绑时调用) */
export function destroyLocalCache(): void {
  stopSse()
  stopFallbackPoll()
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
  sseRetryCount = 0
  refreshInFlight = null
  await dbClearDishesByStore(0).catch(() => {})
  await dbClearImages().catch(() => {})
  await dbClearMenus().catch(() => {})
  await dbClearEmployees().catch(() => {})
  // 清理各门店的菜品版本号标记,避免残留旧版本号影响下次绑定后的短路判断
  try {
    for (let i = localStorage.length - 1; i >= 0; i--) {
      const k = localStorage.key(i)
      if (k && k.startsWith('dish_ver_')) localStorage.removeItem(k)
    }
  } catch { /* 静默 */ }
  currentStoreId = null
  initialized = false
}
