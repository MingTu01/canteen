import api from './index'
import type {
  GroupOrder,
  GroupOrderQuery,
  GroupOrderCreateDTO,
  GroupOrderDetail,
  PageResult,
} from './types'

export const groupOrderApi = {
  list: (params: GroupOrderQuery) =>
    api.get<PageResult<GroupOrder>>('/group-order', { params }).then((r) => r.data),
  detail: (id: number) =>
    api.get<GroupOrderDetail>(`/group-order/${id}`).then((r) => r.data),
  create: (data: GroupOrderCreateDTO) =>
    api.post<GroupOrder>('/group-order', data).then((r) => r.data),
  confirm: (id: number) => api.put<GroupOrder>(`/group-order/${id}/confirm`).then((r) => r.data),
  cancel: (id: number) => api.put<GroupOrder>(`/group-order/${id}/cancel`).then((r) => r.data),
  complete: (id: number) => api.put<GroupOrder>(`/group-order/${id}/complete`).then((r) => r.data),
  delete: (id: number) => api.delete<void>(`/group-order/${id}`).then((r) => r.data),
}
