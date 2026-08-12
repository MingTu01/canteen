/**
 * 全局 API 类型定义
 * 字段参考后端实体 com.example.canteen.entity.*
 */

/** 后端统一响应结构 ApiResponse<T> */
export interface ApiResponse<T = unknown> {
  code: number
  message: string
  data: T
  timestamp: number
}

/** 分页响应结构 */
export interface PageResult<T> {
  records: T[]
  total: number
  page: number
  size: number
}

/** 通用分页查询参数 */
export interface PageQuery {
  page?: number
  size?: number
  keyword?: string
}

/* ============================================================
 * 管理员
 * ============================================================ */
export interface Admin {
  id?: number
  username: string
  name: string
  storeId?: number
  role: number
  status?: number
  password?: string
  createdAt?: string
  updatedAt?: string
}

export interface AdminInfo {
  id: number
  username: string
  name: string
  storeId: number
  role: number
}

export interface LoginDTO {
  username: string
  password: string
}

export interface LoginResult {
  token: string
  admin: AdminInfo
}

export interface ChangePasswordDTO {
  oldPassword: string
  newPassword: string
}

/* ============================================================
 * 菜品 Dish
 * ============================================================ */
export interface Dish {
  id?: number
  storeId: number
  name: string
  price: number
  image?: string
  category?: string
  mealTypes?: string
  status?: number
  stock?: number | null
  maxPerOrder?: number | null
  /** 辣度:0=不辣,1=微辣,2=中辣,3=重辣 */
  spiceLevel?: number
  isDeleted?: number
  createdAt?: string
  updatedAt?: string
}

export interface DishCategory {
  id?: number
  storeId: number
  name: string
  sort?: number
  status?: number
  isDeleted?: number
  createdAt?: string
  updatedAt?: string
}

export interface DishQuery extends PageQuery {
  storeId: number
  category?: string
  mealType?: number
  status?: number
}

/* ============================================================
 * 订单 Order / OrderItem
 * ============================================================ */
export interface Order {
  id?: number
  orderNo?: string
  storeId: number
  employeeId: number
  date: string
  mealType: number
  totalAmount: number
  status: number
  /** 订单来源: 0-正常订餐, 1-未订餐用餐 */
  orderSource?: number
  pickupCode?: string
  createdAt?: string
  updatedAt?: string
  /** 非数据库字段:员工卡号(列表展示用,由后端填充) */
  cardNo?: string
  /** 非数据库字段:员工姓名(列表展示用,由后端填充) */
  employeeName?: string
}

export interface OrderItem {
  id?: number
  orderId?: number
  dishId: number
  dishName?: string
  price: number
  quantity: number
  createdAt?: string
}

export interface OrderItemDTO {
  dishId: number
  quantity: number
}

export interface OrderCreateDTO {
  employeeId: number
  storeId: number
  date: string
  mealType: number
  items: OrderItemDTO[]
}

export interface OrderDetail {
  order: Order
  items: OrderItem[]
}

export interface OrderQuery extends PageQuery {
  storeId: number
  status?: number
  mealType?: number
  date?: string
  startDate?: string
  endDate?: string
  employeeId?: number
  employeeName?: string
}

/** 订餐汇总单条菜品统计 */
export interface OrderSummaryItem {
  dishId: number | null
  dishName: string
  price: number | string
  quantity: number
  orderCount: number
}

/** 订餐汇总响应 */
export interface OrderSummary {
  date: string
  mealType?: number | null
  items: OrderSummaryItem[]
  totalQuantity: number
  totalOrders: number
  dishCount: number
}

export interface DashboardStats {
  [key: string]: unknown
}

/* ============================================================
 * 员工 Employee
 * ============================================================ */
export interface Employee {
  id?: number
  storeId: number
  cardNo: string
  /** 手机号(H5/小程序登录用,同店内唯一) */
  phone?: string
  name: string
  avatar?: string
  departmentId?: number
  /** 部门名称(后端填充,非持久化) */
  departmentName?: string
  /** 门店名称(超管全局视图时填充,非持久化) */
  storeName?: string
  balance?: number
  status?: number
  password?: string
  isDeleted?: number
  createdAt?: string
  updatedAt?: string
}

