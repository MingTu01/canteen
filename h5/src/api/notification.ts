import { get } from './index'
import type { Notification } from './types'

/**
 * 门店通知 API
 * 后端:NotificationController
 * - getStoreNotifications:查询门店启用的通知公告列表
 *
 * 注意:后端 GET /api/notification/store/{storeId} 校验门店归属,
 * 员工角色(token 含 storeId)可访问本店通知。
 */

/** 查询门店通知公告列表 */
export function getStoreNotifications(storeId: number): Promise<Notification[]> {
  return get<Notification[]>(`/notification/store/${storeId}`)
}
