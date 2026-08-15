package com.example.canteen.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.canteen.entity.Admin;
import com.example.canteen.entity.DailySettlement;
import com.example.canteen.entity.Order;
import com.example.canteen.entity.RechargeRecord;
import com.example.canteen.exception.BusinessException;
import com.example.canteen.mapper.AdminMapper;
import com.example.canteen.mapper.DailySettlementMapper;
import com.example.canteen.mapper.OrderMapper;
import com.example.canteen.mapper.RechargeRecordMapper;
import com.example.canteen.security.SecurityContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 日终对账/关店流程 Service。
 *
 * 三阶段状态机:1=待对账 → 2=已对账 → 3=已关店。
 * - generateSettlement:生成/刷新对账数据(仅 status=1 可刷新)。
 * - confirmSettlement:确认对账(1→2),记录 settledAt。
 * - closeStore:关店(2→3),记录 closedAt,需先确认对账。
 *
 * 订单状态口径:1=待取餐 2=已完成(已取餐) 3=已取消 4=未就餐(超时未核销,已付款未退款)。
 * 营业额:已完成(2) + 未就餐(4) 的 totalAmount 之和(均已收款未退款,未就餐也是食堂收入)。
 * 退款总额:已取消订单(status=3)的 totalAmount 之和。
 * 消费总额:与营业额同口径。
 * 充值无支付方式字段,现金充值=充值总额,线上充值=0。
 */
@Service
public class DailySettlementService {
    /** 1=待对账 2=已对账 3=已关店 */
    public static final int STATUS_PENDING = 1;
    public static final int STATUS_CONFIRMED = 2;
    public static final int STATUS_CLOSED = 3;

    private final OrderMapper orderMapper;
    private final RechargeRecordMapper rechargeRecordMapper;
    private final AdminMapper adminMapper;
    private final DailySettlementMapper dailySettlementMapper;

    public DailySettlementService(OrderMapper orderMapper,
                                  RechargeRecordMapper rechargeRecordMapper,
                                  AdminMapper adminMapper,
                                  DailySettlementMapper dailySettlementMapper) {
        this.orderMapper = orderMapper;
        this.rechargeRecordMapper = rechargeRecordMapper;
        this.adminMapper = adminMapper;
        this.dailySettlementMapper = dailySettlementMapper;
    }

    /** 校验门店访问权限 */
    private void checkStoreAccess(Long storeId) {
        SecurityContext.checkStoreAccess(storeId);
    }

    /**
     * 生成/刷新指定日期的对账数据:查询当天订单、充值记录统计汇总。
     * 已有记录且状态为待对账(1)时更新;已对账/已关店(status>=2)则禁止刷新。
     * 没有记录则创建(状态=1 待对账)。
     */
    @Transactional
    public DailySettlement generateSettlement(Long storeId, LocalDate date) {
        checkStoreAccess(storeId);
        LocalDateTime start = date.atStartOfDay();
        LocalDateTime end = date.plusDays(1).atStartOfDay();

        // 当日订单(包含已取消,用于统计已取消数与退款)
        // 口径统一:营业额按订餐日期统计,与看板 getDashboardStats 一致;次日订单计入就餐日,而非下单日
        List<Order> orders = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .eq(Order::getStoreId, storeId)
                .eq(Order::getDate, date));
        // 当日充值
        List<RechargeRecord> recharges = rechargeRecordMapper.selectList(new LambdaQueryWrapper<RechargeRecord>()
                .eq(RechargeRecord::getStoreId, storeId)
                .ge(RechargeRecord::getCreatedAt, start)
                .lt(RechargeRecord::getCreatedAt, end));

        int orderCount = orders.size();
        int completedCount = (int) orders.stream().filter(o -> o.getStatus() != null && o.getStatus() == 2).count();
        int cancelledCount = (int) orders.stream().filter(o -> o.getStatus() != null && o.getStatus() == 3).count();
        // 未就餐(超时未核销,已付款未退款)
        int missedCount = (int) orders.stream().filter(o -> o.getStatus() != null && o.getStatus() == 4).count();
        // 已取餐 = 已完成(status=2)
        int servedCount = completedCount;

