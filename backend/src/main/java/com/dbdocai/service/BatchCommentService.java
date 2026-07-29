package com.dbdocai.service;

import com.dbdocai.llm.LlmAdapter;
import com.dbdocai.util.CommentSqlUtil;
import com.dbdocai.util.JdbcUrlValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class BatchCommentService {
    private static final Logger log = LoggerFactory.getLogger(BatchCommentService.class);
    private final DocumentService documentService;
    private final DataSourceStoreService storeService;
    private final LlmAdapter llmAdapter;

    public BatchCommentService(DocumentService documentService, DataSourceStoreService storeService, LlmAdapter llmAdapter) {
        this.documentService = documentService;
        this.storeService = storeService;
        this.llmAdapter = llmAdapter;
    }

    public static class CommentRule {
        public String id;
        public String name;
        public String pattern;
        public String patternType;  // prefix / suffix / contains / regex
        public String typeFilter;
        public String template;
        public boolean enabled = true;
    }

    public static class MatchResult {
        public String tableName;
        public String columnName;
        public String dataType;
        public String ruleId;
        public String currentComment;
        public String newComment;
    }

    public static class PreviewResult {
        public List<MatchResult> matches;
        public int totalMatched;
        public int alreadyCommented;
        public int willWrite;
    }

    private static final Map<String, String> EN_CN_MAP = new LinkedHashMap<>();
    static {
        EN_CN_MAP.put("deleted", "删除"); EN_CN_MAP.put("active", "激活"); EN_CN_MAP.put("locked", "锁定");
        EN_CN_MAP.put("hidden", "隐藏"); EN_CN_MAP.put("valid", "有效"); EN_CN_MAP.put("expired", "过期");
        EN_CN_MAP.put("time", "时间"); EN_CN_MAP.put("at", "时间"); EN_CN_MAP.put("date", "日期");
        EN_CN_MAP.put("count", "数量"); EN_CN_MAP.put("amount", "金额"); EN_CN_MAP.put("price", "价格");
        EN_CN_MAP.put("name", "名称"); EN_CN_MAP.put("code", "编码"); EN_CN_MAP.put("type", "类型");
        EN_CN_MAP.put("status", "状态"); EN_CN_MAP.put("level", "级别"); EN_CN_MAP.put("source", "来源");
        EN_CN_MAP.put("description", "描述"); EN_CN_MAP.put("remark", "备注"); EN_CN_MAP.put("id", "ID");
        EN_CN_MAP.put("user", "用户"); EN_CN_MAP.put("order", "订单"); EN_CN_MAP.put("product", "产品");
        EN_CN_MAP.put("create", "创建"); EN_CN_MAP.put("update", "更新"); EN_CN_MAP.put("delete", "删除");
        EN_CN_MAP.put("total", "总"); EN_CN_MAP.put("view", "查看"); EN_CN_MAP.put("image", "图片");
        EN_CN_MAP.put("file", "文件"); EN_CN_MAP.put("url", "链接"); EN_CN_MAP.put("content", "内容");
        EN_CN_MAP.put("title", "标题"); EN_CN_MAP.put("mobile", "手机"); EN_CN_MAP.put("phone", "电话");
        EN_CN_MAP.put("email", "邮箱"); EN_CN_MAP.put("address", "地址"); EN_CN_MAP.put("ip", "IP");
    }

    public static List<CommentRule> getDefaultRules() {
        List<CommentRule> rules = new ArrayList<>();
        rules.add(newRule("R1", "is_* 布尔字段", "is_", "prefix", "tinyint", "是否${desc}"));
        rules.add(newRule("R2", "has_* 布尔字段", "has_", "prefix", "tinyint", "是否有${desc}"));
        rules.add(newRule("R3", "*_id 外键字段", "_id", "suffix", null, "${desc}ID"));
        rules.add(newRule("R4", "*_time 时间字段", "_time", "suffix", null, "${desc}时间"));
        rules.add(newRule("R5", "*_at 时间字段", "_at", "suffix", null, "${desc}时间"));
        rules.add(newRule("R6", "create_* 创建字段", "create_", "prefix", null, "创建${desc}"));
        rules.add(newRule("R7", "update_* 更新字段", "update_", "prefix", null, "更新${desc}"));
        rules.add(newRule("R8", "*_count 数量字段", "_count", "suffix", null, "${desc}数量"));
        rules.add(newRule("R9", "*_amount 金额字段", "_amount", "suffix", null, "${desc}金额"));
        rules.add(newRule("R10", "*_remark 备注字段", "_remark", "suffix", null, "${desc}备注"));
        return rules;
    }

    private static CommentRule newRule(String id, String name, String pattern, String patternType, String typeFilter, String template) {
        CommentRule r = new CommentRule();
        r.id = id; r.name = name; r.pattern = pattern; r.patternType = patternType;
        r.typeFilter = typeFilter; r.template = template; r.enabled = true;
        return r;
    }

    public PreviewResult preview(List<CommentRule> rules, String dataSourceId, String schema, List<String> tableNames) {
        Map<String, Object> doc = documentService.generateDocument(dataSourceId, schema, tableNames);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tables = (List<Map<String, Object>>) doc.get("tables");

        List<MatchResult> matches = new ArrayList<>();
        List<CommentRule> enabledRules = rules.stream().filter(r -> r.enabled).collect(Collectors.toList());

        for (Map<String, Object> table : tables) {
            String tn = (String) table.get("name");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> columns = (List<Map<String, Object>>) table.get("columns");
            for (Map<String, Object> col : columns) {
                String cn = (String) col.get("name");
                String dt = (String) col.get("dataType");
                String existing = (String) col.get("comment");
                if (existing != null && !existing.isEmpty()) continue; // skip already commented

                for (CommentRule rule : enabledRules) {
                    if (matchesRule(cn, dt, rule)) {
                        MatchResult mr = new MatchResult();
                        mr.tableName = tn; mr.columnName = cn; mr.dataType = dt;
                        mr.ruleId = rule.id; mr.currentComment = existing;
                        mr.newComment = renderTemplate(rule.template, cn, tn, rule);
                        matches.add(mr);
                        break; // first match wins
                    }
                }
            }
        }

        PreviewResult pr = new PreviewResult();
        pr.matches = matches;
        pr.totalMatched = matches.size();
        pr.alreadyCommented = 0;
        pr.willWrite = matches.size();
        return pr;
    }

    public int execute(List<CommentRule> rules, String dataSourceId, String schema, List<String> tableNames) {
        PreviewResult preview = preview(rules, dataSourceId, schema, tableNames);
        com.dbdocai.dto.DataSourceConfigDTO ds = storeService.getWithSecret(dataSourceId);
        if (ds == null) throw new IllegalArgumentException("数据源不存在: " + dataSourceId);
        int written = 0;
        // P0-1 双保险：写库前重连再次校验并净化 JDBC URL，阻断恶意 URL 触发的 RCE。
        String safeUrl = JdbcUrlValidator.validate(ds.getUrl());
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection(
                safeUrl, ds.getUsername(),
                ds.getPassword() != null ? ds.getPassword() : "");
             java.sql.Statement stmt = conn.createStatement()) {
            for (MatchResult mr : preview.matches) {
                try {
                    // P1-3 修复：按 dbType 分支构造列注释 SQL，标识符白名单防注入；
                    // SQLite 不支持列注释时 buildColumnCommentSql 返回 null，跳过该列。
                    String sql = CommentSqlUtil.buildColumnCommentSql(conn, ds.getDbType(), mr.tableName, mr.columnName, mr.newComment);
                    if (sql == null) {
                        log.warn("数据源类型 {} 不支持列注释，跳过写库：{}.{}", ds.getDbType(), mr.tableName, mr.columnName);
                        continue;
                    }
                    stmt.execute(sql);
                    written++;
                } catch (Exception e) {
                    log.warn("Failed to comment {}.{}: {}", mr.tableName, mr.columnName, e.getMessage());
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("批量写入注释失败: " + e.getMessage(), e);
        }
        return written;
    }

    private boolean matchesRule(String columnName, String dataType, CommentRule rule) {
        if (rule.typeFilter != null && !rule.typeFilter.isEmpty()
                && dataType != null && !dataType.toLowerCase().contains(rule.typeFilter.toLowerCase())) return false;

        String name = columnName.toLowerCase();
        String pattern = rule.pattern.toLowerCase();
        switch (rule.patternType) {
            case "prefix": return name.startsWith(pattern);
            case "suffix": return name.endsWith(pattern);
            case "contains": return name.contains(pattern);
            case "regex": return Pattern.compile(pattern).matcher(name).find();
            default: return false;
        }
    }

    private String renderTemplate(String template, String columnName, String tableName, CommentRule rule) {
        String result = template;
        String desc = deriveDesc(columnName, rule);
        result = result.replace("${desc}", translateEnToCn(desc));
        result = result.replace("${table}", translateEnToCn(tableName));
        return result;
    }

    private String deriveDesc(String columnName, CommentRule rule) {
        String name = columnName.toLowerCase();
        if ("prefix".equals(rule.patternType)) return name.substring(rule.pattern.length());
        if ("suffix".equals(rule.patternType)) return name.substring(0, name.length() - rule.pattern.length());
        if ("regex".equals(rule.patternType)) {
            Matcher m = Pattern.compile(rule.pattern).matcher(name);
            if (m.find() && m.groupCount() >= 1) return m.group(1);
        }
        return name;
    }

    public static String translateEnToCn(String word) {
        if (word == null) return "";
        String lower = word.toLowerCase();
        // Try word-by-word translation
        StringBuilder sb = new StringBuilder();
        String[] parts = lower.split("[_\\-]");
        for (String part : parts) {
            if (EN_CN_MAP.containsKey(part)) sb.append(EN_CN_MAP.get(part));
            else sb.append(part);
        }
        return sb.toString();
    }
}
