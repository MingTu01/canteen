package com.example.canteen.service;

import com.example.canteen.entity.Department;
import com.example.canteen.entity.Employee;
import com.example.canteen.exception.BusinessException;
import com.example.canteen.mapper.DepartmentMapper;
import com.example.canteen.mapper.EmployeeMapper;
import com.example.canteen.mapper.RechargeRecordMapper;
import com.example.canteen.security.SecurityContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 员工服务单元测试
 */
@DisplayName("员工服务测试")
class EmployeeServiceTest {

    private EmployeeMapper employeeMapper;
    private DepartmentMapper departmentMapper;
    private RechargeRecordMapper rechargeRecordMapper;
    private PasswordEncoder passwordEncoder;
    private EmployeeService employeeService;

    private Employee testEmployee1;
    private Employee testEmployee2;
    private Department testDept1;

    @BeforeEach
    void setUp() {
        employeeMapper = mock(EmployeeMapper.class);
        departmentMapper = mock(DepartmentMapper.class);
        rechargeRecordMapper = mock(RechargeRecordMapper.class);
        passwordEncoder = mock(PasswordEncoder.class);
        when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$encoded");
        employeeService = new EmployeeService(employeeMapper, departmentMapper, rechargeRecordMapper, passwordEncoder);

        testEmployee1 = new Employee();
        testEmployee1.setId(1L);
        testEmployee1.setStoreId(1L);
        testEmployee1.setCardNo("CARD001");
        testEmployee1.setName("张明");
        testEmployee1.setDepartmentId(1L);
        testEmployee1.setBalance(new BigDecimal("500.00"));
        testEmployee1.setStatus(1);
        testEmployee1.setIsDeleted(0);

        testEmployee2 = new Employee();
        testEmployee2.setId(2L);
        testEmployee2.setStoreId(1L);
        testEmployee2.setCardNo("CARD002");
        testEmployee2.setName("李娜");
        testEmployee2.setDepartmentId(1L);
        testEmployee2.setBalance(new BigDecimal("300.00"));
        testEmployee2.setStatus(1);
        testEmployee2.setIsDeleted(0);

        testDept1 = new Department();
        testDept1.setId(1L);
        testDept1.setStoreId(1L);
        testDept1.setName("技术部");
    }

    @Test
    @DisplayName("获取门店员工列表")
    void getEmployeesByStore_ReturnsList() {
        when(employeeMapper.selectByStoreId(1L)).thenReturn(Arrays.asList(testEmployee1, testEmployee2));

        List<Employee> result = employeeService.getEmployeesByStore(1L);

        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals("张明", result.get(0).getName());
        verify(employeeMapper).selectByStoreId(1L);
    }

    @Test
    @DisplayName("根据ID获取员工")
    void getEmployeeById_ReturnsEmployee() {
        when(employeeMapper.selectById(1L)).thenReturn(testEmployee1);

        Employee result = employeeService.getEmployeeById(1L);

        assertNotNull(result);
        assertEquals("张明", result.getName());
        assertEquals("CARD001", result.getCardNo());
    }

    @Test
    @DisplayName("根据卡号+门店获取员工 - 员工存在")
    void getEmployeeByCardNoAndStore_Success() {
        when(employeeMapper.selectByCardNoAndStore("CARD001", 1L)).thenReturn(testEmployee1);

        Employee result = employeeService.getEmployeeByCardNoAndStore("CARD001", 1L);

        assertNotNull(result);
        assertEquals("张明", result.getName());
        assertEquals(1, result.getStatus());
    }

    @Test
    @DisplayName("根据卡号+门店获取员工 - 员工不存在")
    void getEmployeeByCardNoAndStore_NotFound() {
        when(employeeMapper.selectByCardNoAndStore("UNKNOWN_CARD", 1L)).thenReturn(null);

        Employee result = employeeService.getEmployeeByCardNoAndStore("UNKNOWN_CARD", 1L);

        assertNull(result);
    }

    @Test
    @DisplayName("创建员工")
    void createEmployee_Success() {
        Employee newEmployee = new Employee();
        newEmployee.setStoreId(1L);
        newEmployee.setCardNo("CARD999");
        newEmployee.setName("新员工");
        newEmployee.setBalance(new BigDecimal("100.00"));
        newEmployee.setStatus(1);

        when(employeeMapper.insert(any(Employee.class))).thenAnswer(invocation -> {
            Employee emp = invocation.getArgument(0);
            emp.setId(10L);
            return 1;
        });

        try (MockedStatic<SecurityContext> mocked = mockStatic(SecurityContext.class)) {
            Employee result = employeeService.createEmployee(newEmployee);

            assertNotNull(result);
            assertEquals(10L, result.getId());
            assertEquals("新员工", result.getName());
            assertEquals(0, result.getIsDeleted());
            mocked.verify(() -> SecurityContext.checkStoreAccess(1L));
        }

        verify(employeeMapper).insert(newEmployee);
    }

    @Test
    @DisplayName("更新员工信息")
    void updateEmployee_Success() {
        testEmployee1.setBalance(new BigDecimal("600.00"));
        when(employeeMapper.selectById(1L)).thenReturn(testEmployee1);
        when(employeeMapper.updateById(any(Employee.class))).thenReturn(1);

        try (MockedStatic<SecurityContext> mocked = mockStatic(SecurityContext.class)) {
            Employee result = employeeService.updateEmployee(testEmployee1);

            assertNotNull(result);
            // P0-1: updateEmployee 禁止修改余额,balance 被设为 null 跳过更新
            assertNull(result.getBalance());
            mocked.verify(() -> SecurityContext.checkStoreAccess(1L));
        }

        verify(employeeMapper).updateById(testEmployee1);
    }

    @Test
    @DisplayName("删除员工 - 软删除(is_deleted=1)")
    void deleteEmployee_SoftDelete() {
        when(employeeMapper.selectById(1L)).thenReturn(testEmployee1);
        when(employeeMapper.updateById(any(Employee.class))).thenReturn(1);

        try (MockedStatic<SecurityContext> mocked = mockStatic(SecurityContext.class)) {
            employeeService.deleteEmployee(1L);

            assertEquals(1, testEmployee1.getIsDeleted());
            mocked.verify(() -> SecurityContext.checkStoreAccess(1L));
        }

        verify(employeeMapper).updateById(testEmployee1);
        verify(employeeMapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("删除员工 - 员工不存在抛异常")
    void deleteEmployee_NotFound() {
        when(employeeMapper.selectById(999L)).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> employeeService.deleteEmployee(999L));
        assertEquals("员工不存在", exception.getMessage());

        verify(employeeMapper, never()).updateById(any(Employee.class));
    }

    @Test
    @DisplayName("获取门店部门列表")
    void getDepartmentsByStore_ReturnsList() {
        Department dept2 = new Department();
        dept2.setId(2L);
        dept2.setStoreId(1L);
        dept2.setName("市场部");

        when(departmentMapper.selectByStoreId(1L)).thenReturn(Arrays.asList(testDept1, dept2));

        List<Department> result = employeeService.getDepartmentsByStore(1L);

        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals("技术部", result.get(0).getName());
        assertEquals("市场部", result.get(1).getName());
    }
}
