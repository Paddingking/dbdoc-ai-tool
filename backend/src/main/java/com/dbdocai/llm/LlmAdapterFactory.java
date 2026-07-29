package com.dbdocai.llm;

public class LlmAdapterFactory {

    public static LlmAdapter create(String provider, String baseUrl,
                                     String model, String apiKey) {
        if (provider == null) provider = "ollama";
        switch (provider.toLowerCase()) {
            case "ollama":
                return new OllamaLlmAdapter(baseUrl, model);
            case "openai":
                if (apiKey == null || apiKey.isEmpty())
                    throw new IllegalArgumentException("OpenAI requires an API key");
                return new OpenAILlmAdapter(baseUrl, model, apiKey);
            case "siliconflow":
                if (apiKey == null || apiKey.isEmpty())
                    throw new IllegalArgumentException("SiliconFlow requires an API key");
                return new SiliconFlowLlmAdapter(baseUrl, model, apiKey);
            case "anthropic":
                if (apiKey == null || apiKey.isEmpty())
                    throw new IllegalArgumentException("Anthropic requires an API key");
                return new AnthropicCompatibleLlmAdapter(baseUrl, model, apiKey);
            default:
                throw new IllegalArgumentException("Unsupported provider: " + provider);
        }
    }
}
