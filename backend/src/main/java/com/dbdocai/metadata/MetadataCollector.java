package com.dbdocai.metadata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;

public class MetadataCollector {
    private static final Logger log = LoggerFactory.getLogger(MetadataCollector.class);

    public static class ColumnInfo {
        public String name;
        public String dataType;
        public int jdbcType;
        public int columnSize;
        public Integer decimalDigits;
        public boolean nullable;
        public String defaultValue;
        public String comment;
        public int ordinalPosition;
        public boolean autoIncrement;
        public boolean primaryKey;
        public List<String> enumValues;
        public List<Map<String, String>> sampleRows;

        public Map<String, String> toMap() {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("dataType", dataType);
            m.put("jdbcType", String.valueOf(jdbcType));
            m.put("columnSize", String.valueOf(columnSize));
            if (decimalDigits != null) m.put("decimalDigits", String.valueOf(decimalDigits));
            m.put("nullable", String.valueOf(nullable));
            m.put("defaultValue", defaultValue != null ? defaultValue : "");
            m.put("comment", comment != null ? comment : "");
            m.put("ordinalPosition", String.valueOf(ordinalPosition));
            m.put("autoIncrement", String.valueOf(autoIncrement));
            return m;
        }
    }

    public static class RoutineObject {
        public String name;
        public String type;           // PROCEDURE / FUNCTION / VIEW
        public String schema;
        public String definition;     // full DDL
        public List<Map<String, String>> params = new ArrayList<>();
        public String returnType;
        public String comment;
        public String aiSummary;

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("type", type);
            m.put("schema", schema);
            m.put("definition", definition != null ? definition : "");
            m.put("params", params);
            if (returnType != null) m.put("returnType", returnType);
            m.put("comment", comment != null ? comment : "");
            if (aiSummary != null) m.put("aiSummary", aiSummary);
            return m;
        }
    }

    // ── Routine collection (P1-2) ─────────────────────

    public List<RoutineObject> getRoutines(Connection conn, String catalog, String schema) throws SQLException {
        List<RoutineObject> list = new ArrayList<>();
        DatabaseMetaData meta = conn.getMetaData();
        String dbProduct = meta.getDatabaseProductName();

        // Views via getTables
        try (ResultSet rs = meta.getTables(catalog, schema, "%", new String[]{"VIEW"})) {
            while (rs.next()) {
                RoutineObject r = new RoutineObject();
                r.name = rs.getString("TABLE_NAME");
                r.type = "VIEW";
                r.schema = rs.getString("TABLE_SCHEM");
                r.comment = rs.getString("REMARKS");
                list.add(r);
            }
        }

        // Procedures via getProcedures
        try (ResultSet rs = meta.getProcedures(catalog, schema, "%")) {
            while (rs.next()) {
                RoutineObject r = new RoutineObject();
                r.name = rs.getString("PROCEDURE_NAME");
                r.schema = rs.getString("PROCEDURE_SCHEM");
                r.comment = rs.getString("REMARKS");

                String procType = "PROCEDURE";
                try {
                    short pt = rs.getShort("PROCEDURE_TYPE");
                    if (pt == DatabaseMetaData.procedureReturnsResult) procType = "PROCEDURE";
                    else procType = "FUNCTION";
                } catch (Exception ignored) {}
                r.type = procType;
                list.add(r);
            }
        }

        // Enrich DDL and params for each
        for (RoutineObject r : list) {
            enrichRoutineDetail(conn, r, dbProduct, catalog, schema);
        }

        return list;
    }

    private void enrichRoutineDetail(Connection conn, RoutineObject r, String dbProduct,
                                      String catalog, String schema) {
        try {
            if ("VIEW".equals(r.type)) {
                try (Statement stmt = conn.createStatement()) {
                    String sql;
                    if (isPostgreSQL(dbProduct)) {
                        sql = "SELECT definition FROM pg_views WHERE schemaname=" + quoteIdent(schema != null ? schema : "public") + " AND viewname=" + quoteIdent(r.name);
                    } else {
                        sql = "SELECT VIEW_DEFINITION as definition FROM information_schema.VIEWS WHERE TABLE_SCHEMA=" + quoteString(schema != null ? schema : catalog) + " AND TABLE_NAME=" + quoteString(r.name);
                    }
                    try (ResultSet rs = stmt.executeQuery(sql)) {
                        if (rs.next()) r.definition = rs.getString("definition");
                    }
                } catch (Exception e) { log.debug("View DDL failed for {}: {}", r.name, e.getMessage()); }
            } else {
                // PROCEDURE / FUNCTION: get params via getProcedureColumns
                try {
                    DatabaseMetaData meta = conn.getMetaData();
                    try (ResultSet rs = meta.getProcedureColumns(catalog, schema, r.name, "%")) {
                        while (rs.next()) {
                            Map<String, String> p = new LinkedHashMap<>();
                            p.put("name", rs.getString("COLUMN_NAME"));
                            p.put("dataType", rs.getString("TYPE_NAME"));
                            short colType = rs.getShort("COLUMN_TYPE");
                            String mode = colType == DatabaseMetaData.procedureColumnIn ? "IN"
                                : colType == DatabaseMetaData.procedureColumnOut ? "OUT" : "INOUT";
                            p.put("mode", mode);
                            p.put("ordinalPosition", String.valueOf(rs.getShort("ORDINAL_POSITION")));

                            // Check for return type (column name is null for return in some DBs)
                            if (rs.getString("COLUMN_NAME") == null && colType == DatabaseMetaData.procedureColumnReturn) {
                                r.returnType = rs.getString("TYPE_NAME");
                            } else if (p.get("name") != null) {
                                r.params.add(p);
                            }
                        }
                    }
                } catch (Exception e) { log.debug("Procedure params failed for {}: {}", r.name, e.getMessage()); }

                // Get DDL: database-specific
                try (Statement stmt = conn.createStatement()) {
                    String sql = null;
                    if (isPostgreSQL(dbProduct)) {
                        sql = "SELECT pg_get_functiondef(p.oid) as ddl FROM pg_proc p JOIN pg_namespace n ON p.pronamespace=n.oid WHERE p.proname=" + quoteString(r.name) + " AND n.nspname=" + quoteString(schema != null ? schema : "public");
                    } else if (dbProduct != null && dbProduct.toLowerCase().contains("oracle")) {
                        sql = "SELECT DBMS_METADATA.GET_DDL(" + quoteString(r.type) + "," + quoteString(r.name) + "," + quoteString(schema) + ") AS ddl FROM DUAL";
                    } else if (dbProduct != null && dbProduct.toLowerCase().contains("mysql")) {
                        sql = "SHOW CREATE " + r.type + " " + quoteIdent(r.name);
                    }
                    if (sql != null) {
                        try (ResultSet rs = stmt.executeQuery(sql)) {
                            if (rs.next()) {
                                String ddl = rs.getString("ddl");
                                if (ddl == null) ddl = rs.getString("Create " + r.type.substring(0, 1).toUpperCase() + r.type.substring(1).toLowerCase());
                                r.definition = ddl;
                            }
                        }
                    }
                } catch (Exception e) { log.debug("Routine DDL failed for {}: {}", r.name, e.getMessage()); }
            }
        } catch (Exception e) {
            log.warn("Failed to enrich routine {}: {}", r.name, e.getMessage());
        }
    }

    public static class TableInfo {
        public String name;
        public String comment;
        public String schema;
        public String engine;
        public String dbProduct;
        public List<ColumnInfo> columns = new ArrayList<>();
        public List<Map<String, String>> primaryKeys = new ArrayList<>();
        public List<Map<String, String>> indexes = new ArrayList<>();
        public List<Map<String, String>> foreignKeys = new ArrayList<>();
    }

    public List<String> getTableNames(Connection conn, String catalog, String schemaPattern) throws SQLException {
        List<String> names = new ArrayList<>();
        DatabaseMetaData meta = conn.getMetaData();
        try (ResultSet rs = meta.getTables(catalog, schemaPattern, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                names.add(rs.getString("TABLE_NAME"));
            }
        }
        return names;
    }

    public TableInfo collectTable(Connection conn, String catalog, String schema, String tableName,
                                   boolean includeSample) throws SQLException {
        TableInfo info = new TableInfo();
        DatabaseMetaData meta = conn.getMetaData();
        info.dbProduct = meta.getDatabaseProductName();

        try (ResultSet tableRs = meta.getTables(catalog, schema, tableName, null)) {
            if (tableRs.next()) {
                info.name = tableRs.getString("TABLE_NAME");
                info.comment = tableRs.getString("REMARKS");
                info.schema = tableRs.getString("TABLE_SCHEM");
            }
        }

        try (PreparedStatement pstmt = conn.prepareStatement(
                 "SELECT ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?")) {
             pstmt.setString(1, schema != null ? schema : catalog);
             pstmt.setString(2, tableName);
             try (ResultSet engineRs = pstmt.executeQuery()) {
            if (engineRs.next()) {
                info.engine = engineRs.getString("ENGINE");
            }
            }
        } catch (Exception e) {
            log.debug("Engine info not available for {}: {}", tableName, e.getMessage());
        }

        try (ResultSet colRs = meta.getColumns(catalog, schema, tableName, "%")) {
            while (colRs.next()) {
                ColumnInfo ci = new ColumnInfo();
                ci.name = colRs.getString("COLUMN_NAME");
                ci.dataType = colRs.getString("TYPE_NAME");
                ci.jdbcType = colRs.getInt("DATA_TYPE");
                ci.columnSize = colRs.getInt("COLUMN_SIZE");
                ci.decimalDigits = colRs.getInt("DECIMAL_DIGITS");
                if (colRs.wasNull()) ci.decimalDigits = null;
                ci.nullable = colRs.getInt("NULLABLE") == DatabaseMetaData.columnNullable;
                ci.defaultValue = colRs.getString("COLUMN_DEF");
                ci.comment = colRs.getString("REMARKS");
                ci.ordinalPosition = colRs.getInt("ORDINAL_POSITION");
                ci.autoIncrement = "YES".equalsIgnoreCase(colRs.getString("IS_AUTOINCREMENT"));
                info.columns.add(ci);
            }
        }

        // PostgreSQL: JDBC does NOT return column comments via getColumns,
        // must query pg_catalog directly
        if (isPostgreSQL(info.dbProduct)) {
            enrichPgComments(conn, schema, tableName, info.columns);
        }

        try (ResultSet pkRs = meta.getPrimaryKeys(catalog, schema, tableName)) {
            while (pkRs.next()) {
                Map<String, String> pk = new LinkedHashMap<>();
                pk.put("columnName", pkRs.getString("COLUMN_NAME"));
                pk.put("keySeq", String.valueOf(pkRs.getShort("KEY_SEQ")));
                info.primaryKeys.add(pk);
                for (ColumnInfo ci : info.columns) {
                    if (ci.name.equals(pkRs.getString("COLUMN_NAME"))) {
                        ci.primaryKey = true;
                    }
                }
            }
        }

        try (ResultSet idxRs = meta.getIndexInfo(catalog, schema, tableName, false, true)) {
            while (idxRs.next()) {
                short type = idxRs.getShort("TYPE");
                if (type == DatabaseMetaData.tableIndexStatistic) continue;
                Map<String, String> idx = new LinkedHashMap<>();
                idx.put("name", idxRs.getString("INDEX_NAME"));
                idx.put("unique", String.valueOf(!idxRs.getBoolean("NON_UNIQUE")));
                idx.put("columnName", idxRs.getString("COLUMN_NAME"));
                idx.put("ordinalPosition", String.valueOf(idxRs.getShort("ORDINAL_POSITION")));
                info.indexes.add(idx);
            }
        } catch (Exception e) {
            log.warn("Failed to get indexes for {}: {}", tableName, e.getMessage());
        }

        try (ResultSet fkRs = meta.getImportedKeys(catalog, schema, tableName)) {
            while (fkRs.next()) {
                Map<String, String> fk = new LinkedHashMap<>();
                fk.put("fkName", fkRs.getString("FK_NAME"));
                fk.put("pkTable", fkRs.getString("PKTABLE_NAME"));
                fk.put("pkColumn", fkRs.getString("PKCOLUMN_NAME"));
                fk.put("fkColumn", fkRs.getString("FKCOLUMN_NAME"));
                info.foreignKeys.add(fk);
            }
        } catch (Exception e) {
            log.warn("Failed to get foreign keys for {}: {}", tableName, e.getMessage());
        }

        if (includeSample) {
            sampleAndDetectEnums(conn, tableName, info);
        }

        return info;
    }

    private void sampleAndDetectEnums(Connection conn, String tableName, TableInfo info) {
        try (Statement stmt = conn.createStatement()) {
            stmt.setMaxRows(5);
            String quoted = isPostgreSQL(info.dbProduct) ? quotePgName(tableName) : "`" + tableName.replace("`", "``") + "`";
            try (ResultSet rs = stmt.executeQuery("SELECT * FROM " + quoted)) {
                ResultSetMetaData rsmd = rs.getMetaData();
                int colCount = rsmd.getColumnCount();
                Map<String, Set<String>> distinctValues = new LinkedHashMap<>();
                List<Map<String, String>> rows = new ArrayList<>();

                while (rs.next()) {
                    Map<String, String> row = new LinkedHashMap<>();
                    for (int i = 1; i <= colCount; i++) {
                        String colName = rsmd.getColumnName(i);
                        String val = rs.getString(i);
                        if (val != null) {
                            distinctValues.computeIfAbsent(colName, k -> new LinkedHashSet<>()).add(val);
                        }
                        row.put(colName, val != null ? val : "");
                    }
                    rows.add(row);
                }

                // 枚举检测：distinct values <= 15 且每个值长度 < 60
                for (ColumnInfo ci : info.columns) {
                    Set<String> vals = distinctValues.get(ci.name);
                    if (vals != null && vals.size() <= 15 && vals.size() > 0
                            && vals.size() < rows.size()
                            && vals.stream().allMatch(v -> v.length() < 60)) {
                        ci.enumValues = new ArrayList<>(vals);
                    }

                    // Sample rows for this column
                    ci.sampleRows = new ArrayList<>();
                    for (Map<String, String> row : rows) {
                        Map<String, String> m = new LinkedHashMap<>();
                        m.put(ci.name, row.getOrDefault(ci.name, ""));
                        ci.sampleRows.add(m);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to sample data for {}: {}", tableName, e.getMessage());
        }
    }

    private void enrichPgComments(Connection conn, String schema, String tableName,
                                   List<ColumnInfo> columns) {
        String sql = "SELECT a.attname, col_description(a.attrelid, a.attnum) AS comment "
                   + "FROM pg_class c "
                   + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                   + "JOIN pg_attribute a ON a.attrelid = c.oid "
                   + "WHERE c.relname = ? "
                   + (schema != null ? "AND n.nspname = ? " : "")
                   + "AND a.attnum > 0 AND NOT a.attisdropped";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tableName);
            if (schema != null) ps.setString(2, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String colName = rs.getString("attname");
                    String pgComment = rs.getString("comment");
                    if (pgComment != null && !pgComment.isEmpty()) {
                        for (ColumnInfo ci : columns) {
                            if (ci.name.equals(colName)) {
                                ci.comment = pgComment;
                                break;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to enrich PG comments for {}: {}", tableName, e.getMessage());
        }
    }

    public List<Map<String, String>> sampleData(Connection conn, String tableName, int limit) throws SQLException {
        List<Map<String, String>> rows = new ArrayList<>();
        try (Statement stmt = conn.createStatement()) {
            stmt.setMaxRows(limit);
            try (ResultSet rs = stmt.executeQuery("SELECT * FROM " + quotePgName(tableName))) {
                ResultSetMetaData rsmd = rs.getMetaData();
                int colCount = rsmd.getColumnCount();
                while (rs.next()) {
                    Map<String, String> row = new LinkedHashMap<>();
                    for (int i = 1; i <= colCount; i++) {
                        String val = rs.getString(i);
                        row.put(rsmd.getColumnName(i), val != null ? val : "");
                    }
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    private boolean isPostgreSQL(String dbProduct) {
        return dbProduct != null && dbProduct.toLowerCase().contains("postgresql");
    }

    private String escapeSql(String val) {
        if (val == null) return "";
        return val.replace("'", "''");
    }

    private String quoteString(String val) {
        if (val == null) return "''";
        return "'" + val.replace("'", "''") + "'";
    }

    private String quoteIdent(String val) {
        if (val == null) return "\"\"";
        return "\"" + val.replace("\"", "\"\"") + "\"";
    }

    private String quotePgName(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }
}
