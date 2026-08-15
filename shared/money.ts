/**
 * 金额格式化工具(纯函数)
 *
 * admin-web 与 h5 共享,勿在两端各自复制修改。
 * 由 admin-web utils/money.ts 与 h5 composables/useFormat.ts 的 formatMoney 合并而成,
 * 取更严谨的实现:Number() 全量解析 + Number.isFinite 兜底
 * (h5 旧版 parseFloat 会把 "12abc" 静默截断为 12,合并后统一拒绝为 0.00)。
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
 * 格式化金额为带 ¥ 前缀的字符串(语义化命名)
 * @param val 数值,可为 null/undefined
 * @returns 形如 "¥1234.50";NaN/空值返回 "¥0.00"
 */
export const formatMoneyWithSymbol = (val: number | string | null | undefined): string =>
  `¥${formatMoney(val)}`

/**
 * 格式化金额为带 ¥ 前缀的字符串
 * admin-web 历史函数名,保留导出以兼容既有调用,与 formatMoneyWithSymbol 等价。
 */
export const money = (val: number | string | null | undefined): string => formatMoneyWithSymbol(val)
