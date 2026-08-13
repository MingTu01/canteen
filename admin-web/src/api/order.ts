import api from './index'
import type {
  DashboardStats,
  Order,
  OrderCreateDTO,
  OrderDetail,
  OrderQuery,
  OrderSummary,
  OrderSummaryItem,
} from './types'

export const orderApi = {
  list: (params: OrderQuery) =>
    api.get<Order[]>(`/order/store/${params.storeId}`, { params }).then((r) => r.data),
  listByEmployee: (employeeId: number) =>
    api.get<Order[]>(`/order/employee/${employeeId}`).then((r) => r.data),
  detail: (id: number) => api.get<OrderDetail>(`/order/${id}`).then((r) => r.data),
  create: (data: OrderCreateDTO) => api.post<Order>('/order', data).then((r) => r.data),
  complete: (id: number) => api.put<void>(`/order/${id}/complete`).then((r) => r.data),
  cancel: (id: number) => api.put<void>(`/order/${id}/cancel`).then((r) => r.data),
  dashboard: (storeId: number) =>
    api.get<DashboardStats>(`/order/dashboard/${storeId}`).then((r) => r.data),
  /** 订餐汇总:按门店+日期+餐次(可选)统计各菜品订购数量 */
  summary: (storeId: number, date: string, mealType?: number) =>
    api
      .get<OrderSummary>(`/order/summary/${storeId}`, {
        params: { date, mealType },
      })
      .then((r) => r.data),
}

export type { OrderSummary, OrderSummaryItem }