export interface EmployeeQuery extends PageQuery {
  storeId: number
  departmentId?: number
  status?: number
  cardNo?: string
}

export interface EmployeeLoginDTO {
  cardNo: string
}

/* ============================================================
 * 部门 Department
 * ============================================================ */
export interface Department {
  id?: number
  storeId: number
  name: string
  parentId?: number
  sort?: number
  status?: number
  createdAt?: string
  updatedAt?: string
}

/* ============================================================
 * 菜单 Menu / MenuItem
 * ============================================================ */
export interface Menu {
  id?: number
  storeId: number
  date: string
  mealType: number
  /** 发布状态:0=未发布(草稿),1=已发布 */
  published?: number
  createdAt?: string
  updatedAt?: string
}

export interface MenuItem {
  id?: number
  menuId: number
  dishId: number
  sortOrder?: number
  createdAt?: string
}

export interface MenuCreateDTO {
  storeId: number
  date: string
  mealType: number
  dishIds: number[]
}

/** 菜单复制 DTO */
export interface MenuCopyDTO {
  storeId: number
  sourceDate: string
  targetDate: string
  /** 是否覆盖目标日期已存在的菜单 */
  overwrite?: boolean
}

/** 后端 MenuWithItemsDTO.ItemView:条目 + 菜品详情 */
export interface MenuItemView {
  item: MenuItem
  dish?: Dish
}

export interface MenuWithItems {
  menu: Menu
  items: MenuItemView[]
}

export interface MenuByDateResult {
  [date: string]: MenuWithItems[] | unknown
}

/** 月历日期状态(后端 getMenuDatesByMonth 返回) */
export interface MenuDateStatus {
  date: string
  published: boolean
}

/* ============================================================
 * 通知 Notification
 * ============================================================ */
export interface Notification {
  id?: number
  storeId: number
  title: string
  content: string
  /** 配图 URL(dataURL 或外链) */
  imageUrl?: string
  type: number
  /** 1=启用,0=下架 */
  status?: number
  /** 上架时间,ISO 字符串;为空表示立即上架 */
  publishAt?: string | null
  /** 下架时间,ISO 字符串;为空表示不下架 */
  expireAt?: string | null
  /** 后端计算的展示状态:pending/active/expired/offline */
  displayStatus?: string
  createdAt?: string
  updatedAt?: string
}

export interface NotificationQuery extends PageQuery {
  storeId: number
  type?: number
  status?: number
}

/* ============================================================
 * 充值记录 RechargeRecord
 * ============================================================ */
export interface RechargeRecord {
  id?: number
  storeId: number
  employeeId: number
  amount: number
  balanceBefore?: number
  balanceAfter?: number
  operator?: string
  remark?: string
  employeeName?: string
  createdAt?: string
}

export interface RechargeCreateDTO {
  employeeId: number
  amount: number
  operator?: string
  storeId?: number
  remark?: string
}

export interface RechargeQuery extends PageQuery {
  storeId: number
  employeeId?: number
  startDate?: string
  endDate?: string
}

/* ============================================================
 * 门店 Store
 * ============================================================ */
export interface Store {
  id?: number
  name: string
  code?: string
  address?: string
  phone?: string
  /** 食堂安全码(用于终端绑定,超管可重置) */
  securityCode?: string
  status?: number
  /** 企业 Logo URL */
  logoUrl?: string
  /** 食堂展示图片 URL */
  imageUrl?: string
  /** 取餐终端主图/背景图 URL */
  terminalBackgroundUrl?: string
  /** H5 顶部 banner URL(可选) */
  h5BannerUrl?: string
  /** 食堂简介 */
  description?: string
  /** 早餐开始时间,格式 "07:00" */
  breakfastStart?: string
  /** 早餐结束时间,格式 "09:00" */
  breakfastEnd?: string
  /** 午餐开始时间,格式 "11:00" */
  lunchStart?: string
  /** 午餐结束时间,格式 "13:00" */
  lunchEnd?: string
  /** 晚餐开始时间,格式 "17:00" */
  dinnerStart?: string
  /** 晚餐结束时间,格式 "19:00" */
  dinnerEnd?: string
  createdAt?: string
  updatedAt?: string
}

