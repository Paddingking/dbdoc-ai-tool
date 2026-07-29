package com.dbdocai.config;

import com.dbdocai.llm.LlmAdapter;
import com.dbdocai.llm.LlmAdapterFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LlmConfig {

    @Bean
    @ConfigurationProperties(prefix = "llm")
    public LlmProperties llmProperties() {
        return new LlmProperties();
    }

    @Bean
    public LlmAdapter llmAdapter(LlmProperties props) {
        return buildAdapter(props);
    }

    public static LlmAdapter buildAdapter(LlmProperties props) {
        String provider = props.getProvider();
        String baseUrl = null;
        String model = null;
        String apiKey = props.getApiKey();

        if ("ollama".equalsIgnoreCase(provider) && props.getOllama() != null) {
            baseUrl = props.getOllama().getBaseUrl();
            model = props.getOllama().getModel();
        } else if ("openai".equalsIgnoreCase(provider) && props.getOpenai() != null) {
            baseUrl = props.getOpenai().getBaseUrl();
            model = props.getOpenai().getModel();
            apiKey = apiKey != null ? apiKey : props.getOpenai().getApiKey();
        } else if ("siliconflow".equalsIgnoreCase(provider) && props.getSiliconflow() != null) {
            baseUrl = props.getSiliconflow().getBaseUrl();
            model = props.getSiliconflow().getModel();
            apiKey = apiKey != null ? apiKey : props.getSiliconflow().getApiKey();
        } else if ("anthropic".equalsIgnoreCase(provider) && props.getAnthropic() != null) {
            baseUrl = props.getAnthropic().getBaseUrl();
            model = props.getAnthropic().getModel();
            apiKey = apiKey != null ? apiKey : props.getAnthropic().getApiKey();
        }

        return LlmAdapterFactory.create(provider, baseUrl, model, apiKey);
    }
}
