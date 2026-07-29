package com.dbdocai.llm;

import com.dbdocai.config.LlmRestTemplateConfig;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * LLM 适配器相关单元测试（P1-6）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>{@link LlmRestTemplateConfig} 的 RestTemplate 超时生效（connect 10s / read 60s）；</li>
 *   <li>云厂商（openai / siliconflow / anthropic）缺 API key 时抛异常；</li>
 *   <li>本地 Ollama 不要求 API key，可正常创建。</li>
 * </ul>
 */
public class LlmAdapterFactoryTest {

    @Test
    public void restTemplateHasExpectedTimeouts() {
        RestTemplate rt = LlmRestTemplateConfig.get();
        assertNotNull(rt);
        SimpleClientHttpRequestFactory factory =
                (SimpleClientHttpRequestFactory) rt.getRequestFactory();
        assertEquals(10000, factory.getConnectTimeout(), "connect 超时应为 10s");
        assertEquals(60000, factory.getReadTimeout(), "read 超时应为 60s");
    }

    @Test
    public void missingApiKey_throwsForCloudProviders() {
        assertThrows(IllegalArgumentException.class,
                () -> LlmAdapterFactory.create("openai", "https://api.openai.com/v1", "gpt-4o-mini", null));
        assertThrows(IllegalArgumentException.class,
                () -> LlmAdapterFactory.create("siliconflow", "https://api.siliconflow.cn/v1",
                        "Qwen/Qwen2.5-7B-Instruct", ""));
        assertThrows(IllegalArgumentException.class,
                () -> LlmAdapterFactory.create("anthropic", "https://api.anthropic.com", "claude", null));
    }

    @Test
    public void ollamaDoesNotRequireApiKey() {
        // Ollama 为本地模型，缺 key 不应抛异常
        LlmAdapter adapter = LlmAdapterFactory.create("ollama", "http://localhost:11434", "qwen2.5:7b", null);
        assertNotNull(adapter);
    }
}
