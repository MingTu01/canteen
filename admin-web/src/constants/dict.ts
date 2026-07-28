/** 集中状态映射字典 */

export const ORDER_STATUS = {
  1: { label: '待取餐', type: 'warning' },
  2: { label: '已完成', type: 'success' },
  3: { label: '已取消', type: 'info' },
} as const

export const MEAL_TYPE = {
  1: { label: '早餐', color: '#f59e0b' },
  2: { label: '午餐', color: '#10b981' },
  3: { label: '晚餐', color: '#6366f1' },
} as const

/** 订单来源:0-正常订餐,1-未订餐用餐 */
export const ORDER_SOURCE = {
  0: { label: '正常订餐', type: 'info' },
  1: { label: '未订餐用餐', type: 'warning' },
} as const

export const COMMON_STATUS = {
  0: { label: '禁用', type: 'danger' },
  1: { label: '启用', type: 'success' },
} as const

export const NOTIFICATION_TYPE = {
  1: { label: '系统通知', type: 'primary' },
  2: { label: '公告', type: 'warning' },
  3: { label: '活动', type: 'success' },
} as const

/** 通知展示状态(由后端 displayStatus 字段返回) */
export const NOTIFICATION_DISPLAY_STATUS = {
  pending: { label: '待发布', type: 'info' },
  active: { label: '已发布', type: 'success' },
  expired: { label: '已到期', type: 'warning' },
  offline: { label: '已下架', type: 'danger' },
} as const

export const ADMIN_ROLE = {
  1: { label: '超级管理员', type: 'danger' },
  2: { label: '门店管理员', type: 'primary' },
  4: { label: '财务岗', type: 'success' },
  5: { label: '厨师长', type: 'warning' },
  6: { label: '店长', type: 'primary' },
} as const
