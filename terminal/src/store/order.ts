import { reactive, computed } from 'vue'
import { mealTypeLabel } from '@/utils'

/** 刷卡识别到的员工(订餐流程) */
export interface Employee {
  id: number
  name: string
  departmentName: string
  cardNo: string
  balance: number
  storeId: number
}

/** 购物车项(跨餐别 + 跨日期共享,记录所属餐别与日期) */
export interface CartItem {
  dishId: number
  name: string
  price: number
  category: string
  mealType: number
  date: string
  /** 数量(普通购物车语义,同菜品合并为一条) */
  quantity: number
}

/** 最近一次下单结果(供成功页展示) */
export interface LastOrder {
  total: number
  /** 日期标签(可能跨天,如 "07-27 今天 · 07-28 明天") */
  dateLabel: string
  /** 餐别标签(如 "早餐 · 午餐") */
  mealLabel: string
}

export const orderStore = reactive({
  employee: null as Employee | null,
  cart: [] as CartItem[],
  selectedDate: '',
  lastOrder: null as LastOrder | null,
})

/** 重置整个订餐流程(返回待机时调用) */
export function resetOrderFlow() {
  orderStore.employee = null
  orderStore.cart = []
  orderStore.selectedDate = ''
  orderStore.lastOrder = null
}

export function clearCart() {
  orderStore.cart = []
}

/**
 * 加入购物车(同日期+同餐别+同菜品合并为一条,quantity 累加)。
 * 上限 maxQty(默认 9),超出不再增加。
 */
export function addDish(
  item: { dishId: number; dishName: string; price: number; category: string },
  mealType: number,
  date: string,
  maxQty = 9,
) {
  const found = orderStore.cart.find(
    (i) => i.dishId === item.dishId && i.date === date && i.mealType === mealType,
  )
  if (found) {
    if (found.quantity >= maxQty) return
    found.quantity += 1
  } else {
    orderStore.cart.push({
      dishId: item.dishId,
      name: item.dishName,
      price: Number(item.price),
      category: item.category,
      mealType,
      date,
      quantity: 1,
    })
  }
}

/** 减一(数量 1 时直接移除该菜品) */
export function decDish(dishId: number, mealType: number, date: string) {
  const idx = orderStore.cart.findIndex(
    (i) => i.dishId === dishId && i.date === date && i.mealType === mealType,
  )
  if (idx < 0) return
  const it = orderStore.cart[idx]
  if (it.quantity > 1) {
    it.quantity -= 1
  } else {
    orderStore.cart.splice(idx, 1)
  }
}

/** 直接移除某菜品(购物车弹窗"删除"用) */
export function removeDish(dishId: number, mealType: number, date: string) {
  orderStore.cart = orderStore.cart.filter(
    (i) => !(i.dishId === dishId && i.date === date && i.mealType === mealType),
  )
}

/** 某菜品在购物车中的数量(0 表示未选) */
export function dishQuantity(dishId: number, date: string, mealType: number): number {
  const it = orderStore.cart.find(
    (i) => i.dishId === dishId && i.date === date && i.mealType === mealType,
  )
  return it ? it.quantity : 0
}

/** 某日期已订餐的餐别列表(排序后返回,如 [1,3] 表示早+晚已选) */
export function getMealTypesForDate(date: string): number[] {
  const types = new Set<number>()
  for (const it of orderStore.cart) {
    if (it.date === date) types.add(it.mealType)
  }
  return Array.from(types).sort((a, b) => a - b)
}

/** 购物车全局总道数(数量求和,跨日期跨餐别) */
export const cartTotalCount = computed(() =>
  orderStore.cart.reduce((s, i) => s + i.quantity, 0),
)

/** 购物车全局总价(跨日期跨餐别) */
export const cartTotalAmount = computed(() =>
  orderStore.cart.reduce((s, i) => s + i.price * i.quantity, 0),
)

/** 购物车按"日期 → 餐别"两级分组(用于确认页与下单遍历) */
export interface CartDateGroup {
  date: string
  meals: {
    mealType: number
    items: CartItem[]
    subtotal: number
  }[]
  dateSubtotal: number
}

export function getCartDateGroups(): CartDateGroup[] {
  const dateMap = new Map<string, CartDateGroup>()
  for (const it of orderStore.cart) {
    let dg = dateMap.get(it.date)
    if (!dg) {
      dg = { date: it.date, meals: [], dateSubtotal: 0 }
      dateMap.set(it.date, dg)
    }
    let mg = dg.meals.find((m) => m.mealType === it.mealType)
    if (!mg) {
      mg = { mealType: it.mealType, items: [], subtotal: 0 }
      dg.meals.push(mg)
    }
    mg.items.push(it)
    mg.subtotal += it.price * it.quantity
    dg.dateSubtotal += it.price * it.quantity
  }
  const groups = Array.from(dateMap.values())
  // 日期升序,同日期餐别升序
  groups.sort((a, b) => (a.date < b.date ? -1 : a.date > b.date ? 1 : 0))
  for (const g of groups) g.meals.sort((a, b) => a.mealType - b.mealType)
  return groups
}

/** 移除指定日期+餐别的所有菜品(下单成功后调用) */
export function removeCartGroup(date: string, mealType: number) {
  orderStore.cart = orderStore.cart.filter(
    (i) => !(i.date === date && i.mealType === mealType),
  )
}

/**
 * 记录最近下单(在下单成功、清空购物车前调用)。
 * 跨天购物车:汇总所有日期与餐别标签。
 */
export function setLastOrder(total: number) {
  const groups = getCartDateGroups()
  const dateLabels = groups.map((g) => g.date)
  const mealTypes = Array.from(
    new Set(groups.flatMap((g) => g.meals.map((m) => m.mealType))),
  ).sort((a, b) => a - b)
  orderStore.lastOrder = {
    total,
    dateLabel: dateLabels.join(' · '),
    mealLabel: mealTypes.map(mealTypeLabel).join(' · '),
  }
}
