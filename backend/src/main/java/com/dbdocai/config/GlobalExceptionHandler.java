package com.dbdocai.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局异常处理（P1-2 修复）。
 *
 * <p>fullcheck 报告指出多数端点裸抛异常并把 {@code e.getMessage()} 透传且返 200，
 * 既泄露内部细节又无统一错误形态。本处理器统一返回脱敏错误体与正确状态码：
 * <ul>
 *   <li>参数/校验类异常（{@link IllegalArgumentException}）→ 400；</li>
 *   <li>其它未捕获异常 → 500，且日志打印堆栈，但响应体不回显内部消息（防信息泄露）。</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        return error(HttpStatus.BAD_REQUEST, "请求参数错误");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception e) {
        log.error("未处理的异常", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "服务内部错误");
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
