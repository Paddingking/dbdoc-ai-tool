package com.dbdocai.service;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DbStore 单元测试（P2-6：密码加密落盘 + init 失败重抛）。
 *
 * <p>使用真实 SQLite（位于临时 user.home 下），断言：
 * <ul>
 *   <li>{@code saveDataSource} 写入的 password 列带 {@code ENC:} 前缀（B1 加密落盘，不存明文）；</li>
 *   <li>{@code init()} 在底层连接/建表失败时重抛 {@link RuntimeException}（重抛路径）。</li>
 * </ul>
 *
 * <p>说明：CryptoUtil 主密钥通过系统属性 {@code dbdoc.master.key} 注入，确保走加密而非明文降级；
 * 真实环境下主密钥来自环境变量或 {@code ~/.dbdoc-ai/.masterkey} 文件（机制一致）。
 */
public class DbStoreTest {

    private static String originalUserHome;
    private static String originalMasterKey;

    @BeforeAll
    static void setUpClass() {
        originalUserHome = System.getProperty("user.home");
        originalMasterKey = System.getProperty("dbdoc.master.key");
        // 提供确定性主密钥，确保 CryptoUtil 走加密（ENC:）而非明文降级
        System.setProperty("dbdoc.master.key", "unit-test-master-key-0123456789");
    }

    @AfterAll
    static void tearDownClass() {
        if (originalUserHome == null) {
            System.clearProperty("user.home");
        } else {
            System.setProperty("user.home", originalUserHome);
        }
        if (originalMasterKey == null) {
            System.clearProperty("dbdoc.master.key");
        } else {
            System.setProperty("dbdoc.master.key", originalMasterKey);
        }
    }

    @Test
    void saveDataSource_storesEncryptedPasswordWithEncPrefix() throws Exception {
        Path homeDir = Files.createTempDirectory("dbdoc-enc-test");
        System.setProperty("user.home", homeDir.toString());

        DbStore db = new DbStore();
        db.init();

        String id = "ut-enc-" + System.nanoTime();
        db.saveDataSource(id, "n", "mysql", "jdbc:mysql://x/y", "u", "s3cret-pw");

        String raw = readRawPassword(homeDir, id);
        assertTrue(raw != null && raw.startsWith("ENC:"),
                "落盘密码应带 ENC: 前缀（加密），实际: " + raw);
    }

    @Test
    void init_rethrowsRuntimeExceptionWhenConnectionFails() throws Exception {
        // 将 user.home 指向一个【普通文件】而非目录，使 SQLite 无法在其下创建数据库文件，
        // 触发 getConn() 失败，从而验证 init() 的 RuntimeException 重抛路径。
        Path homeFile = Files.createTempFile("dbdoc-badhome", ".tmp");
        System.setProperty("user.home", homeFile.toString());

        DbStore db = new DbStore();
        assertThrows(RuntimeException.class, db::init,
                "init() 在表创建失败时应重抛 RuntimeException");
    }

    private static String readRawPassword(Path homeDir, String id) throws Exception {
        String url = "jdbc:sqlite:" + homeDir + "/.dbdoc-ai/dbdoc.db";
        try (Connection c = DriverManager.getConnection(url);
             PreparedStatement ps = c.prepareStatement(
                     "SELECT password FROM datasources WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("password");
                }
            }
        }
        return null;
    }
}
