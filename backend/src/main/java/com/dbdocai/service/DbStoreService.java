package com.dbdocai.service;

import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.sql.*;

@Service
public class DbStoreService {

    private String dbPath;

    @PostConstruct
    public void init() {
        String userHome = System.getProperty("user.home");
        dbPath = "jdbc:sqlite:" + userHome + "/.dbdoc-ai/dbdocai.db";
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.execute("CREATE TABLE IF NOT EXISTS datasource_config (" +
                "id TEXT PRIMARY KEY, name TEXT, db_type TEXT, url TEXT, username TEXT, password TEXT, schema_name TEXT)");

            stmt.execute("CREATE TABLE IF NOT EXISTS llm_config (" +
                "key TEXT PRIMARY KEY, value TEXT)");

            stmt.execute("CREATE TABLE IF NOT EXISTS ai_infer_cache (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, data_source_id TEXT, table_name TEXT, column_name TEXT, " +
                "description TEXT, confidence REAL, confirmed INTEGER DEFAULT 0, created_at TEXT," +
                "UNIQUE(data_source_id, table_name, column_name))");

            stmt.execute("CREATE TABLE IF NOT EXISTS sync_snapshot (" +
                "data_source_id TEXT, table_name TEXT, column_count INTEGER," +
                "PRIMARY KEY (data_source_id, table_name))");

        } catch (SQLException e) {
            throw new RuntimeException("Failed to init SQLite store", e);
        }
    }

    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(dbPath);
    }
}
