package com.example.canteen.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.canteen.entity.Store;
import com.example.canteen.mapper.AdminMapper;
import com.example.canteen.mapper.DailyCloseMapper;
import com.example.canteen.mapper.DailySettlementMapper;
import com.example.canteen.mapper.DepartmentMapper;
import com.example.canteen.mapper.DiningTimeSlotMapper;
import com.example.canteen.mapper.DishCategoryMapper;
import com.example.canteen.mapper.DishMapper;
import com.example.canteen.mapper.EmployeeMapper;
import com.example.canteen.mapper.FeedbackMapper;
import com.example.canteen.mapper.GroupOrderMapper;
import com.example.canteen.mapper.MaterialMapper;
import com.example.canteen.mapper.MenuMapper;
import com.example.canteen.mapper.NotificationMapper;
import com.example.canteen.mapper.OrderMapper;
import com.example.canteen.mapper.PurchaseMapper;
import com.example.canteen.mapper.RechargeRecordMapper;
import com.example.canteen.mapper.StockCountMapper;
import com.example.canteen.mapper.StoreMapper;
import com.example.canteen.mapper.SupplierMapper;
import com.example.canteen.entity.Admin;
import com.example.canteen.entity.DailyClose;
import com.example.canteen.entity.DailySettlement;
import com.example.canteen.entity.Department;
import com.example.canteen.entity.DiningTimeSlot;
import com.example.canteen.entity.Dish;
import com.example.canteen.entity.DishCategory;
import com.example.canteen.entity.Employee;
import com.example.canteen.entity.Feedback;
import com.example.canteen.entity.GroupOrder;
import com.example.canteen.entity.Material;
import com.example.canteen.entity.Menu;
import com.example.canteen.entity.Notification;
import com.example.canteen.entity.Order;
import com.example.canteen.entity.Purchase;
import com.example.canteen.entity.RechargeRecord;
import com.example.canteen.entity.StockCount;
import com.example.canteen.entity.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class StoreService {
    private static final Logger log = LoggerFactory.getLogger(StoreService.class);

    private final StoreMapper storeMapper;
    private final AdminMapper adminMapper;
    private final EmployeeMapper employeeMapper;
    private final DishMapper dishMapper;
    private final DishCategoryMapper dishCategoryMapper;
    private final DepartmentMapper departmentMapper;
    private final MenuMapper menuMapper;
    private final OrderMapper orderMapper;
    private final RechargeRecordMapper rechargeRecordMapper;
    private final NotificationMapper notificationMapper;
    private final DiningTimeSlotMapper diningTimeSlotMapper;
    private final SupplierMapper supplierMapper;
    private final PurchaseMapper purchaseMapper;
    private final MaterialMapper materialMapper;
    private final FeedbackMapper feedbackMapper;
    private final GroupOrderMapper groupOrderMapper;
    private final DailyCloseMapper dailyCloseMapper;
    private final DailySettlementMapper dailySettlementMapper;
    private final StockCountMapper stockCountMapper;

    public StoreService(StoreMapper storeMapper,
                        AdminMapper adminMapper,
                        EmployeeMapper employeeMapper,
                        DishMapper dishMapper,
                        DishCategoryMapper dishCategoryMapper,
                        DepartmentMapper departmentMapper,
                        MenuMapper menuMapper,
                        OrderMapper orderMapper,
                        RechargeRecordMapper rechargeRecordMapper,
                        NotificationMapper notificationMapper,
                        DiningTimeSlotMapper diningTimeSlotMapper,
                        SupplierMapper supplierMapper,
                        PurchaseMapper purchaseMapper,
                        MaterialMapper materialMapper,
                        FeedbackMapper feedbackMapper,
                        GroupOrderMapper groupOrderMapper,
                        DailyCloseMapper dailyCloseMapper,
                        DailySettlementMapper dailySettlementMapper,
                        StockCountMapper stockCountMapper) {
        this.storeMapper = storeMapper;
        this.adminMapper = adminMapper;
        this.employeeMapper = employeeMapper;
        this.dishMapper = dishMapper;
        this.dishCategoryMapper = dishCategoryMapper;
        this.departmentMapper = departmentMapper;
        this.menuMapper = menuMapper;
        this.orderMapper = orderMapper;
        this.rechargeRecordMapper = rechargeRecordMapper;
        this.notificationMapper = notificationMapper;
        this.diningTimeSlotMapper = diningTimeSlotMapper;
        this.supplierMapper = supplierMapper;
        this.purchaseMapper = purchaseMapper;
        this.materialMapper = materialMapper;
        this.feedbackMapper = feedbackMapper;
        this.groupOrderMapper = groupOrderMapper;
        this.dailyCloseMapper = dailyCloseMapper;
        this.dailySettlementMapper = dailySettlementMapper;
        this.stockCountMapper = stockCountMapper;
    }

    public List<Store> getAllStores() {
        // 超管管理需要看到所有食堂(含停用),不在此处过滤 status
        return storeMapper.selectList(null);
    }

    /** 公开列表:H5/小程序登录页选择食堂用,只返回营业中的食堂 */
    public List<Store> getActiveStores() {
        return storeMapper.selectList(new LambdaQueryWrapper<Store>().eq(Store::getStatus, 1));
    }

    public Store getStoreById(Long id) {
        return storeMapper.selectById(id);
    }

    public Store createStore(Store store) {
        LocalDateTime now = LocalDateTime.now();
        if (store.getCreatedAt() == null) store.setCreatedAt(now);
        store.setUpdatedAt(now);
        storeMapper.insert(store);
        return store;
    }

    public Store updateStore(Store store) {
        // 显式更新 updatedAt,作为 ETag 源(branding 接口 ETag 基于 updatedAt 计算)
        store.setUpdatedAt(LocalDateTime.now());
        storeMapper.updateById(store);
        return store;
    }

    /**
     * 删除食堂并清理全部关联数据。
     *
     * 修复历史 BUG:此前仅 storeMapper.deleteById(id) 物理删除 store 表一条记录,
     * 不清理 admin/employee/dish/order 等 19+ 张关联表的 store_id 残留,
     * 导致门店管理员仍可登录并继续添加内容,产生孤儿数据(指向不存在的食堂)。
     *
     * 清理策略:
     * 1) admin:将该店管理员的 store_id 置 0 并禁用账号,防止残留旧值登录
     * 2) employee/dish/department:软删除(is_deleted=1),保留审计痕迹
     * 3) 其余关联表:物理删除,避免 id 复用导致跨店数据串扰
     * 4) 最后物理删除 store 表记录
     *
     * 全程 @Transactional 保证原子性:任一清理失败则整体回滚,不留下半清理状态。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteStore(Long id) {
        Store store = storeMapper.selectById(id);
        if (store == null) {
            throw new com.example.canteen.exception.BusinessException("食堂不存在");
        }
        log.warn("开始删除食堂 id={}, name={} 并清理关联数据", id, store.getName());

        // 1) admin:该店管理员账号 store_id 置 0 并禁用,防止残留旧值登录
        Admin adminUpdate = new Admin();
        adminUpdate.setStoreId(0L);
        adminUpdate.setStatus(0);
        adminMapper.update(adminUpdate,
                new LambdaUpdateWrapper<Admin>().eq(Admin::getStoreId, id));

        // 2) employee:软删除(is_deleted=1),保留审计痕迹
        Employee employeeUpdate = new Employee();
        employeeUpdate.setIsDeleted(1);
        employeeMapper.update(employeeUpdate,
                new LambdaUpdateWrapper<Employee>().eq(Employee::getStoreId, id));

        // 3) dish:软删除
        Dish dishUpdate = new Dish();
        dishUpdate.setIsDeleted(1);
        dishMapper.update(dishUpdate,
                new LambdaUpdateWrapper<Dish>().eq(Dish::getStoreId, id));

        // 4) 其余关联表:物理删除
        dishCategoryMapper.delete(new LambdaQueryWrapper<DishCategory>().eq(DishCategory::getStoreId, id));
        departmentMapper.delete(new LambdaQueryWrapper<Department>().eq(Department::getStoreId, id));
        menuMapper.delete(new LambdaQueryWrapper<Menu>().eq(Menu::getStoreId, id));
        orderMapper.delete(new LambdaQueryWrapper<Order>().eq(Order::getStoreId, id));
        rechargeRecordMapper.delete(new LambdaQueryWrapper<RechargeRecord>().eq(RechargeRecord::getStoreId, id));
        notificationMapper.delete(new LambdaQueryWrapper<Notification>().eq(Notification::getStoreId, id));
        diningTimeSlotMapper.delete(new LambdaQueryWrapper<DiningTimeSlot>().eq(DiningTimeSlot::getStoreId, id));
        supplierMapper.delete(new LambdaQueryWrapper<Supplier>().eq(Supplier::getStoreId, id));
        purchaseMapper.delete(new LambdaQueryWrapper<Purchase>().eq(Purchase::getStoreId, id));
        materialMapper.delete(new LambdaQueryWrapper<Material>().eq(Material::getStoreId, id));
        feedbackMapper.delete(new LambdaQueryWrapper<Feedback>().eq(Feedback::getStoreId, id));
        groupOrderMapper.delete(new LambdaQueryWrapper<GroupOrder>().eq(GroupOrder::getStoreId, id));
        dailyCloseMapper.delete(new LambdaQueryWrapper<DailyClose>().eq(DailyClose::getStoreId, id));
        dailySettlementMapper.delete(new LambdaQueryWrapper<DailySettlement>().eq(DailySettlement::getStoreId, id));
        stockCountMapper.delete(new LambdaQueryWrapper<StockCount>().eq(StockCount::getStoreId, id));

        // 5) 最后物理删除 store 表记录
        storeMapper.deleteById(id);
        log.warn("食堂 id={}, name={} 及其关联数据已全部清理", id, store.getName());
    }
}
