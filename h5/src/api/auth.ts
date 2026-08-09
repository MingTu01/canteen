import { post, put, get } from './index'
import type { Employee, EmployeeLoginResult, EmployeeQrcode, PayCode } from './types'

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

/** 生成一次性支付码(5 分钟有效,核销即失效,防截图重放) */
export function generatePayCode(): Promise<PayCode> {
  return post<PayCode>('/employee/paycode')
}

/** 获取当前登录员工的完整信息(基于 token,无需传 ID) */
export function getMe(): Promise<Employee> {
  return get<Employee>('/employee/me')
}

/* ============================================================
 * SSE 员工维度订阅(支付码核销实时刷新)
 * ============================================================ */

/** SSE Ticket 响应(对应后端 /sse/employee-ticket) */
export interface SseTicketResult {
  /** 一次性 ticket,30 秒有效,仅可使用一次 */
  ticket: string
  /** 过期秒数(固定 30) */
  expiresIn: number
}

/**
 * 获取一次性 SSE ticket(员工维度,30 秒有效)。
 * 用 ticket 建立 EventSource,避免 token 出现在 URL query 中。
 * 静默失败(_silent):后端未部署新版本或网络抖动时不弹 toast 干扰用户。
 */
export function getEmployeeTicket(): Promise<SseTicketResult> {
  return get<SseTicketResult>('/sse/employee-ticket', { _silent: true })
}

/* ============================================================
 * 微信登录相关
 * ============================================================ */

/** 微信授权 URL 响应 */
export interface WechatAuthUrlResult {
  authUrl: string
}

/** 微信登录响应(两种状态:直接登录成功 / 需要绑定) */
export interface WechatLoginResult {
  /** "login" = 已绑定直接登录成功;"need_bind" = 未绑定需输入手机号+密码 */
  status: 'login' | 'need_bind'
  /** status=login 时返回 */
  token?: string
  employee?: Employee
  /** status=need_bind 时返回,用于后续绑定接口 */
  bindToken?: string
}

/** 获取微信网页授权 URL(后端拼接完整回调地址) */
export function getWechatAuthUrl(redirect?: string): Promise<WechatAuthUrlResult> {
  return get<WechatAuthUrlResult>('/employee/wechat/auth-url', {
    params: redirect ? { redirect } : {},
  })
}

/** 微信授权码登录:已绑定则直接登录,未绑定返回 bindToken */
export function wechatLogin(code: string): Promise<WechatLoginResult> {
  return post<WechatLoginResult>('/employee/wechat/login', { code })
}

/** 微信绑定:通过手机号+密码验证身份,绑定 openid 后自动登录 */
export function wechatBind(bindToken: string, phone: string, password: string): Promise<EmployeeLoginResult> {
  return post<EmployeeLoginResult>('/employee/wechat/bind', { bindToken, phone, password })
}
