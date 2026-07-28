import { defineStore } from 'pinia'
import { reactive, ref, computed } from 'vue'
import { showToast } from 'vant'
import type { Dish } from '@/api/types'

/**
 * 购物车 Store(跨日期 + 按餐次分组)。
 *
 * 数据结构:
 * - items: Map<groupKey, Map<dishId, CartEntry>>
 *   groupKey = `${date}|${mealType}`,内层按菜品 ID 索引,值为 { dish, quantity }。
 * - selectedDate: 当前查看的菜单日期(用于左侧日期栏高亮 + 右侧菜单加载),不再绑定购物车内容。
 *
 * 跨日期订餐:切换 selectedDate 不清空购物车,下单时遍历所有 group 依次提交。
 *
 * 不持久化:每次打开 H5 重新选菜。
 */

export interface CartEntry {
  dish: Dish
  quantity: number
}

/** 购物车分组(用于下单与弹层渲染) */
export interface CartGroup {
  date: string
  mealType: number
  entries: CartEntry[]
  subtotal: number
}

/** 格式化日期为 yyyy-MM-dd(本地时区,避免 UTC 偏移) */
const formatDate = (d: Date): string => {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

/** 构建 groupKey */
const buildKey = (date: string, mealType: number): string => `${date}|${mealType}`

export const useCartStore = defineStore('cart', () => {
  // 外层按 groupKey(日期+餐别)分组,内层按 dishId 索引
  const items = reactive(new Map<string, Map<number, CartEntry>>())
  // 当前查看的菜单日期(不再绑定购物车内容)
  const selectedDate = ref<string>(formatDate(new Date()))

  // ============ getters ============
  /** 购物车总数量(所有日期所有餐次) */
  const totalCount = computed<number>(() => {
    let sum = 0
    for (const dishMap of items.values()) {
      for (const entry of dishMap.values()) {
        sum += entry.quantity
      }
    }
    return sum
  })

  /** 购物车是否有商品 */
  const hasItems = computed<boolean>(() => totalCount.value > 0)

  /** 指定日期+餐次的小计 */
  const subtotalByGroup = (date: string, mealType: number): number => {
    const dishMap = items.get(buildKey(date, mealType))
    if (!dishMap) return 0
    let sum = 0
    for (const entry of dishMap.values()) {
      sum += entry.dish.price * entry.quantity
    }
    return Math.round(sum * 100) / 100
  }

  /** 所有分组总价(跨日期) */
  const totalPrice = computed<number>(() => {
    let sum = 0
    for (const key of items.keys()) {
      const [date, mtStr] = key.split('|')
      sum += subtotalByGroup(date, Number(mtStr))
    }
    return Math.round(sum * 100) / 100
  })

  /** 获取所有分组(按日期升序、餐别升序) */
  const getAllGroups = (): CartGroup[] => {
    const groups: CartGroup[] = []
    for (const [key, dishMap] of items.entries()) {
      const [date, mtStr] = key.split('|')
      const mealType = Number(mtStr)
      const entries = Array.from(dishMap.values())
      if (entries.length === 0) continue
      groups.push({
        date,
        mealType,
        entries,
        subtotal: subtotalByGroup(date, mealType),
      })
    }
    // 按日期升序,同日期按餐别升序
    groups.sort((a, b) => {
      if (a.date !== b.date) return a.date < b.date ? -1 : 1
      return a.mealType - b.mealType
    })
    return groups
  }

  // ============ actions ============
  /** 获取指定日期+餐次的内层 Map(不存在则创建) */
  const ensureDishMap = (date: string, mealType: number): Map<number, CartEntry> => {
    const key = buildKey(date, mealType)
    let dishMap = items.get(key)
    if (!dishMap) {
      dishMap = new Map<number, CartEntry>()
      items.set(key, dishMap)
    }
    return dishMap
  }

  /** 添加菜品到指定日期+餐次(数量 +1),含单次限购校验 */
  const addItem = (dish: Dish, date: string, mealType: number): void => {
    // 校验单次限购(maxPerOrder > 0 时生效)
    if (dish.maxPerOrder && dish.maxPerOrder > 0) {
      const currentQty = getQuantity(dish.id, date, mealType)
      if (currentQty >= dish.maxPerOrder) {
        showToast(`超过单次限购${dish.maxPerOrder}份`)
        return
      }
    }
    const dishMap = ensureDishMap(date, mealType)
    const existing = dishMap.get(dish.id)
    if (existing) {
      existing.quantity += 1
    } else {
      dishMap.set(dish.id, { dish, quantity: 1 })
    }
  }

  /** 完全移除指定日期+餐次的某菜品 */
  const removeItem = (dishId: number, date: string, mealType: number): void => {
    const key = buildKey(date, mealType)
    const dishMap = items.get(key)
    if (!dishMap) return
    dishMap.delete(dishId)
    if (dishMap.size === 0) {
      items.delete(key)
    }
  }

  /** 减少指定日期+餐次某菜品数量(减到 0 则移除) */
  const decreaseItem = (dishId: number, date: string, mealType: number): void => {
    const key = buildKey(date, mealType)
    const dishMap = items.get(key)
    if (!dishMap) return
    const existing = dishMap.get(dishId)
    if (!existing) return
    if (existing.quantity <= 1) {
      dishMap.delete(dishId)
      if (dishMap.size === 0) {
        items.delete(key)
      }
    } else {
      existing.quantity -= 1
    }
  }

  /** 清空指定日期+餐次 */
  const clearGroup = (date: string, mealType: number): void => {
    items.delete(buildKey(date, mealType))
  }

  /** 清空所有 */
  const clearAll = (): void => {
    items.clear()
  }

  /** 获取指定日期+餐次的购物车条目列表 */
  const getItemsByGroup = (date: string, mealType: number): CartEntry[] => {
    const dishMap = items.get(buildKey(date, mealType))
    if (!dishMap) return []
    return Array.from(dishMap.values())
  }

  /** 获取指定日期+餐次+菜品的数量(用于菜品卡片回显) */
  const getQuantity = (dishId: number, date: string, mealType: number): number => {
    const dishMap = items.get(buildKey(date, mealType))
    if (!dishMap) return 0
    return dishMap.get(dishId)?.quantity ?? 0
  }

  /** 指定日期在购物车中是否有商品(用于日期竖列圆点提示) */
  const dateHasItems = (date: string): boolean => {
    for (const key of items.keys()) {
      if (key.startsWith(`${date}|`)) return true
    }
    return false
  }

  /**
   * 获取指定日期已订餐的餐别列表(用于日期竖列的三餐色圆点提示)。
   * 返回已排序的餐别 type 数组(如 [1,3] 表示早+晚已选,午餐未选)。
   */
  const getMealTypesForDate = (date: string): number[] => {
    const types: number[] = []
    for (const key of items.keys()) {
      if (key.startsWith(`${date}|`)) {
        const mtStr = key.split('|')[1]
        const mt = Number(mtStr)
        if (!Number.isNaN(mt)) types.push(mt)
      }
    }
    return types.sort((a, b) => a - b)
  }

  return {
    // state
    items,
    selectedDate,
    // getters
    totalCount,
    hasItems,
    totalPrice,
    // actions
    addItem,
    removeItem,
    decreaseItem,
    clearGroup,
    clearAll,
    getItemsByGroup,
    getQuantity,
    subtotalByGroup,
    getAllGroups,
    dateHasItems,
    getMealTypesForDate,
  }
})
