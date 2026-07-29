package com.dbdocai.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HealthController 单元测试（P2-5：健康检查探针免鉴权）。
 *
 * <p>独立装配 {@code standaloneSetup}，仅加载 HealthController 本身，不引入鉴权拦截器，
 * 验证 {@code GET /api/ping} 端点：
 * <ul>
 *   <li>无需任何令牌即可返回 {@code success:true, message:pong}；</li>
 *   <li>完整应用下 {@code /api/ping} 亦已在 {@code WebConfig} 中被排除鉴权
 *       （{@code excludePathPatterns("/api/ping")}），保持免令牌探活。</li>
 * </ul>
 */
public class HealthControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new HealthController()).build();
    }

    @Test
    void ping_returnsPongWithoutToken() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("pong"))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertTrue(body.contains("pong"), "响应体应包含 pong");
    }
}
