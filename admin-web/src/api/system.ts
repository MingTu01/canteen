import api from './index'
import type { SystemConfig, SystemHealth, SystemVersion } from './types'

export const systemApi = {
  health: () => api.get<SystemHealth>('/system/health').then((r) => r.data),
  version: () => api.get<SystemVersion>('/system/version').then((r) => r.data),
  config: () => api.get<SystemConfig[]>('/system/config').then((r) => r.data),
  getConfig: (key: string) => api.get<SystemConfig>(`/system/config/${key}`).then((r) => r.data),
  updateConfig: (key: string, value: string) =>
    api.put<void>(`/system/config/${key}`, { value }).then((r) => r.data),
  /** 批量保存配置 */
  batchUpdateConfig: (items: Array<{ key: string; value: string }>) =>
    api.put<void>('/system/config', items).then((r) => r.data),
}
