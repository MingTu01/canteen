import api from './index'
import type { Admin, ChangePasswordDTO, LoginDTO, LoginResult } from './types'

export const adminApi = {
  /** 登录:返回完整 ApiResponse,供 auth store 读取 code/message */
  login: (data: LoginDTO) => api.post<LoginResult>('/admin/login', data),
  list: () => api.get<Admin[]>('/admin').then((r) => r.data),
  create: (data: Admin) => api.post<Admin>('/admin', data).then((r) => r.data),
  update: (id: number, data: Admin) => api.put<Admin>(`/admin/${id}`, data).then((r) => r.data),
  changePassword: (id: number, data: ChangePasswordDTO) =>
    api.put<void>(`/admin/${id}/password`, data).then((r) => r.data),
  delete: (id: number) => api.delete<void>(`/admin/${id}`).then((r) => r.data),
}
