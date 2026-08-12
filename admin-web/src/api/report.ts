import api from './index'
import type {
  ReportData,
  ReportParams,
  FinanceReport,
  EmployeeConsumptionReport,
  ComparisonReport,
  HourlyDistributionReport,
  PeakHoursReport,
} from './types'

export const reportApi = {
  daily: (params: ReportParams) =>
    api.get<ReportData>('/report/daily', { params }).then((r) => r.data),
  weekly: (params: ReportParams) =>
    api.get<ReportData>('/report/weekly', { params }).then((r) => r.data),
  monthly: (params: ReportParams) =>
    api.get<ReportData>('/report/monthly', { params }).then((r) => r.data),
  finance: (params: { storeId: number; startDate: string; endDate: string }) =>
    api.get<FinanceReport>('/report/finance', { params }).then((r) => r.data),
  employeeConsumption: (params: { storeId: number; startDate: string; endDate: string }) =>
    api.get<EmployeeConsumptionReport>('/report/employee-consumption', { params }).then((r) => r.data),
  /** 同比分析(对比去年同期) */
  yoy: (params: { storeId: number; startDate: string; endDate: string }) =>
    api.get<ComparisonReport>('/report/yoy', { params }).then((r) => r.data),
  /** 环比分析(对比上月) */
  mom: (params: { storeId: number; year: number; month: number }) =>
    api.get<ComparisonReport>('/report/mom', { params }).then((r) => r.data),
  /** 拥堵分析-某日时段分布 */
  hourly: (params: { storeId: number; date: string }) =>
    api.get<HourlyDistributionReport>('/report/hourly', { params }).then((r) => r.data),
  /** 拥堵分析-指定时间段高峰时段 */
  peak: (params: { storeId: number; startDate: string; endDate: string }) =>
    api.get<PeakHoursReport>('/report/peak', { params }).then((r) => r.data),
}
