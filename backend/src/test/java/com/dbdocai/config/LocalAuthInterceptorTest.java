package com.dbdocai.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LocalAuthInterceptor 单元测试（P1-6）。
 *
 * <p>覆盖三种关键路径：
 * <ul>
 *   <li>无令牌 → 401 拒绝（fail-closed）；</li>
 *   <li>有效令牌 → 放行；</li>
 *   <li>OPTIONS 预检 → 放行（CORS 预检不应被鉴权拦截）。</li>
 * </ul>
 *
 * <p>测试通过反射注入 {@code authEnabled} 与缓存的 {@code cachedExpectedToken}，
 * 避免触发真实的 env/文件令牌解析副作用。
 */
public class LocalAuthInterceptorTest {

    private static LocalAuthInterceptor newInterceptor(boolean authEnabled, String expectedToken)
            throws Exception {
        LocalAuthInterceptor interceptor = new LocalAuthInterceptor();
        setField(interceptor, "authEnabled", authEnabled);
        setField(interceptor, "cachedExpectedToken", expectedToken);
        return interceptor;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    public void missingToken_isRejectedWith401() throws Exception {
        LocalAuthInterceptor interceptor = newInterceptor(true, "secret-token");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/datasource/list");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());
        assertFalse(allowed, "无令牌请求应被拒绝");
        assertTrue(response.getStatus() == 401, "应返回 401 未授权");
    }

    @Test
    public void validToken_isAllowed() throws Exception {
        LocalAuthInterceptor interceptor = newInterceptor(true, "secret-token");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/datasource/list");
        request.addHeader("X-DBDoc-Token", "secret-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());
        assertTrue(allowed, "携带有效令牌的请求应放行");
    }

    @Test
    public void optionsPreflight_isAllowed() throws Exception {
        LocalAuthInterceptor interceptor = newInterceptor(true, "secret-token");
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/datasource/list");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());
        assertTrue(allowed, "OPTIONS 预检请求应放行（不被鉴权拦截）");
    }
}
