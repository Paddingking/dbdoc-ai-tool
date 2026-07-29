package com.dbdocai.service;

import com.dbdocai.config.LlmProperties;
import com.dbdocai.dto.DataSourceConfigDTO;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * FieldMappingService 单元测试（合并验证：AI 语义匹配 + 预传映射解码）。
 *
 * <p>聚焦验证交付要点：
 * <ol>
 *   <li>{@code aiSemanticMatch} 在无可用 LLM 配置时应安全降级：返回同名匹配 base、
 *       aiMatchedCount=0、不抛异常（success=true 由 Controller 包装）；</li>
 *   <li>同名匹配即全部命中（无未匹配列）时，同样不进 LLM、aiMatchedCount=0、无异常；</li>
 *   <li>{@code MatchResult.fromMap} 正确解码前端预传映射（字段名/计数）；</li>
 *   <li>{@code parseJsonArray} 容错：剥离 ```json 围栏、截取方括号内容、非法输入返回空列表不抛异常。</li>
 * </ol>
 */
public class FieldMappingServiceTest {

    private final DocumentService documentService = mock(DocumentService.class);
    private final DataSourceStoreService storeService = mock(DataSourceStoreService.class);

    /** 构造 docA/docB，使 SRC 含 a/x、TGT 含 a/y（a 同名匹配，x/y 互不匹配）。 */
    private void stubDocService(String dsA, String tableA, String dsB, String tableB) {
        Map<String, Object> colAa = col("a", "int");
        Map<String, Object> colAx = col("x", "int");
        Map<String, Object> tableAObj = new LinkedHashMap<>();
        tableAObj.put("name", tableA);
        tableAObj.put("columns", Arrays.asList(colAa, colAx));
        Map<String, Object> docA = new LinkedHashMap<>();
        docA.put("tables", Collections.singletonList(tableAObj));

        Map<String, Object> colBa = col("a", "int");
        Map<String, Object> colBy = col("y", "int");
        Map<String, Object> tableBObj = new LinkedHashMap<>();
        tableBObj.put("name", tableB);
        tableBObj.put("columns", Arrays.asList(colBa, colBy));
        Map<String, Object> docB = new LinkedHashMap<>();
        docB.put("tables", Collections.singletonList(tableBObj));

        when(documentService.generateDocument(eq(dsA), any(), any())).thenReturn(docA);
        when(documentService.generateDocument(eq(dsB), any(), any())).thenReturn(docB);
    }

    private static Map<String, Object> col(String name, String type) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("dataType", type);
        return m;
    }

    // ── 1. aiSemanticMatch 安全降级（无可用 LLM 配置） ────────

    @Test
    public void aiSemanticMatch_noLlmConfig_returnsBaseWithoutException() {
        // provider=openai 且 apiKey 为 null → LlmConfig.buildAdapter 抛 IllegalArgumentException，
        // 应在 aiSemanticMatch 内部被捕获并降级返回同名匹配 base（aiMatchedCount=0）。
        LlmProperties props = new LlmProperties();
        props.setProvider("openai"); // apiKey 保持 null
        FieldMappingService service = new FieldMappingService(documentService, storeService, props);

        stubDocService("dsA", "SRC", "dsB", "TGT");

        FieldMappingService.MatchResult res =
                service.aiSemanticMatch("dsA", null, "SRC", "dsB", null, "TGT");

        assertNotNull(res, "不应返回 null");
        assertEquals(1, res.matchedCount, "a 同名命中，matchedCount=1");
        assertEquals(0, res.aiMatchedCount, "LLM 不可用，aiMatchedCount 应为 0");
        // base 应包含 source_only(x) 与 target_only(y)，证明返回的是同名匹配 base 而非空结果
        boolean hasSourceOnly = res.mappings.stream()
                .anyMatch(m -> "source_only".equals(m.status));
        boolean hasTargetOnly = res.mappings.stream()
                .anyMatch(m -> "target_only".equals(m.status));
        assertTrue(hasSourceOnly, "应保留 source_only 字段 x");
        assertTrue(hasTargetOnly, "应保留 target_only 字段 y");
    }

    @Test
    public void aiSemanticMatch_allMatched_noLlmCall_noException() {
        // 仅同名匹配、无未匹配列 → 提前返回 base，aiMatchedCount=0，不触达 LLM。
        LlmProperties props = new LlmProperties();
        props.setProvider("ollama"); // 即便有 provider，无未匹配列也不会调用
        FieldMappingService service = new FieldMappingService(documentService, storeService, props);

        // SRC/TGT 均只含 a(int)，全部同名匹配
        Map<String, Object> tableAObj = new LinkedHashMap<>();
        tableAObj.put("name", "SRC");
        tableAObj.put("columns", Collections.singletonList(col("a", "int")));
        Map<String, Object> docA = new LinkedHashMap<>();
        docA.put("tables", Collections.singletonList(tableAObj));

        Map<String, Object> tableBObj = new LinkedHashMap<>();
        tableBObj.put("name", "TGT");
        tableBObj.put("columns", Collections.singletonList(col("a", "int")));
        Map<String, Object> docB = new LinkedHashMap<>();
        docB.put("tables", Collections.singletonList(tableBObj));

        when(documentService.generateDocument(eq("dsA"), any(), any())).thenReturn(docA);
        when(documentService.generateDocument(eq("dsB"), any(), any())).thenReturn(docB);

        FieldMappingService.MatchResult res =
                service.aiSemanticMatch("dsA", null, "SRC", "dsB", null, "TGT");

        assertNotNull(res);
        assertEquals(1, res.matchedCount, "a 同名命中");
        assertEquals(0, res.aiMatchedCount, "无未匹配列，aiMatchedCount=0");
    }

    // ── 2. MatchResult.fromMap 解码 ───────────────────────

    @Test
    public void fromMap_decodesMappingsAndCounts() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("matchedCount", 5);
        raw.put("aiMatchedCount", 2);
        raw.put("conflictCount", 1);

        Map<String, Object> m1 = new LinkedHashMap<>();
        m1.put("sourceTable", "S");
        m1.put("sourceColumn", "sc");
        m1.put("targetTable", "T");
        m1.put("targetColumn", "tc");
        m1.put("status", "matched");
        m1.put("confidence", 0.9);
        m1.put("transformRule", "EXPR:IIF(x='Y',1,0)");
        raw.put("mappings", Collections.singletonList(m1));

        FieldMappingService.MatchResult r = FieldMappingService.MatchResult.fromMap(raw);

        assertEquals(5, r.matchedCount);
        assertEquals(2, r.aiMatchedCount);
        assertEquals(1, r.conflictCount);
        assertEquals(1, r.mappings.size());
        assertEquals("sc", r.mappings.get(0).sourceColumn);
        assertEquals("tc", r.mappings.get(0).targetColumn);
        assertEquals("matched", r.mappings.get(0).status);
        assertEquals(0.9, r.mappings.get(0).confidence, 0.0001);
        assertEquals("EXPR:IIF(x='Y',1,0)", r.mappings.get(0).transformRule);
    }

    @Test
    public void fromMap_handlesMissingFieldsAndEmptyList() {
        // 缺省字段以默认值填充；mappings 缺失/非列表 → 空列表，不抛异常、不 NPE
        Map<String, Object> raw = new LinkedHashMap<>();
        FieldMappingService.MatchResult r = FieldMappingService.MatchResult.fromMap(raw);
        assertEquals(0, r.matchedCount);
        assertEquals(0, r.aiMatchedCount);
        assertNotNull(r.mappings);
        assertEquals(0, r.mappings.size());

        // mappings 为非 List 类型 → 安全跳过
        Map<String, Object> raw2 = new LinkedHashMap<>();
        raw2.put("mappings", "not-a-list");
        FieldMappingService.MatchResult r2 = FieldMappingService.MatchResult.fromMap(raw2);
        assertNotNull(r2.mappings);
        assertEquals(0, r2.mappings.size());
    }

    // ── 3. parseJsonArray 容错（私有方法，反射直测） ──────────

    @Test
    public void parseJsonArray_stripsFencedJson() throws Exception {
        Method m = FieldMappingService.class.getDeclaredMethod("parseJsonArray", String.class);
        m.setAccessible(true);
        FieldMappingService service = new FieldMappingService(documentService, storeService, new LlmProperties());

        String fenced = "```json\n[{\"sourceColumn\":\"a\",\"targetColumn\":\"b\"}]\n```";
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> res = (List<Map<String, Object>>) m.invoke(service, fenced);
        assertEquals(1, res.size());
        assertEquals("a", res.get(0).get("sourceColumn"));
    }

    @Test
    public void parseJsonArray_extractsBracketContent() throws Exception {
        Method m = FieldMappingService.class.getDeclaredMethod("parseJsonArray", String.class);
        m.setAccessible(true);
        FieldMappingService service = new FieldMappingService(documentService, storeService, new LlmProperties());

        String prefixed = "此处是说明文字 [1, 2, 3] 后缀说明";
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> res = (List<Map<String, Object>>) m.invoke(service, prefixed);
        assertEquals(3, res.size());
    }

    @Test
    public void parseJsonArray_nullAndGarbage_returnEmptyNoThrow() throws Exception {
        Method m = FieldMappingService.class.getDeclaredMethod("parseJsonArray", String.class);
        m.setAccessible(true);
        FieldMappingService service = new FieldMappingService(documentService, storeService, new LlmProperties());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nullRes = (List<Map<String, Object>>) m.invoke(service, (String) null);
        assertTrue(nullRes.isEmpty(), "null 应返回空列表");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> garbage = (List<Map<String, Object>>) m.invoke(service, "这不是 json 也没有方括号");
        assertTrue(garbage.isEmpty(), "非法输入应返回空列表且不抛异常");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> broken = (List<Map<String, Object>>) m.invoke(service, "[{bad json]");
        assertTrue(broken.isEmpty(), "损坏 JSON 应返回空列表");
    }
}
