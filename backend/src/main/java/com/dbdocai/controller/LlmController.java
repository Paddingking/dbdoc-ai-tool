package com.dbdocai.controller;

import com.dbdocai.config.LlmConfig;
import com.dbdocai.config.LlmProperties;
import com.dbdocai.llm.LlmAdapter;
import com.dbdocai.service.LlmConfigService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/llm")
public class LlmController {

    private final LlmAdapter llmAdapter;
    private final LlmProperties llmProperties;
    private final LlmConfigService configService;

    public LlmController(LlmAdapter llmAdapter, LlmProperties llmProperties,
                         LlmConfigService configService) {
        this.llmAdapter = llmAdapter;
        this.llmProperties = llmProperties;
        this.configService = configService;
    }

    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getConfig() {
        return ResponseEntity.ok(configService.getConfig());
    }

    @PutMapping("/config")
    public ResponseEntity<Map<String, Object>> updateConfig(@RequestBody Map<String, String> body) {
        String provider = body.get("provider");
        String apiKey = body.get("apiKey");
        String model = body.get("model");
        String baseUrl = body.get("baseUrl");
        configService.updateConfig(provider, apiKey, model, baseUrl);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "配置已更新");
        result.put("provider", llmProperties.getProvider());
        return ResponseEntity.ok(result);
    }

    @PostMapping("/test")
    public ResponseEntity<Map<String, Object>> testConnection() {
        Map<String, Object> body = new HashMap<>();
        try {
            boolean ok = LlmConfig.buildAdapter(llmProperties).testConnection();
            if (ok) {
                body.put("success", true);
                body.put("message", "连接成功");
            } else {
                body.put("success", false);
                body.put("message", "连接失败，请检查 LLM 配置");
            }
        } catch (Exception e) {
            // P1-2：连接测试失败返回友好错误，不向上抛裸异常（避免 whitelabel 500 / 内部细节泄露）
            body.put("success", false);
            body.put("message", "连接测试失败，请检查 LLM 配置");
        }
        return ResponseEntity.ok(body);
    }
}
