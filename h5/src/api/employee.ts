import { get } from './index'
import type { Employee } from './types'

/**
 * 员工 API(员工自助)
 * 后端:EmployeeController
 * - getEmployee:员工只能查自己
 * - getMyQrcode 已定义于 @/api/auth,此处不再重复
 */

/** 获取员工详情(员工角色只能查自己) */
export function getEmployee(id: number): Promise<Employee> {
  return get<Employee>(`/employee/${id}`)
}
