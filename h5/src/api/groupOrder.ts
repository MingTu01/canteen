import { get } from './index'
import type { GroupOrder, GroupOrderDetail } from './types'

/**
 * 团体订餐 API(员工自助查看)
 * 后端:GroupOrderController
 * - /my:员工查看所在门店的团餐列表(非分页,返回全部)
 * - /my/{id}:员工查看团餐详情(校验门店归属)
 */

/** 查询当前员工所在门店的团餐列表(非分页) */
export function getMyGroupOrders(): Promise<GroupOrder[]> {
  return get<GroupOrder[]>('/group-order/my')
}

/** 查询团餐详情(含明细,校验门店归属) */
export function getGroupOrderDetail(id: number): Promise<GroupOrderDetail> {
  return get<GroupOrderDetail>(`/group-order/my/${id}`)
}
