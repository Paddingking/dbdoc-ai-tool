package com.dbdocai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "llm")
public class LlmProperties {

    private String provider = "ollama";
    private String apiKey;
    private OllamaProperties ollama = new OllamaProperties();
    private OpenAIProperties openai = new OpenAIProperties();
    private SiliconFlowProperties siliconflow = new SiliconFlowProperties();
    private AnthropicProperties anthropic = new AnthropicProperties();

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public OllamaProperties getOllama() { return ollama; }
    public void setOllama(OllamaProperties ollama) { this.ollama = ollama; }
    public OpenAIProperties getOpenai() { return openai; }
    public void setOpenai(OpenAIProperties openai) { this.openai = openai; }
    public SiliconFlowProperties getSiliconflow() { return siliconflow; }
    public void setSiliconflow(SiliconFlowProperties siliconflow) { this.siliconflow = siliconflow; }
    public AnthropicProperties getAnthropic() { return anthropic; }
    public void setAnthropic(AnthropicProperties anthropic) { this.anthropic = anthropic; }

    public static class OllamaProperties {
        private String baseUrl = "http://localhost:11434";
        private String model = "qwen2.5:7b";
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
    }

    public static class OpenAIProperties {
        private String apiKey;
        private String baseUrl = "https://api.openai.com/v1";
        private String model = "gpt-4o-mini";
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
    }

    public static class SiliconFlowProperties {
        private String apiKey;
        private String baseUrl = "https://api.siliconflow.cn/v1";
        private String model = "Qwen/Qwen2.5-7B-Instruct";
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
    }

    public static class AnthropicProperties {
        private String apiKey;
        private String baseUrl = "https://api.anthropic.com";
        private String model = "claude-sonnet-4-20250514";
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
    }
}
