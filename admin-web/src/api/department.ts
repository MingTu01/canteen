import api from './index'
import type { Department } from './types'
import { normalizeList } from '@/utils/list'

export const departmentApi = {
  list: (storeId: number) =>
    api.get(`/department/store/${storeId}`).then((r) => normalizeList<Department>(r.data)),
  detail: (id: number) => api.get<Department>(`/department/${id}`).then((r) => r.data),
  create: (data: Department) => api.post<Department>('/department', data).then((r) => r.data),
  update: (id: number, data: Department) =>
    api.put<Department>(`/department/${id}`, data).then((r) => r.data),
  delete: (id: number) => api.delete<void>(`/department/${id}`).then((r) => r.data),
}
