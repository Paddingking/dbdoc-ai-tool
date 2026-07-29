package com.dbdocai.controller;

import com.dbdocai.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DocumentController 两个新增割接端点的单元测试（合并验证：端点接线 + 响应字段 + errorResp 路由）。
 *
 * <p>验证：
 * <ul>
 *   <li>POST /api/document/mapping/export-cutover-sql → {success:true, sql:...}；</li>
 *   <li>POST /api/document/mapping/ai-semantic-match → {success:true, result:{...}}；</li>
 *   <li>非法参数（IllegalArgumentException）经 errorResp 映射为 400，响应 {success:false}；</li>
 *   <li>响应字段名（success/sql/result）与前端 types/api.ts 的 CutoverSqlResponse /
 *       AiSemanticMatchResponse 对齐（无源码 Bug，仅契约一致性确认）。</li>
 * </ul>
 *
 * <p>独立装配 MockMvc，仅加载目标 Controller，不引入鉴权拦截器，聚焦端点内部行为。
 */
@ExtendWith(MockitoExtension.class)
public class DocumentControllerMappingTest {

    @Mock private DocumentService documentService;
    @Mock private SyncService syncService;
    @Mock private DocExportService exportService;
    @Mock private DbStore dbStore;
    @Mock private LintService lintService;
    @Mock private CrossDbCompareService compareService;
    @Mock private DdlService ddlService;
    @Mock private FieldMappingService fieldMappingService;
    @Mock private ImpactAnalysisService impactAnalysisService;
    @Mock private HealthDashboardService healthDashboardService;
    @Mock private CutoverSqlService cutoverSqlService;
    @Mock private BatchCommentService batchCommentService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        DocumentController controller = new DocumentController(
                documentService, syncService, exportService, dbStore, lintService,
                compareService, ddlService, fieldMappingService, impactAnalysisService,
                healthDashboardService, cutoverSqlService, batchCommentService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    public void exportCutoverSql_returnsSuccessAndSql() throws Exception {
        when(cutoverSqlService.generateCutoverSql(anyString(), any(), anyString(), any(), any(), any()))
                .thenReturn("-- 三段式割接 SQL");
        when(fieldMappingService.aiMatchFields(anyString(), any(), anyString(), anyString(), any(), anyString()))
                .thenReturn(new FieldMappingService.MatchResult());

        String body = "{"
                + "\"dataSourceIdA\":\"dsA\",\"schemaA\":null,"
                + "\"dataSourceIdB\":\"dsB\",\"schemaB\":null,"
                + "\"tableMappings\":[{\"sourceTable\":\"SRC\",\"targetTable\":\"TGT\"}]"
                + "}";

        mockMvc.perform(post("/api/document/mapping/export-cutover-sql")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.sql").value("-- 三段式割接 SQL"));
    }

    @Test
    public void aiSemanticMatch_returnsSuccessAndResult() throws Exception {
        FieldMappingService.MatchResult base = new FieldMappingService.MatchResult();
        base.matchedCount = 1;
        base.aiMatchedCount = 0;
        when(fieldMappingService.aiSemanticMatch(anyString(), any(), anyString(), anyString(), any(), anyString()))
                .thenReturn(base);

        String body = "{"
                + "\"dataSourceIdA\":\"dsA\",\"schemaA\":null,\"tableA\":\"SRC\","
                + "\"dataSourceIdB\":\"dsB\",\"schemaB\":null,\"tableB\":\"TGT\"}";

        mockMvc.perform(post("/api/document/mapping/ai-semantic-match")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.result.matchedCount").value(1))
                .andExpect(jsonPath("$.result.aiMatchedCount").value(0));
    }

    @Test
    public void exportCutoverSql_illegalArgumentException_mapsTo400() throws Exception {
        // 回退同名匹配时抛 IllegalArgumentException → errorResp → 400
        when(fieldMappingService.aiMatchFields(anyString(), any(), anyString(), anyString(), any(), anyString()))
                .thenThrow(new IllegalArgumentException("参数非法：缺少数据源"));

        String body = "{"
                + "\"dataSourceIdA\":\"dsA\",\"dataSourceIdB\":\"dsB\","
                + "\"tableMappings\":[{\"sourceTable\":\"SRC\",\"targetTable\":\"TGT\"}]"
                + "}";

        mockMvc.perform(post("/api/document/mapping/export-cutover-sql")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("参数非法：缺少数据源"));
    }
}
