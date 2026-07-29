package com.dbdocai.config;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * LLM 适配器共享的 {@link RestTemplate} 单例（P1-1 修复）。
 *
 * <p>fullcheck 报告指出四个 LLM 适配器各自 {@code new RestTemplate()}，无连接/读取超时，
 * 慢端点会拖死线程池（DoS）。本类提供带超时（connect 10s / read 60s）的共享实例，
 * 由适配器静态获取，避免改动非 Spring 托管的适配器构造链。
 */
public final class LlmRestTemplateConfig {
    private static volatile RestTemplate instance;

    private LlmRestTemplateConfig() {
        // 工具类，禁止实例化
    }

    public static RestTemplate get() {
        if (instance == null) {
            synchronized (LlmRestTemplateConfig.class) {
                if (instance == null) {
                    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
                    factory.setConnectTimeout(10000);
                    factory.setReadTimeout(60000);
                    instance = new RestTemplate(factory);
                }
            }
        }
        return instance;
    }
}
