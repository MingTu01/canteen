import { get } from './index'
import type { Notification } from './types'

/**
 * 门店通知 API
 * 后端:NotificationController
 * - getStoreNotifications:查询门店当前上架中的通知公告(仅 H5 可见集合)
 *
 * 使用 /visible 端点:仅返回 status=1 且处于上下架时间窗口内的通知。
 * 修复:此前复用管理端 /store/{storeId} 接口,已下架通知仍在 H5 首页展示。
 * 注意:后端 GET /api/notification/store/{storeId}/visible 校验门店归属,
 * 员工角色(token 含 storeId)可访问本店通知。
 */

/** 查询门店通知公告列表(仅上架中) */
export function getStoreNotifications(storeId: number): Promise<Notification[]> {
  return get<Notification[]>(`/notification/store/${storeId}/visible`)
}
