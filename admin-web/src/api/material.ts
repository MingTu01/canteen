import api from './index'
import type { Material, MaterialQuery, PageResult, StockCount } from './types'

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
  /** 创建盘点记录 */
  stocktake: (id: number, countedQty: number, remark?: string) =>
    api.post<StockCount>(`/material/${id}/stocktake`, null, { params: { countedQty, remark } }).then((r) => r.data),
  /** 查询盘点记录列表 */
  stocktakeList: (storeId: number, page: number, size: number, status?: number) =>
    api.get<PageResult<StockCount>>('/material/stocktake', { params: { storeId, page, size, status } }).then((r) => r.data),
  /** 恢复单条盘点差异 */
  resolveStockCount: (stockCountId: number) =>
    api.post<StockCount>(`/material/stocktake/${stockCountId}/resolve`).then((r) => r.data),
  /** 批量恢复所有待处理盘点差异 */
  resolveAllStockCount: (storeId: number) =>
    api.post<{ resolvedCount: number }>('/material/stocktake/resolve-all', null, { params: { storeId } }).then((r) => r.data),
}
