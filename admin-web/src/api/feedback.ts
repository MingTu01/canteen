import api from './index'
import type { Feedback, FeedbackQuery, FeedbackStats, PageResult } from './types'

export const feedbackApi = {
  list: (params: FeedbackQuery) =>
    api.get<PageResult<Feedback>>('/feedback', { params }).then((r) => r.data),
  detail: (id: number) => api.get<Feedback>(`/feedback/${id}`).then((r) => r.data),
  create: (data: Feedback) => api.post<Feedback>('/feedback', data).then((r) => r.data),
  reply: (id: number, reply: string) =>
    api.put<Feedback>(`/feedback/${id}/reply`, { reply }).then((r) => r.data),
  updateStatus: (id: number, status: number) =>
    api.put<Feedback>(`/feedback/${id}/status`, { status }).then((r) => r.data),
  delete: (id: number) => api.delete<void>(`/feedback/${id}`).then((r) => r.data),
  stats: (storeId: number) =>
    api.get<FeedbackStats>('/feedback/stats', { params: { storeId } }).then((r) => r.data),
}
