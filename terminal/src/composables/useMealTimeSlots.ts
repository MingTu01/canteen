import { ref } from 'vue'
import api from '@/api'

/**
 * 就餐时段配置 composable(对齐后端 GET /api/terminal/meal-slots)。
 *
 * 数据来源:admin-web「时间管理」页配置的 dining_time_slot 表(每食堂×每餐次一行)。
 * 终端启动时调用 loadMealSlots() 缓存到本地,用于:
 *   - 展示真实时段文字(如"早餐 07:00-10:00"),取代写死的 08:00/12:00/18:00
 *   - 判定当前时间是否在就餐时段内(空档期拒绝取餐,提示"未到用餐时间")
 *   - 识别当前时段对应的餐次(避免午餐时段核销早餐订单的严重 BUG)
 *
 * 缓存策略:模块级单例 ref + loaded 标志位,首次加载后每 10 分钟静默刷新,
 *            管理员修改就餐时段后终端能在 10 分钟内感知到。
 * 兜底:接口失败时保留旧数据(首次失败 slots 为空数组),此时 currentMealType 返回 null,
 *       终端会提示"未到用餐时间",后端也会拒绝核销(双重保险)。
 */

export interface MealTimeSlot {
  id: number
  storeId: number
  mealType: number
  /** 开始时间 HH:mm:ss(后端 LocalTime 序列化) */
  startTime: string
  /** 结束时间 HH:mm:ss */
  endTime: string
}

/** 将 "HH:mm:ss" / "HH:mm" 转成分钟数,便于比较 */
export function timeToMinutes(time: string): number {
  if (!time) return -1
  const parts = time.split(':').map(Number)
  const h = parts[0] || 0
  const m = parts[1] || 0
  return h * 60 + m
}

/** 将 "HH:mm:ss" 格式化成 "HH:mm" 显示 */
export function formatTimeSlot(time: string): string {
  if (!time) return ''
  const parts = time.split(':')
  return `${parts[0]}:${parts[1] || '00'}`
}

const slots = ref<MealTimeSlot[]>([])
let loaded = false
let loadingPromise: Promise<MealTimeSlot[]> | null = null
/** 定时刷新句柄:首次加载成功后启动,每 10 分钟静默刷新一次 */
let refreshTimer: ReturnType<typeof setInterval> | null = null
const REFRESH_INTERVAL = 10 * 60 * 1000

export function useMealTimeSlots() {
  /**
   * 拉取本食堂的就餐时段配置。
   * 并发安全:多次调用只会发一次请求,共享同一个 Promise。
   * @param force true=强制刷新(忽略 loaded 标志),用于定时刷新
   */
  const loadMealSlots = async (force = false): Promise<MealTimeSlot[]> => {
    if (!force && loaded) return slots.value
    if (loadingPromise) return loadingPromise
    loadingPromise = (async () => {
      try {
        const res = await api.get('/terminal/meal-slots')
        if (res.data?.code === 200 && Array.isArray(res.data.data)) {
          slots.value = res.data.data
        }
      } catch {
        /* 接口失败:保留旧数据(首次失败 slots 为空数组,终端提示"未到用餐时间",后端兜底拒绝核销) */
      } finally {
        loaded = true
        loadingPromise = null
        // 首次加载后启动定时刷新(仅启动一次),管理员修改时段后 10 分钟内感知
        if (refreshTimer === null) {
          refreshTimer = setInterval(() => loadMealSlots(true), REFRESH_INTERVAL)
        }
      }
      return slots.value
    })()
    return loadingPromise
  }

  /** 按 mealType 查时段配置 */
  const getSlotByMealType = (mealType: number): MealTimeSlot | null => {
    return slots.value.find(s => s.mealType === mealType) || null
  }

  /**
   * 判断当前时间是否在指定餐次的就餐时段内。
   * @param mealType 餐次 1/2/3
   * @param now 当前时间(可选,默认 new Date())
   * @returns true=在时段内可核销;false=未到/已过/未配置
   */
  const isWithinDiningTime = (mealType: number, now: Date = new Date()): boolean => {
    const slot = getSlotByMealType(mealType)
    if (!slot) return false
    const nowMin = now.getHours() * 60 + now.getMinutes()
    const startMin = timeToMinutes(slot.startTime)
    const endMin = timeToMinutes(slot.endTime)
    return nowMin >= startMin && nowMin <= endMin
  }

  /**
   * 识别当前时间所属的餐次(用于"现在只能核销哪个餐次")。
   * @returns 命中时段的 mealType;空档期或未配置返回 null
   */
  const getCurrentMealType = (now: Date = new Date()): number | null => {
    const nowMin = now.getHours() * 60 + now.getMinutes()
    for (const slot of slots.value) {
      const startMin = timeToMinutes(slot.startTime)
      const endMin = timeToMinutes(slot.endTime)
      if (nowMin >= startMin && nowMin <= endMin) {
        return slot.mealType
      }
    }
    return null
  }

  /** 重置缓存(切换门店/重新绑定终端时调用) */
  const reset = () => {
    slots.value = []
    loaded = false
    loadingPromise = null
    if (refreshTimer !== null) {
      clearInterval(refreshTimer)
      refreshTimer = null
    }
  }

  return {
    slots,
    loadMealSlots,
    getSlotByMealType,
    isWithinDiningTime,
    getCurrentMealType,
    reset,
  }
}
