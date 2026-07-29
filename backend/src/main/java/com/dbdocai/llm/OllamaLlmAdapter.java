package com.dbdocai.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import com.dbdocai.config.LlmRestTemplateConfig;

import java.util.*;

public class OllamaLlmAdapter implements LlmAdapter {
    private static final Logger log = LoggerFactory.getLogger(OllamaLlmAdapter.class);
    private final String baseUrl, model;
    private final RestTemplate restTemplate = LlmRestTemplateConfig.get();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OllamaLlmAdapter(String baseUrl, String model) {
        this.baseUrl = baseUrl != null ? baseUrl : "http://localhost:11434";
        this.model = model != null ? model : "qwen2.5:7b";
    }

    @Override
    public String generate(String systemPrompt, String userPrompt) {
        String url = baseUrl + "/api/generate";
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", model);
        request.put("prompt", systemPrompt + "\n\n" + userPrompt);
        request.put("stream", false);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode respField = root.get("response");
            if (respField == null || respField.asText().isEmpty())
                throw new LlmException("Empty response", "LLM_EMPTY_RESPONSE");
            return respField.asText();
        } catch (Exception e) {
            throw new LlmException("Ollama error: " + e.getMessage(), "LLM_ERROR", e);
        }
    }

    @Override
    public boolean testConnection() {
        try {
            ResponseEntity<String> r = restTemplate.getForEntity(baseUrl + "/api/tags", String.class);
            return r.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String getProviderName() { return "ollama"; }
}
