/**
 * 金额格式化工具(纯函数)
 *
 * 拆分自多个视图文件(Report/Settlement/DailyClose/Recharge/OrderSummary/Employee/GroupOrder),
 * 统一两个常用形式:
 *   - formatMoney(val):纯数字字符串 "1234.50"(不带 ¥)
 *   - money(val):带 ¥ 前缀 "¥1234.50"
 *
 * 旧实现散落各文件,签名与容错不一致:
 *   - money(n: number)        未做 null 兜底 → NaN 风险(Report/Recharge)
 *   - money(n?: number|null)  做了兜底(Settlement/DailyClose)
 *   - formatMoney(val)        做了兜底(Employee/GroupOrder)
 *   - formatPrice(v: unknown) 做了兜底(OrderSummary)
 * 统一为接受 number | null | undefined,内部 Number(... ?? 0)。
 */

/**
 * 格式化金额为两位小数字符串(不含货币符号)
 * @param val 数值,可为 null/undefined
 * @returns 形如 "1234.50";NaN/空值返回 "0.00"
 */
export const formatMoney = (val: number | string | null | undefined): string => {
  const n = Number(val ?? 0)
  return Number.isFinite(n) ? n.toFixed(2) : '0.00'
}

/**
 * 格式化金额为带 ¥ 前缀的字符串
 * @param val 数值,可为 null/undefined
 * @returns 形如 "¥1234.50";NaN/空值返回 "¥0.00"
 */
export const money = (val: number | string | null | undefined): string => `¥${formatMoney(val)}`
