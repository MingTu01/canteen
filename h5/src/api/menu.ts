import { get } from './index'
import type { MenuWithItems, Dish, DishCategory, DiningTimeSlot, PageResult } from './types'

/**
 * 菜单 / 菜品 / 分类 / 就餐时段 API
 * 后端:MenuController / DishController / DishCategoryController / DiningTimeSlotController
 */

/** 查询门店指定日期的所有餐次菜单(含菜品详情) */
export function getMenuByDate(storeId: number, date: string): Promise<MenuWithItems[]> {
  return get<MenuWithItems[]>(`/menu/store/${storeId}/date/${date}`)
}

/** 查询门店某月已配置菜单的日期列表(用于月历标记) */
export function getMenuDates(storeId: number, year: number, month: number): Promise<string[]> {
  return get<string[]>(`/menu/store/${storeId}/dates`, {
    params: { year, month },
  })
}

/** 查询门店新品菜品(分页) */
export function getNewDishes(
  storeId: number,
  params?: { page?: number; size?: number },
): Promise<PageResult<Dish>> {
  return get<PageResult<Dish>>(`/dish/store/${storeId}/new`, { params })
}

/** 查询门店菜品分类列表 */
export function getCategories(storeId: number): Promise<DishCategory[]> {
  return get<DishCategory[]>(`/dish-category/store/${storeId}`)
}

/** 查询门店就餐时段(早餐/午餐/晚餐的起止时间) */
export function getDiningTimes(storeId: number): Promise<DiningTimeSlot[]> {
  return get<DiningTimeSlot[]>(`/timer/store/${storeId}`)
}
