/**
 * 服务器时间工具
 *
 * 统一从后端 /api/system/time 获取时间,避免本机时间篡改绕过限制。
 * 使用:调用 fetchServerTime() 拉取后端时间,缓存在内存中,定时刷新。
 */
import { ref } from 'vue'
import axios from 'axios'

export interface ServerTime {
  /** 毫秒时间戳 */
  timestamp: number
  /** yyyy-MM-dd(Asia/Shanghai) */
  date: string
  /** HH:mm(Asia/Shanghai) */
  time: string
  /** 当前小时*60+分钟(便于过点不订判断) */
  minutes: number
  /** ISO LocalDateTime */
  datetime: string
}

// 直接用 axios 而非封装的 api,因为该接口是白名单,不需要鉴权
const rawApi = axios.create({ baseURL: '/api' })

// 缓存的服务器时间(每次拉取后更新)
const cachedTime = ref<ServerTime | null>(null)
// 上次拉取时间戳(用于估算当前时间,避免频繁请求)
let lastFetchAt = 0
// 拉取失败时的回退标记(使用本机时间)
let useFallback = false

/**
 * 拉取服务器时间
 * @param force 是否强制刷新(忽略缓存)
 */
export async function fetchServerTime(force = false): Promise<ServerTime> {
  // 5 秒内不重复请求
  const now = Date.now()
  if (!force && cachedTime.value && now - lastFetchAt < 5000) {
    return estimateCurrent()
  }
  try {
    const resp = await rawApi.get('/system/time')
    if (resp.data?.code === 200 && resp.data?.data) {
      const data: ServerTime = resp.data.data
      cachedTime.value = data
      lastFetchAt = now
      return data
    }
  } catch (e) {
    console.warn('[serverTime] 拉取服务器时间失败,回退本机时间', e)
  }
  // 拉取失败回退本机时间(保证功能可用,但失去防篡改能力)
  useFallback = true
  return getLocalTime()
}

/**
 * 估算当前服务器时间(基于上次拉取的时间戳 + 本机时间差)
 * 避免每次调用都发起请求
 */
export function estimateCurrent(): ServerTime {
  if (!cachedTime.value || useFallback) {
    return getLocalTime()
  }
  // 基于上次拉取时间戳 + 本机经过的毫秒数估算
  const elapsed = Date.now() - lastFetchAt
  const estimatedTimestamp = cachedTime.value.timestamp + elapsed
  const d = new Date(estimatedTimestamp)
  // 转为 Asia/Shanghai 时区
  const shanghaiStr = d.toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' })
  const [datePart, timePart] = shanghaiStr.split(/\s+/)
  const [h, m] = (timePart || '00:00').split(':').map(Number)
  return {
    timestamp: estimatedTimestamp,
    date: datePart,
    time: `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`,
    minutes: h * 60 + m,
    datetime: `${datePart}T${timePart}:00`,
  }
}

/**
 * 本机时间(回退方案,格式与服务器时间一致)
 */
function getLocalTime(): ServerTime {
  const d = new Date()
  const shanghaiStr = d.toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' })
  const [datePart, timePart] = shanghaiStr.split(/\s+/)
  const [h, m] = (timePart || '00:00').split(':').map(Number)
  return {
    timestamp: d.getTime(),
    date: datePart,
    time: `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`,
    minutes: h * 60 + m,
    datetime: `${datePart}T${timePart}:00`,
  }
}

/**
 * 响应式的当前服务器时间(每秒更新估算值)
 * 适用于需要实时显示时间的场景
 */
export function useServerTime() {
  const current = ref<ServerTime>(getLocalTime())
  let timer: ReturnType<typeof setInterval> | null = null

  const start = async () => {
    current.value = await fetchServerTime()
    timer = setInterval(() => {
      current.value = estimateCurrent()
    }, 1000)
  }

  const stop = () => {
    if (timer) {
      clearInterval(timer)
      timer = null
    }
  }

  return { current, start, stop }
}
