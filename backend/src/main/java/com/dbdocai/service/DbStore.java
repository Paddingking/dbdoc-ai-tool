package com.dbdocai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.dbdocai.util.CryptoUtil;

import javax.annotation.PostConstruct;
import java.sql.*;
import java.util.*;

@Service
public class DbStore {
    private static final Logger log = LoggerFactory.getLogger(DbStore.class);

    @PostConstruct
    public void init() {
        try (Connection c = getConn(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS datasources (" +
                "id TEXT PRIMARY KEY, name TEXT, db_type TEXT, url TEXT, " +
                "username TEXT, password TEXT)");
            s.execute("CREATE TABLE IF NOT EXISTS datasource_schemas (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, ds_id TEXT, schema_name TEXT, " +
                "UNIQUE(ds_id, schema_name))");
            s.execute("CREATE TABLE IF NOT EXISTS llm_config (" +
                "key TEXT PRIMARY KEY, value TEXT)");
            s.execute("CREATE TABLE IF NOT EXISTS ai_infer (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, data_source_id TEXT, " +
                "table_name TEXT, column_name TEXT, description TEXT, " +
                "confidence REAL, status TEXT DEFAULT 'pending', " +
                "created_at TEXT, UNIQUE(data_source_id, table_name, column_name))");
            s.execute("CREATE TABLE IF NOT EXISTS schema_snapshots (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, data_source_id TEXT NOT NULL, " +
                "schema TEXT NOT NULL, table_count INTEGER NOT NULL, " +
                "created_at TEXT DEFAULT (datetime('now')))");
            s.execute("CREATE TABLE IF NOT EXISTS schema_changes (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, snapshot_id INTEGER NOT NULL, " +
                "change_type TEXT NOT NULL, table_name TEXT NOT NULL, " +
                "column_name TEXT, detail TEXT NOT NULL, description TEXT, " +
                "FOREIGN KEY (snapshot_id) REFERENCES schema_snapshots(id))");
            s.execute("CREATE TABLE IF NOT EXISTS viewpoints (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, data_source_id TEXT NOT NULL, " +
                "schema TEXT NOT NULL, name TEXT NOT NULL, description TEXT, " +
                "sort_order INTEGER DEFAULT 0, " +
                "created_at TEXT DEFAULT (datetime('now')), " +
                "updated_at TEXT DEFAULT (datetime('now')))");
            s.execute("CREATE TABLE IF NOT EXISTS viewpoint_tables (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, viewpoint_id INTEGER NOT NULL, " +
                "table_name TEXT NOT NULL, sort_order INTEGER DEFAULT 0, " +
                "FOREIGN KEY (viewpoint_id) REFERENCES viewpoints(id) ON DELETE CASCADE)");
            s.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_vp_ds_schema_name ON viewpoints(data_source_id, schema, name)");
            s.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_vpt_vp_table ON viewpoint_tables(viewpoint_id, table_name)");
            log.info("SQLite tables ready");
        } catch (Exception e) {
            log.error("DB init failed", e);
            throw new RuntimeException("SQLite 初始化失败: " + e.getMessage(), e);
        }
    }

    private Connection getConn() throws SQLException {
        String userHome = System.getProperty("user.home");
        String url = "jdbc:sqlite:" + userHome + "/.dbdoc-ai/dbdoc.db";
        return DriverManager.getConnection(url);
    }

    // ── DataSources (without schema) ────────────

    public void saveDataSource(String id, String name, String dbType, String url,
                                String username, String password) {
        try (Connection c = getConn(); PreparedStatement ps = c.prepareStatement(
                "INSERT OR REPLACE INTO datasources VALUES (?,?,?,?,?,?)")) {
            ps.setString(1, id); ps.setString(2, name); ps.setString(3, dbType);
            ps.setString(4, url); ps.setString(5, username);
            // B1: 密码加密落盘（明文降级已在 CryptoUtil 内处理并告警）
            ps.setString(6, CryptoUtil.encrypt(password));
            ps.executeUpdate();
        } catch (Exception e) { log.error("saveDataSource failed", e); throw new RuntimeException("保存数据源失败", e); }
    }

    public void clearAndAddSchemas(String dsId, List<String> schemaNames) {
        try (Connection c = getConn()) {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM datasource_schemas WHERE ds_id=?")) {
                ps.setString(1, dsId); ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT OR IGNORE INTO datasource_schemas(ds_id,schema_name) VALUES (?,?)")) {
                for (String s : schemaNames) { ps.setString(1, dsId); ps.setString(2, s); ps.addBatch(); }
                ps.executeBatch();
            }
        } catch (Exception e) { log.error("clearAndAddSchemas failed", e); throw new RuntimeException("批量更新数据源 Schema 失败", e); }
    }

