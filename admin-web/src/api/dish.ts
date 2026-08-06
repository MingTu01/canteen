import api from './index'
import type { Dish, DishQuery, PageResult } from './types'

export const dishApi = {
  list: (params: DishQuery) =>
    api.get<PageResult<Dish>>(`/dish/store/${params.storeId}`, { params }).then((r) => r.data),
  detail: (id: number) => api.get<Dish>(`/dish/${id}`).then((r) => r.data),
  create: (data: Dish) => api.post<Dish>('/dish', data).then((r) => r.data),
  update: (id: number, data: Dish) => api.put<Dish>(`/dish/${id}`, data).then((r) => r.data),
  delete: (id: number) => api.delete<void>(`/dish/${id}`).then((r) => r.data),
  toggleStatus: (id: number) =>
    api.put<void>(`/dish/${id}/toggle-status`).then((r) => r.data),
  // 批量操作
  batchUpdateStatus: (data: { dishIds: number[]; status: number; storeId: number }) =>
    api.put<{ affected: number }>('/dish/batch/status', data).then((r) => r.data),
  batchUpdateCategory: (data: { dishIds: number[]; category: string; storeId: number }) =>
    api.put<{ affected: number }>('/dish/batch/category', data).then((r) => r.data),
  batchDelete: (data: { dishIds: number[]; storeId: number }) =>
    api.delete<{ affected: number }>('/dish/batch', { data }).then((r) => r.data),
  // 回收站
  trash: (params: { storeId: number; page?: number; size?: number }) =>
    api.get<PageResult<Dish>>('/dish/trash', { params }).then((r) => r.data),
  restore: (id: number) =>
    api.put<void>(`/dish/${id}/restore`).then((r) => r.data),
  purge: (id: number) =>
    api.delete<void>(`/dish/${id}/purge`).then((r) => r.data),
}