/** 食堂品牌信息(公开接口返回) */
export interface StoreBranding {
  id: number
  name: string
  logoUrl?: string
  imageUrl?: string
  terminalBackgroundUrl?: string
  h5BannerUrl?: string
  description?: string
  updatedAt?: string
}

/** 文件上传响应 */
export interface UploadResult {
  url: string
  fileName: string
  size: number
}

/** 切换食堂响应 */
export interface SwitchStoreResult {
  storeId: number
  token: string
  storeName: string | null
}

/* ============================================================
 * 就餐时段 DiningTimeSlot
 * ============================================================ */
export interface DiningTimeSlot {
  id?: number
  storeId: number
  mealType: number
  startTime: string
  endTime: string
  createdAt?: string
  updatedAt?: string
}

/* ============================================================
 * 备份 Backup
 * ============================================================ */
export interface BackupInfo {
  name: string
  size: number
  sizeText?: string
  lastModified?: number
  lastModifiedText?: string
  createdTime?: string
  /** full | store */
  type?: string
  storeId?: number | null
  storeName?: string | null
  formatVersion?: string
  tableCount?: number
  totalRows?: number
}

export interface BackupDownloadInfo {
  name: string
  path: string
  size: number
  sizeText?: string
}

/* ============================================================
 * 系统 System
 * ============================================================ */
export interface SystemHealth {
  status: string
  timestamp: string
  database?: string
  databaseError?: string
  /** JVM 内存 */
  jvmMaxMemory?: number
  jvmUsedMemory?: number
  jvmFreeMemory?: number
  jvmMemoryUsagePercent?: number
  /** 系统级 CPU/内存 */
  cpuUsagePercent?: number
  processCpuUsagePercent?: number
  systemTotalMemory?: number
  systemUsedMemory?: number
  systemMemoryUsagePercent?: number
  availableProcessors?: number
  /** 磁盘占用 */
  diskTotal?: number
  diskUsed?: number
  diskFree?: number
  diskUsagePercent?: number
}

export interface SystemVersion {
  version: string
  buildTime?: string
  description?: string
  migrations?: Array<Record<string, unknown>>
  latestMigration?: string
  configs?: Record<string, string>
}

export interface SystemConfig {
  config_key: string
  config_value: string
  description?: string
}

/* ============================================================
 * 报表 Report
 * ============================================================ */
export interface ReportParams {
  storeId: number
  date?: string
  startDate?: string
  endDate?: string
  month?: string
  mealType?: number
}

export interface ReportData {
  [key: string]: unknown
}

/** 财务对账数据 */
export interface FinanceReport {
  totalRecharge: number
  totalConsumption: number
  totalRefund: number
  currentBalance: number
  netFlow: number
}

/** 员工消费统计 - 单条员工记录 */
export interface EmployeeConsumptionRow {
  employeeId: number
  employeeName: string
  departmentName: string
  totalConsumption: number
  orderCount: number
}

/** 员工消费统计响应 */
export interface EmployeeConsumptionReport {
  employees: EmployeeConsumptionRow[]
  totalConsumption: number
  totalOrders: number
}

/** 日终对账:餐次统计 */
export interface DailyCloseMealStat {
  mealType: number
  mealTypeName: string
  orderCount: number
  revenue: number
}

/** 日终对账:热销菜品 TOP10 */
export interface DailyCloseTopDish {
  dishName: string
  quantity: number
}

/** 日终对账数据 */
export interface DailyCloseReport {
  date: string
  totalOrders: number
  completedOrders: number
  canceledOrders: number
  pendingOrders: number
  totalRevenue: number
  totalRefund: number
  totalRecharge: number
  totalConsumption: number
  mealTypeStats: DailyCloseMealStat[]
  topDishes: DailyCloseTopDish[]
  openingBalance: number
  endingBalance: number
  balanceChange: number
}

/* ============================================================
 * 同比/环比分析 YoY / MoM
 * ============================================================ */

/** 同/环比对比汇总(单期) */
export interface ComparisonSummary {
  orderCount: number
  revenue: number
  refund: number
}

