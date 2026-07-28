/**
 * 日期工具函数(纯函数,本地时区,无副作用)
 *
 * 拆分自多个视图文件(Report/Settlement/DailyClose/Recharge/Menu/OrderSummary),
 * 统一实现避免不同页面 todayStr 返回不一致(OrderSummary 之前用 UTC,凌晨会差一天)。
 *
 * 所有函数基于本地时区,与 H5 端 utils/date 的命名保持一致以便交叉复用。
 */

/** 数字补零到 2 位 */
const pad2 = (n: number): string => String(n).padStart(2, '0')

/**
 * 格式化 Date 为 yyyy-MM-dd(本地时区)
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
 * @returns a < b 返回 -1,a > b 返回 1,相等返回 0
 */
export const compareDate = (a: string, b: string): number => (a < b ? -1 : a > b ? 1 : 0)
