import api from './index'
import type {
  Purchase,
  PurchaseQuery,
  PurchaseCreateDTO,
  PurchaseDetail,
  PageResult,
} from './types'

export const purchaseApi = {
  list: (params: PurchaseQuery) =>
    api.get<PageResult<Purchase>>('/purchase', { params }).then((r) => r.data),
  detail: (id: number) =>
    api.get<PurchaseDetail>(`/purchase/${id}`).then((r) => r.data),
  create: (data: PurchaseCreateDTO) =>
    api.post<Purchase>('/purchase', data).then((r) => r.data),
  /** 更新状态:status=2 入库 / status=3 取消 */
  updateStatus: (id: number, status: number) =>
    api.put<Purchase>(`/purchase/${id}/status`, null, { params: { status } }).then((r) => r.data),
  delete: (id: number) => api.delete<void>(`/purchase/${id}`).then((r) => r.data),
}
