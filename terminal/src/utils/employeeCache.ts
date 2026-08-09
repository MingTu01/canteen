/**
 * 员工列表本地缓存 + 头像预加载管理器。
 *
 * 职责:
 * 1. 启动时拉取本食堂全量员工列表 → 存 IndexedDB(employees store)
 * 2. 后台并发预下载所有员工头像 → 存 IndexedDB(images store,与菜品图片共用)
 * 3. 提供 getEmployeeByCardNo(cardNo):优先查本地缓存(毫秒级),未命中走网络
 * 4. 每天定时轮询更新(全量覆盖)
 * 5. 清理缓存(切换门店/解绑时)
 *
 * 店铺隔离:终端绑定后 storeId 锁定,只拉取本食堂员工。
 */
import api, { loadConfig } from '@/api'
import {
  dbPutEmployees,
  dbGetEmployeeByCardNo,
  dbClearEmployees,
  dbPutImage,
  dbGetImage,
  type CachedEmployee,
} from '@/utils/db'

/** 轮询间隔:24 小时(员工列表变化不频繁,每天全量刷新一次) */
const POLL_INTERVAL = 24 * 60 * 60 * 1000

/** 头像下载并发数 */
const AVATAR_CONCURRENCY = 5

let pollTimer: ReturnType<typeof setInterval> | null = null
let currentStoreId: number | null = null
/** 代际号:每次发起新拉取时递增,旧请求完成后发现代际号不匹配则丢弃数据 */
let refreshGen = 0

/**
 * 拉取本食堂全量员工列表并缓存到 IndexedDB。
 * 同时后台预下载所有头像。
 *
 * 竞态保护:用代际号(generation)替代简单的 loading 标志,
 * 店铺切换时旧请求完成后会发现代际号不匹配而丢弃数据,不会阻塞新请求。
 */
async function doRefreshEmployees(): Promise<void> {
  const myGen = ++refreshGen
  const targetStoreId = currentStoreId
  try {
    const resp = await api.get('/terminal/employees')
    // 店铺已切换或被新请求取代,丢弃旧数据
    if (myGen !== refreshGen || currentStoreId !== targetStoreId) {
      console.log('[employeeCache] 店铺已切换,丢弃旧员工数据')
      return
    }
    if (resp.data?.code !== 200 || !Array.isArray(resp.data.data)) {
      console.warn('[employeeCache] 拉取员工列表失败:', resp.data?.message)
      return
    }

    const employees: CachedEmployee[] = resp.data.data.map((e: any) => ({
      id: e.id,
      cardNo: e.cardNo,
      name: e.name,
      avatar: e.avatar || undefined,
      departmentId: e.departmentId ?? null,
      departmentName: e.departmentName ?? '',
      balance: e.balance ?? 0,
      status: e.status ?? 1,
      storeId: e.storeId ?? 0,
    }))

    // 二次校验:写入前确认店铺未切换
    if (myGen !== refreshGen || currentStoreId !== targetStoreId) {
      console.log('[employeeCache] 店铺已切换,放弃写入')
      return
    }
    // 写入 IndexedDB(全量覆盖)
    await dbPutEmployees(employees)
    console.log(`[employeeCache] 已缓存 ${employees.length} 名员工`)

    // 写入后再次校验,避免切换后预加载旧头像
    if (myGen !== refreshGen) return
    // 后台预下载头像(不阻塞主流程)
    preloadAvatars(employees, myGen).catch(() => {})
  } catch (e) {
    if (myGen === refreshGen) {
      console.error('[employeeCache] 刷新员工列表异常:', e)
    }
  }
}

/**
 * 后台并发预下载所有员工头像到 IndexedDB。
 * 头像存入 images store(与菜品图片共用),key 为完整 avatar URL(带签名)。
 * 已缓存的跳过,失败的重试 2 次。
 *
 * @param myGen 发起时的代际号,用于在下载过程中检测店铺是否已切换
 */
