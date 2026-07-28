/**
 * 日期与时间工具函数(纯函数,无副作用,无响应式依赖)
 *
 * 拆分自 Order.vue / Orders.vue,消除两个文件中的日期工具重复代码。
 * 所有函数均为纯函数,可在任意组件中复用。
 */

/**
 * 格式化 Date 为 yyyy-MM-dd(本地时区,避免 UTC 偏移)
 * @param d 日期对象
 * @returns 形如 "2026-07-26"
 */
export const formatDateStr = (d: Date): string => {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

/**
 * 解析 "HH:mm:ss" 或 "HH:mm" 为分钟数,便于比较
 * @param t 时间字符串,如 "12:30:00"
 * @returns 分钟数(0-1439);空值返回 -1
 */
export const parseTimeToMinutes = (t?: string): number => {
  if (!t) return -1
  const [h, m] = t.split(':').map(Number)
  return (h || 0) * 60 + (m || 0)
}

/**
 * 当前时间的分钟数(基于本地时区)
 * @returns 分钟数(0-1439)
 */
export const nowMinutes = (): number => {
  const n = new Date()
  return n.getHours() * 60 + n.getMinutes()
}

/**
 * 比较两个 yyyy-MM-dd 字符串
 * @param a 日期字符串
 * @param b 日期字符串
 * @returns a < b 返回 -1,a > b 返回 1,相等返回 0
 */
export const compareDate = (a: string, b: string): number => (a < b ? -1 : a > b ? 1 : 0)

/**
 * 计算指定日期加 n 天后的 yyyy-MM-dd
 * @param dateStr 起始日期 yyyy-MM-dd
 * @param n 天数(可为负数)
 * @returns 新日期 yyyy-MM-dd
 */
export const addDays = (dateStr: string, n: number): string => {
  const d = new Date(`${dateStr}T00:00:00`)
  d.setDate(d.getDate() + n)
  return formatDateStr(d)
}

/**
 * 转换为中文完整日期:2026-07-16 → "2026年7月16日"
 * @param dateStr yyyy-MM-dd 字符串
 * @returns 中文完整日期;无效输入返回原字符串
 */
export const toChineseDate = (dateStr: string): string => {
  if (!dateStr) return ''
  const d = /^\d{4}-\d{2}-\d{2}$/.test(dateStr)
    ? new Date(`${dateStr}T00:00:00`)
    : new Date(dateStr)
  if (Number.isNaN(d.getTime())) return dateStr
  return `${d.getFullYear()}年${d.getMonth() + 1}月${d.getDate()}日`
}

/**
 * 周几中文标签
 * @param dateStr yyyy-MM-dd 字符串
 * @returns "周日" / "周一" / ... / "周六";无效输入返回空字符串
 */
export const weekdayLabel = (dateStr: string): string => {
  const d = new Date(`${dateStr}T00:00:00`)
  if (Number.isNaN(d.getTime())) return ''
  const weekdays = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']
  return weekdays[d.getDay()]
}

/**
 * 相对日期标签:今天 / 明天 / 后天 / 周X
 * 不包含数字日期,仅返回相对标签。
 * @param dateStr yyyy-MM-dd 字符串
 * @returns "今天" / "明天" / "后天" / "周一" 等
 */
export const relativeDateLabel = (dateStr: string): string => {
  const today = formatDateStr(new Date())
  if (dateStr === today) return '今天'
  if (dateStr === addDays(today, 1)) return '明天'
  if (dateStr === addDays(today, 2)) return '后天'
  return weekdayLabel(dateStr)
}

/**
 * 数字日期标签:"7月26日"
 * @param dateStr yyyy-MM-dd 字符串
 * @returns "M月d日";无效输入返回空字符串
 */
export const numericDateLabel = (dateStr: string): string => {
  const d = new Date(`${dateStr}T00:00:00`)
  if (Number.isNaN(d.getTime())) return ''
  return `${d.getMonth() + 1}月${d.getDate()}日`
}

/**
 * 分组日期标签:数字日期 + 相对标签(用于购物车弹层、订单分组)
 * 示例:"7月26日 · 今天" / "7月28日 · 周二"
 * @param dateStr yyyy-MM-dd 字符串
 * @returns "M月d日 · 相对标签"
 */
export const formatGroupDate = (dateStr: string): string => {
  const d = new Date(`${dateStr}T00:00:00`)
  if (Number.isNaN(d.getTime())) return dateStr
  return `${numericDateLabel(dateStr)} · ${relativeDateLabel(dateStr)}`
}
