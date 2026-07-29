package com.dbdocai.service;

import com.dbdocai.dto.DataSourceConfigDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 数据割接 SQL 脚本生成器（移植自桌面副本，按主线代码风格整理）。
 *
 * <p>基于字段映射生成三段式割接脚本（纯文本拼装，不执行、不新建 JDBC 连接）：
 * <ol>
 *   <li>① 全量割接：BEGIN; INSERT INTO tgt(...) SELECT ... FROM src; COMMIT;</li>
 *   <li>② 行数校验：SELECT 'tbl', src_count, tgt_count 对比；</li>
 *   <li>③ 回滚提示：字段覆盖报告 + {@code -- ROLLBACK;} 注释（仅提示，不包裹可执行事务）。</li>
 * </ol>
 *
 * <p>支持的方言：oracle / dm / postgresql / mysql / sqlite（按 {@code dbType} 小写分支；
 * mysql 标识符用反引号、其余双引号；B 库为 mysql 用 START TRANSACTION，其余 BEGIN/COMMIT）。
 *
 * <p>transformRule 的翻译规则（与桌面一致）：
 * <ul>
 *   <li>无 rule → 直接使用源列；</li>
 *   <li>EXPR: IIF(x='Y',1,0) → CASE WHEN x='Y' THEN 1 ELSE 0 END（仅单层）；</li>
 *   <li>CAST: TO_CHAR(field) → 原样保留（交给目标库 / infa 处理）。</li>
 * </ul>
 *
 * <p>安全性：本服务只读数据源方言元数据（{@code DataSourceStoreService.get} 返回脱敏 DTO，
 * 仅需 dbType），绝不建立数据库连接、不触碰凭据，不绕过主线已闭合的鉴权 / SSRF 防护。
 */
@Service
public class CutoverSqlService {
    private static final Logger log = LoggerFactory.getLogger(CutoverSqlService.class);
    private final DataSourceStoreService storeService;

    public CutoverSqlService(DataSourceStoreService storeService) {
        this.storeService = storeService;
    }

    /**
     * 生成三段式割接 SQL 文本（不执行、不建连接，仅拼装）。
     *
     * @param dsIdA        源库数据源 id（经 DataSourceStoreService.get 读方言）
     * @param schemaA      源库 schema（可空）
     * @param dsIdB        目标库数据源 id
     * @param schemaB      目标库 schema（可空）
     * @param tableMappings 表映射列表，元素含 sourceTable / targetTable
     * @param fieldMaps    键为 "srcTable→tgtTable"，值为该表对的字段映射结果
     * @return 完整割接 SQL 文档（含头注释、①INSERT…SELECT、②行数校验、③ROLLBACK 提示）
     */
    public String generateCutoverSql(String dsIdA, String schemaA,
                                      String dsIdB, String schemaB,
                                      List<Map<String, String>> tableMappings,
                                      Map<String, FieldMappingService.MatchResult> fieldMaps) {
        DataSourceConfigDTO dsA = storeService.get(dsIdA);
        DataSourceConfigDTO dsB = storeService.get(dsIdB);
        String dialectA = dialectOf(dsA);
        String dialectB = dialectOf(dsB);

        StringBuilder sb = new StringBuilder();
        sb.append("-- ============================================================\n");
        sb.append("-- 数据割接脚本\n");
        sb.append("-- 源: ").append(dsA == null ? dsIdA : dsA.getName()).append(" (").append(dialectA).append(")\n");
        sb.append("-- 目标: ").append(dsB == null ? dsIdB : dsB.getName()).append(" (").append(dialectB).append(")\n");
        sb.append("-- 生成时间: ").append(new Date()).append("\n");
        sb.append("-- ============================================================\n\n");

        List<String> beginTx = "mysql".equals(dialectB) ? Arrays.asList("START TRANSACTION;", "COMMIT;")
                                                        : Arrays.asList("BEGIN;", "COMMIT;");

        List<String> sourceOnlyReport = new ArrayList<>();
        List<String> targetOnlyReport = new ArrayList<>();

        for (Map<String, String> tm : tableMappings) {
            String srcTable = tm.get("sourceTable");
            String tgtTable = tm.get("targetTable");
            if (srcTable == null || tgtTable == null) continue;
            String key = srcTable + "→" + tgtTable;
            FieldMappingService.MatchResult mr = fieldMaps.get(key);
            if (mr == null) continue;

            // 收集可用字段对（跳过 source_only / target_only）
            List<FieldMappingService.FieldMapping> usable = new ArrayList<>();
            List<FieldMappingService.FieldMapping> srcOnly = new ArrayList<>();
            List<FieldMappingService.FieldMapping> tgtOnly = new ArrayList<>();
            for (FieldMappingService.FieldMapping m : mr.mappings) {
                if (m.sourceColumn == null || m.targetColumn == null
                    || "source_only".equals(m.status) || "target_only".equals(m.status)) {
                    if ("source_only".equals(m.status)) srcOnly.add(m);
                    else if ("target_only".equals(m.status)) tgtOnly.add(m);
                    continue;
                }
                usable.add(m);
            }
            if (usable.isEmpty()) {
                sb.append("-- 跳过 ").append(srcTable).append("→").append(tgtTable).append("（无可用字段映射）\n\n");
                continue;
            }

            String srcQualified = qualify(dialectA, schemaA, srcTable);
            String tgtQualified = qualify(dialectB, schemaB, tgtTable);

            sb.append("-- ────────────────────────────────────────────\n");
            sb.append("-- ").append(srcTable).append(" → ").append(tgtTable).append("\n");
            sb.append("-- ────────────────────────────────────────────\n");
            sb.append(beginTx.get(0)).append("\n");
            sb.append("INSERT INTO ").append(tgtQualified).append(" (\n");
            // 目标列
            for (int i = 0; i < usable.size(); i++) {
                sb.append("    ").append(quoteIdent(dialectB, usable.get(i).targetColumn));
                sb.append(i < usable.size() - 1 ? ",\n" : "\n");
            }
            sb.append(") SELECT\n");
            // 源表达式
            for (int i = 0; i < usable.size(); i++) {
                sb.append("    ").append(toSqlExpression(usable.get(i)));
                sb.append(i < usable.size() - 1 ? ",\n" : "\n");
            }
            sb.append("FROM ").append(srcQualified).append(";\n");
            sb.append(beginTx.get(1)).append("\n\n");

            // 行数校验
            sb.append("-- 行数校验\n");
            sb.append("SELECT '").append(srcTable).append("' AS table_name,\n");
            sb.append("       (SELECT COUNT(*) FROM ").append(srcQualified).append(") AS src_count,\n");
            sb.append("       (SELECT COUNT(*) FROM ").append(tgtQualified).append(") AS tgt_count;\n\n");

            // 记录源/目标独有字段，供覆盖报告
            for (FieldMappingService.FieldMapping m : srcOnly) {
                sourceOnlyReport.add(srcTable + "." + m.sourceColumn);
            }
            for (FieldMappingService.FieldMapping m : tgtOnly) {
                targetOnlyReport.add(tgtTable + "." + m.targetColumn);
            }
        }

        // 覆盖报告
        sb.append("-- ============================================================\n");
        sb.append("-- 字段覆盖报告\n");
        sb.append("-- ============================================================\n");
        sb.append("-- 源独有字段（割接未迁移）: ").append(sourceOnlyReport.size()).append(" 个\n");
        for (String s : sourceOnlyReport) sb.append("--   ").append(s).append("\n");
        sb.append("-- 目标独有字段（需手工补值或设默认值）: ").append(targetOnlyReport.size()).append(" 个\n");
        for (String s : targetOnlyReport) sb.append("--   ").append(s).append("\n");
        sb.append("\n-- 如校验不一致，请回滚：\n");
        sb.append("-- ROLLBACK;\n");

        return sb.toString();
    }

