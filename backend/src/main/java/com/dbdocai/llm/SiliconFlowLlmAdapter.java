package com.dbdocai.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import com.dbdocai.config.LlmRestTemplateConfig;

public class SiliconFlowLlmAdapter implements LlmAdapter {
    private static final Logger log = LoggerFactory.getLogger(SiliconFlowLlmAdapter.class);
    private final String baseUrl, model, apiKey;
    private final RestTemplate restTemplate = LlmRestTemplateConfig.get();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SiliconFlowLlmAdapter(String baseUrl, String model, String apiKey) {
        this.baseUrl = baseUrl != null ? baseUrl : "https://api.siliconflow.cn/v1";
        this.model = model != null ? model : "Qwen/Qwen2.5-7B-Instruct";
        this.apiKey = apiKey;
    }

    @Override
    public String generate(String systemPrompt, String userPrompt) {
        return call(systemPrompt, userPrompt);
    }

    @Override
    public boolean testConnection() {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(apiKey);
            ResponseEntity<String> resp = restTemplate.exchange(
                baseUrl + "/models", HttpMethod.GET, new HttpEntity<>(headers), String.class);
            return resp.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String getProviderName() { return "siliconflow"; }

    private String call(String systemPrompt, String userPrompt) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        ArrayNode messages = body.putArray("messages");
        ObjectNode sys = messages.addObject();
        sys.put("role", "system"); sys.put("content", systemPrompt);
        ObjectNode usr = messages.addObject();
        usr.put("role", "user"); usr.put("content", userPrompt);
        body.put("temperature", 0.3);
        body.put("max_tokens", 4096);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        try {
            ResponseEntity<String> resp = restTemplate.postForEntity(
                baseUrl + "/chat/completions", new HttpEntity<>(body.toString(), headers), String.class);
            JsonNode root = objectMapper.readTree(resp.getBody());
            JsonNode choices = root.get("choices");
            if (choices == null || !choices.isArray() || choices.size() == 0)
                throw new LlmException("No choices", "LLM_EMPTY_RESPONSE");
            return choices.get(0).get("message").get("content").asText();
        } catch (Exception e) {
            throw new LlmException("SiliconFlow error: " + e.getMessage(), "LLM_ERROR", e);
        }
    }
}
