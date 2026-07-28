package com.example.canteen.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.canteen.entity.Dish;
import com.example.canteen.entity.Employee;
import com.example.canteen.entity.Feedback;
import com.example.canteen.exception.BusinessException;
import com.example.canteen.mapper.DishMapper;
import com.example.canteen.mapper.EmployeeMapper;
import com.example.canteen.mapper.FeedbackMapper;
import com.example.canteen.security.SecurityContext;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class FeedbackService {
    /** 反馈分类:1=菜品评价 2=服务投诉 3=建议 4=其他 */
    public static final int CATEGORY_DISH = 1;
    public static final int CATEGORY_SERVICE = 2;
    public static final int CATEGORY_SUGGESTION = 3;
    public static final int CATEGORY_OTHER = 4;
    /** 反馈状态:1=待处理 2=已处理 3=已忽略 */
    public static final int STATUS_PENDING = 1;
    public static final int STATUS_DONE = 2;
    public static final int STATUS_IGNORED = 3;

    private final FeedbackMapper feedbackMapper;
    private final EmployeeMapper employeeMapper;
    private final DishMapper dishMapper;

    public FeedbackService(FeedbackMapper feedbackMapper,
                           EmployeeMapper employeeMapper,
                           DishMapper dishMapper) {
        this.feedbackMapper = feedbackMapper;
        this.employeeMapper = employeeMapper;
        this.dishMapper = dishMapper;
    }

    /**
     * 员工提交反馈(可从 H5 端调用,需校验 employeeId 归属门店)
     */
    public Feedback createFeedback(Feedback feedback) {
        if (feedback.getStoreId() == null) {
            throw new BusinessException("门店ID不能为空");
        }
        if (feedback.getEmployeeId() == null) {
            throw new BusinessException("员工ID不能为空");
        }
        SecurityContext.checkStoreAccess(feedback.getStoreId());
        // 防止冒名:员工只能以自己身份提交反馈
        if (SecurityContext.isEmployee()) {
            Long currentEmployeeId = SecurityContext.currentEmployeeId();
            if (currentEmployeeId == null) {
                throw new SecurityException("无法验证员工身份");
            }
            feedback.setEmployeeId(currentEmployeeId);
        }
        // 校验员工归属门店
        Employee employee = employeeMapper.selectById(feedback.getEmployeeId());
        if (employee == null) {
            throw new BusinessException("员工不存在");
        }
        if (employee.getStoreId() == null || !employee.getStoreId().equals(feedback.getStoreId())) {
            throw new BusinessException("员工不属于本门店");
        }
        // 校验评分范围
        if (feedback.getRating() == null || feedback.getRating() < 1 || feedback.getRating() > 5) {
            throw new BusinessException("评分范围为 1-5");
        }
        if (feedback.getCategory() == null) {
            feedback.setCategory(CATEGORY_DISH);
        }
        feedback.setStatus(STATUS_PENDING);
        feedbackMapper.insert(feedback);
        return feedback;
    }

    /**
     * 分页查询,填充 employeeName/dishName
     */
    public IPage<Feedback> getList(Long storeId, int page, int size,
                                   Integer status, Integer category, String keyword) {
        SecurityContext.checkStoreAccess(storeId);
        LambdaQueryWrapper<Feedback> wrapper = new LambdaQueryWrapper<Feedback>()
                .eq(Feedback::getStoreId, storeId)
                .orderByDesc(Feedback::getId);
        if (status != null) {
            wrapper.eq(Feedback::getStatus, status);
        }
        if (category != null) {
            wrapper.eq(Feedback::getCategory, category);
        }
        if (keyword != null && !keyword.isBlank()) {
            wrapper.like(Feedback::getContent, keyword);
        }
        IPage<Feedback> p = feedbackMapper.selectPage(new Page<>(page, size), wrapper);
        fillTransientFields(p.getRecords());
        return p;
    }

    /** 批量填充 employeeName / dishName,避免 N+1 */
    private void fillTransientFields(List<Feedback> records) {
        if (records.isEmpty()) return;
        // 批量查询员工姓名
        Map<Long, String> empMap = new HashMap<>();
        for (Feedback f : records) {
            if (f.getEmployeeId() != null) {
                empMap.put(f.getEmployeeId(), null);
            }
        }
        if (!empMap.isEmpty()) {
            employeeMapper.selectBatchIds(new ArrayList<>(empMap.keySet())).forEach(e ->
                    empMap.put(e.getId(), e.getName()));
        }
        // 批量查询菜品名称
        Map<Long, String> dishMap = new HashMap<>();
        for (Feedback f : records) {
            if (f.getDishId() != null) {
                dishMap.put(f.getDishId(), null);
            }
        }
        if (!dishMap.isEmpty()) {
            dishMapper.selectBatchIds(new ArrayList<>(dishMap.keySet())).forEach(d ->
                    dishMap.put(d.getId(), d.getName()));
        }
        for (Feedback f : records) {
            if (f.getEmployeeId() != null) {
                f.setEmployeeName(empMap.get(f.getEmployeeId()));
            }
            if (f.getDishId() != null) {
                f.setDishName(dishMap.get(f.getDishId()));
            }
        }
    }

    /**
     * 反馈详情(填充 employeeName/dishName)
     */
    public Feedback getDetail(Long id) {
        Feedback feedback = feedbackMapper.selectById(id);
        if (feedback == null) {
            throw new BusinessException("反馈不存在");
        }
        SecurityContext.checkStoreAccess(feedback.getStoreId());
        if (feedback.getEmployeeId() != null) {
            Employee emp = employeeMapper.selectById(feedback.getEmployeeId());
            if (emp != null) {
                feedback.setEmployeeName(emp.getName());
            }
        }
        if (feedback.getDishId() != null) {
            Dish dish = dishMapper.selectById(feedback.getDishId());
            if (dish != null) {
                feedback.setDishName(dish.getName());
            }
        }
        return feedback;
    }

    /**
     * 员工查看自己的反馈列表(H5 端),按创建时间倒序,填充关联字段
     */
    public List<Feedback> getMyFeedback(Long employeeId) {
        List<Feedback> list = feedbackMapper.selectList(
                new LambdaQueryWrapper<Feedback>()
                        .eq(Feedback::getEmployeeId, employeeId)
                        .orderByDesc(Feedback::getCreatedAt));
        fillTransientFields(list);
        return list;
    }

    /**
     * 按ID查询反馈详情(填充 employeeName/dishName),不做门店权限校验
     * 调用方需自行校验归属(如员工只能查看自己的反馈)
     */
    public Feedback getFeedbackById(Long id) {
        Feedback fb = feedbackMapper.selectById(id);
        if (fb == null) {
            throw new BusinessException("反馈不存在");
        }
        if (fb.getEmployeeId() != null) {
            Employee emp = employeeMapper.selectById(fb.getEmployeeId());
            if (emp != null) {
                fb.setEmployeeName(emp.getName());
            }
        }
        if (fb.getDishId() != null) {
            Dish dish = dishMapper.selectById(fb.getDishId());
            if (dish != null) {
                fb.setDishName(dish.getName());
            }
        }
        return fb;
    }

    /**
     * 管理员回复,状态改为已处理
     */
    public Feedback reply(Long id, String replyText, Long adminId) {
        Feedback feedback = feedbackMapper.selectById(id);
        if (feedback == null) {
            throw new BusinessException("反馈不存在");
        }
        SecurityContext.checkStoreAccess(feedback.getStoreId());
        if (replyText == null || replyText.isBlank()) {
            throw new BusinessException("回复内容不能为空");
        }
        feedback.setReply(replyText);
        feedback.setReplyAdminId(adminId);
        feedback.setRepliedAt(LocalDateTime.now());
        feedback.setStatus(STATUS_DONE);
        feedbackMapper.updateById(feedback);
        return feedback;
    }

    /**
     * 更新状态(标记已处理/已忽略)
     */
    public Feedback updateStatus(Long id, int status) {
        Feedback feedback = feedbackMapper.selectById(id);
        if (feedback == null) {
            throw new BusinessException("反馈不存在");
        }
        SecurityContext.checkStoreAccess(feedback.getStoreId());
        if (status != STATUS_PENDING && status != STATUS_DONE && status != STATUS_IGNORED) {
            throw new BusinessException("非法的状态值");
        }
        feedback.setStatus(status);
        feedbackMapper.updateById(feedback);
        return feedback;
    }

    /**
     * 删除反馈
     */
    public void delete(Long id) {
        Feedback feedback = feedbackMapper.selectById(id);
        if (feedback == null) {
            throw new BusinessException("反馈不存在");
        }
        SecurityContext.checkStoreAccess(feedback.getStoreId());
        feedbackMapper.deleteById(id);
    }

    /**
     * 统计:总数/待处理/平均评分/各分类数
     */
    public Map<String, Object> getStats(Long storeId) {
        SecurityContext.checkStoreAccess(storeId);
        List<Feedback> all = feedbackMapper.selectList(new LambdaQueryWrapper<Feedback>()
                .eq(Feedback::getStoreId, storeId));
        long total = all.size();
        long pending = all.stream().filter(f -> f.getStatus() != null && f.getStatus() == STATUS_PENDING).count();
        // 平均评分(仅统计有评分的)
        double avgRating = all.stream()
                .filter(f -> f.getRating() != null)
                .mapToInt(Feedback::getRating)
                .average()
                .orElse(0);

        Map<String, Long> categoryStats = new HashMap<>();
        categoryStats.put("dish", all.stream().filter(f -> f.getCategory() != null && f.getCategory() == CATEGORY_DISH).count());
        categoryStats.put("service", all.stream().filter(f -> f.getCategory() != null && f.getCategory() == CATEGORY_SERVICE).count());
        categoryStats.put("suggestion", all.stream().filter(f -> f.getCategory() != null && f.getCategory() == CATEGORY_SUGGESTION).count());
        categoryStats.put("other", all.stream().filter(f -> f.getCategory() != null && f.getCategory() == CATEGORY_OTHER).count());

        Map<String, Object> stats = new HashMap<>();
        stats.put("total", total);
        stats.put("pending", pending);
        stats.put("avgRating", Math.round(avgRating * 10) / 10.0);
        stats.put("categoryStats", categoryStats);
        return stats;
    }
}
