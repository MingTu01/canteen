import { get, post, put } from './index'
import type { Order, OrderCreateDTO, OrderDetail } from './types'

/**
 * 订单 API(员工自助)
 * 后端:OrderController
 * - createOrder:下单
 * - getMyOrders:查询自己的订单列表(按 employeeId)
 * - getOrderDetail:订单详情(含 items)
 * - cancelOrder:取消订单(仅待取餐状态可取消)
 * - completeOrder:确认取餐(完成订单)
 */

/** 创建订单 */
export function createOrder(dto: OrderCreateDTO): Promise<Order> {
  return post<Order>('/order', dto)
}

/** 查询当前登录员工的订单列表(基于 token,不依赖前端传 employeeId) */
export function getMyOrders(): Promise<Order[]> {
  return get<Order[]>('/order/my')
}

/** 查询订单详情(含 items 明细) */
export function getOrderDetail(id: number): Promise<OrderDetail> {
  return get<OrderDetail>(`/order/${id}`)
}

/** 取消订单(仅待取餐状态可取消,余额退回) */
export function cancelOrder(id: number): Promise<void> {
  return put<void>(`/order/${id}/cancel`)
}

/** 确认取餐 / 完成订单 */
export function completeOrder(id: number): Promise<void> {
  return put<void>(`/order/${id}/complete`)
}
