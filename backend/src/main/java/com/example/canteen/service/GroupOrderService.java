package com.example.canteen.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.canteen.dto.GroupOrderCreateDTO;
import com.example.canteen.entity.Admin;
import com.example.canteen.entity.Dish;
import com.example.canteen.entity.Employee;
import com.example.canteen.entity.GroupOrder;
import com.example.canteen.entity.GroupOrderItem;
import com.example.canteen.exception.BusinessException;
import com.example.canteen.mapper.AdminMapper;
import com.example.canteen.mapper.DishMapper;
import com.example.canteen.mapper.EmployeeMapper;
import com.example.canteen.mapper.GroupOrderItemMapper;
import com.example.canteen.mapper.GroupOrderMapper;
import com.example.canteen.security.SecurityContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@Service
public class GroupOrderService {
    /** 团体订单状态:1=待确认 2=已确认 3=已取消 4=已完成 */
    public static final int STATUS_PENDING = 1;
    public static final int STATUS_CONFIRMED = 2;
    public static final int STATUS_CANCELLED = 3;
    public static final int STATUS_COMPLETED = 4;

    private final GroupOrderMapper groupOrderMapper;
    private final GroupOrderItemMapper groupOrderItemMapper;
    private final EmployeeMapper employeeMapper;
    private final DishMapper dishMapper;
    private final AdminMapper adminMapper;

    public GroupOrderService(GroupOrderMapper groupOrderMapper,
                             GroupOrderItemMapper groupOrderItemMapper,
                             EmployeeMapper employeeMapper,
                             DishMapper dishMapper,
                             AdminMapper adminMapper) {
        this.groupOrderMapper = groupOrderMapper;
        this.groupOrderItemMapper = groupOrderItemMapper;
        this.employeeMapper = employeeMapper;
        this.dishMapper = dishMapper;
        this.adminMapper = adminMapper;
    }

