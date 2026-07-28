import api from './index'
import type { Material, MaterialQuery, PageResult } from './types'

export const materialApi = {
  list: (params: MaterialQuery) =>
    api.get<PageResult<Material>>('/material', { params }).then((r) => r.data),
  detail: (id: number) => api.get<Material>(`/material/${id}`).then((r) => r.data),
  create: (data: Material) => api.post<Material>('/material', data).then((r) => r.data),
  update: (id: number, data: Material) =>
    api.put<Material>(`/material/${id}`, data).then((r) => r.data),
  delete: (id: number) => api.delete<void>(`/material/${id}`).then((r) => r.data),
  /** 入库 */
  inbound: (id: number, qty: number, remark?: string) =>
    api.post<Material>(`/material/${id}/inbound`, null, { params: { qty, remark } }).then((r) => r.data),
  /** 出库 */
  outbound: (id: number, qty: number, remark?: string) =>
    api.post<Material>(`/material/${id}/outbound`, null, { params: { qty, remark } }).then((r) => r.data),
}
