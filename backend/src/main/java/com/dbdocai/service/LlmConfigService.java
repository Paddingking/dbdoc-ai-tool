package com.dbdocai.service;

import com.dbdocai.config.LlmProperties;
import com.dbdocai.util.CryptoUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class LlmConfigService {
    private static final Logger log = LoggerFactory.getLogger(LlmConfigService.class);
    private final LlmProperties llmProperties;
    private final DbStore db;

    public LlmConfigService(LlmProperties llmProperties, DbStore db) {
        this.llmProperties = llmProperties;
        this.db = db;
        // Load persisted config on startup
        loadFromDb();
    }

    private void loadFromDb() {
        Map<String, String> cfg = db.getAllConfig();
        if (cfg.containsKey("provider")) llmProperties.setProvider(cfg.get("provider"));
        if (cfg.containsKey("apiKey")) {
            // B1: 读取时解密 apiKey（无 ENC: 前缀的遗留明文原样返回）
            llmProperties.setApiKey(CryptoUtil.decrypt(cfg.get("apiKey")));
        }
        if (cfg.containsKey("model")) {
            String model = cfg.get("model");
            if ("openai".equals(llmProperties.getProvider())) llmProperties.getOpenai().setModel(model);
            else if ("siliconflow".equals(llmProperties.getProvider())) llmProperties.getSiliconflow().setModel(model);
            else if ("anthropic".equals(llmProperties.getProvider())) llmProperties.getAnthropic().setModel(model);
            else llmProperties.getOllama().setModel(model);
        }
        // B2: 按 provider 读取各自持久化的 baseUrl（新结构）；旧版单 baseUrl 键作为兜底
        String legacyBaseUrl = cfg.get("baseUrl");
        applyProviderBaseUrl(cfg, "ollama", legacyBaseUrl);
        applyProviderBaseUrl(cfg, "openai", legacyBaseUrl);
        applyProviderBaseUrl(cfg, "siliconflow", legacyBaseUrl);
        applyProviderBaseUrl(cfg, "anthropic", legacyBaseUrl);
        log.info("LLM config from DB: provider={}", llmProperties.getProvider());
    }

    private void applyProviderBaseUrl(Map<String, String> cfg, String p, String legacy) {
        String v = cfg.get("baseUrl." + p);
        if (v == null) v = legacy;
        if (v == null) return;
        if ("ollama".equals(p)) llmProperties.getOllama().setBaseUrl(v);
        else if ("openai".equals(p)) llmProperties.getOpenai().setBaseUrl(v);
        else if ("siliconflow".equals(p)) llmProperties.getSiliconflow().setBaseUrl(v);
        else if ("anthropic".equals(p)) llmProperties.getAnthropic().setBaseUrl(v);
    }

    public Map<String, Object> getConfig() {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("provider", llmProperties.getProvider());
        String key = llmProperties.getApiKey();
        if (key == null || key.isEmpty()) {
            if ("openai".equals(llmProperties.getProvider())) key = llmProperties.getOpenai().getApiKey();
            else if ("siliconflow".equals(llmProperties.getProvider())) key = llmProperties.getSiliconflow().getApiKey();
            else if ("anthropic".equals(llmProperties.getProvider())) key = llmProperties.getAnthropic().getApiKey();
        }
        cfg.put("apiKey", mask(key));
        cfg.put("availableProviders", Arrays.asList("ollama", "openai", "siliconflow", "anthropic"));
        Map<String, String> models = new LinkedHashMap<>();
        models.put("ollama", llmProperties.getOllama().getModel());
        models.put("openai", llmProperties.getOpenai().getModel());
        models.put("siliconflow", llmProperties.getSiliconflow().getModel());
        models.put("anthropic", llmProperties.getAnthropic().getModel());
        cfg.put("models", models);
        Map<String, String> baseUrls = new LinkedHashMap<>();
        baseUrls.put("ollama", llmProperties.getOllama().getBaseUrl());
        baseUrls.put("openai", llmProperties.getOpenai().getBaseUrl());
        baseUrls.put("siliconflow", llmProperties.getSiliconflow().getBaseUrl());
        baseUrls.put("anthropic", llmProperties.getAnthropic().getBaseUrl());
        cfg.put("baseUrls", baseUrls);
        return cfg;
    }

    public void updateConfig(String provider, String apiKey, String model, String baseUrl) {
        String p = (provider != null) ? provider : llmProperties.getProvider();
        if (provider != null) { llmProperties.setProvider(provider); db.setConfig("provider", provider); }
        if (apiKey != null && !apiKey.isEmpty()
            && !apiKey.startsWith("sk-***") && !apiKey.startsWith("ailab_***")) {
            // B1: 内存保留明文供 LlmAdapter 使用；落盘前加密
            llmProperties.setApiKey(apiKey);
            db.setConfig("apiKey", CryptoUtil.encrypt(apiKey));
        }
        if (baseUrl != null && !baseUrl.trim().isEmpty()) {
            // B2: 按 provider 持久化 baseUrl（明文落盘：URL 非密钥，与 apiKey 加密策略区分）
            if ("openai".equals(p)) llmProperties.getOpenai().setBaseUrl(baseUrl);
            else if ("siliconflow".equals(p)) llmProperties.getSiliconflow().setBaseUrl(baseUrl);
            else if ("anthropic".equals(p)) llmProperties.getAnthropic().setBaseUrl(baseUrl);
            else llmProperties.getOllama().setBaseUrl(baseUrl);
            db.setConfig("baseUrl." + p, baseUrl);
        }
        if (model != null) {
            db.setConfig("model", model);
            if ("openai".equals(p)) llmProperties.getOpenai().setModel(model);
            else if ("siliconflow".equals(p)) llmProperties.getSiliconflow().setModel(model);
            else if ("anthropic".equals(p)) llmProperties.getAnthropic().setModel(model);
            else llmProperties.getOllama().setModel(model);
        }
        log.info("LLM config updated: provider={} model={}", llmProperties.getProvider(), model);
    }

    private String mask(String key) {
        if (key == null || key.isEmpty()) return "";
        if (key.length() <= 8) return "***";
        return key.substring(0, 4) + "***" + key.substring(key.length() - 4);
    }
}
