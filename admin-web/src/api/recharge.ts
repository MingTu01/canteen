import api from './index'
import type { RechargeCreateDTO, RechargeQuery, RechargeRecord } from './types'

export const rechargeApi = {
  list: (params: RechargeQuery) =>
    api.get<RechargeRecord[]>(`/recharge/store/${params.storeId}`, { params }).then((r) => r.data),
  listByEmployee: (employeeId: number) =>
    api.get<RechargeRecord[]>(`/recharge/employee/${employeeId}`).then((r) => r.data),
  create: (data: RechargeCreateDTO) =>
    api.post<RechargeRecord>('/recharge', data).then((r) => r.data),
}
