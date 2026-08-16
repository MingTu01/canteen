package com.example.canteen.service;

import com.example.canteen.entity.Admin;
import com.example.canteen.entity.DailySettlement;
import com.example.canteen.entity.Department;
import com.example.canteen.entity.DiningTimeSlot;
import com.example.canteen.entity.Dish;
import com.example.canteen.entity.DishCategory;
import com.example.canteen.entity.Employee;
import com.example.canteen.entity.Feedback;
import com.example.canteen.entity.GroupOrder;
import com.example.canteen.entity.Menu;
import com.example.canteen.entity.Notification;
import com.example.canteen.entity.Order;
import com.example.canteen.entity.Purchase;
import com.example.canteen.entity.Store;
import com.example.canteen.entity.Supplier;
import com.example.canteen.mapper.AdminMapper;
import com.example.canteen.mapper.DailySettlementMapper;
import com.example.canteen.mapper.DepartmentMapper;
import com.example.canteen.mapper.DiningTimeSlotMapper;
import com.example.canteen.mapper.DishCategoryMapper;
import com.example.canteen.mapper.DishMapper;
import com.example.canteen.mapper.EmployeeMapper;
import com.example.canteen.mapper.FeedbackMapper;
import com.example.canteen.mapper.GroupOrderMapper;
import com.example.canteen.mapper.MenuMapper;
import com.example.canteen.mapper.NotificationMapper;
import com.example.canteen.mapper.OrderMapper;
import com.example.canteen.mapper.PurchaseMapper;
import com.example.canteen.mapper.StoreMapper;
import com.example.canteen.mapper.SupplierMapper;
import org.springframework.stereotype.Component;

/**
 * 操作日志详情名称解析器。
 * 供 OperationLogAspect 注册为 SpEL 变量,使日志模板可将实体 ID 解析为人类可读名称。
 * 原则:日志必须"可读、可搜索"(单号/姓名/标题),禁止裸内部 ID;
 * 实体可能已被删除,所有方法查不到时回退为 "xxID {id}" 保证日志不空。
 */
@Component
public class LogDetailResolver {

    private final EmployeeMapper employeeMapper;
    private final StoreMapper storeMapper;
    private final SupplierMapper supplierMapper;
    private final PurchaseMapper purchaseMapper;
    private final OrderMapper orderMapper;
    private final NotificationMapper notificationMapper;
    private final DishMapper dishMapper;
    private final DepartmentMapper departmentMapper;
    private final DishCategoryMapper dishCategoryMapper;
    private final DiningTimeSlotMapper diningTimeSlotMapper;
    private final AdminMapper adminMapper;
    private final FeedbackMapper feedbackMapper;
    private final GroupOrderMapper groupOrderMapper;
    private final DailySettlementMapper dailySettlementMapper;
    private final MenuMapper menuMapper;

    public LogDetailResolver(EmployeeMapper employeeMapper,
                             StoreMapper storeMapper,
                             SupplierMapper supplierMapper,
                             PurchaseMapper purchaseMapper,
                             OrderMapper orderMapper,
                             NotificationMapper notificationMapper,
                             DishMapper dishMapper,
                             DepartmentMapper departmentMapper,
                             DishCategoryMapper dishCategoryMapper,
                             DiningTimeSlotMapper diningTimeSlotMapper,
                             AdminMapper adminMapper,
                             FeedbackMapper feedbackMapper,
                             GroupOrderMapper groupOrderMapper,
                             DailySettlementMapper dailySettlementMapper,
                             MenuMapper menuMapper) {
        this.employeeMapper = employeeMapper;
        this.storeMapper = storeMapper;
        this.supplierMapper = supplierMapper;
        this.purchaseMapper = purchaseMapper;
        this.orderMapper = orderMapper;
        this.notificationMapper = notificationMapper;
        this.dishMapper = dishMapper;
        this.departmentMapper = departmentMapper;
        this.dishCategoryMapper = dishCategoryMapper;
        this.diningTimeSlotMapper = diningTimeSlotMapper;
        this.adminMapper = adminMapper;
        this.feedbackMapper = feedbackMapper;
        this.groupOrderMapper = groupOrderMapper;
        this.dailySettlementMapper = dailySettlementMapper;
        this.menuMapper = menuMapper;
    }

    /** 餐次:1-早餐 2-午餐 3-晚餐 */
    public String mealType(Integer type) {
        if (type == null) return "";
        switch (type) {
            case 1: return "早餐";
            case 2: return "午餐";
            case 3: return "晚餐";
            default: return "餐次" + type;
        }
    }

    /** 采购单状态:1-待入库 2-已入库 3-已取消 */
    public String purchaseStatus(Integer status) {
        if (status == null) return "";
        switch (status) {
            case 1: return "待入库";
            case 2: return "已入库";
            case 3: return "已取消";
            default: return "状态" + status;
        }
    }

    /** 反馈分类:1-菜品评价 2-服务投诉 3-建议 4-其他 */
    public String feedbackCategory(Integer category) {
        if (category == null) return "";
        switch (category) {
            case 1: return "菜品评价";
            case 2: return "服务投诉";
            case 3: return "建议";
            case 4: return "其他";
            default: return "分类" + category;
        }
    }

    /** 员工姓名,查不到则返回 "员工ID xxx" */
    public String employeeName(Long id) {
        if (id == null) return "";
        Employee e = employeeMapper.selectById(id);
        return e != null ? e.getName() : "员工ID " + id;
    }

