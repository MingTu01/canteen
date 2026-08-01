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

export function useOrderConfig() {
  const loadConfig = async () => {
    if (loaded) return config.value
    try {
      const res = await api.get('/system/order-config')
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

  const isOrderableByDeadline = (orderDate: string, now: Date): boolean => {
    const order = new Date(orderDate + 'T00:00:00')
    const today = new Date(now.getFullYear(), now.getMonth(), now.getDate())
    const diffDays = Math.round((order.getTime() - today.getTime()) / 86400000)
    if (diffDays <= 0) return true
    const deadlineMinutes = parseTimeToMinutes(config.value.order_deadline_time)
    const nowMinutes = now.getHours() * 60 + now.getMinutes()
    if (diffDays === 1) return nowMinutes < deadlineMinutes
    return diffDays <= config.value.order_advance_days
  }

  const isCancellableByDeadline = (orderDate: string, now: Date): boolean => {
    const order = new Date(orderDate + 'T00:00:00')
    const today = new Date(now.getFullYear(), now.getMonth(), now.getDate())
    const diffDays = Math.round((order.getTime() - today.getTime()) / 86400000)
    if (diffDays <= 0) return true
    const deadlineMinutes = parseTimeToMinutes(config.value.cancel_deadline_time)
    const nowMinutes = now.getHours() * 60 + now.getMinutes()
    if (diffDays === 1) return nowMinutes < deadlineMinutes
    return true
  }

  return { config, loadConfig, isOrderableByDeadline, isCancellableByDeadline, parseTimeToMinutes }
}
