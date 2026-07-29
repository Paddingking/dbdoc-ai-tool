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

public class AnthropicCompatibleLlmAdapter implements LlmAdapter {
    private static final Logger log = LoggerFactory.getLogger(AnthropicCompatibleLlmAdapter.class);
    private final String baseUrl, model, apiKey;
    private final RestTemplate restTemplate = LlmRestTemplateConfig.get();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AnthropicCompatibleLlmAdapter(String baseUrl, String model, String apiKey) {
        this.baseUrl = baseUrl != null ? baseUrl : "https://api.anthropic.com";
        this.model = model != null ? model : "claude-sonnet-4-20250514";
        this.apiKey = apiKey;
    }

    @Override
    public String generate(String systemPrompt, String userPrompt) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", 8192);
        body.put("system", systemPrompt);
        ArrayNode msgs = body.putArray("messages");
        ObjectNode m = msgs.addObject();
        m.put("role", "user"); m.put("content", userPrompt);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-api-key", apiKey);
        headers.set("anthropic-version", "2023-06-01");

        try {
            ResponseEntity<String> resp = restTemplate.postForEntity(
                baseUrl + "/v1/messages", new HttpEntity<>(body.toString(), headers), String.class);
            JsonNode root = objectMapper.readTree(resp.getBody());
            JsonNode content = root.get("content");
            if (content == null || !content.isArray() || content.size() == 0)
                throw new LlmException("Empty response", "LLM_EMPTY_RESPONSE");
            StringBuilder text = new StringBuilder();
            for (JsonNode block : content) {
                if (block.has("text")) text.append(block.get("text").asText());
            }
            return text.toString();
        } catch (Exception e) {
            throw new LlmException("Anthropic error: " + e.getMessage(), "LLM_ERROR", e);
        }
    }

    @Override
    public boolean testConnection() {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", model); body.put("max_tokens", 50);
            body.put("system", "Reply with just the word OK.");
            ArrayNode msgs = body.putArray("messages");
            ObjectNode m = msgs.addObject();
            m.put("role", "user"); m.put("content", "Say OK");
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-api-key", apiKey);
            headers.set("anthropic-version", "2023-06-01");
            ResponseEntity<String> r = restTemplate.postForEntity(
                baseUrl + "/v1/messages", new HttpEntity<>(body.toString(), headers), String.class);
            return r.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String getProviderName() { return "anthropic"; }
}
