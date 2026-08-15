package com.example.canteen.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.canteen.entity.Store;
import com.example.canteen.mapper.AdminMapper;
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
import org.springframework.jdbc.core.JdbcTemplate;
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
    private final DailySettlementMapper dailySettlementMapper;
    private final StockCountMapper stockCountMapper;
    private final JdbcTemplate jdbcTemplate;

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
                        DailySettlementMapper dailySettlementMapper,
                        StockCountMapper stockCountMapper,
                        JdbcTemplate jdbcTemplate) {
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
        this.dailySettlementMapper = dailySettlementMapper;
        this.stockCountMapper = stockCountMapper;
        this.jdbcTemplate = jdbcTemplate;
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
     * 修复历史 BUG:
     * 1) 此前 employee/dish 用 setIsDeleted(1)+update(entity,wrapper) 软删除,
     *    但 is_deleted 字段被 MyBatis-Plus 全局逻辑删除托管,update 的 SET 子句会跳过该字段,
     *    且实体只设置了 isDeleted 一个字段 → 生成 "UPDATE employee SET WHERE ..." 空 SET 非法 SQL,
     *    抛 BadSqlGrammarException 导致删除食堂始终报 500(事务回滚)。
     *    正确做法:delete(wrapper),MyBatis-Plus 自动转 UPDATE ... SET is_deleted=1。
     * 2) 补齐此前遗漏的 5 张表:menu_item/order_item/purchase_item/group_order_item(明细子表)、
     *    store_config(门店配置)。子表必须在父表(menu/order/purchase/group_order)之前删除,
     *    否则成为孤儿行;且后续从 store{N} 备份恢复该食堂时,恢复的 INNER JOIN 清理匹配不到
     *    孤儿明细行,重新插入备份主键时冲突导致恢复失败。
     *
     * 清理策略:
     * 1) admin:物理删除该店管理员账号(禁用会产生不可见/不可登录/不可重建的孤儿账号)
     * 2) employee/dish:软删除(is_deleted=1),保留审计痕迹
     * 3) 明细子表先删,其余关联表物理删除,避免 id 复用导致跨店数据串扰
     * 4) 最后物理删除 store 表记录
     *
     * 注:该食堂的备份文件(store{N}_*.json.gz)刻意保留 —— 删除后仍可从备份恢复整个食堂。
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

        // 1) admin:物理删除该店管理员账号
        // 修复 BUG:原实现"store_id 置 0 + 禁用"会产生三死孤儿账号——
        // 账号列表/登录均过滤 status=1 导致不可见、不可登录;
        // 创建同名账号时用户名查重不过滤 status 导致"用户名已存在"无法重建;
        // 且备份不包含 admin 表,恢复备份也无法救回。
        // 物理删除后用户名立即可重建;被删账号记入日志便于追溯。
        List<Admin> storeAdmins = adminMapper.selectList(
                new LambdaQueryWrapper<Admin>().eq(Admin::getStoreId, id));
        if (!storeAdmins.isEmpty()) {
            List<String> deletedUsernames = storeAdmins.stream()
                    .map(Admin::getUsername).toList();
            log.warn("删除食堂 id={} 同时删除其管理员账号: {}", id, deletedUsernames);
            adminMapper.delete(new LambdaQueryWrapper<Admin>().eq(Admin::getStoreId, id));
        }

        // 2) employee:软删除(is_deleted=1),保留审计痕迹
        employeeMapper.delete(new LambdaQueryWrapper<Employee>().eq(Employee::getStoreId, id));

        // 3) dish:软删除
        dishMapper.delete(new LambdaQueryWrapper<Dish>().eq(Dish::getStoreId, id));

        // 4) 明细子表:先于父表删除(无实体 Mapper,用 JdbcTemplate;表名 order 为保留字需反引号)
        jdbcTemplate.update(
                "DELETE FROM menu_item WHERE menu_id IN (SELECT id FROM menu WHERE store_id = ?)", id);
        jdbcTemplate.update(
                "DELETE FROM order_item WHERE order_id IN (SELECT id FROM `order` WHERE store_id = ?)", id);
        jdbcTemplate.update(
                "DELETE FROM purchase_item WHERE purchase_id IN (SELECT id FROM purchase WHERE store_id = ?)", id);
        jdbcTemplate.update(
                "DELETE FROM group_order_item WHERE group_order_id IN (SELECT id FROM group_order WHERE store_id = ?)", id);

        // 5) 其余关联表:物理删除
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
        dailySettlementMapper.delete(new LambdaQueryWrapper<DailySettlement>().eq(DailySettlement::getStoreId, id));
        stockCountMapper.delete(new LambdaQueryWrapper<StockCount>().eq(StockCount::getStoreId, id));

        // 6) store_config:门店级配置(含订餐/手续费配置),避免孤儿配置残留
        jdbcTemplate.update("DELETE FROM store_config WHERE store_id = ?", id);

        // 7) 最后物理删除 store 表记录
        storeMapper.deleteById(id);
        log.warn("食堂 id={}, name={} 及其关联数据已全部清理", id, store.getName());
    }
}