async function preloadAvatars(employees: CachedEmployee[], myGen: number): Promise<void> {
  const config = loadConfig()
  const baseUrl = config?.serverUrl || ''

  // 收集需要下载的头像 URL(去重,过滤空值)
  const avatarUrls = [...new Set(
    employees
      .map((e) => e.avatar)
      .filter((u): u is string => !!u && u.startsWith('/uploads/'))
  )]

  if (avatarUrls.length === 0) return

  let completed = 0
  let failed = 0
  let skipped = 0
  const total = avatarUrls.length

  async function downloadAvatar(url: string): Promise<void> {
    // 店铺已切换,停止下载
    if (myGen !== refreshGen) return
    // 已缓存则跳过
    const existing = await dbGetImage(url)
    if (existing) {
      skipped++
      return
    }

    const fullUrl = url.startsWith('http') ? url : baseUrl + url
    for (let attempt = 1; attempt <= 2; attempt++) {
      // 每次重试前检查店铺是否已切换
      if (myGen !== refreshGen) return
      try {
        const resp = await api.get(fullUrl, { responseType: 'blob', timeout: 15000 })
        const blob = resp.data as Blob
        if (!blob || blob.size < 100) continue
        await dbPutImage(url, blob)
        completed++
        return
      } catch {
        if (attempt === 2) {
          failed++
        }
      }
    }
  }

  // 并发下载(限并发 5)
  const queue = [...avatarUrls]
  async function worker() {
    while (queue.length > 0) {
      if (myGen !== refreshGen) return  // 店铺已切换,停止
      const url = queue.shift()!
      await downloadAvatar(url)
    }
  }
  await Promise.all(
    Array.from({ length: AVATAR_CONCURRENCY }, () => worker()),
  )
  if (myGen === refreshGen) {
    console.log(`[employeeCache] 头像预加载完成:新增 ${completed},跳过 ${skipped},失败 ${failed},共 ${total}`)
  }
}

/**
 * 按 cardNo 查员工(刷卡时调用)。
 * 优先查本地缓存(毫秒级),未命中走网络。
 *
 * 安全校验:缓存中 status !== 1(已停用)的员工不返回,避免停用员工
 * 在 24 小时轮询间隔内仍可刷卡。缓存初始化时后端已过滤 status=1,
 * 但员工可能在缓存建立后被停用,此处兜底校验。
 *
 * @returns 员工信息;null=卡号不存在或已停用
 */
export async function getEmployeeByCardNo(cardNo: string): Promise<CachedEmployee | null> {
  // 1. 优先查本地缓存
  const cached = await dbGetEmployeeByCardNo(cardNo)
  if (cached) {
    // 兜底校验:缓存可能过期(员工被停用后24小时内缓存仍存在)
    if (cached.status !== 1) {
      console.warn(`[employeeCache] 员工 ${cardNo} 已停用(status=${cached.status}),拒绝刷卡`)
      return null
    }
    return cached
  }

  // 2. 未命中:走网络(可能卡号是新员工,本地缓存还没更新)
  //    后端 selectByCardNoAndStore 已过滤 status=1 AND is_deleted=0
  try {
    const resp = await api.get(`/terminal/employee/${encodeURIComponent(cardNo)}`)
    if (resp.data?.code === 200 && resp.data.data) {
      return {
        id: resp.data.data.id,
        cardNo: resp.data.data.cardNo,
        name: resp.data.data.name,
        avatar: resp.data.data.avatar || undefined,
        departmentId: resp.data.data.departmentId ?? null,
        departmentName: resp.data.data.departmentName ?? '',
        balance: resp.data.data.balance ?? 0,
        status: resp.data.data.status ?? 1,
        storeId: resp.data.data.storeId ?? 0,
      }
    }
  } catch {
    /* 网络错误返回 null */
  }
  return null
}

/** 对外刷新接口(轮询定时器调用) */
function refreshEmployees(): Promise<void> {
  return doRefreshEmployees()
}

/**
 * 初始化员工缓存(终端启动时调用)。
 * 立即拉取一次,然后每 24 小时轮询更新。
 *
 * 店铺隔离:传入 storeId,切换店铺时先清理旧员工数据再拉取新店铺数据。
 * 代际号机制:旧请求完成后会自动丢弃数据,不阻塞新请求。
 */
export async function initEmployeeCache(storeId: number): Promise<void> {
  // 店铺切换:先清理旧店铺员工缓存(含头像会在 refreshDishes 的 dbClearUnusedImages 中清理)
  if (currentStoreId !== null && currentStoreId !== storeId) {
    console.log(`[employeeCache] 店铺切换 ${currentStoreId} → ${storeId},清理旧员工缓存`)
    await dbClearEmployees().catch(() => {})
  }
  currentStoreId = storeId
  // 直接调用 doRefreshEmployees,代际号会自动让旧 in-flight 请求失效
  await doRefreshEmployees()
  if (pollTimer) clearInterval(pollTimer)
  pollTimer = setInterval(refreshEmployees, POLL_INTERVAL)
}

/** 销毁员工缓存(切换门店/解绑时调用) */
export async function destroyEmployeeCache(): Promise<void> {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
  // 递增代际号,让所有 in-flight 请求失效
  refreshGen++
  currentStoreId = null
  await dbClearEmployees()
}
