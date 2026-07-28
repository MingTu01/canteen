import api from './index'
import type { Menu, MenuCreateDTO, MenuCopyDTO, MenuWithItems } from './types'

export const menuApi = {
  list: (storeId: number) =>
    api.get<Menu[]>(`/menu/store/${storeId}`).then((r) => r.data),
  getByDate: (storeId: number, date: string) =>
    api.get<MenuWithItems[]>(`/menu/store/${storeId}/date/${date}`).then((r) => r.data),
  /** 查询门店某月已配置菜单的日期列表(用于月历标记) */
  getDatesByMonth: (storeId: number, year: number, month: number) =>
    api.get<string[]>(`/menu/store/${storeId}/dates`, { params: { year, month } }).then((r) => r.data),
  create: (data: MenuCreateDTO) => api.post<Menu>('/menu', data).then((r) => r.data),
  /** 复制菜单:源日期 -> 目标日期(所有餐次) */
  copy: (data: MenuCopyDTO) =>
    api.post<{ copied: number }>('/menu/copy', data).then((r) => r.data),
  delete: (id: number) => api.delete<void>(`/menu/${id}`).then((r) => r.data),
}
