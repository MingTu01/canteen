import api from './index'
import type { Supplier, SupplierQuery, PageResult } from './types'

export const supplierApi = {
  list: (params: SupplierQuery) =>
    api.get<PageResult<Supplier>>('/supplier', { params }).then((r) => r.data),
  /** 采购单下拉选择用:返回合作中的供应商列表 */
  activeList: (storeId: number) =>
    api.get<Supplier[]>('/supplier/active', { params: { storeId } }).then((r) => r.data),
  detail: (id: number) => api.get<Supplier>(`/supplier/${id}`).then((r) => r.data),
  create: (data: Supplier) => api.post<Supplier>('/supplier', data).then((r) => r.data),
  update: (id: number, data: Supplier) =>
    api.put<Supplier>(`/supplier/${id}`, data).then((r) => r.data),
  delete: (id: number) => api.delete<void>(`/supplier/${id}`).then((r) => r.data),
}