    public void addDataSourceSchema(String dsId, String schemaName) {
        try (Connection c = getConn(); PreparedStatement ps = c.prepareStatement(
                "INSERT OR IGNORE INTO datasource_schemas(ds_id,schema_name) VALUES (?,?)")) {
            ps.setString(1, dsId); ps.setString(2, schemaName);
            ps.executeUpdate();
        } catch (Exception e) { log.error("addDataSourceSchema failed", e); throw new RuntimeException("新增数据源 Schema 失败", e); }
    }

    public void removeDataSourceSchema(String dsId, String schemaName) {
        try (Connection c = getConn(); PreparedStatement ps = c.prepareStatement(
                "DELETE FROM datasource_schemas WHERE ds_id=? AND schema_name=?")) {
            ps.setString(1, dsId); ps.setString(2, schemaName);
            ps.executeUpdate();
        } catch (Exception e) { log.error("removeDataSourceSchema failed", e); throw new RuntimeException("删除数据源 Schema 失败", e); }
    }

    public List<String> getDataSourceSchemas(String dsId) {
        List<String> list = new ArrayList<>();
        try (Connection c = getConn(); PreparedStatement ps = c.prepareStatement(
                "SELECT schema_name FROM datasource_schemas WHERE ds_id=?")) {
            ps.setString(1, dsId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(rs.getString("schema_name"));
        } catch (Exception e) { log.error("getSchemas: {}", e.getMessage()); }
        return list;
    }

    public void deleteDataSource(String id) {
        try (Connection c = getConn()) {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM datasource_schemas WHERE ds_id=?")) {
                ps.setString(1, id); ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM datasources WHERE id=?")) {
                ps.setString(1, id); ps.executeUpdate();
            }
        } catch (Exception e) { log.error("deleteDataSource failed", e); throw new RuntimeException("删除数据源失败", e); }
    }