    private static String dialectOf(DataSourceConfigDTO ds) {
        if (ds == null || ds.getDbType() == null) return "unknown";
        return ds.getDbType().toLowerCase();
    }

    private static String qualify(String dialect, String schema, String table) {
        if (schema == null || schema.isEmpty()) {
            return quoteIdent(dialect, table);
        }
        return quoteIdent(dialect, schema) + "." + quoteIdent(dialect, table);
    }

    private static String quoteIdent(String dialect, String ident) {
        if (ident == null || ident.isEmpty()) return ident;
        if ("mysql".equals(dialect)) {
            return "`" + ident.replace("`", "``") + "`";
        }
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }

    /**
     * 将字段映射的 transformRule 翻译为可在 SELECT 中使用的 SQL 表达式。
     * <ul>
     *   <li>无 rule → 源列原样；</li>
     *   <li>EXPR: IIF(x='Y',1,0) → CASE WHEN x='Y' THEN 1 ELSE 0 END；</li>
     *   <li>CAST: TO_CHAR(field) → TO_CHAR(field)（原样保留，交给目标库 / infa）。</li>
     * </ul>
     */
    private static String toSqlExpression(FieldMappingService.FieldMapping m) {
        String rule = m.transformRule == null ? "" : m.transformRule.trim();
        String srcCol = m.sourceColumn;
        if (rule.isEmpty()) {
            return srcCol;
        }
        if (rule.startsWith("EXPR:")) {
            String expr = rule.substring(5).trim();
            return iifToCaseWhen(expr);
        }
        if (rule.startsWith("CAST:")) {
            return rule.substring(5).trim();
        }
        // 未知 rule 格式：回退到源列
        return srcCol;
    }

    /**
     * 将 Informatica 风格 {@code IIF(cond, a, b)} 翻译为
     * {@code CASE WHEN cond THEN a ELSE b END}。仅处理简单的单层 IIF。
     */
    private static String iifToCaseWhen(String expr) {
        expr = expr.trim();
        if (expr.toUpperCase().startsWith("IIF(") && expr.endsWith(")")) {
            String inner = expr.substring(4, expr.length() - 1);
            // 按括号深度切分顶层逗号（不处理嵌套括号）
            List<String> parts = splitTopLevel(inner);
            if (parts.size() == 3) {
                return "CASE WHEN " + parts.get(0).trim() + " THEN " + parts.get(1).trim()
                     + " ELSE " + parts.get(2).trim() + " END";
            }
        }
        // 无法识别的 IIF —— 原样返回，交由数据库在运行时报错
        return expr;
    }

    private static List<String> splitTopLevel(String s) {
        List<String> result = new ArrayList<>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '(') depth++;
            else if (c == ')') depth--;
            if (c == ',' && depth == 0) {
                result.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) result.add(cur.toString());
        return result;
    }
}
