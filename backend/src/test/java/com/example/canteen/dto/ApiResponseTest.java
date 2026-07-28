package com.example.canteen.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ApiResponse 统一响应格式测试
 */
@DisplayName("API响应格式测试")
class ApiResponseTest {

    @Test
    @DisplayName("成功响应 - 携带数据")
    void success_WithData() {
        ApiResponse<String> response = ApiResponse.success("test data");

        assertEquals(200, response.getCode());
        assertEquals("success", response.getMessage());
        assertEquals("test data", response.getData());
    }

    @Test
    @DisplayName("成功响应 - 携带自定义消息和数据")
    void success_WithMessageAndData() {
        ApiResponse<Integer> response = ApiResponse.success("操作成功", 42);

        assertEquals(200, response.getCode());
        assertEquals("操作成功", response.getMessage());
        assertEquals(42, response.getData());
    }

    @Test
    @DisplayName("错误响应 - 指定错误码和消息")
    void error_WithCodeAndMessage() {
        ApiResponse<Void> response = ApiResponse.error(404, "资源不存在");

        assertEquals(404, response.getCode());
        assertEquals("资源不存在", response.getMessage());
        assertNull(response.getData());
    }

    @Test
    @DisplayName("错误响应 - 仅指定消息，默认500")
    void error_WithMessageOnly() {
        ApiResponse<Void> response = ApiResponse.error("服务器内部错误");

        assertEquals(500, response.getCode());
        assertEquals("服务器内部错误", response.getMessage());
        assertNull(response.getData());
    }

    @Test
    @DisplayName("成功响应 - null数据")
    void success_WithNullData() {
        ApiResponse<Void> response = ApiResponse.success(null);

        assertEquals(200, response.getCode());
        assertEquals("success", response.getMessage());
        assertNull(response.getData());
    }
}
