package com.dbdocai.service;

import com.dbdocai.config.LlmConfig;
import com.dbdocai.config.LlmProperties;
import com.dbdocai.dto.DataSourceConfigDTO;
import com.dbdocai.llm.LlmAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 字段映射服务：提供同名匹配（aiMatchFields）、AI 语义匹配（aiSemanticMatch）、
 * 表映射自动探测（autoDetectTableMappings）以及 Informatica XML 导出（exportInfaXml）。
 *
 * <p>本次合并自桌面副本的能力：{@link #aiSemanticMatch} 与 {@link MatchResult#fromMap}，
 * 复用主线 {@link LlmConfig#buildAdapter} 每次重建 LLM 适配器（密钥经 {@code LlmConfigService}
 * 在运行时解密注入 {@link LlmProperties}），LLM 调用失败时安全降级为同名匹配结果。
 */
@Service
public class FieldMappingService {
    private static final Logger log = LoggerFactory.getLogger(FieldMappingService.class);
    private final DocumentService documentService;
    private final DataSourceStoreService storeService;
    private final LlmProperties llmProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public FieldMappingService(DocumentService documentService, DataSourceStoreService storeService,
                               LlmProperties llmProperties) {
        this.documentService = documentService;
        this.storeService = storeService;
        this.llmProperties = llmProperties;
    }

    public static class FieldMapping {
        public String sourceTable;
        public String sourceColumn;
        public String sourceType;
        public String targetTable;
        public String targetColumn;
        public String targetType;
        public String status;         // matched / ai_matched / conflict / source_only / target_only
        public double confidence;     // AI match confidence
        public String transformRule;  // expression for infa
    }

    public static class MatchResult {
        public List<FieldMapping> mappings;
        public int matchedCount;
        public int aiMatchedCount;
        public int conflictCount;

        /**
         * 将前端回传的原始 Map 解码为 MatchResult（用于割接 / infa-xml 复用预构建映射）。
         * 字段名与 {@link FieldMapping} 保持一致，缺失字段以默认值填充，单个字段解码失败不影响整体。
         */
        @SuppressWarnings("unchecked")
        public static MatchResult fromMap(Map<String, Object> raw) {
            MatchResult r = new MatchResult();
            r.matchedCount = asInt(raw.get("matchedCount"));
            r.aiMatchedCount = asInt(raw.get("aiMatchedCount"));
            r.conflictCount = asInt(raw.get("conflictCount"));
            r.mappings = new ArrayList<>();
            Object list = raw.get("mappings");
            if (list instanceof List) {
                for (Object item : (List<Object>) list) {
                    if (!(item instanceof Map)) continue;
                    Map<String, Object> m = (Map<String, Object>) item;
                    FieldMapping fm = new FieldMapping();
                    fm.sourceTable = (String) m.get("sourceTable");
                    fm.sourceColumn = (String) m.get("sourceColumn");
                    fm.sourceType = (String) m.get("sourceType");
                    fm.targetTable = (String) m.get("targetTable");
                    fm.targetColumn = (String) m.get("targetColumn");
                    fm.targetType = (String) m.get("targetType");
                    fm.status = (String) m.get("status");
                    Object conf = m.get("confidence");
                    fm.confidence = conf instanceof Number ? ((Number) conf).doubleValue() : 0.0;
                    fm.transformRule = (String) m.get("transformRule");
                    r.mappings.add(fm);
                }
            }
            return r;
        }

        private static int asInt(Object o) {
            return o instanceof Number ? ((Number) o).intValue() : 0;
        }
    }

    public List<Map<String, String>> autoDetectTableMappings(String dsIdA, String schemaA,
                                                              String dsIdB, String schemaB) {
        Map<String, Object> docA = documentService.generateDocument(dsIdA, schemaA, null);
        Map<String, Object> docB = documentService.generateDocument(dsIdB, schemaB, null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tablesA = (List<Map<String, Object>>) docA.get("tables");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tablesB = (List<Map<String, Object>>) docB.get("tables");

        Set<String> namesB = tablesB.stream().map(t -> ((String) t.get("name")).toLowerCase()).collect(Collectors.toSet());
        List<Map<String, String>> result = new ArrayList<>();
        for (Map<String, Object> ta : tablesA) {
            String nameA = (String) ta.get("name");
            if (namesB.contains(nameA.toLowerCase())) {
                Map<String, String> m = new LinkedHashMap<>();
                m.put("sourceTable", nameA);
                // 找到匹配的目标表
                for (Map<String, Object> tb : tablesB) {
                    if (nameA.equalsIgnoreCase((String) tb.get("name"))) {
                        m.put("targetTable", (String) tb.get("name"));
                        break;
                    }
                }
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> cols = (List<Map<String, Object>>) ta.get("columns");
                m.put("sourceColumns", String.valueOf(cols.size()));
                result.add(m);
            }
        }
        return result;
    }

    public MatchResult aiMatchFields(String dsIdA, String schemaA, String tableA,
                                      String dsIdB, String schemaB, String tableB) {
        Map<String, Object> docA = documentService.generateDocument(dsIdA, schemaA, java.util.Arrays.asList(tableA));
        Map<String, Object> docB = documentService.generateDocument(dsIdB, schemaB, java.util.Arrays.asList(tableB));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tsA = (List<Map<String, Object>>) docA.get("tables");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tsB = (List<Map<String, Object>>) docB.get("tables");

        if (tsA.isEmpty() || tsB.isEmpty()) return new MatchResult();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> colsA = (List<Map<String, Object>>) tsA.get(0).get("columns");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> colsB = (List<Map<String, Object>>) tsB.get(0).get("columns");

        MatchResult result = new MatchResult();
        result.mappings = new ArrayList<>();
        Set<String> matchedB = new HashSet<>();

        // P1: 同名精确匹配
        Map<String, Map<String, Object>> mapB = new LinkedHashMap<>();
        for (Map<String, Object> cb : colsB) mapB.put(((String) cb.get("name")).toLowerCase(), cb);

        for (Map<String, Object> ca : colsA) {
            String nameA = (String) ca.get("name");
            Map<String, Object> cb = mapB.get(nameA.toLowerCase());
            FieldMapping fm = new FieldMapping();
            fm.sourceTable = tableA; fm.sourceColumn = nameA;
            fm.sourceType = (String) ca.get("dataType");
            fm.targetTable = tableB;
            if (cb != null) {
                fm.targetColumn = (String) cb.get("name");
                fm.targetType = (String) cb.get("dataType");
                fm.status = Objects.equals(fm.sourceType, fm.targetType) ? "matched" : "conflict";
                fm.confidence = Objects.equals(fm.sourceType, fm.targetType) ? 1.0 : 0.7;
                matchedB.add(fm.targetColumn.toLowerCase());
                result.matchedCount++;
            } else {
                fm.status = "source_only";
                fm.confidence = 0;
            }
            result.mappings.add(fm);
        }
        // 目标独有列
        for (Map<String, Object> cb : colsB) {
            if (!matchedB.contains(((String) cb.get("name")).toLowerCase())) {
                FieldMapping fm = new FieldMapping();
                fm.sourceTable = tableA; fm.targetTable = tableB;
                fm.targetColumn = (String) cb.get("name");
                fm.targetType = (String) cb.get("dataType");
                fm.status = "target_only";
                fm.confidence = 0;
                result.mappings.add(fm);
            }
        }
        return result;
    }

    /**
     * 对同名匹配未命中的字段调用 LLM 做语义匹配。
     *
     * <p>输入：基于 {@link #aiMatchFields} 同名匹配结果中的 source_only / target_only 字段。
     * 输出：合并后的 MatchResult，新增 status="ai_matched" 的字段，并累加 {@code aiMatchedCount}。
     *
     * <p>失败安全：LLM 调用异常、无可匹配项或无可匹配字段时，均降级返回同名匹配 base，
     * 且 {@code success=true}（调用方据此判断无需中断流程）。
     */
    public MatchResult aiSemanticMatch(String dsIdA, String schemaA, String tableA,
                                        String dsIdB, String schemaB, String tableB) {
        // 1. 以同名匹配结果为基础
        MatchResult base = aiMatchFields(dsIdA, schemaA, tableA, dsIdB, schemaB, tableB);
        if (base.mappings == null) return base;

        // 2. 收集未匹配的源 / 目标列
        List<String> srcCols = new ArrayList<>();
        List<String> tgtCols = new ArrayList<>();
        for (FieldMapping m : base.mappings) {
            if ("source_only".equals(m.status) && m.sourceColumn != null) {
                srcCols.add(m.sourceColumn + " " + (m.sourceType == null ? "" : m.sourceType));
            } else if ("target_only".equals(m.status) && m.targetColumn != null) {
                tgtCols.add(m.targetColumn + " " + (m.targetType == null ? "" : m.targetType));
            }
        }
        if (srcCols.isEmpty() || tgtCols.isEmpty()) {
            return base; // 没有可匹配的字段，直接返回基础结果
        }

        // 3. 构建 LLM prompt
        String systemPrompt = "你是数据库迁移字段映射专家。给定源表和目标表的未匹配字段列表，"
            + "根据字段名、类型和业务语义判断哪些字段是同一个含义。"
            + "只返回 JSON 数组，每个元素 {sourceColumn, targetColumn, confidence, reason}。"
            + "confidence 为 0-1 之间的数值。reason 简短说明依据。无法判断的不要返回。";
        String userPrompt = "源表 " + tableA + " 未匹配字段:\n  " + String.join("\n  ", srcCols)
            + "\n\n目标表 " + tableB + " 未匹配字段:\n  " + String.join("\n  ", tgtCols)
            + "\n\n请返回 JSON 数组：";

        // 4. 每次调用重建适配器，使运行时 LLM 配置（含解密后的密钥）立即生效
        String response;
        try {
            LlmAdapter adapter = LlmConfig.buildAdapter(llmProperties);
            response = adapter.generate(systemPrompt, userPrompt);
        } catch (Exception e) {
            log.warn("AI 语义匹配 LLM 调用失败，降级返回同名匹配结果: {}", e.getMessage());
            return base;
        }
        List<Map<String, Object>> aiMatches = parseJsonArray(response);
        if (aiMatches.isEmpty()) return base;

        // 5. 应用 AI 匹配：将命中 source_only 的行升级为 ai_matched，并移除对应的 target_only 行
        Map<String, String> srcToTgt = new LinkedHashMap<>(); // lower-case src → target col
        Map<String, Double> confMap = new HashMap<>();
        for (Map<String, Object> m : aiMatches) {
            Object s = m.get("sourceColumn");
            Object t = m.get("targetColumn");
            if (s == null || t == null) continue;
            srcToTgt.put(s.toString().toLowerCase(), t.toString());
            Object c = m.get("confidence");
            confMap.put(s.toString().toLowerCase(), c instanceof Number ? ((Number) c).doubleValue() : 0.7);
        }
        Set<String> matchedTargets = new HashSet<>();
        for (FieldMapping m : base.mappings) {
            if (!"source_only".equals(m.status) || m.sourceColumn == null) continue;
            String tgt = srcToTgt.get(m.sourceColumn.toLowerCase());
            if (tgt == null) continue;
            // 找到对应 target_only 行的目标类型（大小写不敏感）
            String targetType = null;
            for (FieldMapping t : base.mappings) {
                if ("target_only".equals(t.status) && t.targetColumn != null
                    && t.targetColumn.equalsIgnoreCase(tgt)) {
                    targetType = t.targetType;
                    break;
                }
            }
            m.targetColumn = tgt;
            m.targetType = targetType;
            m.status = "ai_matched";
            m.confidence = confMap.getOrDefault(m.sourceColumn.toLowerCase(), 0.7);
            matchedTargets.add(tgt.toLowerCase());
            base.aiMatchedCount++;
        }
        // 移除已被 AI 匹配掉的 target_only 行
        base.mappings.removeIf(m -> "target_only".equals(m.status)
            && m.targetColumn != null && matchedTargets.contains(m.targetColumn.toLowerCase()));
        return base;
    }

    /**
     * 容错解析 LLM 返回的 JSON 数组：剥离可能的 ```json 围栏与前后多余文本，
     * 截取最外层方括号内容后用 Jackson 反序列化。解析失败返回空列表（不抛异常）。
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseJsonArray(String raw) {
        if (raw == null) return Collections.emptyList();
        String t = raw.trim();
        int fence = t.indexOf("```json");
        if (fence >= 0) {
            int s = t.indexOf('\n', fence) + 1;
            int e = t.indexOf("```", s);
            if (e > s) t = t.substring(s, e).trim();
        }
        int b1 = t.indexOf('[');
        int b2 = t.lastIndexOf(']');
        if (b1 < 0 || b2 <= b1) return Collections.emptyList();
        String json = t.substring(b1, b2 + 1);
        try {
            return objectMapper.readValue(json, List.class);
        } catch (Exception e) {
            log.warn("AI 语义匹配 JSON 解析失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    public String exportInfaXml(List<Map<String, String>> tableMappings,
                                 Map<String, MatchResult> fieldMaps,
                                 String folderName) {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<!DOCTYPE POWERMART SYSTEM \"powrmart.dtd\">\n");
        xml.append("<POWERMART>\n");
        xml.append("  <REPOSITORY NAME=\"DBDoc_AI_Export\" VERSION=\"10\" CODEPAGE=\"UTF-8\" DATABASETYPE=\"Oracle\">\n");
        xml.append("    <FOLDER NAME=\"").append(escapeXml(folderName)).append("\" GROUP=\"\" OWNER=\"DBDocAI\">\n");

        Set<String> writtenSources = new HashSet<>();
        Set<String> writtenTargets = new HashSet<>();

        for (Map<String, String> tm : tableMappings) {
            String srcTable = tm.get("sourceTable");
            String tgtTable = tm.get("targetTable");
            MatchResult fm = fieldMaps.get(srcTable + "→" + tgtTable);
            if (fm == null) continue;

            // SOURCE 定义
            if (!writtenSources.contains(srcTable)) {
                writtenSources.add(srcTable);
                xml.append(generateSourceXml(srcTable, fm));
            }
            // TARGET 定义
            if (!writtenTargets.contains(tgtTable)) {
                writtenTargets.add(tgtTable);
                xml.append(generateTargetXml(tgtTable, fm));
            }
            // MAPPING
            xml.append(generateMappingXml(srcTable, tgtTable, fm));
        }

        xml.append("    </FOLDER>\n");
        xml.append("  </REPOSITORY>\n");
        xml.append("</POWERMART>");
        return xml.toString();
    }

    private String generateSourceXml(String tableName, MatchResult fm) {
        StringBuilder sb = new StringBuilder();
        sb.append("      <SOURCE NAME=\"").append(escapeXml(tableName))
          .append("\" DBDTYPE=\"Oracle\" OWNERNAME=\"SOURCE_SCHEMA\">\n");
        int num = 1;
        for (FieldMapping m : fm.mappings) {
            if ("target_only".equals(m.status)) continue;
            sb.append("        <SOURCEFIELD NAME=\"").append(escapeXml(m.sourceColumn))
              .append("\" DATATYPE=\"").append(mapInfaType(m.sourceType))
              .append("\" KEYTYPE=\"").append(m.status.equals("matched") ? "NOT A KEY" : "NOT A KEY")
              .append("\" NULLABLE=\"NULL\" FIELDNUMBER=\"").append(num++).append("\"/>\n");
        }
        sb.append("      </SOURCE>\n");
        return sb.toString();
    }

    private String generateTargetXml(String tableName, MatchResult fm) {
        StringBuilder sb = new StringBuilder();
        sb.append("      <TARGET NAME=\"").append(escapeXml(tableName))
          .append("\" DATABASETYPE=\"Oracle\" CONSTRAINT=\"\">\n");
        int num = 1;
        Set<String> done = new HashSet<>();
        for (FieldMapping m : fm.mappings) {
            if (m.targetColumn != null && !done.contains(m.targetColumn)) {
                done.add(m.targetColumn);
                sb.append("        <TARGETFIELD NAME=\"").append(escapeXml(m.targetColumn))
              .append("\" DATATYPE=\"").append(mapInfaType(m.targetType))
              .append("\" ISPRIMARYKEY=\"NO\" KEYTYPE=\"NOT A KEY\" NULLABLE=\"NULL\" FIELDNUMBER=\"").append(num++).append("\"/>\n");
            }
        }
        sb.append("      </TARGET>\n");
        return sb.toString();
    }

    private String generateMappingXml(String srcTable, String tgtTable, MatchResult fm) {
        StringBuilder sb = new StringBuilder();
        String mapName = "M_" + srcTable + "_TO_" + tgtTable;
        sb.append("      <MAPPING NAME=\"").append(escapeXml(mapName)).append("\" ISVALID=\"YES\">\n");
        sb.append("        <TRANSFORMATION NAME=\"SQ_").append(escapeXml(srcTable)).append("\" TYPE=\"Source Qualifier\">\n");
        sb.append("          <TABLEATTRIBUTE NAME=\"Sql Query\" VALUE=\"\"/>\n");
        for (FieldMapping m : fm.mappings) {
            if ("target_only".equals(m.status)) continue;
            sb.append("          <TRANSFORMFIELD NAME=\"").append(escapeXml(m.sourceColumn))
              .append("\" DATATYPE=\"").append(mapInfaType(m.sourceType)).append("\" PORTTYPE=\"INPUT/OUTPUT\"/>\n");
        }
        sb.append("        </TRANSFORMATION>\n");
        sb.append("        <TARGETINSTANCE NAME=\"T_").append(escapeXml(tgtTable))
          .append("\" TRANSFORMATION_NAME=\"").append(escapeXml(tgtTable)).append("\" TRANSFORMATION_TYPE=\"Target Definition\">\n");
        for (FieldMapping m : fm.mappings) {
            if (m.targetColumn == null || m.sourceColumn == null || "source_only".equals(m.status) || "target_only".equals(m.status)) continue;
            sb.append("          <CONNECTOR FROMFIELD=\"").append(escapeXml(m.sourceColumn))
              .append("\" FROMINSTANCE=\"SQ_").append(escapeXml(srcTable))
              .append("\" FROMINSTANCETYPE=\"Source Qualifier\" TARGETFIELD=\"").append(escapeXml(m.targetColumn))
              .append("\" TOINSTANCE=\"").append(escapeXml(tgtTable)).append("\" TOINSTANCETYPE=\"Target Definition\"/>\n");
        }
        sb.append("        </TARGETINSTANCE>\n");
        sb.append("      </MAPPING>\n");
        return sb.toString();
    }

    private String mapInfaType(String dbType) {
        if (dbType == null) return "string";
        String t = dbType.toLowerCase();
        if (t.contains("varchar") || t.contains("char") || t.contains("text")) return "string";
        if (t.contains("int") || t.contains("serial")) return "integer";
        if (t.contains("decimal") || t.contains("numeric")) return "decimal";
        if (t.contains("float") || t.contains("double")) return "double";
        if (t.contains("date") || t.contains("timestamp") || t.contains("time")) return "date/time";
        if (t.contains("clob") || t.contains("blob")) return "text";
        return "string";
    }

    private String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    public ByteArrayOutputStream exportInfaXmlBytes(List<Map<String, String>> tableMappings,
                                                     Map<String, MatchResult> fieldMaps,
                                                     String folderName) {
        String xml = exportInfaXml(tableMappings, fieldMaps, folderName);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try { baos.write(xml.getBytes("UTF-8")); } catch (Exception ignored) {}
        return baos;
    }
}