    public Map<String, String> getDataSource(String id) {
        try (Connection c = getConn(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM datasources WHERE id=?")) {
            ps.setString(1, id);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rowToMap(rs);
        } catch (Exception e) { log.error("getDataSource: {}", e.getMessage()); }
        return null;
    }

    public List<Map<String, String>> listDataSources() {
        List<Map<String, String>> list = new ArrayList<>();
        try (Connection c = getConn(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT * FROM datasources")) {
            while (rs.next()) {
                Map<String, String> m = rowToMap(rs);
                m.put("schemas", String.join(",", getDataSourceSchemas(m.get("id"))));
                m.put("password", "***"); // 不在列表中暴露密码
                list.add(m);
            }
        } catch (Exception e) { log.error("listDataSources: {}", e.getMessage()); }
        return list;
    }

    // ── LLM Config ───────────────────────────────

    public void setConfig(String key, String value) {
        try (Connection c = getConn(); PreparedStatement ps = c.prepareStatement(
                "INSERT OR REPLACE INTO llm_config VALUES (?,?)")) {
            ps.setString(1, key); ps.setString(2, value); ps.executeUpdate();
        } catch (Exception e) { log.error("setConfig failed", e); throw new RuntimeException("保存配置失败", e); }
    }

    public String getConfig(String key) {
        try (Connection c = getConn(); PreparedStatement ps = c.prepareStatement(
                "SELECT value FROM llm_config WHERE key=?")) {
            ps.setString(1, key);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getString("value");
        } catch (Exception ignored) {}
        return null;
    }

    public Map<String, String> getAllConfig() {
        Map<String, String> map = new LinkedHashMap<>();
        try (Connection c = getConn(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT * FROM llm_config")) {
            while (rs.next()) map.put(rs.getString("key"), rs.getString("value"));
        } catch (Exception ignored) {}
        return map;
    }

    // ── AI Infer ──────────────────────────────────

    public void saveAiInfer(String dataSourceId, String tableName, String columnName,
                             String description, double confidence) {
        try (Connection c = getConn(); PreparedStatement ps = c.prepareStatement(
                "INSERT OR REPLACE INTO ai_infer(data_source_id,table_name,column_name,description,confidence,status,created_at) " +
                "VALUES (?,?,?,?,?,'pending',datetime('now'))")) {
            ps.setString(1, dataSourceId); ps.setString(2, tableName);
            ps.setString(3, columnName); ps.setString(4, description);
            ps.setDouble(5, confidence); ps.executeUpdate();
        } catch (Exception e) { log.error("saveAiInfer failed", e); throw new RuntimeException("保存 AI 推断结果失败", e); }
    }

    public void updateAiInferStatus(String dataSourceId, String tableName, String columnName, String status) {
        try (Connection c = getConn(); PreparedStatement ps = c.prepareStatement(
                "UPDATE ai_infer SET status=? WHERE data_source_id=? AND table_name=? AND column_name=?")) {
            ps.setString(1, status); ps.setString(2, dataSourceId);
            ps.setString(3, tableName); ps.setString(4, columnName); ps.executeUpdate();
        } catch (Exception e) { log.error("updateAiInferStatus failed", e); throw new RuntimeException("更新 AI 推断状态失败", e); }
    }

    public List<Map<String, Object>> getAiInferResults(String dataSourceId) {
        List<Map<String, Object>> list = new ArrayList<>();
        try (Connection c = getConn(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM ai_infer WHERE data_source_id=? AND status='pending'")) {
            ps.setString(1, dataSourceId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("tableName", rs.getString("table_name"));
                m.put("columnName", rs.getString("column_name"));
                m.put("description", rs.getString("description"));
                m.put("confidence", rs.getDouble("confidence"));
                m.put("status", rs.getString("status"));
                list.add(m);
            }
        } catch (Exception e) { log.error("getAiInferResults: {}", e.getMessage()); }
        return list;
    }

    public Set<String> getRejectedColumns(String dataSourceId, String tableName) {
        Set<String> set = new HashSet<>();
        try (Connection c = getConn(); PreparedStatement ps = c.prepareStatement(
                "SELECT column_name FROM ai_infer WHERE data_source_id=? AND table_name=? AND status='rejected'")) {
            ps.setString(1, dataSourceId); ps.setString(2, tableName);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) set.add(rs.getString("column_name"));
        } catch (Exception e) { log.error("getRejectedColumns: {}", e.getMessage()); }
        return set;
    }

    public void deletePendingAiInfer(String dataSourceId, List<String> tableNames) {
        try (Connection c = getConn()) {
            if (tableNames == null || tableNames.isEmpty()) {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM ai_infer WHERE data_source_id=? AND status='pending'")) {
                    ps.setString(1, dataSourceId); ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM ai_infer WHERE data_source_id=? AND table_name=? AND status='pending'")) {
                    for (String tn : tableNames) { ps.setString(1, dataSourceId); ps.setString(2, tn); ps.executeUpdate(); }
                }
            }
        } catch (Exception e) { log.error("deletePendingAiInfer failed", e); throw new RuntimeException("删除待处理 AI 推断失败", e); }
    }

    // ── Schema Snapshots & Changes (P0-1) ──────────

    public long insertSnapshot(String dataSourceId, String schema, int tableCount) {
        try (Connection c = getConn(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO schema_snapshots(data_source_id,schema,table_count,created_at) VALUES (?,?,?,datetime('now'))",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, dataSourceId); ps.setString(2, schema != null ? schema : "");
            ps.setInt(3, tableCount); ps.executeUpdate();
            ResultSet rs = ps.getGeneratedKeys();
            if (rs.next()) return rs.getLong(1);
        } catch (Exception e) { log.error("insertSnapshot: {}", e.getMessage()); }
        return -1;
    }

    public void insertChanges(long snapshotId, List<Map<String, Object>> changes) {
        try (Connection c = getConn(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO schema_changes(snapshot_id,change_type,table_name,column_name,detail,description) VALUES (?,?,?,?,?,?)")) {
            for (Map<String, Object> ch : changes) {
                ps.setLong(1, snapshotId);
                ps.setString(2, (String) ch.get("changeType"));
                ps.setString(3, (String) ch.get("tableName"));
                ps.setString(4, (String) ch.get("columnName"));
                ps.setString(5, (String) ch.get("detail"));
                ps.setString(6, (String) ch.get("description"));
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (Exception e) { log.error("insertChanges failed", e); throw new RuntimeException("写入结构变更记录失败", e); }
    }

    public List<Map<String, Object>> getSnapshots(String dataSourceId, String schema, int page, int size) {
        List<Map<String, Object>> list = new ArrayList<>();
        int offset = (page - 1) * size;
        try (Connection c = getConn(); PreparedStatement ps = c.prepareStatement(
                "SELECT s.*, (SELECT COUNT(*) FROM schema_changes WHERE snapshot_id=s.id AND change_type='added') as added_count, " +
                "(SELECT COUNT(*) FROM schema_changes WHERE snapshot_id=s.id AND change_type='modified') as modified_count, " +
                "(SELECT COUNT(*) FROM schema_changes WHERE snapshot_id=s.id AND change_type='deleted') as deleted_count " +
                "FROM schema_snapshots s WHERE s.data_source_id=? AND s.schema=? ORDER BY s.id DESC LIMIT ? OFFSET ?")) {
            ps.setString(1, dataSourceId); ps.setString(2, schema != null ? schema : "");
            ps.setInt(3, size); ps.setInt(4, offset);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getLong("id"));
                m.put("dataSourceId", rs.getString("data_source_id"));
                m.put("schema", rs.getString("schema"));
                m.put("tableCount", rs.getInt("table_count"));
                m.put("createdAt", rs.getString("created_at"));
                m.put("addedCount", rs.getInt("added_count"));
                m.put("modifiedCount", rs.getInt("modified_count"));
                m.put("deletedCount", rs.getInt("deleted_count"));
                list.add(m);
            }
        } catch (Exception e) { log.error("getSnapshots: {}", e.getMessage()); }
        return list;
    }

    public List<Map<String, Object>> getChangesBySnapshot(long snapshotId) {
        List<Map<String, Object>> list = new ArrayList<>();
        try (Connection c = getConn(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM schema_changes WHERE snapshot_id=? ORDER BY change_type, table_name")) {
            ps.setLong(1, snapshotId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getLong("id"));
                m.put("changeType", rs.getString("change_type"));
                m.put("tableName", rs.getString("table_name"));
                m.put("columnName", rs.getString("column_name"));
                m.put("detail", rs.getString("detail"));
                m.put("description", rs.getString("description"));
                list.add(m);
            }
        } catch (Exception e) { log.error("getChangesBySnapshot: {}", e.getMessage()); }
        return list;
    }

    public List<Map<String, Object>> getTableHistory(String dataSourceId, String schema, String tableName, int limit) {
        List<Map<String, Object>> list = new ArrayList<>();
        try (Connection c = getConn(); PreparedStatement ps = c.prepareStatement(
                "SELECT sc.*, ss.created_at as snapshot_time FROM schema_changes sc " +
                "JOIN schema_snapshots ss ON sc.snapshot_id=ss.id " +
                "WHERE ss.data_source_id=? AND ss.schema=? AND sc.table_name=? " +
                "ORDER BY ss.id DESC LIMIT ?")) {
            ps.setString(1, dataSourceId); ps.setString(2, schema != null ? schema : "");
            ps.setString(3, tableName); ps.setInt(4, limit);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("changeType", rs.getString("change_type"));
                m.put("tableName", rs.getString("table_name"));
                m.put("columnName", rs.getString("column_name"));
                m.put("detail", rs.getString("detail"));
                m.put("description", rs.getString("description"));
                m.put("snapshotTime", rs.getString("snapshot_time"));
                list.add(m);
            }
        } catch (Exception e) { log.error("getTableHistory: {}", e.getMessage()); }
        return list;
    }

    public int countSnapshots(String dataSourceId, String schema) {
        try (Connection c = getConn(); PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM schema_snapshots WHERE data_source_id=? AND schema=?")) {
            ps.setString(1, dataSourceId); ps.setString(2, schema != null ? schema : "");
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt(1);
        } catch (Exception e) { log.error("countSnapshots: {}", e.getMessage()); }
        return 0;
    }

    // ── Viewpoints (P0-2) ──────────────────────────

    public List<Map<String, Object>> listViewpoints(String dataSourceId, String schema) {
        List<Map<String, Object>> list = new ArrayList<>();
        try (Connection c = getConn(); PreparedStatement ps = c.prepareStatement(
                "SELECT v.*, (SELECT COUNT(*) FROM viewpoint_tables vt WHERE vt.viewpoint_id=v.id) as table_count " +
                "FROM viewpoints v WHERE v.data_source_id=? AND v.schema=? ORDER BY v.sort_order, v.name")) {
            ps.setString(1, dataSourceId); ps.setString(2, schema != null ? schema : "");
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getLong("id"));
                m.put("name", rs.getString("name"));
                m.put("description", rs.getString("description"));
                m.put("tableCount", rs.getInt("table_count"));
                m.put("createdAt", rs.getString("created_at"));
                m.put("updatedAt", rs.getString("updated_at"));
                list.add(m);
            }
        } catch (Exception e) { log.error("listViewpoints: {}", e.getMessage()); }
        return list;
    }

    public long createViewpoint(String dataSourceId, String schema, String name, String description) {
        try (Connection c = getConn(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO viewpoints(data_source_id,schema,name,description) VALUES (?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, dataSourceId); ps.setString(2, schema != null ? schema : "");
            ps.setString(3, name); ps.setString(4, description);
            ps.executeUpdate();
            ResultSet rs = ps.getGeneratedKeys();
            if (rs.next()) return rs.getLong(1);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("UNIQUE")) {
                log.warn("Viewpoint name conflict: {}", name);
                return -2;
            }
            log.error("createViewpoint: {}", e.getMessage());
        }
        return -1;
    }

    public void updateViewpoint(long id, String name, String description) {
        try (Connection c = getConn(); PreparedStatement ps = c.prepareStatement(
                "UPDATE viewpoints SET name=?, description=?, updated_at=datetime('now') WHERE id=?")) {
            ps.setString(1, name); ps.setString(2, description); ps.setLong(3, id);
            ps.executeUpdate();
        } catch (Exception e) { log.error("updateViewpoint failed", e); throw new RuntimeException("更新视图失败", e); }
    }

    public void deleteViewpoint(long id) {
        try (Connection c = getConn()) {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM viewpoint_tables WHERE viewpoint_id=?")) {
                ps.setLong(1, id); ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM viewpoints WHERE id=?")) {
                ps.setLong(1, id); ps.executeUpdate();
            }
        } catch (Exception e) { log.error("deleteViewpoint failed", e); throw new RuntimeException("删除视图失败", e); }
    }

