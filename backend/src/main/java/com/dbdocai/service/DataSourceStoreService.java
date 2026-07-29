package com.dbdocai.service;

import com.dbdocai.dto.DataSourceConfigDTO;
import com.dbdocai.util.CryptoUtil;
import com.dbdocai.util.JdbcUrlValidator;

import java.util.*;

import org.springframework.stereotype.Service;

@Service
public class DataSourceStoreService {
    private final DbStore db;

    public DataSourceStoreService(DbStore db) { this.db = db; }

    public DataSourceConfigDTO add(DataSourceConfigDTO dto) {
        if (dto.getId() == null || dto.getId().isEmpty()) {
            dto.setId(UUID.randomUUID().toString().replace("-", ""));
        }
        // P0-1（保存侧，双保险之一）：写库前严格校验并净化 JDBC URL，阻断恶意 URL 落盘。
        // 校验失败（如协议不在白名单）直接抛 IllegalArgumentException 拦截保存。
        String safeUrl = JdbcUrlValidator.validate(dto.getUrl());
        db.saveDataSource(dto.getId(), dto.getName(), dto.getDbType(), safeUrl,
            dto.getUsername(), dto.getPassword());
        if (dto.getSchema() != null && !dto.getSchema().isEmpty()) {
            db.addDataSourceSchema(dto.getId(), dto.getSchema());
        }
        return dto;
    }

    public void addSchema(String dsId, String schema) { db.addDataSourceSchema(dsId, schema); }
    public void clearAndAddSchemas(String dsId, List<String> schemas) { db.clearAndAddSchemas(dsId, schemas); }
    public void removeSchema(String dsId, String schema) { db.removeDataSourceSchema(dsId, schema); }
    public List<String> getSchemas(String dsId) { return db.getDataSourceSchemas(dsId); }

    public boolean remove(String id) { db.deleteDataSource(id); return true; }

    /**
     * 对外接口：返回脱敏后的数据源（password 永远为 "***"）。
     * 遵守 CLAUDE.md #3，不向前端/日志泄露凭据。
     */
    public DataSourceConfigDTO get(String id) {
        Map<String, String> m = db.getDataSource(id);
        return m != null ? mapToDto(m, true) : null;
    }

    /**
     * 内部服务专用：返回含真实（解密后）密码的数据源 DTO，供重连数据库使用。
     * 绝不可直接返回给 HTTP 层。
     */
    public DataSourceConfigDTO getWithSecret(String id) {
        Map<String, String> m = db.getDataSource(id);
        return m != null ? mapToDto(m, false) : null;
    }

    public List<DataSourceConfigDTO> list() {
        List<DataSourceConfigDTO> list = new ArrayList<>();
        for (Map<String, String> m : db.listDataSources()) list.add(mapToDto(m, true));
        return list;
    }

    /**
     * @param maskPassword true=对外脱敏（"***"）；false=返回真实密码（内部重连用）
     */
    private DataSourceConfigDTO mapToDto(Map<String, String> m, boolean maskPassword) {
        DataSourceConfigDTO dto = new DataSourceConfigDTO();
        dto.setId(m.get("id")); dto.setName(m.get("name")); dto.setDbType(m.get("dbType"));
        dto.setUrl(m.get("url")); dto.setUsername(m.get("username"));
        // 存储层读出的 password 已是解密后的真实明文；对外必须脱敏。
        dto.setPassword(maskPassword ? "***" : m.get("password"));
        dto.setSchema(m.get("schemas"));
        return dto;
    }
}
