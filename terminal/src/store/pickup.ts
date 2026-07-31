import { reactive } from 'vue'

/** 取餐流程中识别到的员工 */
export interface PickupEmployee {
  id: number
  name: string
  departmentName: string
  cardNo: string
  balance: number
  storeId: number
  /** 头像 URL（可选） */
  avatar?: string
}

/** 取餐流程中待取餐的订单 */
export interface PickupOrder {
  id: number
  mealType: number
  date: string
  totalAmount: number
  /** 订单来源: 0-正常订餐, 1-未订餐用餐 */
  orderSource?: number
  orderItems: { dishName: string; price: number; quantity: number; dishImage?: string }[]
}

export const pickupStore = reactive({
  employee: null as PickupEmployee | null,
  order: null as PickupOrder | null,
})

export function resetPickupFlow() {
  pickupStore.employee = null
  pickupStore.order = null
}
