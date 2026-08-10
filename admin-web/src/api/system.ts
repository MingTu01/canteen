import api from './index'
import type { SystemConfig, SystemHealth, SystemVersion } from './types'

/** 订餐配置(按门店) */
export interface OrderConfig {
  order_advance_days: string | number
  order_deadline_time: string
  cancel_deadline_time: string
  max_order_quantity: string | number
  allow_cross_day_order: string | boolean
}

export const systemApi = {
  health: () => api.get<SystemHealth>('/system/health').then((r) => r.data),
  version: () => api.get<SystemVersion>('/system/version').then((r) => r.data),
  config: () => api.get<SystemConfig[]>('/system/config').then((r) => r.data),
  getConfig: (key: string) => api.get<SystemConfig>(`/system/config/${key}`).then((r) => r.data),
  updateConfig: (key: string, value: string) =>
    api.put<void>(`/system/config/${key}`, { value }).then((r) => r.data),
  /** 批量保存配置(全局,超管) */
  batchUpdateConfig: (items: Array<{ key: string; value: string }>) =>
    api.put<void>('/system/config', items).then((r) => r.data),
  /** 读取指定门店的订餐配置 */
  getOrderConfig: (storeId: number) =>
    api.get<OrderConfig>('/store-config/order', { params: { storeId } }).then((r) => r.data),
  /** 批量保存指定门店的订餐配置 */
  updateOrderConfig: (storeId: number, items: Array<{ key: string; value: string }>) =>
    api.put<void>('/store-config/order', items, { params: { storeId } }).then((r) => r.data),
}
