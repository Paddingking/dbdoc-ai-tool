package com.dbdocai.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 简易健康检查探针（P2-11）。
 *
 * <p>提供 {@code GET /api/ping} 作为可探针（liveness/readiness），供容器或本地
 * 进程管理器探活。该端点已在 {@link com.dbdocai.config.WebConfig} 中排除鉴权拦截，
 * 以便无需令牌即可探活。
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    @GetMapping("/ping")
    public ResponseEntity<Map<String, Object>> ping() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("message", "pong");
        return ResponseEntity.ok(body);
    }
}
