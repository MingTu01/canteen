/**
 * 员工列表本地缓存管理器。
 *
 * 职责:
 * 1. 启动时拉取本食堂全量员工列表 → 存 IndexedDB(employees store)
 * 2. 提供 getEmployeeByCardNo(cardNo):优先查本地缓存(毫秒级),未命中走网络
 * 3. 每天定时轮询更新(全量覆盖)
 * 4. 清理缓存(切换门店/解绑时)
 *
 * 头像不在此预加载:渲染链路走 imageCache.ts 的 getCachedAvatar
 * (独立 IndexedDB canteen_terminal_avatar,懒加载),预下载写入 images store
 * 属于死数据,且会拖慢菜品刷新时的 dbClearUnusedImages 全表扫描。
 *
 * 店铺隔离:终端绑定后 storeId 锁定,只拉取本食堂员工。
 */
import api from '@/api'
import {
  dbPutEmployees,
  dbGetEmployeeByCardNo,
  dbClearEmployees,
  type CachedEmployee,
} from '@/utils/db'

/** 轮询间隔:24 小时(员工列表变化不频繁,每天全量刷新一次) */
const POLL_INTERVAL = 24 * 60 * 60 * 1000

let pollTimer: ReturnType<typeof setInterval> | null = null
let currentStoreId: number | null = null
/** 代际号:每次发起新拉取时递增,旧请求完成后发现代际号不匹配则丢弃数据 */
let refreshGen = 0

/**
 * 拉取本食堂全量员工列表并缓存到 IndexedDB。
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
  } catch (e) {
    if (myGen === refreshGen) {
      console.error('[employeeCache] 刷新员工列表异常:', e)
    }
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
  // 1. 优先查本地缓存(精确匹配 + 多格式兼容 HID 读卡器)
  const variants = generateCardNoVariants(cardNo)
  for (const variant of variants) {
    const cached = await dbGetEmployeeByCardNo(variant)
    if (cached) {
      if (cached.status !== 1) {
        console.warn(`[employeeCache] 员工 ${cardNo} 已停用(status=${cached.status}),拒绝刷卡`)
        return null
      }
      return cached
    }
  }

  // 2. 缓存未命中:走网络(后端已支持多格式卡号匹配)
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

/**
 * 生成卡号的多种格式变体,兼容 USB 读卡器(OUR_IDR.dll)和 HID 键盘模拟读卡器。
 *
 * 读卡器型号差异:同一张卡可能输出不同位数(常见 WG26/WG34 协议会
 * 在卡号前补 0 到固定位数,如 0012345678)。
 * admin 端已统一"无前导零"录入,但历史数据可能是补零格式,
 * 因此变体同时覆盖"去零"与"补零"双向,配合后 10/8 位截断:
 *   1. 原始卡号(精确)
 *   2. 去前导零(读卡器补零 → 数据库无零格式)
 *   3. 仅数字(去分隔符,如 WG26 "123;45678")
 *   4. 补零到 10 位 / 8 位(读卡器短卡号 → 数据库补零旧数据)
 *   5. 后 10 位 / 后 8 位(读卡器输出更长序列)
 */
function generateCardNoVariants(cardNo: string): string[] {
  if (!cardNo) return []
  const trimmed = cardNo.trim()
  const variants: string[] = []
  const push = (v: string) => {
    if (v && !variants.includes(v)) variants.push(v)
  }

  push(trimmed)

  // 仅保留数字(在读卡器输出含分隔符时提前得到纯数字形式)
  const digitsOnly = trimmed.replace(/[^0-9]/g, '')
  push(digitsOnly)

  // 去前导零(最常见:读卡器补零输出,数据库为无零格式)
  const noZeros = digitsOnly.replace(/^0+/, '')
  push(noZeros)

  // 补零到固定位数(反向兼容:数据库历史数据为补零格式,读卡器输出短卡号)
  if (noZeros && noZeros.length < 10) push(noZeros.padStart(10, '0'))
  if (noZeros && noZeros.length < 8) push(noZeros.padStart(8, '0'))

  // 后 10 位 / 后 8 位(读卡器输出更长序列时截断)
  if (digitsOnly.length > 10) push(digitsOnly.substring(digitsOnly.length - 10))
  if (digitsOnly.length > 8) push(digitsOnly.substring(digitsOnly.length - 8))

  return variants
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
  // 店铺切换:先清理旧店铺员工缓存
  //(头像由 imageCache 独立缓存懒加载,不写入 images store,无需在此清理)
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
