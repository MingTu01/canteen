import { ref } from 'vue'
import api from '@/api'

export interface OrderConfig {
  order_advance_days: number
  order_deadline_time: string  // "15:00"
  cancel_deadline_time: string // "15:00"
  max_order_quantity: number
  allow_cross_day_order: boolean
  /** 未订餐用餐手续费开关/金额(按餐别) */
  unsolicited_fee_enabled: boolean
  unsolicited_fee_breakfast: number
  unsolicited_fee_lunch: number
  unsolicited_fee_dinner: number
}

const config = ref<OrderConfig>({
  order_advance_days: 7,
  order_deadline_time: '15:00',
  cancel_deadline_time: '15:00',
  max_order_quantity: 10,
  allow_cross_day_order: true,
  unsolicited_fee_enabled: false,
  unsolicited_fee_breakfast: 0,
  unsolicited_fee_lunch: 0,
  unsolicited_fee_dinner: 0,
})
let loaded = false
let loadedStoreId: number | null = null

/**
 * 强制重新加载配置(忽略缓存)。
 * 供 onActivated 等场景调用,确保管理员修改配置后前端能感知。
 */
export function resetOrderConfigCache() {
  loaded = false
  loadedStoreId = null
}

/** 解析手续费金额:非法/负数回退 0 */
function parseFeeValue(v: unknown): number {
  const n = Number(v)
  return v != null && v !== '' && !isNaN(n) && n > 0 ? n : 0
}

export function useOrderConfig() {
  const loadConfig = async (storeId?: number | null, force = false) => {
    // 门店切换后或强制刷新时重新加载
    if (!force && loaded && loadedStoreId === (storeId ?? null)) return config.value
    try {
      const params = storeId ? { storeId } : undefined
      // H5 axios 拦截器在 code===200 时已解包返回 body.data(即配置对象本身),
      // 运行时 d 已是配置对象,但 TS 类型仍是 AxiosResponse,用 as any 断言绕过
      const d = await api.get('/system/order-config', { params }) as any
      if (d && typeof d === 'object') {
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
          // 未订餐用餐手续费("true"/"false" 转 boolean,金额转 number)
          unsolicited_fee_enabled: d.unsolicited_fee_enabled === 'true' || d.unsolicited_fee_enabled === true,
          unsolicited_fee_breakfast: parseFeeValue(d.unsolicited_fee_breakfast),
          unsolicited_fee_lunch: parseFeeValue(d.unsolicited_fee_lunch),
          unsolicited_fee_dinner: parseFeeValue(d.unsolicited_fee_dinner),
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
   * 业务规则:订单日期 X 的截止时间是 (X-1) 的 deadline_time,即"前一天 deadline 前可订"。
   * - 今天及之前:截止时间(昨天 deadline)已过 → 不可订
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
    if (diffDays === 1) {
      // 明天:截止时间是今天 deadline
      return nowMinutes < deadlineMinutes
    }
    // 后天及以后:受提前天数限制(0=不限制)
    if (config.value.order_advance_days <= 0) return true
    return diffDays <= config.value.order_advance_days
  }

  /**
   * 判断指定日期的订单是否仍可取消。
   * - 今天及之前:截止时间已过 → 不可取消
   * - 明天:截止时间是今天 deadline → deadline 前可取消
   * - 后天及以后:可取消
   */
  const isCancellableByDeadline = (orderDate: string, now: Date): boolean => {
    const order = new Date(orderDate + 'T00:00:00')
    const today = new Date(now.getFullYear(), now.getMonth(), now.getDate())
    const diffDays = Math.round((order.getTime() - today.getTime()) / 86400000)
    if (diffDays <= 0) return false // 今天及之前:截止时间已过
    const deadlineMinutes = parseTimeToMinutes(config.value.cancel_deadline_time)
    const nowMinutes = now.getHours() * 60 + now.getMinutes()
    if (diffDays === 1) {
      // 明天:截止时间是今天 deadline
      return nowMinutes < deadlineMinutes
    }
    return true // 后天及以后:可取消
  }

  return { config, loadConfig, isOrderableByDeadline, isCancellableByDeadline, parseTimeToMinutes }
}
