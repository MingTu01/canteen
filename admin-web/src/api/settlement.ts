import api from './index'
import type { DailySettlement, DailySettlementStatus, DailySettlementHistory } from './types'

export const settlementApi = {
  /** 获取指定日期对账数据 */
  get: (params: { storeId: number; date: string }) =>
    api.get<DailySettlement>('/settlement', { params }).then((r) => r.data),
  /** 生成/刷新对账数据 */
  generate: (params: { storeId: number; date: string }) =>
    api.post<DailySettlement>('/settlement/generate', null, { params }).then((r) => r.data),
  /** 历史列表 */
  list: (params: {
    storeId: number
    startDate?: string
    endDate?: string
    page?: number
    size?: number
  }) => api.get<DailySettlementHistory>('/settlement/list', { params }).then((r) => r.data),
  /** 确认对账(1→2) */
  confirm: (id: number) =>
    api.put<DailySettlement>(`/settlement/${id}/confirm`).then((r) => r.data),
  /** 关店(2→3) */
  close: (id: number) => api.put<DailySettlement>(`/settlement/${id}/close`).then((r) => r.data),
  /** 今日状态 */
  today: (params: { storeId: number; date?: string }) =>
    api.get<DailySettlementStatus>('/settlement/today', { params }).then((r) => r.data),
}