    /** 食堂名称 */
    public String storeName(Long id) {
        if (id == null) return "";
        Store s = storeMapper.selectById(id);
        return s != null ? s.getName() : "门店ID " + id;
    }

    /** 供应商名称 */
    public String supplierName(Long id) {
        if (id == null) return "";
        Supplier s = supplierMapper.selectById(id);
        return s != null ? s.getName() : "供应商ID " + id;
    }

    /** 采购单号 */
    public String purchaseNo(Long id) {
        if (id == null) return "";
        Purchase p = purchaseMapper.selectById(id);
        return p != null ? (p.getPurchaseNo() != null ? p.getPurchaseNo() : "采购单" + id) : "采购单ID " + id;
    }

    /** 订单摘要:单号(员工,日期 餐次),可按单号/姓名直接搜索 */
    public String orderBrief(Long id) {
        if (id == null) return "";
        Order o = orderMapper.selectById(id);
        if (o == null) return "订单ID " + id;
        String no = o.getOrderNo() != null ? o.getOrderNo() : String.valueOf(id);
        String emp = o.getEmployeeId() != null ? employeeName(o.getEmployeeId()) : "";
        String meal = mealType(o.getMealType());
        String date = o.getDate() != null ? o.getDate().toString() : "";
        StringBuilder sb = new StringBuilder("单号 ").append(no);
        if (!emp.isEmpty()) sb.append("(").append(emp);
        if (!date.isEmpty() || !meal.isEmpty()) {
            sb.append(sb.indexOf("(") >= 0 ? "," : "(");
            if (!date.isEmpty()) sb.append(date);
            if (!meal.isEmpty()) sb.append(date.isEmpty() ? "" : " ").append(meal);
        }
        if (sb.indexOf("(") >= 0) sb.append(")");
        return sb.toString();
    }

    /** 通知标题 */
    public String notificationTitle(Long id) {
        if (id == null) return "";
        Notification n = notificationMapper.selectById(id);
        return n != null && n.getTitle() != null ? n.getTitle() : "通知ID " + id;
    }

    /** 菜品名称 */
    public String dishName(Long id) {
        if (id == null) return "";
        Dish d = dishMapper.selectById(id);
        return d != null && d.getName() != null ? d.getName() : "菜品ID " + id;
    }

    /** 部门名称 */
    public String departmentName(Long id) {
        if (id == null) return "";
        Department d = departmentMapper.selectById(id);
        return d != null && d.getName() != null ? d.getName() : "部门ID " + id;
    }

    /** 菜品分类名称 */
    public String dishCategoryName(Long id) {
        if (id == null) return "";
        DishCategory c = dishCategoryMapper.selectById(id);
        return c != null && c.getName() != null ? c.getName() : "分类ID " + id;
    }

    /** 就餐时段摘要:门店 餐次 HH:mm-HH:mm(实体无 name 字段) */
    public String timeSlotName(Long id) {
        if (id == null) return "";
        DiningTimeSlot t = diningTimeSlotMapper.selectById(id);
        if (t == null) return "时段ID " + id;
        return storeName(t.getStoreId()) + " " + mealType(t.getMealType())
                + " " + (t.getStartTime() != null ? t.getStartTime() : "")
                + "-" + (t.getEndTime() != null ? t.getEndTime() : "");
    }

    /** 管理员摘要:姓名(账号) */
    public String adminBrief(Long id) {
        if (id == null) return "";
        Admin a = adminMapper.selectById(id);
        if (a == null) return "管理员ID " + id;
        return a.getName() != null ? a.getName() + "(" + a.getUsername() + ")" : a.getUsername();
    }

    /** 反馈摘要:#id 员工 分类 */
    public String feedbackBrief(Long id) {
        if (id == null) return "";
        Feedback f = feedbackMapper.selectById(id);
        if (f == null) return "反馈ID " + id;
        StringBuilder sb = new StringBuilder("#").append(id);
        if (f.getEmployeeId() != null) sb.append(" ").append(employeeName(f.getEmployeeId()));
        String cat = feedbackCategory(f.getCategory());
        if (!cat.isEmpty()) sb.append(" ").append(cat);
        return sb.toString();
    }

    /** 团餐订单摘要:标题(单号) */
    public String groupOrderBrief(Long id) {
        if (id == null) return "";
        GroupOrder g = groupOrderMapper.selectById(id);
        if (g == null) return "团餐ID " + id;
        if (g.getTitle() == null) return "团餐ID " + id;
        return g.getOrderNo() != null ? g.getTitle() + "(单号" + g.getOrderNo() + ")" : g.getTitle();
    }

    /** 对账摘要:门店 日期 对账单 */
    public String settlementBrief(Long id) {
        if (id == null) return "";
        DailySettlement s = dailySettlementMapper.selectById(id);
        if (s == null) return "对账ID " + id;
        return storeName(s.getStoreId()) + " " + (s.getSettleDate() != null ? s.getSettleDate() : "") + " 对账单";
    }

    /** 菜单摘要:门店 日期 餐次 */
    public String menuBrief(Long id) {
        if (id == null) return "";
        Menu m = menuMapper.selectById(id);
        if (m == null) return "菜单ID " + id;
        return storeName(m.getStoreId()) + " " + (m.getDate() != null ? m.getDate() : "") + mealType(m.getMealType()) + "菜单";
    }
}
