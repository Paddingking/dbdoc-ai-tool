package com.dbdocai.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GlobalExceptionHandler 单元测试（P1-6）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>参数/校验异常（{@link IllegalArgumentException}）→ 400；</li>
 *   <li>其它未捕获异常 → 500；</li>
 *   <li>响应体脱敏：不含原始异常的内部细节（凭证、拓扑、堆栈）。</li>
 * </ul>
 */
public class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    public void illegalArgumentException_returns400() {
        ResponseEntity<Map<String, Object>> resp = handler.handleIllegalArgument(
                new IllegalArgumentException("某个内部参数细节 secret=xxx"));
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals("请求参数错误", resp.getBody().get("message"));
    }

    @Test
    public void genericException_returns500() {
        ResponseEntity<Map<String, Object>> resp = handler.handleGeneric(
                new RuntimeException("DB password leaked: my-secret-pw @ 10.0.0.1"));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
    }

    @Test
    public void responseIsSanitized_noInternalDetailsLeak() {
        String secret = "DB password leaked: my-secret-pw @ 10.0.0.1";
        ResponseEntity<Map<String, Object>> resp = handler.handleGeneric(new RuntimeException(secret));
        Map<String, Object> body = resp.getBody();
        String message = String.valueOf(body.get("message"));

        assertTrue(message.contains("服务内部错误"), "应返回通用脱敏消息");
        assertFalse(message.contains("my-secret-pw"), "响应体不得含内部凭证细节");
        assertFalse(message.contains("10.0.0.1"), "响应体不得含内部拓扑细节");
        assertEquals(false, body.get("success"));
    }
}
