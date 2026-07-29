package com.dbdocai.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 列注释更新 SQL 构造工具（P1-3 修复）。
 *
 * <p>此前 {@code COMMENT ON COLUMN} 用字符串拼接构造且仅支持 Postgres 语法，
 * 既无其他数据库方言，又存在 SQL 注入面。本工具统一处理：
 * <ol>
 *   <li>标识符（表名/列名）强制走 {@code ^[\w]+$} 白名单校验，未通过直接抛
 *       {@link IllegalArgumentException}（防御注入）；</li>
 *   <li>注释文本统一做单引号转义；</li>
 *   <li>按 {@code dbType} 分支：
 *     <ul>
 *       <li>Postgres：{@code COMMENT ON COLUMN "t"."c" IS 'cmt'}</li>
 *       <li>MySQL：需完整列定义才能 {@code ALTER TABLE .. MODIFY COLUMN .. COMMENT}，
 *           故从元数据取回列定义再拼接；其中 {@code TYPE_NAME} 经白名单
 *           {@code ^[\w() ]+$} 校验（Round 2 P2），{@code DEFAULT} 值经
 *           {@link #sanitizeColumnDef(String)} 校验/归一化，含未转义单引号或非预期格式时
 *           跳过 DEFAULT 子句，杜绝拼接注入；</li>
 *       <li>SQLite：不支持列注释，返回 {@code null}（调用方跳过，不抛错中断）；</li>
 *     </ul>
 *   </li>
 * </ol>
 */
public final class CommentSqlUtil {
    private static final Logger log = LoggerFactory.getLogger(CommentSqlUtil.class);

    /** MySQL 分支 TYPE_NAME 白名单：仅允许字母/数字/下划线/括号/空格。 */
    private static final String TYPE_NAME_PATTERN = "[\\w() ]+";

    private CommentSqlUtil() {
        // 工具类，禁止实例化
    }

    /**
     * 按数据库类型构造列注释更新 SQL。
     *
     * @param conn     已建立的连接（MySQL 分支需读取列元数据）
     * @param dbType   数据源类型（mysql / postgresql / sqlite 等，大小写不敏感）
     * @param table    表名（必须匹配 {@code ^[\w]+$}）
     * @param column   列名（必须匹配 {@code ^[\w]+$}）
     * @param description 注释文本（内部做单引号转义）
     * @return 可执行的 SQL；SQLite 等不支持列注释的类型返回 {@code null}
     * @throws SQLException 读取列元数据失败时（MySQL 分支）
     * @throws IllegalArgumentException 标识符未通过白名单校验时（疑似注入）
     */
    public static String buildColumnCommentSql(Connection conn, String dbType,
                                               String table, String column, String description)
            throws SQLException {
        validateIdentifier(table);
        validateIdentifier(column);
        String safeComment = description == null ? "" : description.replace("'", "''");
        String t = dbType == null ? "" : dbType.toLowerCase();

        if (t.contains("postgres")) {
            return "COMMENT ON COLUMN \"" + table + "\".\"" + column + "\" IS '" + safeComment + "'";
        } else if (t.contains("mysql")) {
            ColumnMeta cm = fetchColumnMeta(conn, table, column);
            if (cm == null) {
                throw new IllegalStateException(
                        "无法获取列定义以更新 MySQL 列注释: " + table + "." + column);
            }
            // P2（Round 2）：TYPE_NAME 直拼存在注入面，先过白名单；不匹配则跳过整条注释写入。
            if (cm.typeName == null || !cm.typeName.matches(TYPE_NAME_PATTERN)) {
                log.warn("MySQL 列 '{}.{}' 的 TYPE_NAME '{}' 未通过白名单 {}，"
                        + "为防注入跳过该列注释写入。", table, column, cm.typeName, TYPE_NAME_PATTERN);
                return null;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("ALTER TABLE `").append(table).append("` MODIFY COLUMN `").append(column).append("` ")
              .append(cm.typeName);
            if (cm.columnSize > 0) {
                sb.append("(").append(cm.columnSize);
                if (cm.decimalDigits != null && cm.decimalDigits > 0) {
                    sb.append(",").append(cm.decimalDigits);
                }
                sb.append(")");
            }
            if (!cm.nullable) {
                sb.append(" NOT NULL");
            }
            String safeColumnDef = sanitizeColumnDef(cm.columnDef);
            if (safeColumnDef != null) {
                sb.append(" DEFAULT ").append(safeColumnDef);
            }
            sb.append(" COMMENT '").append(safeComment).append("'");
            return sb.toString();
        } else if (t.contains("sqlite")) {
            // SQLite 不支持列注释，返回 null 由调用方跳过
            return null;
        } else {
            // 未知类型：沿用 Postgres 语义（与原行为一致），标识符已校验
            return "COMMENT ON COLUMN \"" + table + "\".\"" + column + "\" IS '" + safeComment + "'";
        }
    }

    /**
     * 标识符白名单校验：仅允许字母/数字/下划线，防止 SQL 注入。
     *
     * @param id 待校验标识符（表名或列名）
     * @throws IllegalArgumentException 标识符为 null 或含非法字符
     */
    public static void validateIdentifier(String id) {
        if (id == null || !id.matches("^[\\w]+$")) {
            throw new IllegalArgumentException("非法的数据库对象标识符（疑似 SQL 注入）: " + id);
        }
    }

    /** 从数据库元数据取回列定义（用于 MySQL MODIFY COLUMN）。 */
    private static ColumnMeta fetchColumnMeta(Connection conn, String table, String column)
            throws SQLException {
        ColumnMeta meta = new ColumnMeta();
        DatabaseMetaData dbMeta = conn.getMetaData();
        try (ResultSet rs = dbMeta.getColumns(null, null, table, column)) {
            if (rs.next()) {
                meta.typeName = rs.getString("TYPE_NAME");
                int size = rs.getInt("COLUMN_SIZE");
                meta.columnSize = rs.wasNull() ? 0 : size;
                int dec = rs.getInt("DECIMAL_DIGITS");
                meta.decimalDigits = rs.wasNull() ? null : dec;
                meta.nullable = !"NO".equalsIgnoreCase(rs.getString("IS_NULLABLE"));
                meta.columnDef = rs.getString("COLUMN_DEF");
                return meta;
            }
        }
        return null;
    }

    /**
     * 对 {@code DatabaseMetaData} 返回的 {@code COLUMN_DEF}（列 DEFAULT 值）做安全处理，
     * 返回可直接拼接在 {@code DEFAULT } 之后的安全片段；若值不可信则返回 {@code null}，
     * 表示调用方应跳过 DEFAULT 子句以避免 SQL 注入。
     *
     * <p>处理规则（最小安全、不改变既有合法语义）：
     * <ol>
     *   <li>纯数字（可选负号/小数点，如 {@code 0}、{@code 3.14}）→ 原样返回；</li>
     *   <li>首尾均为单引号、且内部单引号均已正确转义为 {@code ''} 的合法字符串字面量
     *       （如 {@code 'hello'}、{@code 'O''Brien'}）→ 原样返回，避免破坏 MySQL 常见的
     *       已包裹表示；</li>
     *   <li>不含单引号的“安全 token”（字母/数字/下划线/空格/括号/常见运算符/点，
     *       如 {@code CURRENT_TIMESTAMP}、{@code NOW()}）→ 原样返回；</li>
     *   <li>其余（含未转义单引号或 {@code ;} 等非预期字符）→ 返回 {@code null}，跳过 DEFAULT，
     *       符合“非预期格式则跳过 DEFAULT 子句”的防御策略。</li>
     * </ol>
     *
     * <p>说明：MySQL Connector/J 对字符串默认值通常返回已包裹单引号的字面量
     * （如 {@code 'hello'}），若对整体做单引号翻倍转义会得到 {@code ''hello''}（非法），
     * 故此处对“合法已包裹字面量”保留原样，仅对不可信值跳过，而非机械翻倍转义。
     *
     * @param columnDef {@code COLUMN_DEF} 原始值（可能为 null）
     * @return 安全的 DEFAULT 片段，或 {@code null} 表示跳过 DEFAULT 子句
     */
    private static String sanitizeColumnDef(String columnDef) {
        if (columnDef == null) {
            return null;
        }
        String v = columnDef.trim();
        if (v.isEmpty()) {
            return null;
        }
        // 规则 1：纯数字（可选负号/小数点）
        if (v.matches("-?\\d+(\\.\\d+)?")) {
            return v;
        }
        // 规则 2：完整单引号包裹的合法字符串字面量
        if (v.length() >= 2 && v.charAt(0) == '\'' && v.charAt(v.length() - 1) == '\'') {
            String inner = v.substring(1, v.length() - 1);
            return isProperlyEscapedQuoted(inner) ? v : null;
        }
        // 规则 3：不含单引号的“安全 token”（函数/表达式等）
        if (v.indexOf('\'') < 0
                && v.matches("[A-Za-z0-9_(). ,+\\-*/|&!%^~<>?=:]+")) {
            return v;
        }
        // 规则 4：不可信，跳过 DEFAULT 子句以防注入
        return null;
    }

    /**
     * 判断字符串内部单引号是否均正确转义为 {@code ''}（即不存在可“脱义”的孤立单引号）。
     *
     * @param inner 已去除首尾包裹单引号后的字符串内容
     * @return 若所有单引号均为 {@code ''} 转义对则返回 {@code true}，否则 {@code false}
     */
    private static boolean isProperlyEscapedQuoted(String inner) {
        int i = 0;
        int n = inner.length();
        while (i < n) {
            char c = inner.charAt(i);
            if (c == '\'') {
                // 单引号必须以 '' 成对出现，否则视为未转义（存在注入风险）
                if (i + 1 < n && inner.charAt(i + 1) == '\'') {
                    i += 2;
                } else {
                    return false;
                }
            } else {
                i++;
            }
        }
        return true;
    }

    /** 列元数据类型。 */
    private static class ColumnMeta {
        String typeName;
        int columnSize;
        Integer decimalDigits;
        boolean nullable;
        String columnDef;
    }
}
