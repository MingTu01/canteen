import { get } from './index'
import type { Employee, EmployeeQrcode } from './types'

/**
 * 员工 API(员工自助)
 * 后端:EmployeeController
 * - getEmployee:员工只能查自己
 * - getMyQrcode:取餐终端扫码用的身份二维码
 */

/** 获取员工详情(员工角色只能查自己) */
export function getEmployee(id: number): Promise<Employee> {
  return get<Employee>(`/employee/${id}`)
}

/** 获取当前登录员工的身份二维码内容(供取餐终端扫码,等同刷卡) */
export function getMyQrcode(): Promise<EmployeeQrcode> {
  return get<EmployeeQrcode>('/employee/my-qrcode')
}
