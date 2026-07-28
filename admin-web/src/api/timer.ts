import api from './index'
import type { DiningTimeSlot } from './types'

export const timerApi = {
  list: (storeId: number) =>
    api.get<DiningTimeSlot[]>(`/timer/store/${storeId}`).then((r) => r.data),
  create: (data: DiningTimeSlot) =>
    api.post<DiningTimeSlot>('/timer', data).then((r) => r.data),
  update: (id: number, data: DiningTimeSlot) =>
    api.put<DiningTimeSlot>(`/timer/${id}`, data).then((r) => r.data),
  delete: (id: number) => api.delete<void>(`/timer/${id}`).then((r) => r.data),
}
