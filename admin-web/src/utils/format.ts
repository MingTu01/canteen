/**
 * 报表专用格式化工具(纯函数)
 *
 * 拆分自 Report.vue,被 YoyReport / MomReport / CongestionReport 等子组件复用。
 */

/**
 * 增长率颜色:正绿、负红、零/无灰
 * @param v 增长率数值(百分比)
 * @returns CSS color 字符串
 */
export const growthColor = (v: number | null | undefined): string => {
  if (v == null) return 'var(--color-text-muted)'
  if (v > 0) return '#10b981'
  if (v < 0) return '#ef4444'
  return 'var(--color-text-muted)'
}

/**
 * 增长率文本:+12.3% / -5.0% / —
 * @param v 增长率数值(百分比)
 * @returns 形如 "+12.3%";null 返回 "—"
 */
export const growthText = (v: number | null | undefined): string => {
  if (v == null) return '—'
  const sign = v > 0 ? '+' : ''
  return `${sign}${v.toFixed(1)}%`
}

/**
 * 格式化小时为 "HH:00"
 * @param h 小时(0-23)
 * @returns 形如 "08:00"
 */
export const fmtHour = (h: number): string => `${String(h).padStart(2, '0')}:00`