/** 同/环比增长率(基期为 0 时字段为 null) */
export interface ComparisonGrowth {
  orderCountGrowth: number | null
  revenueGrowth: number | null
  refundGrowth: number | null
}

/** 同/环比分析响应 */
export interface ComparisonReport {
  current: ComparisonSummary
  previous: ComparisonSummary
  growth: ComparisonGrowth
}

/* ============================================================
 * 拥堵分析 Hourly / Peak
 * ============================================================ */

/** 某日单小时订单数 */
export interface HourlyBucket {
  hour: number
  count: number
}

/** 某日时段分布响应 */
export interface HourlyDistributionReport {
  date: string
  hourly: HourlyBucket[]
  totalOrders: number
  peakHour: number
  peakCount: number
}

/** 高峰时段分析-单小时统计 */
export interface PeakHourBucket {
  hour: number
  totalOrders: number
  avgOrders: number
  isPeak: boolean
}

/** 高峰时段分析响应 */
export interface PeakHoursReport {
  startDate: string
  endDate: string
  days: number
  totalOrders: number
  avgOrdersPerHour: number
  avgOrdersPerHourPerDay: number
  threshold: number
  hours: PeakHourBucket[]
  peakHours: PeakHourBucket[]
}

/* ============================================================
 * 供应商 Supplier
 * ============================================================ */
export interface Supplier {
  id?: number
  storeId: number
  name: string
  contactPerson?: string
  phone?: string
  address?: string
  category?: string
  /** 1=合作中 0=已停用 */
  status?: number
  remark?: string
  createdAt?: string
  updatedAt?: string
}

export interface SupplierQuery extends PageQuery {
  storeId: number
}

/* ============================================================
 * 采购单 Purchase / PurchaseItem
 * ============================================================ */
export interface Purchase {
  id?: number
  storeId: number
  purchaseNo?: string
  supplierId: number
  totalAmount?: number
  purchaseDate?: string
  /** 1=待入库 2=已入库 3=已取消 */
  status?: number
  remark?: string
  operatorId?: number
  /** 关联查询字段 */
  supplierName?: string
  operatorName?: string
  createdAt?: string
  updatedAt?: string
}

export interface PurchaseItem {
  id?: number
  purchaseId?: number
  /** 关联食材ID(入库时自动增加该食材库存) */
  materialId?: number
  materialName: string
  unit?: string
  quantity: number
  price: number
  amount?: number
}

export interface PurchaseCreateDTO {
  purchase: Purchase
  items: PurchaseItem[]
}

export interface PurchaseDetail {
  purchase: Purchase
  items: PurchaseItem[]
}

export interface PurchaseQuery extends PageQuery {
  storeId: number
  status?: number
  startDate?: string
  endDate?: string
}

/* ============================================================
 * 库存食材 Material
 * ============================================================ */
export interface Material {
  id?: number
  storeId: number
  name: string
  unit?: string
  stockQty?: number
  minStock?: number
  category?: string
  createdAt?: string
  updatedAt?: string
}

export interface MaterialQuery extends PageQuery {
  storeId: number
  /** true=仅显示预警 */
  lowStock?: boolean
}

/** 库存盘点记录 */
export interface StockCount {
  id?: number
  storeId?: number
  materialId?: number
  materialName?: string
  systemQty?: number
  countedQty?: number
  difference?: number
  /** 1=待处理 2=已处理 */
  status?: number
  operatorId?: number
  remark?: string
  createdAt?: string
  resolvedAt?: string
}

/* ============================================================
 * 员工反馈/评价 Feedback
 * ============================================================ */
export interface Feedback {
  id?: number
  storeId: number
  employeeId: number
  /** 关联订单(可为空) */
  orderId?: number | null
  /** 关联菜品(可为空) */
  dishId?: number | null
  /** 评分 1-5 */
  rating: number
  /** 反馈内容 */
  content?: string
  /** 1=菜品评价 2=服务投诉 3=建议 4=其他 */
  category?: number
  /** 1=待处理 2=已处理 3=已忽略 */
  status?: number
  /** 管理员回复 */
  reply?: string
  replyAdminId?: number | null
  repliedAt?: string | null
  createdAt?: string
  /** 关联查询字段 */
  employeeName?: string
  dishName?: string
}