    public List<String> getViewpointTables(long viewpointId) {
        List<String> list = new ArrayList<>();
        try (Connection c = getConn(); PreparedStatement ps = c.prepareStatement(
                "SELECT table_name FROM viewpoint_tables WHERE viewpoint_id=? ORDER BY sort_order, table_name")) {
            ps.setLong(1, viewpointId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(rs.getString("table_name"));
        } catch (Exception e) { log.error("getViewpointTables: {}", e.getMessage()); }
        return list;
    }

    public void setViewpointTables(long viewpointId, List<String> tableNames) {
        try (Connection c = getConn()) {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM viewpoint_tables WHERE viewpoint_id=?")) {
                ps.setLong(1, viewpointId); ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO viewpoint_tables(viewpoint_id,table_name,sort_order) VALUES (?,?,?)")) {
                for (int i = 0; i < tableNames.size(); i++) {
                    ps.setLong(1, viewpointId); ps.setString(2, tableNames.get(i)); ps.setInt(3, i);
                    ps.executeUpdate();
                }
            }
        } catch (Exception e) { log.error("setViewpointTables failed", e); throw new RuntimeException("设置视图表失败", e); }
    }

    public void addTableToViewpoint(long viewpointId, String tableName) {
        try (Connection c = getConn(); PreparedStatement ps = c.prepareStatement(
                "INSERT OR IGNORE INTO viewpoint_tables(viewpoint_id,table_name,sort_order) VALUES (?,?,(SELECT COALESCE(MAX(sort_order),0)+1 FROM viewpoint_tables WHERE viewpoint_id=?))")) {
            ps.setLong(1, viewpointId); ps.setString(2, tableName); ps.setLong(3, viewpointId);
            ps.executeUpdate();
        } catch (Exception e) { log.error("addTableToViewpoint failed", e); throw new RuntimeException("向视图添加表失败", e); }
    }

    public void removeTableFromViewpoint(long viewpointId, String tableName) {
        try (Connection c = getConn(); PreparedStatement ps = c.prepareStatement(
                "DELETE FROM viewpoint_tables WHERE viewpoint_id=? AND table_name=?")) {
            ps.setLong(1, viewpointId); ps.setString(2, tableName); ps.executeUpdate();
        } catch (Exception e) { log.error("removeTableFromViewpoint failed", e); throw new RuntimeException("从视图移除表失败", e); }
    }

    private Map<String, String> rowToMap(ResultSet rs) throws SQLException {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("id", rs.getString("id"));
        m.put("name", rs.getString("name"));
        m.put("dbType", rs.getString("db_type"));
        m.put("url", rs.getString("url"));
        m.put("username", rs.getString("username"));
        // B1: 读取时解密；无 ENC: 前缀的遗留明文原样返回（向后兼容）
        m.put("password", CryptoUtil.decrypt(rs.getString("password")));
        return m;
    }
}
