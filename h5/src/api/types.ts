/**
 * 全局 API 类型定义
 * 字段参考后端实体 com.example.canteen.entity.* 与 dto.*
 *
 * 注意:后端 ApiResponse.success() 返回 code=200(非 0),code=401 表示未登录,
 * code=403 表示无权限,其余非 200 code 均视为业务错误。
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

/* ============================================================
 * 员工 Employee(对应后端 EmployeeVO)
 * ============================================================ */
export interface Employee {
  id: number
  storeId: number
  cardNo: string
  /** 手机号(H5/小程序登录用,同店内唯一) */
  phone?: string
  name: string
  avatar?: string
  departmentId?: number
  /** 部门名称(关联查询,后端可能不返回,H5 端展示用) */
  departmentName?: string
  balance?: number
  status?: number
  createdAt?: string
  updatedAt?: string
}

/** 员工登录结果(卡号/手机号登录返回) */
export interface EmployeeLoginResult {
  token: string
  employee: Employee
}

/* ============================================================
 * 门店 Store(对应后端 entity.Store,公开接口字段子集)
 * ============================================================ */
export interface Store {
  id: number
  name: string
  code?: string
  address?: string
  phone?: string
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

/** 公开接口返回的食堂列表项(字段子集,无敏感字段) */
export interface PublicStore {
  id: number
  name: string
  code?: string
  address?: string
  logoUrl?: string
  imageUrl?: string
  description?: string
}

/* ============================================================
 * 食堂品牌信息 Branding(对应后端 /store/{id}/branding 接口返回)
 * ============================================================ */
export interface Branding {
  id: number
  name: string
  logoUrl?: string
  imageUrl?: string
  terminalBackgroundUrl?: string
  h5BannerUrl?: string
  description?: string
  updatedAt?: string
}

/* ============================================================
 * 菜品 Dish / 分类 DishCategory
 * ============================================================ */
export interface Dish {
  id: number
  storeId: number
  name: string
  price: number
  /** 图片相对路径(/uploads/xxx)或外链 */
  image?: string
  /** 图片完整 URL(前端拼接后端域名后的展示地址) */
  imageUrl?: string
  category?: string
  /** 适用餐次(逗号分隔:1=早餐,2=午餐,3=晚餐),如 "1,2,3" */
  mealTypes?: string
  /** 是否新品:1=是 0=否 */
  isNew?: number
  /** 是否特价/推荐:1=是 0=否(H5 端展示用,后端 Dish 实体未直接含此字段,保留兼容) */
  isSpecial?: number
  /** 上架状态:1=上架 0=下架 */
  status?: number
  stock?: number | null
  maxPerOrder?: number | null
  description?: string
  createdAt?: string
  updatedAt?: string
}

export interface DishCategory {
  id: number
  storeId: number
  name: string
  sort?: number
  status?: number
  createdAt?: string
  updatedAt?: string
}

/* ============================================================
 * 菜单 Menu / MenuItem / MenuWithItems
 * 对应后端 MenuWithItemsDTO
 * ============================================================ */
export interface Menu {
  id: number
  storeId: number
  date: string
  mealType: number
  createdAt?: string
  updatedAt?: string
}

export interface MenuItem {
  id: number
  menuId: number
  dishId: number
  sortOrder?: number
  /** 关联查询的菜品详情(后端 MenuWithItemsDTO.ItemView.dish) */
  dish?: Dish
  createdAt?: string
}

/** 后端 MenuWithItemsDTO.ItemView:条目 + 菜品详情 */
export interface MenuItemView {
  item: MenuItem
  dish?: Dish
}

/** 后端 MenuWithItemsDTO:菜单 + 条目列表 */
export interface MenuWithItems {
  menu: Menu
  items: MenuItemView[]
}

/* ============================================================
 * 就餐时段 DiningTimeSlot
 * ============================================================ */
export interface DiningTimeSlot {
  id: number
  storeId: number
  mealType: number
  startTime: string
  endTime: string
  /** 展示标签(前端由 mealType 推导,后端无此字段) */
  label?: string
  createdAt?: string
  updatedAt?: string
}

/* ============================================================
 * 订单 Order / OrderItem
 * ============================================================ */
export interface Order {
  id: number
  orderNo?: string
  storeId: number
  employeeId: number
  date: string
  mealType: number
  totalAmount: number
  /** 1=待取餐 2=已完成 3=已取消 */
  status: number
  pickupCode?: string
  /** 关联查询字段:员工姓名 */
  employeeName?: string
  /** 订单明细(详情接口返回时填充) */
  items?: OrderItem[]
  createdAt?: string
  updatedAt?: string
}

export interface OrderItem {
  id: number
  orderId?: number
  dishId: number
  dishName?: string
  price: number
  quantity: number
  /** 小计 = price * quantity(后端可能不返回,前端计算) */
  amount?: number
  /** 菜品图片相对路径(后端关联 dish 表查询填充,订单详情展示用) */
  dishImage?: string
  createdAt?: string
}

/** 订单创建 DTO(对应后端 OrderCreateDTO) */
export interface OrderItemDTO {
  dishId: number
  quantity: number
}

export interface OrderCreateDTO {
  storeId: number
  employeeId: number
  date: string
  mealType: number
  items: OrderItemDTO[]
  /** 订单来源: 0-正常订餐(默认), 1-未订餐用餐 */
  orderSource?: number
}

/** 订单详情(后端返回 { order, items }) */
export interface OrderDetail {
  order: Order
  items: OrderItem[]
}

/* ============================================================
 * 充值记录 RechargeRecord
 * ============================================================ */
export interface RechargeRecord {
  id: number
  storeId: number
  employeeId: number
  amount: number
  balanceBefore?: number
  balanceAfter?: number
  /** 充值类型(后端实体暂无此字段,保留兼容:H5 端展示用) */
  type?: string
  operator?: string
  createdAt?: string
}

/* ============================================================
 * 通知 Notification
 * ============================================================ */
export interface Notification {
  id: number
  storeId: number
  title: string
  content: string
  imageUrl?: string
  type?: number
  status?: number
  /** 上架时间,ISO 字符串;为空表示立即上架 */
  startDate?: string | null
  /** 下架时间,ISO 字符串;为空表示不下架 */
  endDate?: string | null
  /** 后端计算的展示状态:pending/active/expired/offline */
  displayStatus?: string
  createdAt?: string
  updatedAt?: string
}

/* ============================================================
 * 反馈 Feedback
 * ============================================================ */
export interface Feedback {
  id?: number
  storeId?: number
  employeeId?: number
  orderId?: number | null
  dishId?: number | null
  /** 评分 1-5 */
  rating: number
  content?: string
  /** 1=菜品评价 2=服务投诉 3=建议 4=其他 */
  category?: number
  /** 1=待处理 2=已处理 3=已忽略 */
  status?: number
  /** 管理员回复 */
  reply?: string
  /** 回复时间 */
  replyAt?: string
  createdAt?: string
  /** 关联查询字段 */
  employeeName?: string
  dishName?: string
}

/* ============================================================
 * 团体订餐 GroupOrder / GroupOrderItem / GroupOrderDetail
 * ============================================================ */
export interface GroupOrder {
  id: number
  storeId: number
  orderNo?: string
  /** 订单标题(如"3楼会议室会议餐") */
  title: string
  /** 联系人(组织人姓名) */
  contactPerson?: string
  /** 联系电话 */
  contactPhone?: string
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
  notes?: string
  createdAt?: string
  updatedAt?: string
  /** 关联查询字段 */
  organizerName?: string
  operatorName?: string
}

export interface GroupOrderItem {
  id: number
  groupOrderId?: number
  dishId: number
  dishName?: string
  price?: number
  /** 份数 */
  quantity: number
  /** 小计 */
  amount?: number
}

export interface GroupOrderDetail {
  groupOrder: GroupOrder
  items: GroupOrderItem[]
}

/* ============================================================
 * 员工身份二维码(对应后端 /employee/my-qrcode 返回)
 * ============================================================ */
export interface EmployeeQrcode {
  cardNo: string
  storeId: number
  employeeId: number
  name: string
  expire: number
  sign: string
}

/* ============================================================
 * 通用枚举常量(供格式化与 UI 使用)
 * ============================================================ */
export const MealType = {
  Breakfast: 1,
  Lunch: 2,
  Dinner: 3,
} as const

export type MealTypeValue = (typeof MealType)[keyof typeof MealType]

export const OrderStatus = {
  /** 待取餐 */
  Pending: 1,
  /** 已完成 */
  Completed: 2,
  /** 已取消 */
  Cancelled: 3,
} as const

export type OrderStatusValue = (typeof OrderStatus)[keyof typeof OrderStatus]
