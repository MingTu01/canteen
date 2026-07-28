/**
 * 餐别配色与图标配置(composable)
 *
 * 拆分自 Order.vue / Orders.vue,消除两个文件中的餐别配色重复代码。
 * 颜色规范:早餐橙、午餐绿、晚餐紫(用于胶囊 + 图标 + badge + 日期栏圆点)
 *
 * 用法:
 *   import { useMealConfig } from '@/composables/useMealConfig'
 *   const { mealColorMap, mealPillStyle, mealBadgeStyle, mealIconColor, mealDotColor, mealIconMap } = useMealConfig()
 */

import { Sunrise, Sun, Sunset } from 'lucide-vue-next'
import type { Component } from 'vue'

/** 餐别颜色(rgb 字符串用于 rgba,text 用于直接 color) */
export interface MealColor {
  rgb: string
  text: string
}

/** 餐别编号 → 颜色配置 */
export const MEAL_COLORS: Record<number, MealColor> = {
  1: { rgb: '255, 107, 53', text: '#ff6b35' }, // 早餐 - 橙色
  2: { rgb: '16, 185, 129', text: '#10b981' }, // 午餐 - 绿色(区别于主题蓝)
  3: { rgb: '124, 58, 237', text: '#7c3aed' }, // 晚餐 - 紫色
}

/** 餐别图标映射(1早 Sunrise / 2午 Sun / 3晚 Sunset) */
export const MEAL_ICONS: Record<number, Component> = {
  1: Sunrise,
  2: Sun,
  3: Sunset,
}

/** 获取餐别的颜色配置(无匹配时回退到早餐色) */
export const getMealColor = (mealType: number): MealColor => {
  return MEAL_COLORS[mealType] || MEAL_COLORS[1]
}

/** 获取餐别图标的颜色 */
export const mealIconColor = (mealType: number): string => {
  return getMealColor(mealType).text
}

/** 获取餐别圆点颜色(日期竖列用,与菜单页胶囊一致) */
export const mealDotColor = (mealType: number): string => {
  return getMealColor(mealType).text
}

/** 获取餐别胶囊内联样式(菜单页餐别标题用) */
export const mealPillStyle = (mealType: number): Record<string, string> => {
  const c = getMealColor(mealType)
  return {
    background: `linear-gradient(135deg, rgba(${c.rgb}, 0.12), rgba(${c.rgb}, 0.22))`,
    color: c.text,
    borderColor: `rgba(${c.rgb}, 0.3)`,
    boxShadow: `0 2px 8px rgba(${c.rgb}, 0.15)`,
  }
}

/** 获取餐别 badge 的内联样式(订单列表与确认弹窗用) */
export const mealBadgeStyle = (mealType: number): Record<string, string> => {
  const c = getMealColor(mealType)
  return {
    background: `linear-gradient(135deg, rgba(${c.rgb}, 0.15), rgba(${c.rgb}, 0.25))`,
    color: c.text,
    borderColor: `rgba(${c.rgb}, 0.3)`,
  }
}

/**
 * 餐别配置 composable
 * 返回所有餐别相关的颜色、图标、样式函数。
 * 实际为纯常量,用 composable 形式包装以保持调用约定一致。
 */
export const useMealConfig = () => {
  return {
    mealColorMap: MEAL_COLORS,
    mealIconMap: MEAL_ICONS,
    mealPillStyle,
    mealBadgeStyle,
    mealIconColor,
    mealDotColor,
    getMealColor,
  }
}
