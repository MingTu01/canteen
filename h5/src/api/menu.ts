import { get } from './index'
import type { MenuWithItems, DishCategory, DiningTimeSlot } from './types'

/**
 * 菜单 / 菜品 / 分类 / 就餐时段 API
 * 后端:MenuController / DishController / DishCategoryController / DiningTimeSlotController
 */

/** 查询门店指定日期的已发布菜单(含菜品详情)— 点菜端只看已发布 */
export function getMenuByDate(storeId: number, date: string): Promise<MenuWithItems[]> {
  return get<MenuWithItems[]>(`/menu/store/${storeId}/date/${date}`, {
    params: { published: 1 },
  })
}

/** 查询门店某月已发布菜单的日期列表(用于月历标记) */
export function getMenuDates(storeId: number, year: number, month: number): Promise<{ date: string; published: boolean }[]> {
  return get<{ date: string; published: boolean }[]>(`/menu/store/${storeId}/dates`, {
    params: { year, month },
  })
}

/** 查询门店菜品分类列表 */
export function getCategories(storeId: number): Promise<DishCategory[]> {
  return get<DishCategory[]>(`/dish-category/store/${storeId}`)
}

/** 查询门店就餐时段(早餐/午餐/晚餐的起止时间) */
export function getDiningTimes(storeId: number): Promise<DiningTimeSlot[]> {
  return get<DiningTimeSlot[]>(`/timer/store/${storeId}`)
}
