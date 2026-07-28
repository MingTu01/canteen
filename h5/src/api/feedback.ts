import { get, post } from './index'
import type { Feedback } from './types'

/**
 * 反馈评价 API(员工自助)
 * 后端:FeedbackController
 * - POST /feedback:员工创建反馈
 * - GET /feedback/my:员工查询自己的反馈列表(非分页)
 * - GET /feedback/my/{id}:员工查询反馈详情
 */

/** 创建反馈/评价(员工可调用) */
export function createFeedback(payload: {
  storeId: number
  employeeId: number
  orderId?: number | null
  dishId?: number | null
  rating: number
  content?: string
  category?: number
}): Promise<Feedback> {
  return post<Feedback>('/feedback', payload)
}

/** 查询我的反馈列表(非分页,后端返回全部) */
export function getMyFeedback(): Promise<Feedback[]> {
  return get<Feedback[]>('/feedback/my')
}

/** 查询反馈详情 */
export function getFeedbackDetail(id: number): Promise<Feedback> {
  return get<Feedback>(`/feedback/my/${id}`)
}
