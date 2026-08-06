package com.example.canteen.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.canteen.annotation.OperationLog;
import com.example.canteen.dto.ApiResponse;
import com.example.canteen.dto.EmployeeVO;
import com.example.canteen.entity.Department;
import com.example.canteen.entity.Employee;
import com.example.canteen.mapper.EmployeeMapper;
import com.example.canteen.security.SecurityContext;
import com.example.canteen.service.EmployeeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 员工管理接口:CRUD / 批量导入 / 批量充值 / 余额预警 / 导出。
 * 鉴权相关接口(登录/手机号登录/改密/二维码)已迁出至 EmployeeAuthController。
 */
@RestController
@RequestMapping("/api/employee")
public class EmployeeController {
    private final EmployeeService employeeService;
    private final EmployeeMapper employeeMapper;

    @Autowired
    private ObjectMapper objectMapper;

    public EmployeeController(EmployeeService employeeService, EmployeeMapper employeeMapper) {
        this.employeeService = employeeService;
        this.employeeMapper = employeeMapper;
    }

    @GetMapping("/store/{storeId}")
    public ApiResponse<Map<String, Object>> getEmployeesByStore(@PathVariable Long storeId,
                                                                @RequestParam(defaultValue = "1") int page,
                                                                @RequestParam(defaultValue = "10") int size,
                                                                @RequestParam(required = false) String keyword) {
        SecurityContext.checkStoreAccess(storeId);
        if (SecurityContext.isEmployee()) {
            throw new com.example.canteen.exception.SecurityException("无权访问");
        }
        LambdaQueryWrapper<Employee> wrapper = new LambdaQueryWrapper<Employee>()
                .eq(Employee::getStoreId, storeId)
                .eq(Employee::getIsDeleted, 0);
        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(w -> w.like(Employee::getName, keyword)
                    .or().like(Employee::getCardNo, keyword));
        }
        wrapper.orderByDesc(Employee::getId);
        IPage<Employee> p = employeeMapper.selectPage(new Page<>(page, size), wrapper);
        List<EmployeeVO> voList = p.getRecords().stream().map(EmployeeVO::from).collect(Collectors.toList());
        Map<String, Object> result = new HashMap<>();
        result.put("records", voList);
        result.put("total", p.getTotal());
        result.put("page", page);
        result.put("size", size);
        return ApiResponse.success(result);
    }

    @GetMapping("/{id}")
    public ApiResponse<EmployeeVO> getEmployeeById(@PathVariable Long id) {
        Employee employee = employeeService.getEmployeeById(id);
        if (employee == null) {
            throw new com.example.canteen.exception.BusinessException("员工不存在");
        }
        // P0-1 多租户:校验门店归属
        SecurityContext.checkStoreAccess(employee.getStoreId());
        // 员工角色只能查自己
        Long currentEmployeeId = SecurityContext.currentEmployeeId();
        if (currentEmployeeId != null && !currentEmployeeId.equals(id) && !SecurityContext.isSuperAdmin()) {
            throw new com.example.canteen.exception.SecurityException("无权查看他人信息");
        }
        return ApiResponse.success(EmployeeVO.from(employee));
    }

    @GetMapping("/card/{cardNo}")
    public ApiResponse<EmployeeVO> getEmployeeByCardNo(@PathVariable String cardNo,
                                                       @RequestParam(required = false) Long storeId) {
        // S7 卡号查询加 storeId:门店管理员自动取当前门店;超管需显式传 storeId
        Long targetStore = storeId != null ? storeId : SecurityContext.currentStoreId();
        // P0-2 多租户:校验门店归属,防止门店管理员越权查询其他门店员工
        SecurityContext.checkStoreAccess(targetStore);
        Employee employee = employeeService.getEmployeeByCardNoAndStore(cardNo, targetStore);
        return ApiResponse.success(EmployeeVO.from(employee));
    }

    @OperationLog(value = "创建员工", detail = "'员工 ' + #employee.name + ' 工号 ' + #employee.cardNo")
    @PostMapping
    public ApiResponse<EmployeeVO> createEmployee(@RequestBody Employee employee) {
        if (!SecurityContext.hasAdminLevel()) {
            throw new com.example.canteen.exception.SecurityException("无权执行此操作");
        }
        SecurityContext.checkStoreAccess(employee.getStoreId());
        return ApiResponse.success(EmployeeVO.from(employeeService.createEmployee(employee)));
    }

    @OperationLog(value = "更新员工", detail = "'员工ID ' + #id + ' 姓名 ' + #employee.name")
    @PutMapping("/{id}")
    public ApiResponse<EmployeeVO> updateEmployee(@PathVariable Long id, @RequestBody Employee employee) {
        if (!SecurityContext.hasAdminLevel()) {
            throw new com.example.canteen.exception.SecurityException("无权执行此操作");
        }
        employee.setId(id);
        // storeId 校验和覆盖在 service 层基于 existing 完成,避免信任 body storeId
        return ApiResponse.success(EmployeeVO.from(employeeService.updateEmployee(employee)));
    }

    @OperationLog(value = "删除员工", detail = "'员工ID ' + #id")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteEmployee(@PathVariable Long id) {
        if (!SecurityContext.hasAdminLevel()) {
            throw new com.example.canteen.exception.SecurityException("无权执行此操作");
        }
        Employee existing = employeeService.getEmployeeById(id);
        if (existing != null) {
            SecurityContext.checkStoreAccess(existing.getStoreId());
        }
        employeeService.deleteEmployee(id);
        return ApiResponse.success(null);
    }

    @GetMapping("/department/store/{storeId}")
    public ApiResponse<List<Department>> getDepartmentsByStore(@PathVariable Long storeId) {
        SecurityContext.checkStoreAccess(storeId);
        return ApiResponse.success(employeeService.getDepartmentsByStore(storeId));
    }

    /**
     * 批量导入员工。
     * 请求体:{ "storeId": 1, "employees": [ {cardNo,name,departmentName,balance,password,status}, ... ] }
     * 返回:{ "success": N, "failed": M, "errors": [{row,cardNo,name,reason}] }
     */
    @OperationLog(value = "批量导入员工", detail = "'门店ID ' + #body['storeId'] + ' 行数 ' + #body['employees'].size()")
    @PostMapping("/batch")
    public ApiResponse<Map<String, Object>> batchImport(@RequestBody Map<String, Object> body) {
        if (!SecurityContext.hasAdminLevel()) {
            throw new com.example.canteen.exception.SecurityException("无权执行此操作");
        }
        Object storeIdObj = body.get("storeId");
        if (storeIdObj == null) {
            throw new com.example.canteen.exception.BusinessException("缺少 storeId");
        }
        Long storeId = Long.valueOf(storeIdObj.toString());
        SecurityContext.checkStoreAccess(storeId);
        Object listObj = body.get("employees");
        if (!(listObj instanceof List<?> rawList)) {
            throw new com.example.canteen.exception.BusinessException("缺少 employees 列表");
        }
        // 将 Map 行转换为 Employee,解析失败行记录错误但不中断
        List<Employee> rows = new java.util.ArrayList<>();
        List<Map<String, Object>> errors = new java.util.ArrayList<>();
        int index = 0;
        for (Object o : rawList) {
            index++;
            int rowNo = index + 1; // 与 service 层一致:数据行从 2 开始(表头为 1)
            try {
                rows.add(objectMapper.convertValue(o, Employee.class));
            } catch (Exception ex) {
                Map<String, Object> err = new HashMap<>();
                err.put("row", rowNo);
                err.put("reason", "数据解析失败: " + ex.getMessage());
                errors.add(err);
            }
        }
        Map<String, Object> result = employeeService.batchImport(storeId, rows);
        // 合并解析阶段错误到最终结果
        if (!errors.isEmpty()) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> serviceErrors = (List<Map<String, Object>>) result.get("errors");
            List<Map<String, Object>> allErrors = new java.util.ArrayList<>(errors);
            if (serviceErrors != null) {
                allErrors.addAll(serviceErrors);
            }
            int serviceFailed = result.get("failed") instanceof Number n ? n.intValue() : 0;
            result.put("errors", allErrors);
            result.put("failed", errors.size() + serviceFailed);
        }
        return ApiResponse.success(result);
    }

    /**
     * 批量充值:给指定门店所有活跃员工充值指定金额。
     * 请求体:{ "storeId": 1, "amount": 100.00 } (门店管理员可不传 storeId,自动取当前门店)
     * 返回:{ "successCount": N, "totalAmount": M }
     */
    @OperationLog(value = "批量充值", detail = "'门店ID ' + #body['storeId'] + ' 金额 ' + #body['amount']")
    @PostMapping("/batch-recharge")
    public ApiResponse<Map<String, Object>> batchRecharge(@RequestBody Map<String, Object> body) {
        if (!SecurityContext.hasAdminLevel()) {
            throw new com.example.canteen.exception.SecurityException("无权执行此操作");
        }
        Object storeIdObj = body.get("storeId");
        Long storeId = storeIdObj != null
                ? Long.valueOf(storeIdObj.toString())
                : SecurityContext.currentStoreId();
        if (storeId == null) {
            throw new com.example.canteen.exception.BusinessException("缺少 storeId");
        }
        SecurityContext.checkStoreAccess(storeId);
        Object amountObj = body.get("amount");
        if (amountObj == null) {
            throw new com.example.canteen.exception.BusinessException("缺少 amount");
        }
        BigDecimal amount = new BigDecimal(amountObj.toString());
        return ApiResponse.success(employeeService.batchRecharge(storeId, amount));
    }

    /**
     * 余额预警名单:查询余额低于阈值的员工列表(分页)。
     * GET /api/employee/low-balance?storeId=&threshold=&page=&size=
     */
    @GetMapping("/low-balance")
    public ApiResponse<Map<String, Object>> lowBalanceList(
            @RequestParam Long storeId,
            @RequestParam(defaultValue = "20") BigDecimal threshold,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (!SecurityContext.hasAdminLevel()) {
            throw new com.example.canteen.exception.SecurityException("无权执行此操作");
        }
        return ApiResponse.success(employeeService.getLowBalanceList(storeId, threshold, page, size));
    }

    /**
     * 余额预警统计:返回预警人数、总余额、平均余额。
     * GET /api/employee/low-balance/stats?storeId=&threshold=
     */
    @GetMapping("/low-balance/stats")
    public ApiResponse<Map<String, Object>> lowBalanceStats(
            @RequestParam Long storeId,
            @RequestParam(defaultValue = "20") BigDecimal threshold) {
        if (!SecurityContext.hasAdminLevel()) {
            throw new com.example.canteen.exception.SecurityException("无权执行此操作");
        }
        return ApiResponse.success(employeeService.getLowBalanceStats(storeId, threshold));
    }

    /**
     * 导出员工列表为 CSV(UTF-8 BOM,Excel 兼容)。
     * GET /api/employee/export?storeId=&keyword=&department=
     * 导出字段:姓名、卡号、部门、余额、状态、创建时间
     */
    @GetMapping("/export")
    public ResponseEntity<byte[]> exportEmployees(
            @RequestParam Long storeId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long department) {
        if (!SecurityContext.hasAdminLevel()) {
            throw new com.example.canteen.exception.SecurityException("无权执行此操作");
        }
        SecurityContext.checkStoreAccess(storeId);

        LambdaQueryWrapper<Employee> wrapper = new LambdaQueryWrapper<Employee>()
                .eq(Employee::getStoreId, storeId)
                .eq(Employee::getIsDeleted, 0);
        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(w -> w.like(Employee::getName, keyword)
                    .or().like(Employee::getCardNo, keyword));
        }
        if (department != null) {
            wrapper.eq(Employee::getDepartmentId, department);
        }
        wrapper.orderByDesc(Employee::getId);
        List<Employee> list = employeeMapper.selectList(wrapper);

        // 部门名称映射
        Map<Long, String> deptNameMap = new HashMap<>();
        List<Department> depts = employeeService.getDepartmentsByStore(storeId);
        if (depts != null) {
            for (Department d : depts) {
                deptNameMap.put(d.getId(), d.getName());
            }
        }

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        StringBuilder sb = new StringBuilder();
        // UTF-8 BOM,使 Excel 正确识别编码
        sb.append('\ufeff');
        sb.append("姓名,卡号,手机号,部门,余额,状态,创建时间\n");
        for (Employee e : list) {
            sb.append(csvEscape(e.getName())).append(',');
            sb.append(csvEscape(e.getCardNo())).append(',');
            sb.append(csvEscape(e.getPhone())).append(',');
            sb.append(csvEscape(e.getDepartmentId() == null ? "" : deptNameMap.getOrDefault(e.getDepartmentId(), ""))).append(',');
            sb.append(e.getBalance() == null ? "0" : e.getBalance().toPlainString()).append(',');
            sb.append(e.getStatus() != null && e.getStatus() == 1 ? "启用" : "禁用").append(',');
            sb.append(e.getCreatedAt() == null ? "" : e.getCreatedAt().format(fmt)).append('\n');
        }

        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        String filename = "employees-" + storeId + "-" + System.currentTimeMillis() + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .contentLength(bytes.length)
                .body(bytes);
    }

    /** CSV 字段转义:含逗号/引号/换行时用双引号包裹,内部双引号转义为两个双引号。
     *  同时防止 CSV 公式注入:以 =、+、-、@ 开头的字段前加单引号。 */
    private String csvEscape(String v) {
        if (v == null) return "";
        // 防止 CSV 公式注入
        if (v.startsWith("=") || v.startsWith("+") || v.startsWith("-") || v.startsWith("@")) {
            v = "'" + v;
        }
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    /**
     * 上传/更新员工头像(批量导入照片使用)。
     * 文件名(去扩展名)作为卡号,自动匹配当前门店内对应员工。
     * 走与 FileController 一致的存储策略:UUID 重命名 + magic bytes 校验 + /uploads 目录。
     * 替换头像时自动删除旧文件,避免磁盘孤儿文件。
     *
     * @param identifier 匹配标识符(文件名去扩展名,支持卡号 / 姓名 / 手机号自动适配)
     * @param storeId    门店 ID(超管需显式传;门店管理员可不传,自动取当前门店)
     * @param file       图片文件(前端已 canvas 压缩)
     */
    @OperationLog(value = "上传员工头像", detail = "'标识符 ' + #identifier + ' 门店ID ' + #storeId")
    @PostMapping("/{identifier}/avatar")
    public ApiResponse<Map<String, Object>> uploadAvatar(@PathVariable String identifier,
                                                         @RequestParam(required = false) Long storeId,
                                                         @RequestParam("file") MultipartFile file) {
        if (!SecurityContext.hasAdminLevel()) {
            throw new com.example.canteen.exception.SecurityException("无权执行此操作");
        }
        Long targetStore = storeId != null ? storeId : SecurityContext.currentStoreId();
        if (targetStore == null) {
            throw new com.example.canteen.exception.BusinessException("缺少 storeId");
        }
        SecurityContext.checkStoreAccess(targetStore);

        if (identifier == null || identifier.isBlank()) {
            return ApiResponse.error(400, "标识符不能为空");
        }
        if (file == null || file.isEmpty()) {
            return ApiResponse.error(400, "文件为空");
        }
        // 5MB 防御性上限(前端已压缩,实际约几十 KB)
        if (file.getSize() > 5 * 1024 * 1024) {
            return ApiResponse.error(400, "文件过大,最大 5MB");
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            return ApiResponse.error(400, "仅允许上传图片文件");
        }

        // 按标识符自动适配:卡号 → 手机号 → 姓名
        Employee employee = employeeService.findEmployeeByIdentifier(identifier, targetStore);
        if (employee == null) {
            return ApiResponse.error(404, "未匹配到员工: " + identifier + "(支持卡号/手机号/姓名)");
        }
        String cardNo = employee.getCardNo();

        try {
            // 删除旧头像文件(避免磁盘孤儿文件)
            if (employee.getAvatar() != null && !employee.getAvatar().isBlank()) {
                try {
                    String oldUrl = employee.getAvatar().split("\\?")[0]; // 去掉 ?v=xxx
                    String oldFileName = oldUrl.replace("/uploads/", "");
                    Path oldFile = Paths.get("uploads").resolve(oldFileName).normalize();
                    // 防止路径遍历
                    if (oldFile.startsWith(Paths.get("uploads").normalize())
                            && Files.exists(oldFile)) {
                        Files.delete(oldFile);
                    }
                } catch (Exception e) {
                    // 删除失败不影响主流程,记录日志即可
                    org.slf4j.LoggerFactory.getLogger(EmployeeController.class)
                            .warn("删除旧头像文件失败: {}", e.getMessage());
                }
            }
            String url = saveAvatarFile(file);
            // 仅更新 avatar 字段:用 updateById 时其他字段为 null 会被默认 NOT_NULL 策略跳过
            Employee patch = new Employee();
            patch.setId(employee.getId());
            patch.setAvatar(url);
            employeeMapper.updateById(patch);

            Map<String, Object> result = new HashMap<>();
            result.put("url", url);
            result.put("cardNo", cardNo);
            result.put("employeeId", employee.getId());
            result.put("employeeName", employee.getName());
            return ApiResponse.success(result);
        } catch (IOException e) {
            return ApiResponse.error(500, "文件保存失败: " + e.getMessage());
        }
    }

    /** 保存头像到 uploads 目录,UUID 重命名 + 扩展名白名单 + magic bytes 校验。返回相对 URL(带 mtime 版本号)。 */
    private String saveAvatarFile(MultipartFile file) throws IOException {
        Path uploadDir = Paths.get("uploads");
        if (!Files.exists(uploadDir)) {
            Files.createDirectories(uploadDir);
        }
        String originalName = file.getOriginalFilename();
        String ext = ".jpg";
        if (originalName != null && originalName.contains(".")) {
            ext = originalName.substring(originalName.lastIndexOf('.'));
            String lowerExt = ext.toLowerCase();
            if (!lowerExt.matches("\\.(jpg|jpeg|png|gif|webp)")) {
                ext = ".jpg";
            }
        }
        String fileName = UUID.randomUUID().toString().replace("-", "") + ext;
        Path target = uploadDir.resolve(fileName).normalize().toAbsolutePath();
        if (!target.startsWith(uploadDir.toAbsolutePath())) {
            throw new com.example.canteen.exception.BusinessException("非法文件名");
        }
        validateImageMagicBytes(file, ext.toLowerCase());
        file.transferTo(target.toFile());
        long mtime = Files.getLastModifiedTime(target).toMillis();
        return "/uploads/" + fileName + "?v=" + mtime;
    }

    /** 校验图片前几字节是否匹配声明的扩展名,防止扩展名伪装上传恶意脚本。 */
    private void validateImageMagicBytes(MultipartFile file, String lowerExt) throws IOException {
        byte[] header = new byte[12];
        int read;
        try (InputStream is = file.getInputStream()) {
            read = is.read(header);
        }
        if (read <= 0) {
            throw new com.example.canteen.exception.BusinessException("文件内容与扩展名不符");
        }
        boolean ok = false;
        switch (lowerExt) {
            case ".jpg":
            case ".jpeg":
                ok = read >= 3
                        && (header[0] & 0xFF) == 0xFF
                        && (header[1] & 0xFF) == 0xD8
                        && (header[2] & 0xFF) == 0xFF;
                break;
            case ".png":
                ok = read >= 8
                        && (header[0] & 0xFF) == 0x89
                        && (header[1] & 0xFF) == 0x50
                        && (header[2] & 0xFF) == 0x4E
                        && (header[3] & 0xFF) == 0x47
                        && (header[4] & 0xFF) == 0x0D
                        && (header[5] & 0xFF) == 0x0A
                        && (header[6] & 0xFF) == 0x1A
                        && (header[7] & 0xFF) == 0x0A;
                break;
            case ".gif":
                ok = read >= 4
                        && (header[0] & 0xFF) == 0x47
                        && (header[1] & 0xFF) == 0x49
                        && (header[2] & 0xFF) == 0x46
                        && (header[3] & 0xFF) == 0x38;
                break;
            case ".webp":
                ok = read >= 12
                        && (header[0] & 0xFF) == 0x52
                        && (header[1] & 0xFF) == 0x49
                        && (header[2] & 0xFF) == 0x46
                        && (header[3] & 0xFF) == 0x46
                        && (header[8] & 0xFF) == 0x57
                        && (header[9] & 0xFF) == 0x45
                        && (header[10] & 0xFF) == 0x42
                        && (header[11] & 0xFF) == 0x50;
                break;
            default:
                ok = false;
        }
        if (!ok) {
            throw new com.example.canteen.exception.BusinessException("文件内容与扩展名不符");
        }
    }
}