export interface FeedbackQuery extends PageQuery {
  storeId: number
  status?: number
  category?: number
}

export interface FeedbackStats {
  total: number
  pending: number
  avgRating: number
  categoryStats: {
    dish: number
    service: number
    suggestion: number
    other: number
  }
}

/* ============================================================
 * 团体订餐/会议餐 GroupOrder / GroupOrderItem
 * ============================================================ */
export interface GroupOrder {
  id?: number
  storeId: number
  /** 团体订单号 */
  orderNo?: string
  /** 订单标题 */
  title: string
  /** 组织人(员工ID) */
  organizerId?: number | null
  /** 用餐人数 */
  headcount: number
  /** 用餐日期 */
  mealDate: string
  /** 1早 2中 3晚 */
  mealType: number
  /** 用餐地点 */
  location?: string
  /** 总金额 */
  totalAmount?: number
  /** 1=待确认 2=已确认 3=已取消 4=已完成 */
  status?: number
  /** 备注(特殊要求) */
  remark?: string
  /** 操作人(adminId) */
  operatorId?: number | null
  createdAt?: string
  updatedAt?: string
  /** 关联查询字段 */
  organizerName?: string
  operatorName?: string
}

export interface GroupOrderItem {
  id?: number
  groupOrderId?: number
  dishId: number
  dishName?: string
  price?: number
  /** 份数 */
  quantity: number
  /** 小计 */
  amount?: number
}

export interface GroupOrderCreateDTO {
  groupOrder: GroupOrder
  items: GroupOrderItem[]
}

export interface GroupOrderDetail {
  groupOrder: GroupOrder
  items: GroupOrderItem[]
}

export interface GroupOrderQuery extends PageQuery {
  storeId: number
  status?: number
  startDate?: string
  endDate?: string
}

/* ============================================================
 * 日终对账 DailyClose
 * ============================================================ */

/** 日终对账汇总:菜品销量 TOP5 单条 */
export interface DailyCloseDishSale {
  dishName: string
  quantity: number
  amount: number
}

/** 日终对账汇总 */
export interface DailyCloseSummary {
  date: string
  storeId: number
  orderCount: number
  paidCount: number
  completedCount: number
  cancelledCount: number
  /** 未就餐订单数(超时未核销,已付款未退款) */
  missedCount?: number
  totalRevenue: number
  totalRefund: number
  rechargeAmount: number
  newEmployeeCount: number
  dishSales: DailyCloseDishSale[]
}

/** 日终对账历史记录(daily_close 表) */
export interface DailyCloseRecord {
  id?: number
  storeId: number
  closeDate: string
  orderCount?: number
  totalRevenue?: number
  totalRefund?: number
  rechargeAmount?: number
  status?: number
  operatorId?: number
  remark?: string
  createdAt?: string
}

/** 日终对账历史分页结果 */
export interface DailyCloseHistory {
  records: DailyCloseRecord[]
  total: number
  page: number
  size: number
}

/* ============================================================
 * 日终对账/关店流程 DailySettlement
 * 三阶段:1=待对账 2=已对账 3=已关店
 * ============================================================ */

/** 日终对账记录(daily_settlement 表) */
export interface DailySettlement {
  id?: number
  storeId: number
  settleDate: string
  totalRevenue?: number
  totalRefund?: number
  totalRecharge?: number
  totalConsumption?: number
  cashRevenue?: number
  onlineRevenue?: number
  orderCount?: number
  completedCount?: number
  cancelledCount?: number
  servedCount?: number
  operatorId?: number
  status?: number
  remark?: string
  settledAt?: string
  closedAt?: string
  createdAt?: string
  updatedAt?: string
  /** 关联查询字段:操作人姓名 */
  operatorName?: string
}

/** 今日对账状态 */
export interface DailySettlementStatus {
  date: string
  /** null=未对账 1=待对账 2=已对账 3=已关店 */
  status?: number | null
  statusText: string
  id?: number
  settledAt?: string | null
  closedAt?: string | null
}

/** 对账历史分页结果 */
export interface DailySettlementHistory {
  records: DailySettlement[]
  total: number
  page: number
  size: number
}
