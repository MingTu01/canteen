/**
 * 终端工具函数(纯函数)
 *
 * 注意:餐次配色/图标已迁移到 composables/useMealConfig.ts,
 * 金额/日期格式化已迁移到 composables/useFormat.ts。
 * 本文件仅保留终端特有的日期/卡片工具函数。
 */

import type { Component } from 'vue'
import { Sunrise, Sun, Sunset, Moon, Beef, Leaf, Salad, Soup, Wheat, Coffee, Utensils } from 'lucide-vue-next'

/** 餐别中文标签(对齐 H5 formatMealType,保留以兼容老代码) */
export function mealTypeLabel(t: number): string {
  return ({ 1: '早餐', 2: '午餐', 3: '晚餐' } as Record<number, string>)[t] || '未知'
}

/**
 * 餐别对应时段文字(取餐信息页 "午餐 · 12:00-13:00 时段")。
 *
 * 优先使用从后端拉取的真实就餐时段(admin-web 时间管理配置);
 * 若 slots 为空(接口未加载/失败),降级到写死的默认时段,保证页面不空白。
 *
 * @param t 餐次 1/2/3
 * @param slots 从 useMealTimeSlots 拉取的真实时段列表(可选)
 */
export function mealTypeTime(t: number, slots?: { mealType: number; startTime: string; endTime: string }[] | null): string {
  if (slots && slots.length > 0) {
    const slot = slots.find(s => s.mealType === t)
    if (slot && slot.startTime && slot.endTime) {
      // "07:00:00" -> "07:00"
      const fmt = (s: string) => {
        const parts = s.split(':')
        return `${parts[0]}:${parts[1] || '00'}`
      }
      return `${fmt(slot.startTime)}-${fmt(slot.endTime)}`
    }
  }
  // 降级:写死默认时段(仅在后端时段未加载时兜底,避免页面空白)
  return ({ 1: '08:00-09:00', 2: '12:00-13:00', 3: '18:00-19:00' } as Record<number, string>)[t] || '12:00-13:00'
}

/** 选菜页餐别图标:早 sunrise / 午 sun / 晚 sunset */
export function selectMealIcon(t: number): Component {
  const m: Record<number, Component> = { 1: Sunrise, 2: Sun, 3: Sunset }
  return m[t] || Moon
}

/** 订单查询页餐别图标:早 sunrise / 午 sun / 晚 moon */
export function queryMealIcon(t: number): Component {
  const m: Record<number, Component> = { 1: Sunrise, 2: Sun, 3: Moon }
  return m[t] || Moon
}

/** 按 category 映射菜品占位图标 */
export function dishIcon(category: string): Component {
  const c = (category || '').trim()
  if (c.includes('荤')) return Beef
  if (c.includes('素')) return Leaf
  if (c.includes('凉')) return Salad
  if (c.includes('汤')) return Soup
  if (c.includes('主') || c.includes('饭') || c.includes('面') || c.includes('食')) return Wheat
  if (c.includes('饮')) return Coffee
  return Utensils
}

const WEEK = ['星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六']

export function pad2(n: number): string {
  return String(n).padStart(2, '0')
}

/** Date -> yyyy-MM-dd */
export function toDateKey(d: Date): string {
  return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`
}

/** yyyy-MM-dd -> Date(本地) */
export function parseDateKey(s: string): Date {
  const [y, m, d] = s.split('-').map(Number)
  return new Date(y, (m || 1) - 1, d || 1)
}

/** yyyy-MM-dd -> "7月16日" */
export function shortDate(s: string): string {
  const d = parseDateKey(s)
  return `${d.getMonth() + 1}月${d.getDate()}日`
}

/** Date -> "2026年7月16日 星期三" */
export function fullDateLabel(d: Date): string {
  return `${d.getFullYear()}年${d.getMonth() + 1}月${d.getDate()}日 ${WEEK[d.getDay()]}`
}

/** 相对今天:今天/明天/昨天,否则空串 */
export function relativeLabel(s: string): string {
  const today = toDateKey(new Date())
  const tom = toDateKey(new Date(Date.now() + 86400000))
  const yes = toDateKey(new Date(Date.now() - 86400000))
  if (s === today) return '今天'
  if (s === tom) return '明天'
  if (s === yes) return '昨天'
  return ''
}

/** 从 startKey 起连续 count 天;direction 1=未来, -1=过去 */
export function dateWindow(startKey: string, count: number, direction: 1 | -1): string[] {
  const start = parseDateKey(startKey)
  const arr: string[] = []
  for (let i = 0; i < count; i++) {
    const d = new Date(start)
    d.setDate(start.getDate() + i * direction)
    arr.push(toDateKey(d))
  }
  return arr
}

/** dateKey 偏移 days 天 */
export function shiftKey(key: string, days: number): string {
  const d = parseDateKey(key)
  d.setDate(d.getDate() + days)
  return toDateKey(d)
}
