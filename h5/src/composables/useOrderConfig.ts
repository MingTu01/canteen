import { ref } from 'vue'
import api from '@/api'

export interface OrderConfig {
  order_advance_days: number
  order_deadline_time: string  // "15:00"
  cancel_deadline_time: string // "15:00"
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

export function useOrderConfig() {
  const loadConfig = async (storeId?: number | null) => {
    // 门店切换后重新加载
    if (loaded && loadedStoreId === (storeId ?? null)) return config.value
    try {
      const params = storeId ? { storeId } : undefined
      const res = await api.get('/system/order-config', { params })
      if (res.data?.data) {
        const d = res.data.data
        config.value = {
          order_advance_days: parseInt(d.order_advance_days) || 7,
          order_deadline_time: d.order_deadline_time || '15:00',
          cancel_deadline_time: d.cancel_deadline_time || '15:00',
          max_order_quantity: parseInt(d.max_order_quantity) || 10,
          allow_cross_day_order: d.allow_cross_day_order === 'true',
        }
        loaded = true
        loadedStoreId = storeId ?? null
      }
    } catch {
      // use defaults
    }
    return config.value
  }

  /** Parse "HH:mm" to minutes */
  const parseTimeToMinutes = (time: string): number => {
    const [h, m] = time.split(':').map(Number)
    return h * 60 + m
  }

  /**
   * 判断指定日期是否仍可订餐。
   * 业务规则:订单日期 X 的截止时间是 (X-1) 15:00,即"前一天 15:00 前可订"。
   * - 今天及之前:截止时间(昨天15:00)已过 → 不可订
   * - 明天:截止时间是今天15:00 → 今天15:00前可订
   * - 后天及以后:截止时间在未来 → 可订(不限提前天数,只要发布了菜单即可)
   */
  const isOrderableByDeadline = (orderDate: string, now: Date): boolean => {
    const order = new Date(orderDate + 'T00:00:00')
    const today = new Date(now.getFullYear(), now.getMonth(), now.getDate())
    const diffDays = Math.round((order.getTime() - today.getTime()) / 86400000)
    if (diffDays <= 0) return false // 今天及之前:截止时间(昨天15:00)已过
    const deadlineMinutes = parseTimeToMinutes(config.value.order_deadline_time)
    const nowMinutes = now.getHours() * 60 + now.getMinutes()
    if (diffDays === 1) {
      // 明天:截止时间是今天15:00
      return nowMinutes < deadlineMinutes
    }
    // 后天及以后:只要发布了菜单就可订餐,不限制提前天数
    return true
  }

  /**
   * 判断指定日期的订单是否仍可取消。
   * 业务规则:取消截止时间与订餐截止时间一致,前一天 15:00 前可取消。
   * - 今天及之前:截止时间已过 → 不可取消
   * - 明天:截止时间是今天15:00 → 今天15:00前可取消
   * - 后天及以后:截止时间在未来 → 可取消
   */
  const isCancellableByDeadline = (orderDate: string, now: Date): boolean => {
    const order = new Date(orderDate + 'T00:00:00')
    const today = new Date(now.getFullYear(), now.getMonth(), now.getDate())
    const diffDays = Math.round((order.getTime() - today.getTime()) / 86400000)
    if (diffDays <= 0) return false // 今天及之前:截止时间已过
    const deadlineMinutes = parseTimeToMinutes(config.value.cancel_deadline_time)
    const nowMinutes = now.getHours() * 60 + now.getMinutes()
    if (diffDays === 1) {
      // 明天:截止时间是今天15:00
      return nowMinutes < deadlineMinutes
    }
    return true // 后天及以后:可取消
  }

  return { config, loadConfig, isOrderableByDeadline, isCancellableByDeadline, parseTimeToMinutes }
}
