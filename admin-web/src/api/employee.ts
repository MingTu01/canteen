import api from './index'
import type { Employee, EmployeeLoginDTO, EmployeeQuery, PageResult } from './types'

export interface EmployeeImportRow {
  cardNo: string
  /** 手机号(H5/小程序登录用,同店内唯一) */
  phone?: string
  name: string
  departmentName?: string
  balance?: number
  password?: string
  status?: number
  /** 头像:支持图片 URL 或 dataURL 字符串 */
  avatar?: string
}

export interface EmployeeImportResult {
  success: number
  failed: number
  created?: number
  updated?: number
  errors: Array<{ row: number; cardNo?: string; name?: string; reason: string }>
}

/** 余额预警名单查询参数 */
export interface LowBalanceQuery {
  storeId: number
  /** 阈值(默认 20) */
  threshold?: number
  page?: number
  size?: number
}

/** 余额预警统计 */
export interface LowBalanceStats {
  count: number
  totalBalance: number
  avgBalance: number
  threshold: number
}

/** 员工导出参数 */
export interface EmployeeExportQuery {
  storeId: number
  keyword?: string
  department?: number
}

export const employeeApi = {
  list: (params: EmployeeQuery) =>
    api.get<PageResult<Employee>>(`/employee/store/${params.storeId}`, { params }).then((r) => r.data),
  detail: (id: number) => api.get<Employee>(`/employee/${id}`).then((r) => r.data),
  getByCardNo: (cardNo: string) =>
    api.get<Employee>(`/employee/card/${cardNo}`).then((r) => r.data),
  create: (data: Employee) => api.post<Employee>('/employee', data).then((r) => r.data),
  update: (id: number, data: Employee) =>
    api.put<Employee>(`/employee/${id}`, data).then((r) => r.data),
  delete: (id: number) => api.delete<void>(`/employee/${id}`).then((r) => r.data),
  login: (data: EmployeeLoginDTO) => api.post('/employee/login', data),
  /** 批量导入员工 */
  batchImport: (storeId: number, employees: EmployeeImportRow[]) =>
    api.post<EmployeeImportResult>('/employee/batch', { storeId, employees }).then((r) => r.data),
  /** 批量充值:给本食堂所有在职员工充值指定金额 */
  batchRecharge: (data: { storeId?: number; amount: number }) =>
    api.post<{ successCount: number; totalAmount: number }>('/employee/batch-recharge', data).then((r) => r.data),
  /** 批量重置密码:把勾选员工密码重置为 12345678,首次登录强制修改 */
  resetPasswords: (data: { storeId?: number; employeeIds: number[] }) =>
    api.post<{ successCount: number }>('/employee/reset-passwords', data).then((r) => r.data),
  /** 余额预警名单(分页) */
  lowBalanceList: (params: LowBalanceQuery) =>
    api.get<PageResult<Employee>>('/employee/low-balance', { params }).then((r) => r.data),
  /** 余额预警统计 */
  lowBalanceStats: (storeId: number, threshold = 20) =>
    api.get<LowBalanceStats>('/employee/low-balance/stats', {
      params: { storeId, threshold },
    }).then((r) => r.data),
  /** 导出员工列表为 CSV(返回 Blob) */
  export: async (params: EmployeeExportQuery) => {
    const res = await api.get('/employee/export', {
      params,
      responseType: 'blob',
    })
    // 拦截器对 blob 不会走 code 校验,res 即 Blob
    return res as unknown as Blob
  },
  /** 上传/更新员工头像(文件名作为卡号自动匹配) */
  uploadAvatar: (cardNo: string, file: Blob, storeId?: number) => {
    const form = new FormData()
    form.append('file', file)
    return api
      .post<{ url: string; cardNo: string; employeeId: number; employeeName: string }>(
        `/employee/${encodeURIComponent(cardNo)}/avatar`,
        form,
        { params: storeId ? { storeId } : undefined },
      )
      .then((r) => r.data)
  },
}
