/**
 * 角色常量与显示名称集中定义。
 *
 * 角色值:
 * - 0: 员工(H5/小程序)
 * - 1: 超级管理员(可跨门店)
 * - 2: 门店管理员(仅本门店)
 * - 3: 终端(扫码设备)
 * - 4: 财务岗(报表+充值)
 * - 5: 厨师长(订餐汇总+菜品)
 * - 6: 店长(全店管理,不可删数据)
 */
export const ROLE_EMPLOYEE = 0
export const ROLE_SUPER_ADMIN = 1
export const ROLE_STORE_ADMIN = 2
export const ROLE_TERMINAL = 3
export const ROLE_FINANCE = 4
export const ROLE_CHEF = 5
export const ROLE_STORE_MANAGER = 6

/** 角色显示名称映射 */
export const ROLE_LABELS: Record<number, string> = {
  0: '员工',
  1: '超级管理员',
  2: '门店管理员',
  3: '终端',
  4: '财务',
  5: '厨师',
  6: '店长',
}
