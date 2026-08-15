/**
 * 格式化工具(composable)。
 * 提供金额、日期、餐次、订单状态的格式化函数。
 * 金额与日期的核心实现委托仓库根 shared/(admin-web 与 h5 共享),
 * 本文件仅保留 h5 特有的解析与展示逻辑。
 */

import { formatDateStr } from '../../../shared/date'
import { formatMoney } from '../../../shared/money'

export { formatMoney }

/** 格式化日期:yyyy-MM-dd(支持 ISO 字符串 / Date / 时间戳) */
export function formatDate(dateStr: string | Date | number | null | undefined): string {
  if (!dateStr) return ''
  let d: Date
  if (typeof dateStr === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(dateStr)) {
    // date-only 字符串按本地时间解析,避免被解析为 UTC 午夜导致时区偏移
    d = new Date(`${dateStr}T00:00:00`)
  } else {
    d = new Date(dateStr)
  }
  if (Number.isNaN(d.getTime())) return ''
  return formatDateStr(d)
}

/** 格式化日期时间:yyyy-MM-dd HH:mm */
export function formatDateTime(dateStr: string | Date | number | null | undefined): string {
  if (!dateStr) return ''
  const d = new Date(dateStr)
  if (Number.isNaN(d.getTime())) return ''
  const date = formatDate(d)
  const hh = String(d.getHours()).padStart(2, '0')
  const mm = String(d.getMinutes()).padStart(2, '0')
  return `${date} ${hh}:${mm}`
}

/** 格式化餐次:1→早餐 2→午餐 3→晚餐 */
export function formatMealType(type: number | null | undefined): string {
  switch (type) {
    case 1:
      return '早餐'
    case 2:
      return '午餐'
    case 3:
      return '晚餐'
    default:
      return '未知'
  }
}

/** 格式化餐次(单字):1→早 2→中 3→晚 */
export function formatMealTypeShort(type: number | null | undefined): string {
  switch (type) {
    case 1:
      return '早'
    case 2:
      return '中'
    case 3:
      return '晚'
    default:
      return '?'
  }
}

/** 格式化订单状态:1→待取餐 2→已完成 3→已取消 4→未就餐 */
export function formatOrderStatus(status: number | null | undefined): string {
  switch (status) {
    case 1:
      return '待取餐'
    case 2:
      return '已完成'
    case 3:
      return '已取消'
    case 4:
      return '未就餐'
    default:
      return '未知'
  }
}

/** composable 入口:返回所有格式化方法 */
export function useFormat() {
  return {
    formatMoney,
    formatDate,
    formatDateTime,
    formatMealType,
    formatMealTypeShort,
    formatOrderStatus,
  }
}
