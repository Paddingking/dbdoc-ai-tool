package com.dbdocai.controller;

import com.dbdocai.service.DataSourceStoreService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DataSourceController 单元测试（P1-3：testConnection 响应脱敏）。
 *
 * <p>独立装配 MockMvc（{@code standaloneSetup}），仅加载目标 Controller，不引入鉴权拦截器/CORS，
 * 聚焦验证 {@code POST /api/datasource/test} 在连接失败时的响应：
 * <ul>
 *   <li>返回 {@code success:false} 与通用文案；</li>
 *   <li>响应体不得泄露 url / 账号 / 密码 / 数据库类型 / 原始异常类名或连接细节
 *       （统一由 Controller 捕获异常后返回固定文案，符合 P2-10 脱敏要求）。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
public class DataSourceControllerTest {

    @Mock
    private DataSourceStoreService storeService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        DataSourceController controller = new DataSourceController(storeService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void testConnection_failureMessageIsSanitized_noSensitiveLeak() throws Exception {
        // 故意构造含敏感信息的非法/不可达数据源：协议白名单之外（jdbc:oracle）会在
        // JdbcUrlValidator 处抛 IllegalArgumentException，被 Controller 捕获后返回通用文案，
        // 全程不触达网络、也不回显任何内部细节。
        String json = "{"
                + "\"url\":\"jdbc:oracle:thin:@db.internal.corp:1521:SECRETS\","
                + "\"dbType\":\"oracle\","
                + "\"username\":\"admin\","
                + "\"password\":\"P@ssw0rd!\""
                + "}";

        MvcResult result = mockMvc.perform(post("/api/datasource/test")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andReturn();

        String body = result.getResponse().getContentAsString();

        // 应返回通用失败文案
        assertTrue(body.contains("连接失败"), "应返回通用失败文案");

        // 不得泄露任何内部敏感信息
        assertFalse(body.contains("db.internal.corp"), "不得泄露数据库主机");
        assertFalse(body.contains("SECRETS"), "不得泄露库名");
        assertFalse(body.contains("admin"), "不得泄露账号");
        assertFalse(body.contains("P@ssw0rd!"), "不得泄露密码");
        assertFalse(body.contains("oracle"), "不得泄露数据库类型细节");
        assertFalse(body.contains("thin"), "不得泄露 JDBC 驱动细节");
        assertFalse(containsIgnoreCase(body, "SQLException"), "不得泄露原始异常类名");
        assertFalse(containsIgnoreCase(body, "Communications link"), "不得泄露连接异常细节");
        assertFalse(containsIgnoreCase(body, "stacktrace"), "不得泄露堆栈信息");
    }

    private static boolean containsIgnoreCase(String s, String sub) {
        return s.toLowerCase().contains(sub.toLowerCase());
    }
}
