import api from './index'
import type { Notification, NotificationQuery } from './types'

export const notificationApi = {
  list: (params: NotificationQuery) =>
    api.get<Notification[]>(`/notification/store/${params.storeId}`, { params }).then((r) => r.data),
  create: (data: Notification) =>
    api.post<Notification>('/notification', data).then((r) => r.data),
  update: (id: number, data: Notification) =>
    api.put<Notification>(`/notification/${id}`, data).then((r) => r.data),
  delete: (id: number) => api.delete<void>(`/notification/${id}`).then((r) => r.data),
  /** 上架/下架(仅修改 status) */
  toggleStatus: (id: number, status: number) =>
    api.put<Notification>(`/notification/${id}/status`, null, { params: { status } }).then((r) => r.data),
}
