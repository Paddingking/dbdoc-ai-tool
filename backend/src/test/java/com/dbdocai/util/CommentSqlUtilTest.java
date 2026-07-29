package com.dbdocai.util;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CommentSqlUtil 单元测试（P1-3 / Round 2 P2）。
 *
 * <p>覆盖：
 * <ol>
 *   <li>{@link CommentSqlUtil#validateIdentifier(String)} 注入样例拒绝/接受；</li>
 *   <li>{@link CommentSqlUtil#buildColumnCommentSql(Connection, String, String, String, String)}
 *       对 postgres / mysql / sqlite 三分支断言；</li>
 *   <li>私有 {@code sanitizeColumnDef} 对 DEFAULT 注入样例（{@code 'O''Brien'} 保留、
 *       含 {@code ;} 的恶意值返回 null 跳过）。</li>
 * </ol>
 */
public class CommentSqlUtilTest {

    // ── 1. validateIdentifier 注入样例 ─────────────────────────────

    @Test
    public void validateIdentifier_rejectsInjectionSamples() {
        // SQL 注入：含引号与注释符
        assertThrows(IllegalArgumentException.class,
                () -> CommentSqlUtil.validateIdentifier("a'; DROP--"));
        // 路径穿越式非法字符
        assertThrows(IllegalArgumentException.class,
                () -> CommentSqlUtil.validateIdentifier("../etc"));
    }

    @Test
    public void validateIdentifier_acceptsValidIdentifiers() {
        // ASCII 字母/数字/下划线 通过（正常表名）
        CommentSqlUtil.validateIdentifier("users");
        CommentSqlUtil.validateIdentifier("my_table_1");
        CommentSqlUtil.validateIdentifier("TBL_COL");

        // 说明：当前白名单 ^[\w]+$ 仅含 ASCII 字母/数字/下划线（与 CLAUDE.md 注入防御一致），
        // 中文标识符（如「正常表名」）按设计被拒绝——若后续需支持中文对象名，应在单独轮次放宽白名单。
        assertThrows(IllegalArgumentException.class,
                () -> CommentSqlUtil.validateIdentifier("正常表名"));
    }

    // ── 2. buildColumnCommentSql 三分支 ───────────────────────────

    @Test
    public void buildColumnCommentSql_postgres_branch() throws Exception {
        // Postgres 分支不读取连接元数据，conn 可为 null
        String sql = CommentSqlUtil.buildColumnCommentSql(
                null, "postgresql", "users", "name", "用户名称");
        assertEquals("COMMENT ON COLUMN \"users\".\"name\" IS '用户名称'", sql);
    }

    @Test
    public void buildColumnCommentSql_mysql_branch() throws Exception {
        Connection conn = mock(Connection.class);
        DatabaseMetaData meta = mock(DatabaseMetaData.class);
        ResultSet rs = mock(ResultSet.class);

        when(conn.getMetaData()).thenReturn(meta);
        when(meta.getColumns(null, null, "users", "name")).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getString("TYPE_NAME")).thenReturn("varchar");
        when(rs.getInt("COLUMN_SIZE")).thenReturn(255);
        when(rs.wasNull()).thenReturn(false);
        when(rs.getInt("DECIMAL_DIGITS")).thenReturn(0); // wasNull=false → 0
        when(rs.getString("IS_NULLABLE")).thenReturn("YES"); // nullable=true
        when(rs.getString("COLUMN_DEF")).thenReturn(null);  // 跳过 DEFAULT

        String sql = CommentSqlUtil.buildColumnCommentSql(conn, "mysql", "users", "name", "备注");

        assertTrue(sql.contains("ALTER TABLE"), "应含 ALTER TABLE");
        assertTrue(sql.contains("MODIFY COLUMN"), "应含 MODIFY COLUMN");
        assertTrue(sql.contains("`users`"), "表名应反引号包裹");
        assertTrue(sql.contains("`name`"), "列名应反引号包裹");
        assertTrue(sql.contains("varchar(255)"), "类型与长度应拼接");
        assertTrue(sql.contains("COMMENT '备注'"), "注释应正确转义拼接");
        assertFalse(sql.contains("NOT NULL"), "可空列不应含 NOT NULL");
        assertFalse(sql.contains("DEFAULT"), "DEFAULT 为 null 时应跳过");
    }

    @Test
    public void buildColumnCommentSql_mysql_skipsOnBadTypeName() throws Exception {
        Connection conn = mock(Connection.class);
        DatabaseMetaData meta = mock(DatabaseMetaData.class);
        ResultSet rs = mock(ResultSet.class);

        when(conn.getMetaData()).thenReturn(meta);
        when(meta.getColumns(null, null, "users", "name")).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        // 含注入字符的非法 TYPE_NAME
        when(rs.getString("TYPE_NAME")).thenReturn("varchar; DROP TABLE users--");
        when(rs.getInt("COLUMN_SIZE")).thenReturn(0);
        when(rs.wasNull()).thenReturn(false);
        when(rs.getInt("DECIMAL_DIGITS")).thenReturn(0);
        when(rs.getString("IS_NULLABLE")).thenReturn("YES");
        when(rs.getString("COLUMN_DEF")).thenReturn(null);

        // 白名单不匹配 → 跳过整条注释写入，返回 null（Round 2 P2）
        assertNull(CommentSqlUtil.buildColumnCommentSql(conn, "mysql", "users", "name", "备注"));
    }

    @Test
    public void buildColumnCommentSql_sqlite_branch() throws Exception {
        // SQLite 不支持列注释，返回 null（conn 可为 null）
        assertNull(CommentSqlUtil.buildColumnCommentSql(null, "sqlite", "users", "name", "备注"));
    }

    // ── 3. sanitizeColumnDef（DEFAULT 注入） ──────────────────────

    @Test
    public void sanitizeColumnDef_keepsEscapedLiteral_and_rejectsMalicious() throws Exception {
        Method m = CommentSqlUtil.class.getDeclaredMethod("sanitizeColumnDef", String.class);
        m.setAccessible(true);

        // 'O''Brien'：合法转义单引号 → 保留
        assertEquals("'O''Brien'", m.invoke(null, "'O''Brien'"));

        // 含 ; 的恶意值 → 返回 null（调用方跳过 DEFAULT）
        assertNull(m.invoke(null, "'; DROP TABLE users--"));

        // 纯数字 → 保留
        assertEquals("3.14", m.invoke(null, "3.14"));

        // 安全函数 token → 保留
        assertEquals("CURRENT_TIMESTAMP", m.invoke(null, "CURRENT_TIMESTAMP"));
    }
}