    /**
     * 创建团体订单:生成单号、计算总价、保存主表 + 明细
     */
    @Transactional
    public GroupOrder createGroupOrder(GroupOrderCreateDTO dto) {
        GroupOrder groupOrder = dto.getGroupOrder();
        if (groupOrder == null) {
            throw new BusinessException("团体订单信息不能为空");
        }
        if (groupOrder.getStoreId() == null) {
            throw new BusinessException("门店ID不能为空");
        }
        SecurityContext.checkStoreAccess(groupOrder.getStoreId());
        if (groupOrder.getTitle() == null || groupOrder.getTitle().isBlank()) {
            throw new BusinessException("订单标题不能为空");
        }
        if (groupOrder.getMealDate() == null) {
            throw new BusinessException("用餐日期不能为空");
        }
        if (groupOrder.getMealType() == null) {
            throw new BusinessException("餐次不能为空");
        }
        if (groupOrder.getHeadcount() == null || groupOrder.getHeadcount() <= 0) {
            throw new BusinessException("用餐人数必须大于0");
        }

        // 校验组织人归属门店
        if (groupOrder.getOrganizerId() != null) {
            Employee organizer = employeeMapper.selectById(groupOrder.getOrganizerId());
            if (organizer == null) {
                throw new BusinessException("组织人不存在");
            }
            if (organizer.getStoreId() == null || !organizer.getStoreId().equals(groupOrder.getStoreId())) {
                throw new BusinessException("组织人不属于本门店");
            }
        }

        List<GroupOrderItem> items = dto.getItems() == null ? new ArrayList<>() : dto.getItems();
        if (items.isEmpty()) {
            throw new BusinessException("菜品明细不能为空");
        }

        // 批量查询所有菜品,避免 N+1 查询
        List<Long> dishIds = items.stream()
                .map(GroupOrderItem::getDishId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, Dish> dishMap = new HashMap<>();
        if (!dishIds.isEmpty()) {
            dishMapper.selectBatchIds(dishIds).forEach(d -> dishMap.put(d.getId(), d));
        }

        // 校验菜品归属门店 + 计算总价 + 回填名称/价格/小计
        BigDecimal total = BigDecimal.ZERO;
        for (GroupOrderItem item : items) {
            if (item.getDishId() == null) {
                throw new BusinessException("菜品不能为空");
            }
            if (item.getQuantity() == null || item.getQuantity() <= 0) {
                throw new BusinessException("份数必须大于0");
            }
            Dish dish = dishMap.get(item.getDishId());
            if (dish == null) {
                throw new BusinessException("菜品不存在:" + item.getDishId());
            }
            if (dish.getStoreId() == null || !dish.getStoreId().equals(groupOrder.getStoreId())) {
                throw new BusinessException("菜品不属于本门店:" + dish.getName());
            }
            item.setDishName(dish.getName());
            // 单价空值保护:为 null 时按 0 处理,避免 NPE
            BigDecimal price = dish.getPrice() == null ? BigDecimal.ZERO : dish.getPrice();
            item.setPrice(price);
            BigDecimal amount = price.multiply(BigDecimal.valueOf(item.getQuantity()));
            item.setAmount(amount);
            total = total.add(amount);
        }

        groupOrder.setOrderNo(generateOrderNo());
        groupOrder.setTotalAmount(total);
        groupOrder.setStatus(STATUS_PENDING);
        groupOrder.setOperatorId(SecurityContext.currentAdminId());
        groupOrderMapper.insert(groupOrder);

        for (GroupOrderItem item : items) {
            item.setId(null);
            item.setGroupOrderId(groupOrder.getId());
            groupOrderItemMapper.insert(item);
        }
        return groupOrder;
    }

    /**
     * 生成团体订单号:GO + yyyyMMdd + 6 位随机数
     */
    private String generateOrderNo() {
        String dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        int random = ThreadLocalRandom.current().nextInt(100000, 1000000);
        return "GO" + dateStr + random;
    }

    /**
     * 分页查询,填充 organizerName / operatorName
     */
    public IPage<GroupOrder> getList(Long storeId, int page, int size,
                                     Integer status, LocalDate startDate, LocalDate endDate) {
        SecurityContext.checkStoreAccess(storeId);
        LambdaQueryWrapper<GroupOrder> wrapper = new LambdaQueryWrapper<GroupOrder>()
                .eq(GroupOrder::getStoreId, storeId)
                .orderByDesc(GroupOrder::getId);
        if (status != null) {
            wrapper.eq(GroupOrder::getStatus, status);
        }
        if (startDate != null) {
            wrapper.ge(GroupOrder::getMealDate, startDate);
        }
        if (endDate != null) {
            wrapper.le(GroupOrder::getMealDate, endDate);
        }
        IPage<GroupOrder> p = groupOrderMapper.selectPage(new Page<>(page, size), wrapper);
        fillTransientFields(p.getRecords());
        return p;
    }

    /** 批量填充 organizerName / operatorName */
    private void fillTransientFields(List<GroupOrder> records) {
        if (records.isEmpty()) return;
        // 批量查询组织人(员工)姓名
        Map<Long, String> empMap = new HashMap<>();
        for (GroupOrder g : records) {
            if (g.getOrganizerId() != null) {
                empMap.put(g.getOrganizerId(), null);
            }
        }
        if (!empMap.isEmpty()) {
            employeeMapper.selectBatchIds(new ArrayList<>(empMap.keySet())).forEach(e ->
                    empMap.put(e.getId(), e.getName()));
        }
        // 批量查询操作人(管理员)姓名
        Map<Long, String> adminMap = new HashMap<>();
        for (GroupOrder g : records) {
            if (g.getOperatorId() != null) {
                adminMap.put(g.getOperatorId(), null);
            }
        }
        if (!adminMap.isEmpty()) {
            adminMapper.selectBatchIds(new ArrayList<>(adminMap.keySet())).forEach(a ->
                    adminMap.put(a.getId(), a.getName()));
        }
        for (GroupOrder g : records) {
            if (g.getOrganizerId() != null) {
                g.setOrganizerName(empMap.get(g.getOrganizerId()));
            }
            if (g.getOperatorId() != null) {
                g.setOperatorName(adminMap.get(g.getOperatorId()));
            }
        }
    }

    /**
     * 员工查看所在门店的团体订单(H5 端)
     * 团体订餐按门店管理,暂不支持按个人/部门关联,故返回员工所在门店
     * 状态为已确认或已完成的团餐,按用餐日期倒序
     */
    public List<GroupOrder> getGroupOrdersByStore(Long storeId) {
        List<GroupOrder> list = groupOrderMapper.selectList(
                new LambdaQueryWrapper<GroupOrder>()
                        .eq(GroupOrder::getStoreId, storeId)
                        .in(GroupOrder::getStatus, STATUS_CONFIRMED, STATUS_COMPLETED)
                        .orderByDesc(GroupOrder::getMealDate));
        fillTransientFields(list);
        return list;
    }

    /**
     * 查询团体订单详情(含明细)
     */
    public Map<String, Object> getDetail(Long id) {
        GroupOrder groupOrder = groupOrderMapper.selectById(id);
        if (groupOrder == null) {
            throw new BusinessException("团体订单不存在");
        }
        SecurityContext.checkStoreAccess(groupOrder.getStoreId());
        // 填充名称
        if (groupOrder.getOrganizerId() != null) {
            Employee emp = employeeMapper.selectById(groupOrder.getOrganizerId());
            if (emp != null) {
                groupOrder.setOrganizerName(emp.getName());
            }
        }
        if (groupOrder.getOperatorId() != null) {
            Admin admin = adminMapper.selectById(groupOrder.getOperatorId());
            if (admin != null) {
                groupOrder.setOperatorName(admin.getName());
            }
        }
        List<GroupOrderItem> items = groupOrderItemMapper.selectList(
                new LambdaQueryWrapper<GroupOrderItem>()
                        .eq(GroupOrderItem::getGroupOrderId, id)
                        .orderByAsc(GroupOrderItem::getId));
        Map<String, Object> result = new HashMap<>();
        result.put("groupOrder", groupOrder);
        result.put("items", items);
        return result;
    }

    /**
     * 确认订单:仅待确认可确认(原子状态流转,防并发重复操作)
     */
    @Transactional
    public GroupOrder confirm(Long id) {
        GroupOrder existing = groupOrderMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException("团体订单不存在");
        }
        SecurityContext.checkStoreAccess(existing.getStoreId());
        if (existing.getStatus() != STATUS_PENDING) {
            throw new BusinessException("当前订单状态不允许确认");
        }
        LocalDateTime now = LocalDateTime.now();
        int rows = groupOrderMapper.update(null, new UpdateWrapper<GroupOrder>()
                .eq("id", id)
                .eq("status", STATUS_PENDING)
                .set("status", STATUS_CONFIRMED)
                .set("updated_at", now));
        if (rows == 0) {
            throw new BusinessException("订单状态已变更,请刷新后重试");
        }
        existing.setStatus(STATUS_CONFIRMED);
        existing.setUpdatedAt(now);
        return existing;
    }

