package com.leoli.gateway.admin.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * ============================================================================
 * 统一 API 响应封装类
 * ============================================================================
 * <p>
 * 用于标准化所有 Controller 的返回格式，替代手动构建 HashMap。
 * <p>
 * 响应格式:
 * {
 *   "code": 200,           // 业务状态码
 *   "message": "Success",  // 描述信息
 *   "data": {...},         // 泛型数据载体
 *   "traceId": "xxx",      // 可选，链路追踪ID
 *   "timestamp": 123456789 // 响应时间戳
 * }
 * <p>
 * 使用示例:
 * - ApiResponse.success(data)                  // 简单成功响应
 * - ApiResponse.success(data, "查询成功")       // 带消息的成功响应
 * - ApiResponse.error(500, "系统错误")          // 错误响应
 * - ApiResponse.notFound("路由不存在")          // 404快捷
 * - ApiResponse.badRequest("参数缺失")          // 400快捷
 *
 * @param <T> 数据类型
 * @author leoli
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 业务状态码
     * - 200: 成功
     * - 400: 参数错误
     * - 404: 资源不存在
     * - 500: 系统错误
     */
    private int code;

    /**
     * 描述信息
     */
    private String message;

    /**
     * 数据载体（泛型）
     */
    private T data;

    /**
     * 链路追踪ID（可选）
     */
    private String traceId;

    /**
     * 响应时间戳（自动生成）
     */
    private long timestamp;

    // ===================== 静态工厂方法 =====================

    /**
     * 创建成功响应（默认消息）
     */
    public static <T> ApiResponse<T> success(T data) {
        return ApiResponse.<T>builder()
                .code(200)
                .message("Success")
                .data(data)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    /**
     * 创建成功响应（自定义消息）
     */
    public static <T> ApiResponse<T> success(T data, String message) {
        return ApiResponse.<T>builder()
                .code(200)
                .message(message != null ? message : "Success")
                .data(data)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    /**
     * 创建空数据成功响应
     */
    public static <T> ApiResponse<T> success() {
        return success(null);
    }

    /**
     * 创建空数据成功响应（自定义消息）
     */
    public static <T> ApiResponse<T> success(String message) {
        return success(null, message);
    }

    /**
     * 创建错误响应（默认500）
     */
    public static <T> ApiResponse<T> error(String message) {
        return error(500, message);
    }

    /**
     * 创建错误响应（自定义状态码）
     */
    public static <T> ApiResponse<T> error(int code, String message) {
        return ApiResponse.<T>builder()
                .code(code)
                .message(message != null ? message : "Error")
                .timestamp(System.currentTimeMillis())
                .build();
    }

    /**
     * 创建404响应（资源不存在）
     */
    public static <T> ApiResponse<T> notFound(String message) {
        return error(404, message != null ? message : "Resource not found");
    }

    /**
     * 创建400响应（参数错误）
     */
    public static <T> ApiResponse<T> badRequest(String message) {
        return error(400, message != null ? message : "Bad request");
    }

    /**
     * 创建403响应（权限不足）
     */
    public static <T> ApiResponse<T> forbidden(String message) {
        return error(403, message != null ? message : "Forbidden");
    }

    /**
     * 创建401响应（未认证）
     */
    public static <T> ApiResponse<T> unauthorized(String message) {
        return error(401, message != null ? message : "Unauthorized");
    }

    // ===================== 辅助方法 =====================

    /**
     * 判断是否成功
     */
    public boolean isSuccess() {
        return code == 200;
    }

    /**
     * 设置链路追踪ID
     */
    public ApiResponse<T> withTraceId(String traceId) {
        this.traceId = traceId;
        return this;
    }

    /**
     * 过渡期兼容方法：转换为 Map 格式
     * <p>
     * 用于现有前端代码逐步迁移，返回与原有 HashMap 格式一致的结构
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("code", code);
        map.put("message", message);
        map.put("data", data);
        if (traceId != null) {
            map.put("traceId", traceId);
        }
        map.put("timestamp", timestamp);
        return map;
    }
}