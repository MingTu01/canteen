import api from './index'
import type { Store, StoreBranding, SwitchStoreResult } from './types'

export const storeApi = {
  list: () => api.get<Store[]>('/store').then((r) => r.data),
  get: (id: number) => api.get<Store>(`/store/${id}`).then((r) => r.data),
  create: (data: Store) => api.post<Store>('/store', data).then((r) => r.data),
  update: (id: number, data: Store) => api.put<Store>(`/store/${id}`, data).then((r) => r.data),
  /** 删除食堂(敏感操作:password 为当前登录管理员密码,后端强制二次验证) */
  delete: (id: number, password: string) =>
    api.delete<void>(`/store/${id}`, { data: { password } }).then((r) => r.data),
  /** 重置食堂安全码,返回 { id, name, securityCode } */
  resetSecurityCode: (id: number) =>
    api.post<{ id: number; name: string; securityCode: string }>(`/store/${id}/reset-security-code`).then((r) => r.data),
  /** 获取食堂品牌信息(公开接口,可选传 If-None-Match 做 304 缓存) */
  getBranding: (id: number, ifNoneMatch?: string) =>
    api.get<StoreBranding>(`/store/${id}/branding`, {
      headers: ifNoneMatch ? { 'If-None-Match': ifNoneMatch } : {},
      validateStatus: (s) => s === 200 || s === 304,
    }).then((r) => ({ data: r.data, status: r.status as 200 | 304 })),
  /** 更新食堂品牌信息(仅超管) */
  updateBranding: (id: number, data: Partial<Pick<Store, 'logoUrl' | 'imageUrl' | 'terminalBackgroundUrl' | 'h5BannerUrl' | 'description'>>) =>
    api.put<Store>(`/store/${id}/branding`, data).then((r) => r.data),
  /** 获取当前登录用户所属食堂 */
  getCurrent: () => api.get<Store | null>('/store/current').then((r) => r.data),
  /** 超管切换当前管理食堂(重签 token,必须指定具体食堂 ID) */
  switchTo: (id: number) =>
    api.post<SwitchStoreResult>(`/store/${id}/switch`).then((r) => r.data),
}

/** 文件上传 API */
export const fileApi = {
  /** 上传图片(前端已 canvas 压缩到 300k 左右) */
  uploadImage: (file: File) => {
    const formData = new FormData()
    formData.append('file', file)
    return api.post<{ url: string; fileName: string; size: number }>('/file/upload-image', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    }).then((r) => r.data)
  },
}
