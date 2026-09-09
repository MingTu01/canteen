/**
 * 服务器时间工具
 *
 * 终端所有「业务时间」(时钟显示/就餐时段判定/今日订单过滤/订餐截止判定)
 * 一律以服务器时间为准,防止修改本机时间绕过限制或造成显示/核销错位。
 *
 * 原理:调用后端白名单接口 GET /api/system/time,计算
 *   offset = 服务器时间 - 本机时间
 * 之后 serverNow() = Date.now() + offset,毫秒级精度、零额外请求。
 *
 * 同步策略:
 * - 启动时立即同步一次(App.vue 调 startServerTimeSync)
 * - 之后每 5 分钟静默重同步(校正本机时钟漂移)
 * - 失败时保留上次 offset(从未成功则 offset=0,回退本机时间)
 *
 * 纯 UI 计时(无操作超时/动画/节流/缓存 TTL)仍用本机时间,与业务无关。
 */
import api from '@/api'

/** 服务器时间 - 本机时间(毫秒)。0 = 未同步,直接用本机时间 */
let serverOffset = 0

/** 是否已成功同步过至少一次 */
let everSynced = false

/** 同步周期:5 分钟(校正时钟漂移足够) */
const SYNC_INTERVAL = 5 * 60 * 1000

/** 定时器句柄(仅启动一次) */
let syncTimer: ReturnType<typeof setInterval> | null = null

/** 同步互斥:避免并发重复请求 */
let syncing = false

/** 当前服务器时间戳(毫秒) */
export function serverNow(): number {
  return Date.now() + serverOffset
}

/** 当前服务器时间 Date 对象(用于 getHours/getDate 等本地字段读取) */
export function serverDate(): Date {
  return new Date(serverNow())
}

/** 是否已成功同步过服务器时间 */
export function isServerTimeSynced(): boolean {
  return everSynced
}

/**
 * 同步一次服务器时间。
 * 网络往返补偿:取「请求发出前」与「响应到达后」本机时刻的中点作为
 * 对应服务器时刻的参考点,offset = serverTimestamp - 中点。
 * 内网 RTT <20ms,误差可忽略。
 */
export async function syncServerTime(): Promise<void> {
  if (syncing) return
  syncing = true
  const t0 = Date.now()
  try {
    const resp = await api.get('/system/time', { timeout: 5000 })
    const t1 = Date.now()
    const serverTs = Number(resp.data?.data?.timestamp)
    if (resp.data?.code === 200 && Number.isFinite(serverTs) && serverTs > 0) {
      const midpoint = t0 + (t1 - t0) / 2
      serverOffset = Math.round(serverTs - midpoint)
      everSynced = true
    }
  } catch {
    /* 失败保留旧 offset;从未成功则回退本机时间 */
  } finally {
    syncing = false
  }
}

/**
 * 启动服务器时间同步(App.vue 挂载时调用一次)。
 * 立即同步一次 + 每 5 分钟重同步。
 */
export function startServerTimeSync(): void {
  void syncServerTime()
  if (syncTimer === null) {
    syncTimer = setInterval(() => {
      void syncServerTime()
    }, SYNC_INTERVAL)
  }
}
