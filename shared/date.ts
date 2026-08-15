/**
 * 日期与时间工具函数(纯函数,本地时区,无副作用,无响应式依赖)
 *
 * admin-web 与 h5 共享,勿在两端各自复制修改。
 * 由两端原 utils/date.ts 合并而成:
 *   - 共同函数:formatDateStr / addDays / compareDate(取 admin-web 的 pad2 实现)
 *   - admin-web 独有:todayStr / monthStr / monthStartStr
 *   - h5 独有:parseTimeToMinutes / nowMinutes / toChineseDate / weekdayLabel /
 *     relativeDateLabel / numericDateLabel / formatGroupDate
 */

/** 数字补零到 2 位 */
const pad2 = (n: number): string => String(n).padStart(2, '0')

/**
 * 格式化 Date 为 yyyy-MM-dd(本地时区,避免 UTC 偏移)
 * @param d 日期对象
 * @returns 形如 "2026-07-26"
 */
export const formatDateStr = (d: Date): string => {
  return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`
}

/**
 * 今天日期字符串(本地时区)
 * @returns yyyy-MM-dd
 */
export const todayStr = (): string => formatDateStr(new Date())

/**
 * 本月月份字符串
 * @returns yyyy-MM
 */
export const monthStr = (): string => {
  const d = new Date()
  return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}`
}

/**
 * 本月起始日期(本月 1 号)
 * @returns yyyy-MM-01
 */
export const monthStartStr = (): string => `${monthStr()}-01`

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
 * 比较两个 yyyy-MM-dd 字符串
 * @param a 日期字符串
 * @param b 日期字符串
 * @returns a < b 返回 -1,a > b 返回 1,相等返回 0
 */
export const compareDate = (a: string, b: string): number => (a < b ? -1 : a > b ? 1 : 0)

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
