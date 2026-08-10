import api from './index'
import type { BackupInfo } from './types'

export interface BackupCreateParams {
  /** full | store */
  type?: 'full' | 'store'
  storeId?: number
}

export interface BackupCreateResult {
  name: string
  size: number
  sizeText?: string
  type: string
  storeId?: number | null
  storeName?: string | null
  tableCount?: number
  totalRows?: number
}

export interface BackupImportResult {
  name: string
  size: number
  type: string
  storeId?: number | null
  imported: boolean
  restored?: boolean
  restoredTables?: string[]
  restoredRows?: number
  /** 因敏感字段脱敏被跳过的管理员账号数(需部署脚本重置密码后登录) */
  redactedAdminsSkipped?: number
}

export const backupApi = {
  list: () => api.get<BackupInfo[]>('/backup/list').then((r) => r.data),
  create: (params?: BackupCreateParams) =>
    api.post<BackupCreateResult>('/backup/create', params ?? {}).then((r) => r.data),
  restore: (backupName: string) =>
    api.post(`/backup/restore/${backupName}`).then((r) => r.data),
  delete: (backupName: string) =>
    api.delete<void>(`/backup/${backupName}`).then((r) => r.data),
  /** 下载备份(文件流),触发浏览器下载 */
  download: async (backupName: string) => {
    const res = await api.get(`/backup/download/${backupName}`, { responseType: 'blob' })
    // 拦截器对 blob 不会走 code 校验,res 即 Blob
    const blob = res as unknown as Blob
    const url = window.URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = backupName
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    window.URL.revokeObjectURL(url)
  },
  /** 导入备份文件(可选立即恢复) */
  importBackup: (file: File, restore = false) => {
    const formData = new FormData()
    formData.append('file', file)
    return api
      .post<BackupImportResult>('/backup/import', formData, {
        params: { restore },
        headers: { 'Content-Type': 'multipart/form-data' },
        timeout: 120000,
      })
      .then((r) => r.data)
  },
}