        // 营业额:已完成(2) + 未就餐(4) 的金额之和(均已收款未退款,未就餐也是食堂收入)
        BigDecimal totalRevenue = orders.stream()
                .filter(o -> o.getStatus() != null && (o.getStatus() == 2 || o.getStatus() == 4))
                .map(o -> o.getTotalAmount() == null ? BigDecimal.ZERO : o.getTotalAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalRefund = orders.stream()
                .filter(o -> o.getStatus() != null && o.getStatus() == 3)
                .map(o -> o.getTotalAmount() == null ? BigDecimal.ZERO : o.getTotalAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalConsumption = totalRevenue;
        BigDecimal totalRecharge = recharges.stream()
                .map(r -> r.getAmount() == null ? BigDecimal.ZERO : r.getAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        // 当前系统充值均由管理员操作(无支付方式字段),视为现金充值
        BigDecimal cashRevenue = totalRecharge;
        BigDecimal onlineRevenue = BigDecimal.ZERO;

        DailySettlement existing = findSettlement(storeId, date);
        if (existing != null) {
            if (existing.getStatus() != null && existing.getStatus() >= STATUS_CONFIRMED) {
                throw new BusinessException("该日期已对账/已关店,无法重新生成对账数据");
            }
            existing.setTotalRevenue(totalRevenue);
            existing.setTotalRefund(totalRefund);
            existing.setTotalRecharge(totalRecharge);
            existing.setTotalConsumption(totalConsumption);
            existing.setCashRevenue(cashRevenue);
            existing.setOnlineRevenue(onlineRevenue);
            existing.setOrderCount(orderCount);
            existing.setCompletedCount(completedCount);
            existing.setCancelledCount(cancelledCount);
            existing.setServedCount(servedCount);
            // 刷新时同步更新未就餐数,否则 totalRevenue 已含未就餐金额而 missedCount 仍为旧值,构成不一致
            existing.setMissedCount(missedCount);
            existing.setStatus(STATUS_PENDING);
            existing.setUpdatedAt(LocalDateTime.now());
            dailySettlementMapper.updateById(existing);
            fillOperatorName(existing);
            return existing;
        }

        DailySettlement ds = new DailySettlement();
        ds.setStoreId(storeId);
        ds.setSettleDate(date);
        ds.setTotalRevenue(totalRevenue);
        ds.setTotalRefund(totalRefund);
        ds.setTotalRecharge(totalRecharge);
        ds.setTotalConsumption(totalConsumption);
        ds.setCashRevenue(cashRevenue);
        ds.setOnlineRevenue(onlineRevenue);
        ds.setOrderCount(orderCount);
        ds.setCompletedCount(completedCount);
        ds.setCancelledCount(cancelledCount);
        ds.setServedCount(servedCount);
        ds.setMissedCount(missedCount);
        ds.setOperatorId(SecurityContext.currentAdminId());
        ds.setStatus(STATUS_PENDING);
        dailySettlementMapper.insert(ds);
        fillOperatorName(ds);
        return ds;
    }

    /** 查询指定日期的对账记录(单条) */
    public DailySettlement getSettlement(Long storeId, LocalDate date) {
        checkStoreAccess(storeId);
        DailySettlement ds = findSettlement(storeId, date);
        if (ds != null) {
            fillOperatorName(ds);
        }
        return ds;
    }

    /** 按主键查询对账记录(供控制器鉴权使用,不做门店校验) */
    public DailySettlement getById(Long id) {
        DailySettlement ds = dailySettlementMapper.selectById(id);
        if (ds != null) {
            fillOperatorName(ds);
        }
        return ds;
    }

    private DailySettlement findSettlement(Long storeId, LocalDate date) {
        return dailySettlementMapper.selectOne(new LambdaQueryWrapper<DailySettlement>()
                .eq(DailySettlement::getStoreId, storeId)
                .eq(DailySettlement::getSettleDate, date));
    }

    /**
     * 对账历史列表(按日期倒序)。
     */
    public Map<String, Object> getList(Long storeId, LocalDate startDate, LocalDate endDate,
                                       int page, int size) {
        checkStoreAccess(storeId);
        LambdaQueryWrapper<DailySettlement> wrapper = new LambdaQueryWrapper<DailySettlement>()
                .eq(DailySettlement::getStoreId, storeId)
                .orderByDesc(DailySettlement::getSettleDate);
        if (startDate != null) {
            wrapper.ge(DailySettlement::getSettleDate, startDate);
        }
        if (endDate != null) {
            wrapper.le(DailySettlement::getSettleDate, endDate);
        }
        IPage<DailySettlement> p = dailySettlementMapper.selectPage(new Page<>(page, size), wrapper);
        fillOperatorNames(p.getRecords());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", p.getRecords());
        result.put("total", p.getTotal());
        result.put("page", page);
        result.put("size", size);
        return result;
    }

    /**
     * 确认对账:状态 1→2,记录 settledAt。
     */
    @Transactional
    public DailySettlement confirmSettlement(Long id) {
        DailySettlement ds = dailySettlementMapper.selectById(id);
        if (ds == null) {
            throw new BusinessException("对账记录不存在");
        }
        checkStoreAccess(ds.getStoreId());
        if (ds.getStatus() == null || ds.getStatus() != STATUS_PENDING) {
            throw new BusinessException("当前状态不允许确认对账,仅待对账可确认");
        }
        ds.setStatus(STATUS_CONFIRMED);
        ds.setSettledAt(LocalDateTime.now());
        ds.setUpdatedAt(LocalDateTime.now());
        dailySettlementMapper.updateById(ds);
        fillOperatorName(ds);
        return ds;
    }

    /**
     * 关店:状态 2→3,记录 closedAt。需先确认对账(status=2)才能关店。
     */
    @Transactional
    public DailySettlement closeStore(Long id) {
        DailySettlement ds = dailySettlementMapper.selectById(id);
        if (ds == null) {
            throw new BusinessException("对账记录不存在");
        }
        checkStoreAccess(ds.getStoreId());
        if (ds.getStatus() == null || ds.getStatus() != STATUS_CONFIRMED) {
            throw new BusinessException("请先确认对账后再关店");
        }
        ds.setStatus(STATUS_CLOSED);
        ds.setClosedAt(LocalDateTime.now());
        ds.setUpdatedAt(LocalDateTime.now());
        dailySettlementMapper.updateById(ds);
        fillOperatorName(ds);
        return ds;
    }

    /**
     * 获取今日(指定日期)状态:用于前端显示是否已对账/已关店。
     * 未生成对账记录时返回 status=null 的占位结构。
     */
    public Map<String, Object> getCurrentStatus(Long storeId, LocalDate date) {
        checkStoreAccess(storeId);
        DailySettlement ds = findSettlement(storeId, date);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", date.toString());
        if (ds == null) {
            result.put("status", null);
            result.put("statusText", "未对账");
            result.put("settledAt", null);
            result.put("closedAt", null);
        } else {
            result.put("status", ds.getStatus());
            result.put("statusText", statusText(ds.getStatus()));
            result.put("settledAt", ds.getSettledAt());
            result.put("closedAt", ds.getClosedAt());
            result.put("id", ds.getId());
        }
        return result;
    }

    private String statusText(Integer status) {
        if (status == null) return "未对账";
        return switch (status) {
            case 1 -> "待对账";
            case 2 -> "已对账";
            case 3 -> "已关店";
            default -> "未知";
        };
    }

    /** 填充单条记录的操作人姓名 */
    private void fillOperatorName(DailySettlement ds) {
        if (ds == null || ds.getOperatorId() == null) return;
        Admin a = adminMapper.selectById(ds.getOperatorId());
        if (a != null) {
            ds.setOperatorName(a.getName());
        }
    }

    /** 批量填充操作人姓名 */
    private void fillOperatorNames(List<DailySettlement> records) {
        if (records == null || records.isEmpty()) return;
        Map<Long, String> adminMap = new HashMap<>();
        for (DailySettlement ds : records) {
            if (ds.getOperatorId() != null && !adminMap.containsKey(ds.getOperatorId())) {
                adminMap.put(ds.getOperatorId(), null);
            }
        }
        for (Long aid : new ArrayList<>(adminMap.keySet())) {
            Admin a = adminMapper.selectById(aid);
            adminMap.put(aid, a == null ? null : a.getName());
        }
        for (DailySettlement ds : records) {
            if (ds.getOperatorId() != null) {
                ds.setOperatorName(adminMap.get(ds.getOperatorId()));
            }
        }
    }
}
