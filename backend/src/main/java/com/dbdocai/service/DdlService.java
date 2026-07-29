package com.dbdocai.service;

import com.dbdocai.dto.DataSourceConfigDTO;
import com.dbdocai.metadata.MetadataCollector;
import com.dbdocai.util.JdbcUrlValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class DdlService {
    private static final Logger log = LoggerFactory.getLogger(DdlService.class);
    private final DataSourceStoreService storeService;
    private final MetadataCollector collector = new MetadataCollector();

    public DdlService(DataSourceStoreService storeService) {
        this.storeService = storeService;
    }

    public String generateTableDdl(String dataSourceId, String schema, String tableName) {
        DataSourceConfigDTO ds = storeService.getWithSecret(dataSourceId);
        if (ds == null) throw new IllegalArgumentException("数据源不存在");

        String effectiveSchema = schema != null ? schema : ds.getSchema();
        try (Connection conn = createConnection(ds)) {
            String catalog = getCatalog(conn, ds, effectiveSchema);
            MetadataCollector.TableInfo table = collector.collectTable(conn, catalog, effectiveSchema, tableName, false);
            return buildCreateTable(table, ds.getDbType());
        } catch (SQLException e) {
            throw new RuntimeException("DDL生成失败: " + e.getMessage(), e);
        }
    }

    public String generateBatchDdl(String dataSourceId, String schema, List<String> tableNames) {
        StringBuilder all = new StringBuilder();
        all.append("-- DBDoc AI DDL Export\n");
        all.append("-- DataSource: ").append(dataSourceId).append("\n");
        all.append("-- Schema: ").append(schema != null ? schema : "default").append("\n");
        all.append("-- Tables: ").append(String.join(", ", tableNames)).append("\n\n");
        for (String tn : tableNames) {
            all.append(generateTableDdl(dataSourceId, schema, tn)).append("\n\n");
        }
        return all.toString();
    }

    private String buildCreateTable(MetadataCollector.TableInfo table, String dbType) {
        StringBuilder sb = new StringBuilder();

        String quote = "mysql".equals(dbType) ? "`" : "\"";
        String qName = quote + table.name + quote;

        sb.append("CREATE TABLE ").append(qName).append(" (\n");

        List<String> colDefs = new ArrayList<>();
        List<String> pkCols = new ArrayList<>();
        for (MetadataCollector.ColumnInfo col : table.columns) {
            colDefs.add("  " + buildColumnDef(col, dbType, quote));
            if (col.primaryKey) pkCols.add(col.name);
        }
        sb.append(String.join(",\n", colDefs));

        if (!pkCols.isEmpty()) {
            sb.append(",\n  PRIMARY KEY (")
                .append(pkCols.stream().map(n -> quote + n + quote).collect(Collectors.joining(", ")))
                .append(")");
        }

        sb.append("\n)");

        if ("mysql".equals(dbType) && table.engine != null) {
            sb.append(" ENGINE=").append(table.engine).append(" DEFAULT CHARSET=utf8mb4");
        }
        sb.append(";\n");

        // Table comment
        if (table.comment != null && !table.comment.isEmpty()) {
            sb.append("COMMENT ON TABLE ").append(qName).append(" IS '")
                .append(table.comment.replace("'", "''")).append("';\n");
        }

        // Column comments
        for (MetadataCollector.ColumnInfo col : table.columns) {
            if (col.comment != null && !col.comment.isEmpty()) {
                sb.append("COMMENT ON COLUMN ").append(qName).append(".")
                    .append(quote).append(col.name).append(quote).append(" IS '")
                    .append(col.comment.replace("'", "''")).append("';\n");
            }
        }

        // Indexes
        Map<String, List<String>> idxMap = new LinkedHashMap<>();
        Map<String, Boolean> idxUnique = new LinkedHashMap<>();
        for (Map<String, String> idx : table.indexes) {
            String name = idx.get("name");
            idxMap.computeIfAbsent(name, k -> new ArrayList<>()).add(idx.get("columnName"));
            idxUnique.putIfAbsent(name, "true".equals(idx.get("unique")));
        }
        for (Map.Entry<String, List<String>> entry : idxMap.entrySet()) {
            String name = entry.getKey();
            if (isPgAutoIndex(name, table.name, pkCols)) continue; // skip auto PK indexes
            String cols = entry.getValue().stream().map(n -> quote + n + quote).collect(Collectors.joining(", "));
            boolean unique = Boolean.TRUE.equals(idxUnique.get(name));
            sb.append("CREATE ").append(unique ? "UNIQUE " : "").append("INDEX ")
                .append(quote).append(name).append(quote)
                .append(" ON ").append(qName).append(" (").append(cols).append(");\n");
        }

        // Foreign keys
        for (Map<String, String> fk : table.foreignKeys) {
            sb.append("ALTER TABLE ").append(qName)
                .append(" ADD CONSTRAINT ").append(quote).append(fk.get("fkName")).append(quote)
                .append(" FOREIGN KEY (").append(quote).append(fk.get("fkColumn")).append(quote).append(")")
                .append(" REFERENCES ").append(quote).append(fk.get("pkTable")).append(quote)
                .append("(").append(quote).append(fk.get("pkColumn")).append(quote).append(");\n");
        }

        return sb.toString();
    }

    private boolean isPgAutoIndex(String idxName, String tableName, List<String> pkCols) {
        return idxName != null && (idxName.startsWith(tableName + "_pkey")
            || idxName.endsWith("_pkey")
            || (idxName.contains("_pk") && pkCols.size() >= 1));
    }

    private String buildColumnDef(MetadataCollector.ColumnInfo col, String dbType, String quote) {
        StringBuilder def = new StringBuilder();
        def.append(quote).append(col.name).append(quote).append(" ").append(col.dataType);

        if (col.columnSize > 0 && needsSize(col.dataType)) {
            def.append("(").append(col.columnSize);
            if (col.decimalDigits != null) def.append(",").append(col.decimalDigits);
            def.append(")");
        }

        if (!col.nullable) def.append(" NOT NULL");
        if (col.defaultValue != null && !col.defaultValue.isEmpty()) {
            def.append(" DEFAULT ").append(col.defaultValue);
        }
        if (col.autoIncrement && "mysql".equals(dbType)) {
            def.append(" AUTO_INCREMENT");
        }
        return def.toString();
    }

    private boolean needsSize(String dataType) {
        if (dataType == null) return false;
        String t = dataType.toLowerCase();
        return t.contains("char") || t.contains("binary") || t.contains("numeric")
            || t.contains("decimal") || t.contains("number");
    }

    /**
     * 建立数据库连接（重连点）。P0-1 双保险：重连前再次校验并净化 JDBC URL。
     */
    Connection createConnection(DataSourceConfigDTO ds) throws SQLException {
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