    /**
     * 取消订单:待确认/已确认可取消(原子状态流转,防并发重复操作)
     */
    @Transactional
    public GroupOrder cancel(Long id) {
        GroupOrder existing = groupOrderMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException("团体订单不存在");
        }
        SecurityContext.checkStoreAccess(existing.getStoreId());
        if (existing.getStatus() == STATUS_CANCELLED || existing.getStatus() == STATUS_COMPLETED) {
            throw new BusinessException("当前订单状态不允许取消");
        }
        LocalDateTime now = LocalDateTime.now();
        int rows = groupOrderMapper.update(null, new UpdateWrapper<GroupOrder>()
                .eq("id", id)
                .in("status", STATUS_PENDING, STATUS_CONFIRMED)
                .set("status", STATUS_CANCELLED)
                .set("updated_at", now));
        if (rows == 0) {
            throw new BusinessException("订单状态已变更,请刷新后重试");
        }
        existing.setStatus(STATUS_CANCELLED);
        existing.setUpdatedAt(now);
        return existing;
    }

    /**
     * 完成订单:仅已确认可完成(原子状态流转,防并发重复操作)
     */
    @Transactional
    public GroupOrder complete(Long id) {
        GroupOrder existing = groupOrderMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException("团体订单不存在");
        }
        SecurityContext.checkStoreAccess(existing.getStoreId());
        if (existing.getStatus() != STATUS_CONFIRMED) {
            throw new BusinessException("仅已确认状态的订单可完成");
        }
        LocalDateTime now = LocalDateTime.now();
        int rows = groupOrderMapper.update(null, new UpdateWrapper<GroupOrder>()
                .eq("id", id)
                .eq("status", STATUS_CONFIRMED)
                .set("status", STATUS_COMPLETED)
                .set("updated_at", now));
        if (rows == 0) {
            throw new BusinessException("订单状态已变更,请刷新后重试");
        }
        existing.setStatus(STATUS_COMPLETED);
        existing.setUpdatedAt(now);
        return existing;
    }

    /**
     * 删除订单(仅待确认可删),同时删除明细
     */
    @Transactional
    public void delete(Long id) {
        GroupOrder existing = groupOrderMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException("团体订单不存在");
        }
        SecurityContext.checkStoreAccess(existing.getStoreId());
        if (existing.getStatus() != STATUS_PENDING) {
            throw new BusinessException("仅待确认状态的订单可删除");
        }
        groupOrderItemMapper.delete(new LambdaQueryWrapper<GroupOrderItem>()
                .eq(GroupOrderItem::getGroupOrderId, id));
        groupOrderMapper.deleteById(id);
    }
}
