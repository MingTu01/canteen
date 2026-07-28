import api from './index'

export interface OperationLogItem {
  id: number
  adminId: number | null
  adminName: string | null
  storeId: number | null
  operation: string
  method: string | null
  params: string | null
  ip: string | null
  status: number
  errorMsg: string | null
  createdAt: string
}

export interface OperationLogQuery {
  page?: number
  size?: number
  storeId?: number
  operation?: string
  status?: number
}

export const operationLogApi = {
  list: (params: OperationLogQuery) =>
    api.get<OperationLogItem[]>('/operation-log', { params }).then((r) => r.data),
}
