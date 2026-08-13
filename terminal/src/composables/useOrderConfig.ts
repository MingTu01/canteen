import { ref } from 'vue'
import api from '@/api'

/**
 * 订餐截止配置 composable(对齐后端 GET /api/system/order-config)。
 *
 * 通过模块级单例 ref + loaded 标志位缓存配置,整页只拉取一次。
 * 默认值与后端 checkAdvanceOrderDeadline 旧逻辑一致(15:00 截止、提前 7 天)。
 */

export interface OrderConfig {
  order_advance_days: number
  order_deadline_time: string
  cancel_deadline_time: string
  max_order_quantity: number
  allow_cross_day_order: boolean
}

const config = ref<OrderConfig>({
  order_advance_days: 7,
  order_deadline_time: '15:00',
  cancel_deadline_time: '15:00',
  max_order_quantity: 10,
  allow_cross_day_order: true,
})
let loaded = false
let loadedStoreId: number | null = null

/**
 * 强制重新加载配置(忽略缓存)。
 */
export function resetOrderConfigCache() {
  loaded = false
  loadedStoreId = null
}

export function useOrderConfig() {
  const loadConfig = async (storeId?: number | null, force = false) => {
    // 门店切换后或强制刷新时重新加载
    if (!force && loaded && loadedStoreId === (storeId ?? null)) return config.value
    try {
      const params = storeId ? { storeId } : undefined
      const res = await api.get('/system/order-config', { params })
      if (res.data?.data) {
        const d = res.data.data
        config.value = {
          // 0=不限制提前天数;负数或异常回退默认 7
          order_advance_days: d.order_advance_days != null && d.order_advance_days !== ''
            ? (parseInt(d.order_advance_days) || 0)
            : 7,
          order_deadline_time: d.order_deadline_time || '15:00',
          cancel_deadline_time: d.cancel_deadline_time || '15:00',
          // 0=不限制单次最大订餐数
          max_order_quantity: d.max_order_quantity != null && d.max_order_quantity !== ''
            ? (parseInt(d.max_order_quantity) || 0)
            : 10,
          allow_cross_day_order: d.allow_cross_day_order === 'true',
        }
        loaded = true
        loadedStoreId = storeId ?? null
      }
    } catch {
      /* 使用默认值 */
    }
    return config.value
  }

  const parseTimeToMinutes = (time: string): number => {
    const [h, m] = time.split(':').map(Number)
    return h * 60 + m
  }

  /**
   * 判断指定日期是否仍可订餐(对齐 H5 逻辑)。
   * - 今天及之前:截止时间已过 → 不可订
   * - 明天:截止时间是今天 deadline → deadline 前可订
   * - 后天及以后:
   *   - order_advance_days > 0:在提前天数范围内可订
   *   - order_advance_days <= 0(0=不限制):可订
   */
  const isOrderableByDeadline = (orderDate: string, now: Date): boolean => {
    const order = new Date(orderDate + 'T00:00:00')
    const today = new Date(now.getFullYear(), now.getMonth(), now.getDate())
    const diffDays = Math.round((order.getTime() - today.getTime()) / 86400000)
    if (diffDays <= 0) return false // 今天及之前:截止时间已过
    const deadlineMinutes = parseTimeToMinutes(config.value.order_deadline_time)
    const nowMinutes = now.getHours() * 60 + now.getMinutes()
    if (diffDays === 1) return nowMinutes < deadlineMinutes
    // 后天及以后:受提前天数限制(0=不限制)
    if (config.value.order_advance_days <= 0) return true
    return diffDays <= config.value.order_advance_days
  }

  const isCancellableByDeadline = (orderDate: string, now: Date): boolean => {
    const order = new Date(orderDate + 'T00:00:00')
    const today = new Date(now.getFullYear(), now.getMonth(), now.getDate())
    const diffDays = Math.round((order.getTime() - today.getTime()) / 86400000)
    if (diffDays <= 0) return false
    const deadlineMinutes = parseTimeToMinutes(config.value.cancel_deadline_time)
    const nowMinutes = now.getHours() * 60 + now.getMinutes()
    if (diffDays === 1) return nowMinutes < deadlineMinutes
    return true
  }

  return { config, loadConfig, isOrderableByDeadline, isCancellableByDeadline, parseTimeToMinutes }
}
