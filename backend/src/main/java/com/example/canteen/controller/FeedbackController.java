package com.example.canteen.controller;

import com.example.canteen.annotation.OperationLog;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.example.canteen.dto.ApiResponse;
import com.example.canteen.entity.Feedback;
import com.example.canteen.exception.BusinessException;
import com.example.canteen.exception.SecurityException;
import com.example.canteen.security.SecurityContext;
import com.example.canteen.service.FeedbackService;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/feedback")
public class FeedbackController {
    private final FeedbackService feedbackService;

    public FeedbackController(FeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    /** 员工查看自己的反馈列表(H5 端) */
    @GetMapping("/my")
    public ApiResponse<List<Feedback>> getMyFeedback() {
        Long employeeId = SecurityContext.currentEmployeeId();
        if (employeeId == null) {
            throw new SecurityException("无法验证员工身份");
        }
        return ApiResponse.success(feedbackService.getMyFeedback(employeeId));
    }

    /** 员工查看自己的反馈详情(H5 端),只能查看自己的反馈 */
    @GetMapping("/my/{id}")
    public ApiResponse<Feedback> getMyFeedbackDetail(@PathVariable Long id) {
        Long employeeId = SecurityContext.currentEmployeeId();
        if (employeeId == null) {
            throw new SecurityException("无法验证员工身份");
        }
        Feedback feedback = feedbackService.getFeedbackById(id);
        if (!employeeId.equals(feedback.getEmployeeId())) {
            throw new SecurityException("无权查看他人反馈");
        }
        return ApiResponse.success(feedback);
    }

    /** 列表(管理端) */
    @GetMapping
    public ApiResponse<Map<String, Object>> getList(
            @RequestParam Long storeId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Integer category,
            @RequestParam(required = false) String keyword) {
        if (SecurityContext.isEmployee()) {
            throw new com.example.canteen.exception.SecurityException("无权访问管理端数据");
        }
        SecurityContext.checkStoreAccess(storeId);
        IPage<Feedback> p = feedbackService.getList(storeId, page, size, status, category, keyword);
        Map<String, Object> result = new HashMap<>();
        result.put("records", p.getRecords());
        result.put("total", p.getTotal());
        result.put("page", page);
        result.put("size", size);
        return ApiResponse.success(result);
    }

    /** 详情 */
    @GetMapping("/{id}")
    public ApiResponse<Feedback> getDetail(@PathVariable Long id) {
        if (SecurityContext.isEmployee()) {
            throw new com.example.canteen.exception.SecurityException("无权访问管理端数据");
        }
        Feedback feedback = feedbackService.getDetail(id);
        return ApiResponse.success(feedback);
    }

    /** 创建(H5 端员工提交) */
    @OperationLog(value = "创建反馈", detail = "'员工 ' + #resolver.employeeName(#feedback.employeeId) + ' 分类 ' + #resolver.feedbackCategory(#feedback.category)")
    @PostMapping
    public ApiResponse<Feedback> create(@RequestBody Feedback feedback) {
        return ApiResponse.success(feedbackService.createFeedback(feedback));
    }

    /** 管理员回复 */
    @OperationLog(value = "回复反馈", detail = "'反馈 ' + #resolver.feedbackBrief(#id) + ' 回复 ' + #body['reply']")
    @PutMapping("/{id}/reply")
    public ApiResponse<Feedback> reply(@PathVariable Long id, @RequestBody Map<String, String> body) {
        if (SecurityContext.isEmployee()) {
            throw new com.example.canteen.exception.SecurityException("员工无权执行此操作");
        }
        String replyText = body.get("reply");
        Long adminId = SecurityContext.currentAdminId();
        return ApiResponse.success(feedbackService.reply(id, replyText, adminId));
    }

    /** 更新状态(标记已处理/已忽略) */
    @OperationLog(value = "更新反馈状态", detail = "'反馈 ' + #resolver.feedbackBrief(#id) + ' 状态 ' + #body['status']")
    @PutMapping("/{id}/status")
    public ApiResponse<Feedback> updateStatus(@PathVariable Long id, @RequestBody Map<String, Integer> body) {
        if (SecurityContext.isEmployee()) {
            throw new com.example.canteen.exception.SecurityException("员工无权执行此操作");
        }
        Integer status = body.get("status");
        if (status == null) {
            throw new BusinessException("状态不能为空");
        }
        return ApiResponse.success(feedbackService.updateStatus(id, status));
    }

    /** 删除 */
    @OperationLog(value = "删除反馈", detail = "'反馈 ' + #resolver.feedbackBrief(#id)")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        if (SecurityContext.isEmployee()) {
            throw new com.example.canteen.exception.SecurityException("员工无权执行此操作");
        }
        feedbackService.delete(id);
        return ApiResponse.success(null);
    }

    /** 统计(总数/待处理/平均评分/各分类数) */
    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> getStats(@RequestParam Long storeId) {
        if (SecurityContext.isEmployee()) {
            throw new com.example.canteen.exception.SecurityException("无权访问管理端数据");
        }
        return ApiResponse.success(feedbackService.getStats(storeId));
    }
}
