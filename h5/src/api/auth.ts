import { post, put, get } from './index'
import type { EmployeeLoginResult, EmployeeQrcode } from './types'

/**
 * 认证相关 API
 * 后端:AuthController(注销) + EmployeeController(登录/改密/二维码)
 */

/** 手机号登录(H5/小程序):手机号 + 密码 → 员工 token */
export function phoneLogin(phone: string, password: string): Promise<EmployeeLoginResult> {
  return post<EmployeeLoginResult>('/employee/phone-login', { phone, password })
}

/**
 * 卡号登录:需指定门店(卡号可能跨店重复)+ 密码。
 * 后端 POST /api/employee/login { cardNo, storeId, password }
 */
export function login(cardNo: string, storeId: number, password: string): Promise<EmployeeLoginResult> {
  return post<EmployeeLoginResult>('/employee/login', { cardNo, storeId, password })
}

/** 注销:后端将 token 加入黑名单并清除 Cookie */
export function logout(): Promise<{ loggedOut: boolean }> {
  return post<{ loggedOut: boolean }>('/auth/logout')
}

/** 修改密码:校验原密码,新密码至少 8 位 */
export function changePassword(oldPassword: string, newPassword: string): Promise<void> {
  return put<void>('/employee/change-password', { oldPassword, newPassword })
}

/** 获取当前登录员工的身份二维码内容(供取餐终端扫码) */
export function getMyQrcode(): Promise<EmployeeQrcode> {
  return get<EmployeeQrcode>('/employee/my-qrcode')
}
