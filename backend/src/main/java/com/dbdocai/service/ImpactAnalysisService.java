package com.dbdocai.service;

import com.dbdocai.dto.DataSourceConfigDTO;
import com.dbdocai.metadata.MetadataCollector;
import com.dbdocai.util.JdbcUrlValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.*;

@Service
public class ImpactAnalysisService {
    private static final Logger log = LoggerFactory.getLogger(ImpactAnalysisService.class);
    private final DocumentService documentService;
    private final DataSourceStoreService storeService;
    private final MetadataCollector collector = new MetadataCollector();

    public ImpactAnalysisService(DocumentService documentService, DataSourceStoreService storeService) {
        this.documentService = documentService;
        this.storeService = storeService;
    }

    public static class ImpactItem {
        public String type;       // TABLE / VIEW / FUNCTION / PROCEDURE
        public String name;
        public String detail;
        public String via;        // how connected
    }

    public static class ImpactReport {
        public String targetTable;
        public List<ImpactItem> dependents = new ArrayList<>();
        public List<ImpactItem> dependencies = new ArrayList<>();
        public int dependentCount;
        public int dependencyCount;
        public String riskLevel;
        public String summary;
    }

    public ImpactReport analyze(String dataSourceId, String schema, String tableName) {
        ImpactReport report = new ImpactReport();
        report.targetTable = tableName;
        report.dependents = new ArrayList<>();
        report.dependencies = new ArrayList<>();

        DataSourceConfigDTO ds = storeService.getWithSecret(dataSourceId);
        if (ds == null) return report;

        String effectiveSchema = schema != null ? schema : ds.getSchema();
        try (Connection conn = createConnection(ds)) {
            String catalog = getCatalog(conn, ds, effectiveSchema);
            DatabaseMetaData meta = conn.getMetaData();

            // Downstream: what I reference (imported keys)
            try (ResultSet rs = meta.getImportedKeys(catalog, effectiveSchema, tableName)) {
                while (rs.next()) {
                    ImpactItem item = new ImpactItem();
                    item.type = "TABLE"; item.name = rs.getString("PKTABLE_NAME");
                    item.via = "FK " + rs.getString("FK_NAME") + ": "
                        + rs.getString("FKCOLUMN_NAME") + " → " + rs.getString("PKCOLUMN_NAME");
                    item.detail = "DELETE_RULE=" + rs.getShort("DELETE_RULE") + " UPDATE_RULE=" + rs.getShort("UPDATE_RULE");
                    report.dependencies.add(item);
                }
            } catch (Exception e) { log.warn("Imported keys failed: {}", e.getMessage()); }

            // Upstream: who references me (exported keys)
            try (ResultSet rs = meta.getExportedKeys(catalog, effectiveSchema, tableName)) {
                while (rs.next()) {
                    ImpactItem item = new ImpactItem();
                    item.type = "TABLE"; item.name = rs.getString("FKTABLE_NAME");
                    item.via = "FK " + rs.getString("FK_NAME") + ": "
                        + rs.getString("FKCOLUMN_NAME") + " → " + rs.getString("PKCOLUMN_NAME");
                    item.detail = "DELETE_RULE=" + rs.getShort("DELETE_RULE") + " UPDATE_RULE=" + rs.getShort("UPDATE_RULE");
                    report.dependents.add(item);
                }
            } catch (Exception e) { log.warn("Exported keys failed: {}", e.getMessage()); }

            // View & routine references
            List<MetadataCollector.RoutineObject> routines = collector.getRoutines(conn, catalog, effectiveSchema);
            String lower = tableName.toLowerCase();
            for (MetadataCollector.RoutineObject r : routines) {
                if (r.definition != null && r.definition.toLowerCase().contains(lower)) {
                    ImpactItem item = new ImpactItem();
                    item.type = r.type; item.name = r.name;
                    item.via = r.type + " DDL 中引用表名 " + tableName;
                    item.detail = extractSnippet(r.definition, tableName);
                    report.dependents.add(item);
                }
            }

        } catch (Exception e) {
            log.warn("Impact analysis failed: {}", e.getMessage());
        }

        report.dependentCount = report.dependents.size();
        report.dependencyCount = report.dependencies.size();

        // Risk assessment
        int fkCount = (int) report.dependents.stream().filter(i -> "TABLE".equals(i.type)).count();
        int routineCount = (int) report.dependents.stream().filter(i -> "PROCEDURE".equals(i.type) || "FUNCTION".equals(i.type)).count();
        if (fkCount > 3 || routineCount > 0) { report.riskLevel = "high"; report.summary = "高风险: 被 " + fkCount + " 张表和 " + routineCount + " 个存储过程/函数引用"; }
        else if (report.dependentCount > 0) { report.riskLevel = "medium"; report.summary = "中风险: 被 " + report.dependentCount + " 个对象引用"; }
        else { report.riskLevel = "low"; report.summary = "低风险: 无引用依赖"; }

        return report;
    }

    private String extractSnippet(String ddl, String tableName) {
        if (ddl == null) return "";
        int idx = ddl.toLowerCase().indexOf(tableName.toLowerCase());
        if (idx < 0) return ddl.substring(0, Math.min(200, ddl.length()));
        int start = Math.max(0, idx - 50);
        int end = Math.min(ddl.length(), idx + tableName.length() + 50);
        return ddl.substring(start, end);
    }

    /**
     * 建立数据库连接（重连点）。P0-1 双保险：重连前再次校验并净化 JDBC URL，
     * 即使 URL 来自已净化的存储，也阻断历史脏数据/恶意 URL 触达驱动。
     */
    private Connection createConnection(DataSourceConfigDTO ds) throws SQLException {
        String url = JdbcUrlValidator.validate(ds.getUrl());
        String password = ds.getPassword() != null ? ds.getPassword() : "";
        return DriverManager.getConnection(url, ds.getUsername(), password);
    }

    private String getCatalog(Connection conn, DataSourceConfigDTO ds, String effectiveSchema) throws SQLException {
        if ("postgresql".equalsIgnoreCase(ds.getDbType())) return null;
        if (effectiveSchema != null && !effectiveSchema.isEmpty()) return null;
        return conn.getCatalog();
    }
}
