import { get } from './index'
import type { RechargeRecord } from './types'

/**
 * 充值记录 API(员工自助)
 * 后端:RechargeRecordController
 * - getMyRecharges:查询自己的充值记录(按 employeeId)
 *   员工(role=0)只能查自己的充值记录,禁止查看同店其他员工。
 */

/** 查询指定员工的充值记录列表(员工只能查自己) */
export function getMyRecharges(employeeId: number): Promise<RechargeRecord[]> {
  return get<RechargeRecord[]>(`/recharge/employee/${employeeId}`)
}
