package com.dbdocai.controller;

import com.dbdocai.dto.DataSourceConfigDTO;
import com.dbdocai.service.DataSourceStoreService;
import com.dbdocai.util.JdbcUrlValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.sql.*;
import java.util.*;

@RestController
@RequestMapping("/api/datasource")
public class DataSourceController {

    private static final Logger log = LoggerFactory.getLogger(DataSourceController.class);

    private final DataSourceStoreService storeService;

    public DataSourceController(DataSourceStoreService storeService) {
        this.storeService = storeService;
    }

    @PostMapping("/test")
    public ResponseEntity<Map<String, Object>> testConnection(@RequestBody DataSourceConfigDTO dto) {
        Map<String, Object> result = new HashMap<>();
        try {
            // B4: 严格校验并净化 JDBC URL，缓解潜在 RCE
            String url = JdbcUrlValidator.validate(dto.getUrl());
            if ("mysql".equalsIgnoreCase(dto.getDbType()) && !url.contains("useInformationSchema")) {
                url = url + (url.contains("?") ? "&" : "?") + "useInformationSchema=true";
            }
            String password = dto.getPassword() != null ? dto.getPassword() : "";
            // P2-8: Connection 纳入 try-with-resources，避免连接泄漏
            // P2-10: 不回显原始 DB 异常，避免泄露内部凭证/拓扑信息（统一由 GlobalExceptionHandler 脱敏）
            try (Connection conn = DriverManager.getConnection(url, dto.getUsername(), password)) {
                // 连接建立即视为成功
            }
            result.put("success", true);
            result.put("message", "连接成功");
        } catch (Exception e) {
            log.warn("数据源连接测试失败: {}", e.getMessage());
            result.put("success", false);
            result.put("message", "连接失败，请检查数据库地址、账号或网络配置");
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping("/save")
    public ResponseEntity<Map<String, Object>> save(@RequestBody DataSourceConfigDTO dto) {
        dto = storeService.add(dto);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("id", dto.getId());
        return ResponseEntity.ok(result);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String id) {
        boolean removed = storeService.remove(id);
        Map<String, Object> result = new HashMap<>();
        result.put("success", removed);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/debug/{id}")
    public ResponseEntity<Map<String, Object>> debug(@PathVariable String id) {
        DataSourceConfigDTO ds = storeService.get(id);
        Map<String, Object> result = new HashMap<>();
        result.put("found", ds != null);
        if (ds != null) {
            result.put("id", ds.getId());
            result.put("name", ds.getName());
            result.put("dbType", ds.getDbType());
            result.put("url", ds.getUrl());
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> list() {
        List<DataSourceConfigDTO> sources = storeService.list();
        // Remove passwords from response (defensive copy to avoid mutating store)
        List<Map<String, Object>> safeList = new ArrayList<>();
        for (DataSourceConfigDTO s : sources) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", s.getId());
            item.put("name", s.getName());
            item.put("dbType", s.getDbType());
            item.put("url", s.getUrl());
            item.put("username", s.getUsername());
            item.put("schema", s.getSchema());
            safeList.add(item);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("sources", safeList);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/{id}/fetch-schemas")
    public ResponseEntity<Map<String, Object>> fetchSchemas(@PathVariable String id) {
        // P0-1: 使用含真实密码的 DTO 重连，避免脱敏值 "***" 导致认证失败
        DataSourceConfigDTO ds = storeService.getWithSecret(id);
        Map<String, Object> result = new HashMap<>();
        if (ds == null) { result.put("success", false); result.put("message", "数据源不存在"); return ResponseEntity.ok(result); }
        try {
            String password = ds.getPassword() != null ? ds.getPassword() : "";
            List<String> schemas = new ArrayList<>();
            // B4: 校验存储的 JDBC URL
            try (Connection conn = DriverManager.getConnection(JdbcUrlValidator.validate(ds.getUrl()), ds.getUsername(), password)) {
                DatabaseMetaData meta = conn.getMetaData();
                // P1-4: ResultSet 纳入 try-with-resources，异常时自动关闭，避免资源泄漏
                try (ResultSet rs = meta.getSchemas()) {
                    while (rs.next()) {
                        String s = rs.getString("TABLE_SCHEM");
                        if (s != null && !s.startsWith("pg_") && !s.equals("information_schema")) {
                            schemas.add(s);
                        }
                    }
                }
            }
            // Clear old and save new schemas
            storeService.clearAndAddSchemas(id, schemas);
            result.put("success", true);
            result.put("schemas", schemas);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "获取Schema失败: " + e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping("/schemas")
    public ResponseEntity<Map<String, Object>> getSchemas(@RequestBody DataSourceConfigDTO dto) {
        Map<String, Object> result = new HashMap<>();
        try {
            // B4: 严格校验并净化 JDBC URL
            String url = JdbcUrlValidator.validate(dto.getUrl());
            String password = dto.getPassword() != null ? dto.getPassword() : "";
            List<String> schemas = new ArrayList<>();
            try (Connection conn = DriverManager.getConnection(url, dto.getUsername(), password)) {
                DatabaseMetaData meta = conn.getMetaData();
                // P1-4: ResultSet 纳入 try-with-resources，异常时自动关闭，避免资源泄漏
                try (ResultSet rs = meta.getSchemas()) {
                    while (rs.next()) {
                        String s = rs.getString("TABLE_SCHEM");
                        if (s != null && !s.startsWith("pg_") && !s.equals("information_schema")) {
                            schemas.add(s);
                        }
                    }
                }
            }
            result.put("success", true);
            result.put("schemas", schemas);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "获取Schema失败: " + e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping("/{id}/schemas")
    public ResponseEntity<Map<String, Object>> manageSchema(@PathVariable String id, @RequestBody Map<String, String> body) {
        String action = body.get("action"); // "add" or "remove"
        String schema = body.get("schema");
        if ("add".equals(action)) storeService.addSchema(id, schema);
        else if ("remove".equals(action)) storeService.removeSchema(id, schema);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("schemas", storeService.getSchemas(id));
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}/schemas")
    public ResponseEntity<Map<String, Object>> listSchemas(@PathVariable String id) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("schemas", storeService.getSchemas(id));
        return ResponseEntity.ok(result);
    }
}
