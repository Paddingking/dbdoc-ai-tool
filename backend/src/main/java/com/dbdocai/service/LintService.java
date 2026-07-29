package com.dbdocai.service;

import com.dbdocai.dto.DataSourceConfigDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class LintService {
    private static final Logger log = LoggerFactory.getLogger(LintService.class);
    private final DocumentService documentService;
    private final DataSourceStoreService storeService;

    public LintService(DocumentService documentService, DataSourceStoreService storeService) {
        this.documentService = documentService;
        this.storeService = storeService;
    }

    public static class LintIssue {
        public String ruleId;
        public String tableName;
        public String columnName;
        public String message;
        public String suggestion;
    }

    public static class LintReport {
        public String dataSourceId;
        public String schema;
        public int totalTables;
        public int totalColumns;
        public Map<String, Integer> summary;  // error, warn, info
        public List<LintIssue> issues;
        public String generatedAt;
    }

    public LintReport lint(String dataSourceId, String schema, List<String> tableNames) {
        DataSourceConfigDTO ds = storeService.get(dataSourceId);
        if (ds == null) throw new IllegalArgumentException("数据源不存在");
        String effectiveSchema = schema != null ? schema : ds.getSchema();

        Map<String, Object> doc = documentService.generateDocument(dataSourceId, effectiveSchema, tableNames);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tables = (List<Map<String, Object>>) doc.get("tables");

        List<LintIssue> issues = new ArrayList<>();
        int totalColumns = 0;

        for (Map<String, Object> table : tables) {
            String tn = (String) table.get("name");
            String comment = (String) table.get("comment");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> columns = (List<Map<String, Object>>) table.get("columns");
            if (columns == null) columns = Collections.emptyList();
            @SuppressWarnings("unchecked")
            List<Map<String, String>> indexes = (List<Map<String, String>>) table.get("indexes");
            @SuppressWarnings("unchecked")
            List<Map<String, String>> fks = (List<Map<String, String>>) table.get("foreignKeys");

            totalColumns += columns.size();

            checkNoPrimaryKey(tn, columns, issues);
            checkTableNoComment(tn, comment, issues);
            checkCommentCoverage(tn, columns, issues);
            checkNamingStyle(tn, columns, issues);
            checkReservedWord(tn, columns, issues);
            checkTypeMismatch(tn, columns, issues);
            checkVarcharNoLimit(tn, columns, issues);
            checkFkNoIndex(tn, fks, indexes, issues);
            checkRedundantIndex(tn, indexes, issues);
            checkSingleColumnTable(tn, columns, issues);
            checkWideTable(tn, columns, issues);
        }

        LintReport report = new LintReport();
        report.dataSourceId = dataSourceId;
        report.schema = effectiveSchema;
        report.totalTables = tables.size();
        report.totalColumns = totalColumns;
        report.summary = new LinkedHashMap<>();
        report.summary.put("error", (int) issues.stream().filter(i -> i.ruleId.startsWith("R") && "error".equals(getRuleLevel(i.ruleId))).count());
        report.summary.put("warn", (int) issues.stream().filter(i -> i.ruleId.startsWith("R") && "warn".equals(getRuleLevel(i.ruleId))).count());
        report.summary.put("info", (int) issues.stream().filter(i -> i.ruleId.startsWith("R") && "info".equals(getRuleLevel(i.ruleId))).count());
        report.issues = issues;
        report.generatedAt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        return report;
    }

    private String getRuleLevel(String ruleId) {
        switch (ruleId) {
            case "R1": case "R7": case "R9": return "error";
            case "R2": case "R3": case "R5": case "R8": case "R11": return "warn";
            default: return "info";
        }
    }

    // R1: 无主键
    private void checkNoPrimaryKey(String tn, List<Map<String, Object>> columns, List<LintIssue> issues) {
        boolean hasPk = columns.stream().anyMatch(c -> Boolean.TRUE.equals(c.get("primaryKey")));
        if (!hasPk && !columns.isEmpty()) {
            LintIssue i = new LintIssue();
            i.ruleId = "R1"; i.tableName = tn; i.message = "表无主键";
            i.suggestion = "建议添加主键: ALTER TABLE " + tn + " ADD PRIMARY KEY(id)";
            issues.add(i);
        }
    }

    // R2: 表无注释
    private void checkTableNoComment(String tn, String comment, List<LintIssue> issues) {
        if (comment == null || comment.isEmpty()) {
            LintIssue i = new LintIssue();
            i.ruleId = "R2"; i.tableName = tn; i.message = "表缺少注释";
            i.suggestion = "COMMENT ON TABLE " + tn + " IS '请补充表注释'";
            issues.add(i);
        }
    }

    // R3: 字段无注释
    private void checkCommentCoverage(String tn, List<Map<String, Object>> columns, List<LintIssue> issues) {
        int noComment = 0;
        for (Map<String, Object> col : columns) {
            String c = (String) col.get("comment");
            if (c == null || c.isEmpty()) {
                noComment++;
                LintIssue i = new LintIssue();
                i.ruleId = "R3"; i.tableName = tn; i.columnName = (String) col.get("name");
                i.message = "字段 " + col.get("name") + " 缺少注释";
                i.suggestion = "COMMENT ON COLUMN " + tn + "." + col.get("name") + " IS '请补充注释'";
                issues.add(i);
            }
        }
        // R4: 注释覆盖率 < 50%
        if (!columns.isEmpty() && noComment > columns.size() / 2) {
            LintIssue i = new LintIssue();
            i.ruleId = "R4"; i.tableName = tn;
            i.message = "注释覆盖率仅 " + ((columns.size() - noComment) * 100 / columns.size()) + "% (" + (columns.size() - noComment) + "/" + columns.size() + ")";
            i.suggestion = "建议为关键字段补充注释";
            issues.add(i);
        }
    }

    // R5: 命名风格混用
    private static final Set<String> PINYIN_WORDS = new HashSet<>(Arrays.asList(
        "xiangmu", "beizhu", "zhuangtai", "shijian", "mingcheng", "leixing",
        "shezhi", "caozuo", "yonghu", "jiaose", "quanxian", "shuju",
        "bianhao", "riqi", "jine", "shuliang", "danwei", "beiz", "bz",
        "xmid", "xm", "zt", "lx", "sj", "mc", "rq", "je", "sl", "dw"
    ));
    private static final Set<String> SQL_RESERVED = new HashSet<>(Arrays.asList(
        "select", "from", "where", "order", "group", "by", "having", "join",
        "table", "index", "create", "drop", "alter", "add", "delete", "update",
        "insert", "into", "values", "set", "null", "not", "and", "or", "in", "like",
        "between", "case", "when", "then", "else", "end", "as", "on", "using",
        "primary", "key", "foreign", "references", "constraint", "unique", "check",
        "view", "trigger", "function", "procedure", "declare", "begin", "commit",
        "rollback", "grant", "revoke", "union", "intersect", "except", "all",
        "any", "some", "exists", "is", "distinct", "top", "limit", "offset",
        "asc", "desc", "user", "role", "schema", "database", "password"
    ));

    private void checkNamingStyle(String tn, List<Map<String, Object>> columns, List<LintIssue> issues) {
        int camelCount = 0, snakeCount = 0;
        for (Map<String, Object> col : columns) {
            String name = (String) col.get("name");
            if (name.contains("_")) snakeCount++; else if (name.matches(".*[a-z][A-Z].*")) camelCount++;
        }
        if (camelCount > 0 && snakeCount > 0) {
            LintIssue i = new LintIssue();
            i.ruleId = "R5"; i.tableName = tn;
            i.message = "命名风格混用: 驼峰" + camelCount + "个 + 下划线" + snakeCount + "个";
            i.suggestion = "建议统一使用下划线命名风格";
            issues.add(i);
        }
        // Check pinyin
        for (Map<String, Object> col : columns) {
            String name = ((String) col.get("name")).toLowerCase();
            for (String py : PINYIN_WORDS) {
                if (name.contains(py)) {
                    LintIssue i = new LintIssue();
                    i.ruleId = "R6"; i.tableName = tn; i.columnName = (String) col.get("name");
                    i.message = "字段名含拼音: " + col.get("name");
                    i.suggestion = "建议使用英文命名";
                    issues.add(i);
                    break;
                }
            }
        }
    }

    // R7: SQL 保留字
    private void checkReservedWord(String tn, List<Map<String, Object>> columns, List<LintIssue> issues) {
        // Check table name
        if (SQL_RESERVED.contains(tn.toLowerCase())) {
            LintIssue i = new LintIssue();
            i.ruleId = "R7"; i.tableName = tn; i.message = "表名 " + tn + " 是 SQL 保留字";
            i.suggestion = "建议重命名表，或使用引号包裹";
            issues.add(i);
        }
        for (Map<String, Object> col : columns) {
            String name = ((String) col.get("name")).toLowerCase();
            if (SQL_RESERVED.contains(name)) {
                LintIssue i = new LintIssue();
                i.ruleId = "R7"; i.tableName = tn; i.columnName = (String) col.get("name");
                i.message = "字段名 " + col.get("name") + " 是 SQL 保留字";
                i.suggestion = "建议重命名或使用引号包裹";
                issues.add(i);
            }
        }
    }

    // R8: 类型疑似不当
    private void checkTypeMismatch(String tn, List<Map<String, Object>> columns, List<LintIssue> issues) {
        for (Map<String, Object> col : columns) {
            String name = ((String) col.get("name")).toLowerCase();
            String type = ((String) col.get("dataType")).toLowerCase();

            if ((name.contains("phone") || name.contains("tel") || name.contains("mobile"))
                    && (type.contains("int") || type.contains("bigint") || type.contains("number"))) {
                addIssue(issues, "R8", tn, (String) col.get("name"),
                    "字段 " + col.get("name") + "(" + col.get("dataType") + ") 疑似存储手机号，建议使用 varchar",
                    "ALTER TABLE " + tn + " MODIFY " + col.get("name") + " varchar(20)");
            }
            if ((name.startsWith("is_") || name.startsWith("has_") || name.startsWith("can_") || name.contains("_flag"))
                    && (type.contains("varchar") || type.contains("char") || type.contains("text"))) {
                addIssue(issues, "R8", tn, (String) col.get("name"),
                    "字段 " + col.get("name") + "(" + col.get("dataType") + ") 疑似布尔字段，建议使用 tinyint(1)",
                    "ALTER TABLE " + tn + " MODIFY " + col.get("name") + " tinyint(1) DEFAULT 0");
            }
        }
    }

    // R9: varchar 无长度上限
    private void checkVarcharNoLimit(String tn, List<Map<String, Object>> columns, List<LintIssue> issues) {
        for (Map<String, Object> col : columns) {
            String type = ((String) col.get("dataType")).toLowerCase();
            int size;
            try {
                size = Integer.parseInt(String.valueOf(col.getOrDefault("columnSize", "0")));
            } catch (NumberFormatException e) {
                size = 0;
            }
            if ((type.contains("varchar") || type.contains("nvarchar")) && (size <= 0 || size > 8000)) {
                LintIssue i = new LintIssue();
                i.ruleId = "R9"; i.tableName = tn; i.columnName = (String) col.get("name");
                i.message = "字段 " + col.get("name") + " 的 varchar 缺少合理长度限制 (size=" + size + ")";
                i.suggestion = "建议指定合适的长度，如 varchar(255)";
                issues.add(i);
            }
        }
    }

    // R11: 外键字段无索引
    private void checkFkNoIndex(String tn, List<Map<String, String>> fks,
                                 List<Map<String, String>> indexes, List<LintIssue> issues) {
        if (indexes == null || indexes.isEmpty()) return;
        Set<String> indexedCols = indexes.stream()
            .map(idx -> idx.get("columnName")).collect(Collectors.toSet());
        for (Map<String, String> fk : fks) {
            if (!indexedCols.contains(fk.get("fkColumn"))) {
                LintIssue i = new LintIssue();
                i.ruleId = "R11"; i.tableName = tn; i.columnName = fk.get("fkColumn");
                i.message = "外键字段 " + fk.get("fkColumn") + " 缺少索引";
                i.suggestion = "CREATE INDEX idx_" + tn + "_" + fk.get("fkColumn")
                    + " ON " + tn + "(" + fk.get("fkColumn") + ")";
                issues.add(i);
            }
        }
    }

    // R12: 冗余索引
    private void checkRedundantIndex(String tn, List<Map<String, String>> indexes, List<LintIssue> issues) {
        Map<String, List<String>> idxCols = new LinkedHashMap<>();
        for (Map<String, String> idx : indexes) {
            idxCols.computeIfAbsent(idx.get("name"), k -> new ArrayList<>()).add(idx.get("columnName"));
        }
        List<String> idxNames = new ArrayList<>(idxCols.keySet());
        for (int i = 0; i < idxNames.size(); i++) {
            for (int j = i + 1; j < idxNames.size(); j++) {
                List<String> colsA = idxCols.get(idxNames.get(i));
                List<String> colsB = idxCols.get(idxNames.get(j));
                if (colsA.size() < colsB.size() && startsWith(colsB, colsA)) {
                    LintIssue issue = new LintIssue();
                    issue.ruleId = "R12"; issue.tableName = tn;
                    issue.message = "索引 " + idxNames.get(j) + " 可能冗余 (包含 " + idxNames.get(i) + " 的前缀列)";
                    issue.suggestion = "如果 " + idxNames.get(i) + " 已足够，可删除 " + idxNames.get(j);
                    issues.add(issue);
                }
            }
        }
    }

    private boolean startsWith(List<String> longer, List<String> shorter) {
        if (shorter.size() >= longer.size()) return false;
        for (int i = 0; i < shorter.size(); i++) {
            if (!Objects.equals(longer.get(i), shorter.get(i))) return false;
        }
        return true;
    }

    // R13: 单列表
    private void checkSingleColumnTable(String tn, List<Map<String, Object>> columns, List<LintIssue> issues) {
        if (columns.size() == 1) {
            LintIssue i = new LintIssue();
            i.ruleId = "R13"; i.tableName = tn;
            i.message = "仅 1 个字段，可能是过时的配置表";
            i.suggestion = "确认该表是否仍需保留";
            issues.add(i);
        }
    }

    // R14: 列数过多
    private void checkWideTable(String tn, List<Map<String, Object>> columns, List<LintIssue> issues) {
        if (columns.size() > 40) {
            LintIssue i = new LintIssue();
            i.ruleId = "R14"; i.tableName = tn;
            i.message = "列数过多: " + columns.size() + " 列，建议垂直拆分";
            i.suggestion = "将关联性弱的字段拆分到扩展表";
            issues.add(i);
        }
    }

    private void addIssue(List<LintIssue> issues, String ruleId, String tn, String colName, String msg, String sug) {
        LintIssue i = new LintIssue();
        i.ruleId = ruleId; i.tableName = tn; i.columnName = colName;
        i.message = msg; i.suggestion = sug;
        issues.add(i);
    }
}
