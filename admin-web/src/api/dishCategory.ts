import api from './index'
import type { DishCategory } from './types'

export const dishCategoryApi = {
  list: (storeId: number) =>
    api.get<DishCategory[]>(`/dish-category/store/${storeId}`).then((r) => r.data),
  create: (data: DishCategory) =>
    api.post<DishCategory>('/dish-category', data).then((r) => r.data),
  update: (id: number, data: DishCategory) =>
    api.put<DishCategory>(`/dish-category/${id}`, data).then((r) => r.data),
  delete: (id: number) => api.delete<void>(`/dish-category/${id}`).then((r) => r.data),
}
