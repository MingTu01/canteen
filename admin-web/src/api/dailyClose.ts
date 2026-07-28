import api from './index'
import type { DailyCloseSummary, DailyCloseRecord, DailyCloseHistory } from './types'

export const dailyCloseApi = {
  /** 日终对账汇总:订单/营业额/退款/充值/新增员工/菜品销量 TOP5 */
  summary: (params: { storeId: number; date: string }) =>
    api.get<DailyCloseSummary>('/daily-close/summary', { params }).then((r) => r.data),
  /** 确认日终对账:记录到 daily_close 表 */
  confirm: (params: { storeId: number; date: string }) =>
    api.post<DailyCloseRecord>('/daily-close/confirm', null, { params }).then((r) => r.data),
  /** 历史对账记录分页查询 */
  history: (params: { storeId: number; page?: number; size?: number }) =>
    api.get<DailyCloseHistory>('/daily-close/history', { params }).then((r) => r.data),
}
