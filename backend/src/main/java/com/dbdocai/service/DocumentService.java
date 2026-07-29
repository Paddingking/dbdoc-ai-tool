package com.dbdocai.service;

import com.dbdocai.dto.DataSourceConfigDTO;
import com.dbdocai.llm.LlmAdapter;
import com.dbdocai.metadata.MetadataCollector;
import com.dbdocai.metadata.MetadataCollector.RoutineObject;
import com.dbdocai.util.CommentSqlUtil;
import com.dbdocai.util.HtmlEscapeUtil;
import com.dbdocai.util.JdbcUrlValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.*;

@Service
public class DocumentService {
    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);
    private final DataSourceStoreService storeService;
    private final LlmAdapter llmAdapter;
    private final DbStore dbStore;
    private final MetadataCollector collector = new MetadataCollector();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DocumentService(DataSourceStoreService storeService, LlmAdapter llmAdapter,
                           DbStore dbStore) {
        this.storeService = storeService;
        this.llmAdapter = llmAdapter;
        this.dbStore = dbStore;
    }

    public Map<String, Object> generateDocument(String dataSourceId, String schema, List<String> tableNames) {
        DataSourceConfigDTO ds = storeService.getWithSecret(dataSourceId);
        if (ds == null) throw new IllegalArgumentException("数据源不存在: " + dataSourceId);

        try (Connection conn = createConnection(ds)) {
            String effectiveSchema = schema != null ? schema : ds.getSchema();
            String catalog = effectiveSchema != null ? null : getCatalog(conn, ds);

            List<String> allTableNames = collector.getTableNames(conn, catalog, effectiveSchema);
            boolean namesOnly = (tableNames == null || tableNames.isEmpty());
            if (namesOnly) {
                tableNames = allTableNames;
            }

            List<Map<String, Object>> tables = new ArrayList<>();
            List<String> errors = new ArrayList<>();

            for (String tn : tableNames) {
                Map<String, Object> tableMap;
                try {
                    if (namesOnly) {
                        tableMap = createNamesOnlyEntry(tn, effectiveSchema);
                    } else {
                        tableMap = collectFullTable(conn, catalog, effectiveSchema, tn);
                    }
                    if (tableMap != null) {
                        tables.add(tableMap);
                    }
                } catch (Exception e) {
                    String msg = tn + ": " + e.getMessage();
                    errors.add(msg);
                    log.warn("Failed to collect table {}: {}", tn, e.getMessage());
                }
            }

            List<Map<String, Object>> modules = buildModules(tables);

            // Collect routines (P1-2)
            List<Map<String, Object>> routines = new ArrayList<>();
            try {
                for (RoutineObject r : collector.getRoutines(conn, catalog, effectiveSchema)) {
                    routines.add(r.toMap());
                }
            } catch (Exception e) {
                log.warn("Failed to collect routines: {}", e.getMessage());
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("dataSourceId", dataSourceId);
            result.put("tables", tables);
            result.put("modules", modules);
            result.put("routines", routines);
            result.put("generatedAt", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()));
            if (!errors.isEmpty()) {
                result.put("warnings", errors);
            }
            return result;
        } catch (SQLException e) {
            log.error("Failed to generate document: {}", e.getMessage());
            throw new RuntimeException("数据库读取失败: " + e.getMessage(), e);
        }
    }

    public Map<String, Object> autoGroupWithAi(String dataSourceId) {
        DataSourceConfigDTO ds = storeService.getWithSecret(dataSourceId);
        if (ds == null) throw new IllegalArgumentException("数据源不存在: " + dataSourceId);

        try (Connection conn = createConnection(ds)) {
            String effectiveSchema = ds.getSchema();

            String catalog = effectiveSchema != null ? null : getCatalog(conn, ds);

            List<String> allTableNames = collector.getTableNames(conn, catalog, effectiveSchema);
            if (allTableNames.isEmpty()) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("dataSourceId", dataSourceId);
                result.put("tables", Collections.emptyList());
                result.put("modules", Collections.emptyList());
                result.put("generatedAt", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()));
                return result;
            }

            // Build tables with old prefix-based grouping first
            List<Map<String, Object>> tables = new ArrayList<>();
            for (String tn : allTableNames) {
                tables.add(createNamesOnlyEntry(tn, effectiveSchema));
            }

            // Try AI grouping
            Map<String, String> aiGroups = aiGroupTables(allTableNames);
            if (aiGroups != null && !aiGroups.isEmpty()) {
                for (Map<String, Object> t : tables) {
                    String tn = (String) t.get("name");
                    String group = aiGroups.getOrDefault(tn, guessModule(tn));
                    t.put("moduleGroup", group);
                }
            }

            List<Map<String, Object>> modules = buildModules(tables);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("dataSourceId", dataSourceId);
            result.put("tables", tables);
            result.put("modules", modules);
            result.put("generatedAt", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()));
            if (aiGroups != null) result.put("aiGrouped", true);
            return result;
        } catch (SQLException e) {
            throw new RuntimeException("数据库读取失败: " + e.getMessage(), e);
        }
    }

    private Map<String, String> aiGroupTables(List<String> tableNames) {
        if (tableNames.size() > 200) {
            log.info("Too many tables ({}), skipping AI grouping", tableNames.size());
            return null;
        }
        try {
            String tableList = String.join("\n", tableNames.subList(0, Math.min(tableNames.size(), 200)));
            String systemPrompt = "你是一个数据库架构分析专家。请根据表名分析业务模块分组。\n"
                + "输入格式：每行一个表名\n"
                + "输出格式：严格输出JSON，每行格式为 {\"table\":\"表名\",\"group\":\"分组名\"}\n"
                + "规则：\n"
                + "1. 相同前缀或语义相关的表归为一组\n"
                + "2. 分组名用中文描述业务含义\n"
                + "3. 无法明确分类的表归入\"其他\"\n"
                + "只输出JSON数组，不要任何解释。";
            String userPrompt = tableList;
            String response = llmAdapter.generate(systemPrompt, userPrompt);

            // Parse JSON response
            String json = extractJson(response);
            if (json == null) return null;
            List<Map<String, String>> list = objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class));
            Map<String, String> result = new LinkedHashMap<>();
            for (Map<String, String> item : list) {
                result.put(item.get("table"), item.get("group"));
            }
            log.info("AI grouped {} tables into {} groups", result.size(),
                    new HashSet<>(result.values()).size());
            return result;
        } catch (Exception e) {
            log.warn("AI grouping failed, falling back to prefix: {}", e.getMessage());
            return null;
        }
    }

    private String extractJson(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        int i = t.indexOf("```json");
        if (i >= 0) {
            int s = t.indexOf('\n', i) + 1;
            int e = t.indexOf("```", s);
            if (e > s) return t.substring(s, e).trim();
        }
        int b1 = t.indexOf('[');
        int b2 = t.lastIndexOf(']');
        if (b1 >= 0 && b2 > b1) return t.substring(b1, b2 + 1);
        return null;
    }

    private Map<String, Object> createNamesOnlyEntry(String tableName, String schema) {
        Map<String, Object> tableMap = new LinkedHashMap<>();
        tableMap.put("name", tableName);
        tableMap.put("comment", "");
        tableMap.put("schema", schema != null ? schema : "");
        tableMap.put("engine", "");
        tableMap.put("columns", Collections.emptyList());
        tableMap.put("indexes", Collections.emptyList());
        tableMap.put("foreignKeys", Collections.emptyList());
        tableMap.put("enumInfos", Collections.emptyList());
        tableMap.put("moduleGroup", guessModule(tableName));
        return tableMap;
    }

    private Map<String, Object> collectFullTable(Connection conn, String catalog, String schema,
                                                  String tableName) throws SQLException {
        MetadataCollector.TableInfo info = collector.collectTable(conn, catalog, schema, tableName, true);
        if (info.name == null || info.name.isEmpty()) return null;

        Map<String, Object> tableMap = new LinkedHashMap<>();
        tableMap.put("name", info.name);
        tableMap.put("comment", info.comment != null ? info.comment : "");
        tableMap.put("schema", info.schema != null ? info.schema : "");
        tableMap.put("engine", info.engine != null ? info.engine : "");

        List<Map<String, Object>> columns = new ArrayList<>();
        List<Map<String, Object>> enumInfos = new ArrayList<>();
        for (MetadataCollector.ColumnInfo ci : info.columns) {
            Map<String, Object> colMap = new LinkedHashMap<>(ci.toMap());
            colMap.put("primaryKey", ci.primaryKey);

            if (ci.enumValues != null && !ci.enumValues.isEmpty()) {
                Map<String, Object> ei = new LinkedHashMap<>();
                ei.put("tableName", info.name);
                ei.put("columnName", ci.name);
                ei.put("values", ci.enumValues);
                ei.put("detected", true);
                enumInfos.add(ei);
                colMap.put("enumValues", ci.enumValues);
            }

            columns.add(colMap);
        }
        tableMap.put("columns", columns);
        tableMap.put("indexes", info.indexes);
        tableMap.put("foreignKeys", info.foreignKeys);
        tableMap.put("enumInfos", enumInfos);
        tableMap.put("moduleGroup", guessModule(tableName));

        return tableMap;
    }

    private List<Map<String, Object>> buildModules(List<Map<String, Object>> tables) {
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();

        for (Map<String, Object> t : tables) {
            String group = (String) t.getOrDefault("moduleGroup", "其他");
            groups.computeIfAbsent(group, k -> new ArrayList<>()).add(t);
        }

        // Sort groups: biggest first, then alphabetical
        List<Map.Entry<String, List<Map<String, Object>>>> sorted = new ArrayList<>(groups.entrySet());
        sorted.sort((a, b) -> {
            int sizeCmp = Integer.compare(b.getValue().size(), a.getValue().size());
            if (sizeCmp != 0) return sizeCmp;
            return a.getKey().compareTo(b.getKey());
        });

        List<Map<String, Object>> modules = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> entry : sorted) {
            Map<String, Object> mod = new LinkedHashMap<>();
            mod.put("name", entry.getKey());
            mod.put("tableNames", entry.getValue().stream().map(t -> t.get("name")).collect(java.util.stream.Collectors.toList()));
            mod.put("relations", detectRelations(entry.getValue()));
            modules.add(mod);
        }
        return modules;
    }

    private List<Map<String, String>> detectRelations(List<Map<String, Object>> tables) {
        List<Map<String, String>> relations = new ArrayList<>();
        Set<String> tableNames = new HashSet<>();
        for (Map<String, Object> t : tables) tableNames.add((String) t.get("name"));

        for (Map<String, Object> t : tables) {
            @SuppressWarnings("unchecked")
            List<Map<String, String>> fks = (List<Map<String, String>>) t.get("foreignKeys");
            if (fks != null) {
                for (Map<String, String> fk : fks) {
                    if (tableNames.contains(fk.get("pkTable"))) {
                        Map<String, String> rel = new LinkedHashMap<>();
                        rel.put("fromTable", (String) t.get("name"));
                        rel.put("fromColumn", fk.get("fkColumn"));
                        rel.put("toTable", fk.get("pkTable"));
                        rel.put("toColumn", fk.get("pkColumn"));
                        rel.put("type", "N:1");
                        relations.add(rel);
                    }
                }
            }
        }
        return relations;
    }

    private String guessModule(String tableName) {
        if (tableName == null || tableName.isEmpty()) return "未分类";
        // Multi-prefix: ce_rr_xxx -> ce_rr
        int idx1 = tableName.indexOf('_');
        if (idx1 <= 0) return "未分类";
        int idx2 = tableName.indexOf('_', idx1 + 1);
        if (idx2 > idx1 + 1 && idx2 - idx1 <= 4) {
            return tableName.substring(0, idx2); // ce_rr
        }
        return tableName.substring(0, idx1); // ce
    }

    /**
     * 建立数据库连接（重连点）。
     * P0-1（重连侧，双保险之二）：在真正 {@code DriverManager.getConnection} 之前，
     * 即使 URL 已是存储中净化过的，也再次 {@link JdbcUrlValidator#validate} 一次，
     * 确保任何来源（含历史脏数据）的恶意 JDBC URL 都不会触达驱动。
     */
    Connection createConnection(DataSourceConfigDTO ds) throws SQLException {
        String url = ds.getUrl();
        String username = ds.getUsername();
        String password = ds.getPassword();
        if (password == null || password.isEmpty()) password = "";

        if ("mysql".equalsIgnoreCase(ds.getDbType()) && !url.contains("useInformationSchema")) {
            url = url + (url.contains("?") ? "&" : "?") + "useInformationSchema=true";
        }

        // 双保险：重连前再次校验并净化 JDBC URL（剥离危险参数 + 强制超时）
        url = JdbcUrlValidator.validate(url);
        return DriverManager.getConnection(url, username, password);
    }

    private String getCatalog(Connection conn, DataSourceConfigDTO ds) throws SQLException {
        if ("postgresql".equalsIgnoreCase(ds.getDbType())) {
            return null;
        }
        return conn.getCatalog();
    }

    // ── AI Infer: 推断字段业务含义 ─────────────────────

    public List<Map<String, Object>> aiInferFields(String dataSourceId, List<String> tableNames) {
        DataSourceConfigDTO ds = storeService.getWithSecret(dataSourceId);
        if (ds == null) throw new IllegalArgumentException("数据源不存在");

        List<Map<String, Object>> results = new ArrayList<>();
        try (Connection conn = createConnection(ds)) {
            String effectiveSchema = ds.getSchema();
            String catalog = effectiveSchema != null ? null : getCatalog(conn, ds);

            // Quick LLM check
            try {
                llmAdapter.generate("Say OK", "OK");
            } catch (Exception e) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("tableName", "");
                err.put("columnName", "");
                err.put("description", "LLM调用失败，请检查LLM配置: " + e.getMessage());
                err.put("confidence", 0);
                results.add(err);
                return results;
            }

            for (String tn : tableNames) {
                MetadataCollector.TableInfo info;
                try {
                    info = collector.collectTable(conn, catalog, effectiveSchema, tn, false);
                } catch (Exception e) {
                    log.warn("Skip table {} for AI infer: {}", tn, e.getMessage());
                    continue;
                }
                if (info.name == null) continue;

                // Only infer columns WITHOUT existing comments, skip rejected ones
                List<MetadataCollector.ColumnInfo> targets = new ArrayList<>();
                Set<String> rejectedColumns = dbStore.getRejectedColumns(dataSourceId, tn);
                for (MetadataCollector.ColumnInfo ci : info.columns) {
                    if ((ci.comment == null || ci.comment.isEmpty()) && !rejectedColumns.contains(ci.name)) {
                        targets.add(ci);
                    }
                }
                if (targets.size() > 8) targets = new ArrayList<>(targets.subList(0, 8));

                if (targets.isEmpty()) continue;

                String prompt = buildInferPrompt(info.name, targets);
                try {
                    String sysPrompt = "你是数据库专家。根据字段名、类型、注释推断业务含义。对每个字段输出JSON：{\"columnName\":\"字段名\",\"description\":\"含义\",\"confidence\":0.9}。所有字段输出为一个JSON数组。只输出数组，不要解释。";
                    String resp = llmAdapter.generate(sysPrompt, prompt);
                    log.info("AI infer raw response length: {}", resp != null ? resp.length() : 0);
                    String json = extractJsonArray(resp);
                    if (json != null) {
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> batchResults = objectMapper.readValue(json,
                            objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class));
                        for (Map<String, Object> r : batchResults) {
                            r.put("tableName", info.name);
                            results.add(r);
                            // Save to DB for persistence
                            String desc = (String) r.get("description");
                            double conf = r.containsKey("confidence") ? ((Number) r.get("confidence")).doubleValue() : 0.9;
                            dbStore.saveAiInfer(dataSourceId, info.name,
                                (String) r.get("columnName"), desc, conf);
                        }
                        log.info("AI infer parsed {} results for {}", batchResults.size(), info.name);
                    } else {
                        log.warn("AI infer could not extract JSON from response: {}", resp != null ? resp.substring(0, Math.min(200, resp.length())) : "null");
                    }
                } catch (Exception e) {
                    log.warn("AI infer failed for {}: {}", tn, e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("AI推断失败: " + e.getMessage(), e);
        }
        return results;
    }

    public void confirmAiField(String dataSourceId, String tableName, String columnName, String description) {
        DataSourceConfigDTO ds = storeService.getWithSecret(dataSourceId);
        if (ds == null) throw new IllegalArgumentException("数据源不存在");
        try (Connection conn = createConnection(ds);
             Statement stmt = conn.createStatement()) {
            // P1-3 修复：按 dbType 分支构造列注释 SQL，标识符白名单防注入；
            // SQLite 不支持列注释时跳过（不抛错中断），仍标记 AI 推断为已接受。
            String sql = CommentSqlUtil.buildColumnCommentSql(conn, ds.getDbType(), tableName, columnName, description);
            if (sql != null) {
                stmt.execute(sql);
            } else {
                log.warn("数据源类型 {} 不支持列注释，跳过写库：{}.{}", ds.getDbType(), tableName, columnName);
            }
            dbStore.updateAiInferStatus(dataSourceId, tableName, columnName, "accepted");
            log.info("Updated comment for {}.{}: {}", tableName, columnName, description);
        } catch (SQLException e) {
            throw new RuntimeException("更新数据库注释失败: " + e.getMessage(), e);
        }
    }

    public int confirmAiFieldBatch(String dataSourceId, List<Map<String, String>> items) {
        DataSourceConfigDTO ds = storeService.getWithSecret(dataSourceId);
        if (ds == null) throw new IllegalArgumentException("数据源不存在");
        int count = 0;
        try (Connection conn = createConnection(ds);
             Statement stmt = conn.createStatement()) {
            for (Map<String, String> item : items) {
                String tableName = item.get("tableName");
                String columnName = item.get("columnName");
                String description = item.get("description");
                // P1-3 修复：按 dbType 分支构造列注释 SQL，标识符白名单防注入；
                // SQLite 不支持列注释时 buildColumnCommentSql 返回 null，跳过该列但仍标记已接受。
                String sql = CommentSqlUtil.buildColumnCommentSql(conn, ds.getDbType(), tableName, columnName, description);
                if (sql != null) {
                    stmt.execute(sql);
                    count++;
                } else {
                    log.warn("数据源类型 {} 不支持列注释，跳过写库：{}.{}", ds.getDbType(), tableName, columnName);
                }
                dbStore.updateAiInferStatus(dataSourceId, tableName, columnName, "accepted");
            }
            log.info("Batch updated {} comments for {}", count, dataSourceId);
        } catch (SQLException e) {
            throw new RuntimeException("批量更新注释失败: " + e.getMessage(), e);
        }
        return count;
    }

    public void rejectAiFields(String dataSourceId, List<Map<String, String>> items) {
        for (Map<String, String> item : items) {
            dbStore.updateAiInferStatus(dataSourceId, item.get("tableName"), item.get("columnName"), "rejected");
        }
    }

    public void discardPendingAiInfer(String dataSourceId, List<String> tableNames) {
        dbStore.deletePendingAiInfer(dataSourceId, tableNames);
    }

    private String buildInferPrompt(String tableName, List<MetadataCollector.ColumnInfo> columns) {
        StringBuilder sb = new StringBuilder();
        sb.append("表: ").append(tableName).append(" 字段:\n");
        for (MetadataCollector.ColumnInfo ci : columns) {
            sb.append(ci.name).append("(").append(ci.dataType).append(")")
              .append(ci.primaryKey ? " PK" : "")
              .append(ci.comment != null && !ci.comment.isEmpty() ? " 注释:" + ci.comment : "")
              .append("\n");
        }
        return sb.toString();
    }

    private String extractJsonArray(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        int i = t.indexOf("```json");
        if (i >= 0) {
            int s = t.indexOf('\n', i) + 1;
            int e = t.indexOf("```", s);
            if (e > s) t = t.substring(s, e).trim();
        }
        int b1 = t.indexOf('[');
        int b2 = t.lastIndexOf(']');
        if (b1 >= 0 && b2 > b1) return t.substring(b1, b2 + 1);
        return null;
    }

    // ── Export ────────────────────────────────────────

    public String exportHtml(String dataSourceId, List<String> tableNames) {
        Map<String, Object> doc = generateDocument(dataSourceId, null, tableNames);
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"UTF-8\">");
        html.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">");
        html.append("<title>数据库文档</title><style>");
        html.append("*{margin:0;padding:0;box-sizing:border-box}");
        html.append("body{font-family:-apple-system,BlinkMacSystemFont,'Microsoft YaHei',sans-serif;background:#1a1a2e;color:#e8e8ff;padding:20px}");
        html.append("h1{color:#7b68ee;margin-bottom:4px}h2{color:#7b68ee;margin:20px 0 10px;border-bottom:1px solid #2a2a4a;padding-bottom:6px}");
        html.append("table{width:100%;border-collapse:collapse;margin:8px 0 20px;font-size:14px}");
        html.append("th{background:#0f3460;text-align:left;padding:8px 10px;font-weight:600}");
        html.append("td{padding:6px 10px;border-bottom:1px solid #2a2a4a}");
        html.append(".meta{color:#ccccdd;font-size:13px;margin-bottom:20px}");
        html.append(".pk{color:#ffd43b}.type{color:#7b68ee;font-family:monospace;font-size:13px}");
        html.append(".comment{color:#ccccdd}.enum{color:#51cf66;font-size:12px}");
        html.append(".section{margin-bottom:24px}</style></head><body>");
        html.append("<h1>数据库文档</h1>");
        html.append("<div class=\"meta\">生成时间: ").append(doc.get("generatedAt"))
            .append(" | 表数: ").append(((List<?>) doc.get("tables")).size()).append("</div>");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tables = (List<Map<String, Object>>) doc.get("tables");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> modules = (List<Map<String, Object>>) doc.get("modules");

        for (Map<String, Object> mod : modules) {
            html.append("<div class=\"section\"><h2>").append(HtmlEscapeUtil.escape(String.valueOf(mod.get("name")))).append("</h2>");
            @SuppressWarnings("unchecked")
            List<String> modTableNames = (List<String>) mod.get("tableNames");
            Set<String> modSet = new HashSet<>(modTableNames);
            for (Map<String, Object> table : tables) {
                String tn = (String) table.get("name");
                if (!modSet.contains(tn)) continue;
                String comment = (String) table.get("comment");
                html.append("<h3>").append(HtmlEscapeUtil.escape(tn));
                if (comment != null && !comment.isEmpty()) html.append(" — ").append(HtmlEscapeUtil.escape(comment));
                html.append("</h3><table><tr><th>#</th><th>字段名</th><th>类型</th><th>必填</th><th>说明</th></tr>");

                @SuppressWarnings("unchecked")
                List<Map<String, Object>> columns = (List<Map<String, Object>>) table.get("columns");
                if (columns != null) {
                    for (Map<String, Object> col : columns) {
                        html.append("<tr>");
                        html.append("<td>").append(col.get("ordinalPosition")).append("</td>");
                        html.append("<td>");
                        if (Boolean.TRUE.equals(col.get("primaryKey"))) html.append("<span class=\"pk\">PK</span> ");
                        html.append(HtmlEscapeUtil.escape(String.valueOf(col.get("name")))).append("</td>");
                        html.append("<td class=\"type\">").append(HtmlEscapeUtil.escape(String.valueOf(col.get("dataType")))).append("</td>");
                        html.append("<td>").append(Boolean.TRUE.equals(col.get("nullable")) ? "" : "NOT NULL").append("</td>");
                        String colComment = (String) col.get("comment");
                        html.append("<td class=\"comment\">").append(HtmlEscapeUtil.escape(colComment != null ? colComment : "-"));

                        @SuppressWarnings("unchecked")
                        List<String> enumVals = (List<String>) col.get("enumValues");
                        if (enumVals != null && !enumVals.isEmpty()) {
                            StringBuilder enumSb = new StringBuilder();
                            for (int ei = 0; ei < enumVals.size(); ei++) {
                                if (ei > 0) enumSb.append(", ");
                                enumSb.append(HtmlEscapeUtil.escape(enumVals.get(ei)));
                            }
                            html.append("<br><span class=\"enum\">枚举: ").append(enumSb).append("</span>");
                        }
                        html.append("</td></tr>");
                    }
                }
                html.append("</table>");
            }
            html.append("</div>");
        }
        html.append("</body></html>");

        String userHome = System.getProperty("user.home");
        File outDir = new File(userHome, ".dbdoc-ai/exports");
        if (!outDir.exists()) outDir.mkdirs();
        File outFile = resolveExportFile(dataSourceId, ".html");
        try {
            java.nio.file.Files.write(outFile.toPath(), html.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return outFile.getAbsolutePath();
        } catch (Exception e) {
            throw new RuntimeException("导出失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解析并校验导出文件路径，防御路径穿越（P0-3）。
     *
     * <p>要求 {@code dataSourceId} 必须为合法 UUID（32 位十六进制），否则直接拒绝导出；
     * 再用 {@link Paths} 归一化并断言解析结果仍位于导出目录内，越界抛 {@link SecurityException}。
     * 文件名截断使用 {@code Math.min(8, id.length())}，避免短 id 触发 {@code substring(0,8)} 越界（fullcheck #26）。
     *
     * @param dataSourceId 数据源 ID（应为 32 位十六进制 UUID）
     * @param ext          文件扩展名（含点，如 ".html"）
     * @return 经过校验的绝对文件对象
     */
    File resolveExportFile(String dataSourceId, String ext) {
        if (dataSourceId == null || !dataSourceId.matches("[0-9a-fA-F]{32}")) {
            throw new IllegalArgumentException("非法的数据源 ID，拒绝导出: " + dataSourceId);
        }
        String safeName = "dbdoc-" + dataSourceId.substring(0, Math.min(8, dataSourceId.length())) + ext;
        Path outDir = Paths.get(System.getProperty("user.home"), ".dbdoc-ai", "exports")
                .toAbsolutePath().normalize();
        File dir = outDir.toFile();
        if (!dir.exists() && !dir.mkdirs()) {
            throw new RuntimeException("无法创建导出目录: " + outDir);
        }
        Path resolved = outDir.resolve(safeName).normalize();
        if (!resolved.startsWith(outDir)) {
            throw new SecurityException("导出路径越界，已阻断: " + resolved);
        }
        return resolved.toFile();
    }

    public String exportMarkdown(String dataSourceId, List<String> tableNames) {
        Map<String, Object> doc = generateDocument(dataSourceId, null, tableNames);
        StringBuilder md = new StringBuilder();
        md.append("# 数据库文档\n\n");
        md.append("生成时间: ").append(doc.get("generatedAt")).append("\n\n");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> modules = (List<Map<String, Object>>) doc.get("modules");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tables = (List<Map<String, Object>>) doc.get("tables");

        for (Map<String, Object> mod : modules) {
            md.append("## ").append(mod.get("name")).append("\n\n");
            @SuppressWarnings("unchecked")
            List<String> modTableNames = (List<String>) mod.get("tableNames");
            Set<String> modSet = new HashSet<>(modTableNames);
            for (Map<String, Object> table : tables) {
                String tn = (String) table.get("name");
                if (!modSet.contains(tn)) continue;
                String comment = (String) table.get("comment");
                md.append("### ").append(tn);
                if (comment != null && !comment.isEmpty()) md.append(" — ").append(comment);
                md.append("\n\n");
                md.append("| # | 字段名 | 类型 | 必填 | 说明 |\n");
                md.append("|---|--------|------|------|------|\n");

                @SuppressWarnings("unchecked")
                List<Map<String, Object>> columns = (List<Map<String, Object>>) table.get("columns");
                if (columns != null) {
                    for (Map<String, Object> col : columns) {
                        String cn = String.valueOf(col.get("name"));
                        if (Boolean.TRUE.equals(col.get("primaryKey"))) cn = "🔑 " + cn;
                        String colComment = (String) col.get("comment");
                        md.append("| ").append(col.get("ordinalPosition"))
                          .append(" | ").append(cn)
                          .append(" | ").append(col.get("dataType"))
                          .append(" | ").append(Boolean.TRUE.equals(col.get("nullable")) ? "" : "NOT NULL")
                          .append(" | ").append(colComment != null ? colComment : "-")
                          .append(" |\n");
                    }
                }
                md.append("\n");
            }
        }

        String userHome = System.getProperty("user.home");
        File outDir = new File(userHome, ".dbdoc-ai/exports");
        if (!outDir.exists()) outDir.mkdirs();
        File outFile = resolveExportFile(dataSourceId, ".md");
        try {
            java.nio.file.Files.write(outFile.toPath(), md.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return outFile.getAbsolutePath();
        } catch (Exception e) {
            throw new RuntimeException("导出失败: " + e.getMessage(), e);
        }
    }

    // ── AI Routine Summarization (P1-2) ──────────────

    public List<Map<String, Object>> aiSummarizeRoutines(String dataSourceId, String schema, List<String> routineNames) {
        DataSourceConfigDTO ds = storeService.getWithSecret(dataSourceId);
        if (ds == null) throw new IllegalArgumentException("数据源不存在");
        String effectiveSchema = schema != null ? schema : ds.getSchema();

        List<Map<String, Object>> results = new ArrayList<>();
        try (Connection conn = createConnection(ds)) {
            String catalog = effectiveSchema != null ? null : getCatalog(conn, ds);
            Map<String, RoutineObject> routineMap = new LinkedHashMap<>();
            for (RoutineObject r : collector.getRoutines(conn, catalog, effectiveSchema)) {
                routineMap.put(r.name, r);
            }

            for (String name : routineNames) {
                RoutineObject r = routineMap.get(name);
                if (r == null || r.definition == null) continue;

                String ddl = r.definition.length() > 4000 ? r.definition.substring(0, 4000) : r.definition;
                String prompt = "请用中文简要解读以下" + r.type + "的业务逻辑（30字以内）：\n" + ddl;
                String sysPrompt = "你是数据库专家。简洁解读SQL的业务含义。只输出解读，不要解释。";
                try {
                    String summary = llmAdapter.generate(sysPrompt, prompt);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", name);
                    m.put("type", r.type);
                    m.put("summary", summary != null ? summary.trim() : "");
                    results.add(m);
                } catch (Exception e) {
                    log.warn("AI summarize failed for {}: {}", name, e.getMessage());
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("AI解读失败: " + e.getMessage(), e);
        }
        return results;
    }

    // ── AI Chat (P3-1) ───────────────────────────────

    public String aiChat(String dataSourceId, String schema, String question) {
        String summary = buildSchemaSummaryForChat(dataSourceId, schema);
        String sysPrompt = "你是 DBDoc AI 助手，帮助用户理解数据库结构。可以回答表结构、关联关系、业务域归类、字段含义等问题。\n"
            + "根据提供的数据库结构摘要回答。如果信息不足，诚实说明。回答要简洁（3-5句话）。用中文回答。";
        return llmAdapter.generate(sysPrompt, "【数据库结构】\n" + summary + "\n\n【问题】\n" + question);
    }

    private String buildSchemaSummaryForChat(String dataSourceId, String schema) {
        try {
            Map<String, Object> doc = generateDocument(dataSourceId, schema, null);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> modules = (List<Map<String, Object>>) doc.get("modules");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> tables = (List<Map<String, Object>>) doc.get("tables");

            Map<String, Map<String, Object>> tableMap = new LinkedHashMap<>();
            for (Map<String, Object> t : tables) tableMap.put((String) t.get("name"), t);

            StringBuilder sb = new StringBuilder();
            sb.append("Schema: ").append(schema).append(" (").append(tables.size()).append("表)\n");
            int budget = 3000;
            for (Map<String, Object> mod : modules) {
                @SuppressWarnings("unchecked")
                List<String> tns = (List<String>) mod.get("tableNames");
                sb.append("模块[")
                  .append(mod.get("name")).append("]:").append(tns.size()).append("表 ");
                int show = Math.min(3, tns.size());
                for (int i = 0; i < show; i++) {
                    Map<String, Object> t = tableMap.get(tns.get(i));
                    if (t != null) {
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> cols = (List<Map<String, Object>>) t.get("columns");
                        sb.append(tns.get(i)).append("(").append(cols.size()).append("列) ");
                        for (Map<String, Object> c : cols.subList(0, Math.min(5, cols.size()))) {
                            if (Boolean.TRUE.equals(c.get("primaryKey")))
                                sb.append("PK=").append(c.get("name")).append(" ");
                        }
                    }
                }
                sb.append("; ");
                if (sb.length() > budget) break;
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("Failed to build schema summary: {}", e.getMessage());
            return "Schema summary unavailable";
        }
    }
}
