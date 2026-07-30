package com.dbdocai.controller;

import com.dbdocai.service.DocExportService;
import com.dbdocai.service.DocumentService;
import com.dbdocai.service.DbStore;
import com.dbdocai.service.SyncService;
import com.dbdocai.service.LintService;
import com.dbdocai.service.CrossDbCompareService;
import com.dbdocai.service.DdlService;
import com.dbdocai.service.FieldMappingService;
import com.dbdocai.service.ImpactAnalysisService;
import com.dbdocai.service.HealthDashboardService;
import com.dbdocai.service.BatchCommentService;
import com.dbdocai.service.CutoverSqlService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/document")
public class DocumentController {

    private final DocumentService documentService;
    private final SyncService syncService;
    private final DocExportService exportService;
    private final DbStore dbStore;
    private final LintService lintService;
    private final CrossDbCompareService compareService;
    private final DdlService ddlService;
    private final FieldMappingService fieldMappingService;
    private final ImpactAnalysisService impactAnalysisService;
    private final HealthDashboardService healthDashboardService;
    private final BatchCommentService batchCommentService;
    private final CutoverSqlService cutoverSqlService;

    public DocumentController(DocumentService documentService, SyncService syncService,
                              DocExportService exportService, DbStore dbStore,
                              LintService lintService, CrossDbCompareService compareService,
                              DdlService ddlService, FieldMappingService fieldMappingService,
                              ImpactAnalysisService impactAnalysisService,
                              HealthDashboardService healthDashboardService,
                              CutoverSqlService cutoverSqlService, BatchCommentService batchCommentService) {
        this.documentService = documentService;
        this.syncService = syncService;
        this.exportService = exportService;
        this.dbStore = dbStore;
        this.lintService = lintService;
        this.compareService = compareService;
        this.ddlService = ddlService;
        this.fieldMappingService = fieldMappingService;
        this.impactAnalysisService = impactAnalysisService;
        this.healthDashboardService = healthDashboardService;
        this.batchCommentService = batchCommentService;
        this.cutoverSqlService = cutoverSqlService;
    }

    @PostMapping("/generate")
    public ResponseEntity<Map<String, Object>> generate(@RequestBody Map<String, Object> body) {
        try {
            String dataSourceId = (String) body.get("dataSourceId");
            String schema = (String) body.get("schema");
            @SuppressWarnings("unchecked")
            List<String> tableNames = (List<String>) body.get("tableNames");
            Map<String, Object> document = documentService.generateDocument(dataSourceId, schema, tableNames);
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("document", document);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("error", "服务内部错误");
            return ResponseEntity.ok(result);
        }
    }

    @PostMapping("/auto-group")
    public ResponseEntity<Map<String, Object>> autoGroup(@RequestBody Map<String, Object> body) {
        try {
            String dataSourceId = (String) body.get("dataSourceId");
            Map<String, Object> document = documentService.autoGroupWithAi(dataSourceId);
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("document", document);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("error", "服务内部错误");
            return ResponseEntity.ok(result);
        }
    }

    @PostMapping("/ai-infer")
    public ResponseEntity<Map<String, Object>> aiInfer(@RequestBody Map<String, Object> body) {
        try {
            String dataSourceId = (String) body.get("dataSourceId");
            @SuppressWarnings("unchecked")
            List<String> tableNames = (List<String>) body.get("tableNames");
            List<Map<String, Object>> results = documentService.aiInferFields(dataSourceId, tableNames);
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("results", results);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("error", "服务内部错误");
            return ResponseEntity.ok(result);
        }
    }

    @PostMapping("/confirm-ai")
    public ResponseEntity<Map<String, Object>> confirmAi(@RequestBody Map<String, Object> body) {
        try {
            String dataSourceId = (String) body.get("dataSourceId");
            String tableName = (String) body.get("tableName");
            String columnName = (String) body.get("columnName");
            String description = (String) body.get("description");
            documentService.confirmAiField(dataSourceId, tableName, columnName, description);
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("error", "服务内部错误");
            return ResponseEntity.ok(result);
        }
    }

    @PostMapping("/confirm-ai-batch")
    public ResponseEntity<Map<String, Object>> confirmAiBatch(@RequestBody Map<String, Object> body) {
        try {
            String dataSourceId = (String) body.get("dataSourceId");
            @SuppressWarnings("unchecked")
            List<Map<String, String>> items = (List<Map<String, String>>) body.get("items");
            int count = documentService.confirmAiFieldBatch(dataSourceId, items);
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("count", count);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("error", "服务内部错误");
            return ResponseEntity.ok(result);
        }
    }

    @PostMapping("/reject-ai")
    public ResponseEntity<Map<String, Object>> rejectAi(@RequestBody Map<String, Object> body) {
        try {
            String dataSourceId = (String) body.get("dataSourceId");
            @SuppressWarnings("unchecked")
            List<Map<String, String>> items = (List<Map<String, String>>) body.get("items");
            documentService.rejectAiFields(dataSourceId, items);
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("error", "服务内部错误");
            return ResponseEntity.ok(result);
        }
    }

    @PostMapping("/discard-ai")
    public ResponseEntity<Map<String, Object>> discardAi(@RequestBody Map<String, Object> body) {
        try {
            String dataSourceId = (String) body.get("dataSourceId");
            @SuppressWarnings("unchecked")
            List<String> tableNames = (List<String>) body.get("tableNames");
            documentService.discardPendingAiInfer(dataSourceId, tableNames);
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("error", "服务内部错误");
            return ResponseEntity.ok(result);
        }
    }

    @PostMapping("/export")
    public ResponseEntity<Map<String, Object>> export(@RequestBody Map<String, Object> body) {
        try {
            String dataSourceId = (String) body.get("dataSourceId");
            String format = (String) body.get("format");
            @SuppressWarnings("unchecked")
            List<String> tableNames = (List<String>) body.get("tableNames");
            if (tableNames == null || tableNames.isEmpty()) {
                tableNames = null;
            }
            String filePath;
            if ("pdf".equalsIgnoreCase(format)) {
                filePath = exportService.exportPdf(dataSourceId, tableNames);
            } else if ("word".equalsIgnoreCase(format)) {
                filePath = exportService.exportWord(dataSourceId, tableNames);
            } else if ("markdown".equalsIgnoreCase(format)) {
                filePath = documentService.exportMarkdown(dataSourceId, tableNames);
            } else {
                filePath = documentService.exportHtml(dataSourceId, tableNames);
            }
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("filePath", filePath);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("error", "服务内部错误");
            return ResponseEntity.ok(result);
        }
    }

    // ── Sync & Changelog (P0-1) ────────────────────

    @GetMapping("/sync/{dataSourceId}")
    public ResponseEntity<Map<String, Object>> sync(@PathVariable String dataSourceId,
                                                     @RequestParam(required = false) String schema) {
        try {
            List<SyncService.SyncChange> changes = syncService.sync(dataSourceId, schema);
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("changes", changes);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("error", "服务内部错误");
            return ResponseEntity.ok(result);
        }
    }

    @GetMapping("/snapshots/{dataSourceId}")
    public ResponseEntity<Map<String, Object>> getSnapshots(@PathVariable String dataSourceId,
                                                             @RequestParam(required = false, defaultValue = "") String schema,
                                                             @RequestParam(defaultValue = "1") int page,
                                                             @RequestParam(defaultValue = "20") int size) {
        try {
            List<Map<String, Object>> snapshots = dbStore.getSnapshots(dataSourceId, schema, page, size);
            int total = dbStore.countSnapshots(dataSourceId, schema);
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("snapshots", snapshots);
            result.put("total", total);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("error", "服务内部错误");
            return ResponseEntity.ok(result);
        }
    }

    @GetMapping("/snapshots/{snapshotId}/changes")
    public ResponseEntity<Map<String, Object>> getSnapshotChanges(@PathVariable long snapshotId) {
        try {
            List<Map<String, Object>> changes = dbStore.getChangesBySnapshot(snapshotId);
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("changes", changes);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("error", "服务内部错误");
            return ResponseEntity.ok(result);
        }
    }

    @GetMapping("/table-history/{dataSourceId}")
    public ResponseEntity<Map<String, Object>> getTableHistory(@PathVariable String dataSourceId,
                                                                @RequestParam(required = false, defaultValue = "") String schema,
                                                                @RequestParam String table,
                                                                @RequestParam(defaultValue = "50") int limit) {
        try {
            List<Map<String, Object>> history = dbStore.getTableHistory(dataSourceId, schema, table, limit);
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("history", history);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("error", "服务内部错误");
            return ResponseEntity.ok(result);
        }
    }

    // ── Viewpoints (P0-2) ──────────────────────────

    @GetMapping("/viewpoints/{dataSourceId}")
    public ResponseEntity<Map<String, Object>> listViewpoints(@PathVariable String dataSourceId,
                                                               @RequestParam(required = false, defaultValue = "") String schema) {
        try {
            List<Map<String, Object>> viewpoints = dbStore.listViewpoints(dataSourceId, schema);
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("viewpoints", viewpoints);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("error", "服务内部错误");
            return ResponseEntity.ok(result);
        }
    }

    @PostMapping("/viewpoint")
    public ResponseEntity<Map<String, Object>> createViewpoint(@RequestBody Map<String, Object> body) {
        try {
            String dataSourceId = (String) body.get("dataSourceId");
            String schema = (String) body.get("schema");
            String name = (String) body.get("name");
            String description = (String) body.get("description");
            long id = dbStore.createViewpoint(dataSourceId, schema, name, description);
            Map<String, Object> result = new HashMap<>();
            if (id == -2) {
                result.put("success", false);
                result.put("error", "视角名称已存在");
            } else if (id > 0) {
                result.put("success", true);
                result.put("id", id);
            } else {
                result.put("success", false);
                result.put("error", "创建失败");
            }
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("error", "服务内部错误");
            return ResponseEntity.ok(result);
        }
    }

    @PutMapping("/viewpoint/{id}")
    public ResponseEntity<Map<String, Object>> updateViewpoint(@PathVariable long id, @RequestBody Map<String, Object> body) {
        try {
            String name = (String) body.get("name");
            String description = (String) body.get("description");
            dbStore.updateViewpoint(id, name, description);
            @SuppressWarnings("unchecked")
            List<String> tables = (List<String>) body.get("tables");
            if (tables != null) {
                dbStore.setViewpointTables(id, tables);
            }
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("error", "服务内部错误");
            return ResponseEntity.ok(result);
        }
    }

    @DeleteMapping("/viewpoint/{id}")
    public ResponseEntity<Map<String, Object>> deleteViewpoint(@PathVariable long id) {
        try {
            dbStore.deleteViewpoint(id);
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("error", "服务内部错误");
            return ResponseEntity.ok(result);
        }
    }

    @GetMapping("/viewpoint/{id}/tables")
    public ResponseEntity<Map<String, Object>> getViewpointTables(@PathVariable long id) {
        try {
            List<String> tables = dbStore.getViewpointTables(id);
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("tables", tables);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("error", "服务内部错误");
            return ResponseEntity.ok(result);
        }
    }

    @PostMapping("/viewpoint/{id}/tables")
    public ResponseEntity<Map<String, Object>> setViewpointTables(@PathVariable long id, @RequestBody Map<String, Object> body) {
        try {
            @SuppressWarnings("unchecked")
            List<String> tableNames = (List<String>) body.get("tableNames");
            dbStore.setViewpointTables(id, tableNames);
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("error", "服务内部错误");
            return ResponseEntity.ok(result);
        }
    }

    @PostMapping("/viewpoint/{id}/document")
    public ResponseEntity<Map<String, Object>> generateViewpointDoc(@PathVariable long id, @RequestBody Map<String, Object> body) {
        try {
            String schema = (String) body.get("schema");
            List<String> tableNames = dbStore.getViewpointTables(id);
            String dataSourceId = (String) body.get("dataSourceId");
            Map<String, Object> document = documentService.generateDocument(dataSourceId, schema, tableNames);
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("document", document);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("error", "服务内部错误");
            return ResponseEntity.ok(result);
        }
    }

    // ── Lint (P1-1) ────────────────────────────────

    @PostMapping("/lint")
    public ResponseEntity<Map<String, Object>> lint(@RequestBody Map<String, Object> body) {
        try {
            String dataSourceId = (String) body.get("dataSourceId");
            String schema = (String) body.get("schema");
            @SuppressWarnings("unchecked")
            List<String> tableNames = (List<String>) body.get("tableNames");
            LintService.LintReport report = lintService.lint(dataSourceId, schema, tableNames);
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("report", report);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("error", "服务内部错误");
            return ResponseEntity.ok(result);
        }
    }

    // ── Routine AI Summarization (P1-2) ────────────

    @PostMapping("/routines/ai-summarize")
    public ResponseEntity<Map<String, Object>> aiSummarizeRoutines(@RequestBody Map<String, Object> body) {
        try {
            String dataSourceId = (String) body.get("dataSourceId");
            String schema = (String) body.get("schema");
            @SuppressWarnings("unchecked")
            List<String> routineNames = (List<String>) body.get("routineNames");
            List<Map<String, Object>> summaries = documentService.aiSummarizeRoutines(dataSourceId, schema, routineNames);
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("summaries", summaries);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("error", "服务内部错误");
            return ResponseEntity.ok(result);
        }
    }

    // ── Cross-DB Compare (P1-3) ────────────────────

    @PostMapping("/compare")
    public ResponseEntity<Map<String, Object>> compare(@RequestBody Map<String, Object> body) {
        try {
            String dsIdA = (String) body.get("dataSourceIdA");
            String schemaA = (String) body.get("schemaA");
            String dsIdB = (String) body.get("dataSourceIdB");
            String schemaB = (String) body.get("schemaB");
            CrossDbCompareService.CrossDbReport report = compareService.compare(dsIdA, schemaA, dsIdB, schemaB);
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("report", report);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("error", "服务内部错误");
            return ResponseEntity.ok(result);
        }
    }

    // ── DDL Generation (P1-4) ──────────────────────

    @PostMapping("/ddl")
    public ResponseEntity<Map<String, Object>> generateDdl(@RequestBody Map<String, Object> body) {
        try {
            String dataSourceId = (String) body.get("dataSourceId");
            String schema = (String) body.get("schema");
            String tableName = (String) body.get("tableName");
            String ddl = ddlService.generateTableDdl(dataSourceId, schema, tableName);
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("ddl", ddl);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("error", "服务内部错误");
            return ResponseEntity.ok(result);
        }
    }

    @PostMapping("/ddl/batch")
    public ResponseEntity<Map<String, Object>> generateBatchDdl(@RequestBody Map<String, Object> body) {
        try {
            String dataSourceId = (String) body.get("dataSourceId");
            String schema = (String) body.get("schema");
            @SuppressWarnings("unchecked")
            List<String> tableNames = (List<String>) body.get("tableNames");
            String ddl = ddlService.generateBatchDdl(dataSourceId, schema, tableNames);
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("ddl", ddl);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("error", "服务内部错误");
            return ResponseEntity.ok(result);
        }
    }

    // ── P2-1: Field Mapping Engine ──────────────────

    @PostMapping("/mapping/auto-detect-tables")
    public ResponseEntity<Map<String, Object>> autoDetectTableMappings(@RequestBody Map<String, Object> body) {
        try {
            List<Map<String, String>> result = fieldMappingService.autoDetectTableMappings(
                (String) body.get("dataSourceIdA"), (String) body.get("schemaA"),
                (String) body.get("dataSourceIdB"), (String) body.get("schemaB"));
            Map<String, Object> r = new HashMap<>(); r.put("success", true); r.put("mappings", result);
            return ResponseEntity.ok(r);
        } catch (Exception e) { return errorResp(e); }
    }

    @PostMapping("/mapping/ai-match")
    public ResponseEntity<Map<String, Object>> aiMatchFields(@RequestBody Map<String, Object> body) {
        try {
            FieldMappingService.MatchResult result = fieldMappingService.aiMatchFields(
                (String) body.get("dataSourceIdA"), (String) body.get("schemaA"), (String) body.get("tableA"),
                (String) body.get("dataSourceIdB"), (String) body.get("schemaB"), (String) body.get("tableB"));
            Map<String, Object> r = new HashMap<>(); r.put("success", true); r.put("result", result);
            return ResponseEntity.ok(r);
        } catch (Exception e) { return errorResp(e); }
    }

    @PostMapping("/mapping/ai-semantic-match")
    public ResponseEntity<Map<String, Object>> aiSemanticMatch(@RequestBody Map<String, Object> body) {
        try {
            FieldMappingService.MatchResult result = fieldMappingService.aiSemanticMatch(
                (String) body.get("dataSourceIdA"), (String) body.get("schemaA"), (String) body.get("tableA"),
                (String) body.get("dataSourceIdB"), (String) body.get("schemaB"), (String) body.get("tableB"));
            Map<String, Object> r = new HashMap<>(); r.put("success", true); r.put("result", result);
            return ResponseEntity.ok(r);
        } catch (Exception e) { return errorResp(e); }
    }

    @PostMapping("/mapping/export-cutover-sql")
    public ResponseEntity<Map<String, Object>> exportCutoverSql(@RequestBody Map<String, Object> body) {
        try {
            String dsIdA = (String) body.get("dataSourceIdA");
            String schemaA = (String) body.get("schemaA");
            String dsIdB = (String) body.get("dataSourceIdB");
            String schemaB = (String) body.get("schemaB");
            @SuppressWarnings("unchecked")
            List<Map<String, String>> tm = (List<Map<String, String>>) body.get("tableMappings");
            if (tm == null) tm = Collections.emptyList();

            // 优先使用前端预传的字段映射；缺省时按同名匹配回退计算。主线无持久化存储。
            Map<String, FieldMappingService.MatchResult> fieldMaps = new LinkedHashMap<>();
            @SuppressWarnings("unchecked")
            Map<String, Object> prebuilt = (Map<String, Object>) body.get("fieldMaps");
            for (Map<String, String> pair : tm) {
                String src = pair.get("sourceTable");
                String tgt = pair.get("targetTable");
                if (src == null || tgt == null) continue;
                String key = src + "→" + tgt;
                FieldMappingService.MatchResult mr = null;
                if (prebuilt != null && prebuilt.containsKey(key)) {
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> raw = (Map<String, Object>) prebuilt.get(key);
                        mr = FieldMappingService.MatchResult.fromMap(raw);
                    } catch (Exception ignored) { /* 解码失败则回退同名匹配 */ }
                }
                if (mr == null) {
                    mr = fieldMappingService.aiMatchFields(dsIdA, schemaA, src, dsIdB, schemaB, tgt);
                }
                fieldMaps.put(key, mr);
            }
            String sql = cutoverSqlService.generateCutoverSql(dsIdA, schemaA, dsIdB, schemaB, tm, fieldMaps);
            Map<String, Object> r = new HashMap<>(); r.put("success", true); r.put("sql", sql);
            return ResponseEntity.ok(r);
        } catch (Exception e) { return errorResp(e); }
    }

    @PostMapping("/mapping/export-infa-xml")
    public ResponseEntity<Map<String, Object>> exportInfaXml(@RequestBody Map<String, Object> body) {
        try {
            String folderName = (String) body.getOrDefault("folderName", "DBDoc_AI_Mappings");
            @SuppressWarnings("unchecked")
            List<Map<String, String>> tm = (List<Map<String, String>>) body.get("tableMappings");
            // Accept pre-built fieldMappings from frontend; empty map = no field detail in XML
            String xml = fieldMappingService.exportInfaXml(
                tm != null ? tm : Collections.emptyList(),
                Collections.emptyMap(), folderName);
            Map<String, Object> r = new HashMap<>(); r.put("success", true); r.put("xml", xml);
            return ResponseEntity.ok(r);
        } catch (Exception e) { return errorResp(e); }
    }

    // ── P2-2: Impact Analysis ────────────────────────

    @PostMapping("/impact")
    public ResponseEntity<Map<String, Object>> impactAnalysis(@RequestBody Map<String, Object> body) {
        try {
            ImpactAnalysisService.ImpactReport report = impactAnalysisService.analyze(
                (String) body.get("dataSourceId"), (String) body.get("schema"), (String) body.get("tableName"));
            Map<String, Object> r = new HashMap<>(); r.put("success", true); r.put("report", report);
            return ResponseEntity.ok(r);
        } catch (Exception e) { return errorResp(e); }
    }

    // ── P2-4: Health Dashboard ───────────────────────

    @PostMapping("/health")
    public ResponseEntity<Map<String, Object>> healthDashboard(@RequestBody Map<String, Object> body) {
        try {
            HealthDashboardService.HealthDashboard db = healthDashboardService.analyze(
                (String) body.get("dataSourceId"), (String) body.get("schema"));
            Map<String, Object> r = new HashMap<>(); r.put("success", true); r.put("report", db);
            return ResponseEntity.ok(r);
        } catch (Exception e) { return errorResp(e); }
    }

    // ── P3-1: AI Chat ────────────────────────────────

    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(@RequestBody Map<String, Object> body) {
        try {
            String dataSourceId = (String) body.get("dataSourceId");
            String schema = (String) body.get("schema");
            String question = (String) body.get("question");
            String answer = documentService.aiChat(dataSourceId, schema, question);
            Map<String, Object> r = new HashMap<>(); r.put("success", true); r.put("answer", answer);
            return ResponseEntity.ok(r);
        } catch (Exception e) { return errorResp(e); }
    }

    // ── P3-3: Batch Comment ──────────────────────────

    @GetMapping("/batch-comment/default-rules")
    public ResponseEntity<Map<String, Object>> getDefaultRules() {
        List<BatchCommentService.CommentRule> rules = BatchCommentService.getDefaultRules();
        Map<String, Object> r = new HashMap<>(); r.put("success", true); r.put("rules", rules);
        return ResponseEntity.ok(r);
    }

    @PostMapping("/batch-comment/preview")
    public ResponseEntity<Map<String, Object>> batchCommentPreview(@RequestBody Map<String, Object> body) {
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, String>> rulesRaw = (List<Map<String, String>>) body.get("rules");
            List<BatchCommentService.CommentRule> rules = new ArrayList<>();
            if (rulesRaw != null) {
                for (Map<String, String> rw : rulesRaw) {
                    BatchCommentService.CommentRule cr = new BatchCommentService.CommentRule();
                    cr.id = rw.get("id"); cr.name = rw.get("name"); cr.pattern = rw.get("pattern");
                    cr.patternType = rw.get("patternType"); cr.typeFilter = rw.get("typeFilter");
                    cr.template = rw.get("template"); cr.enabled = "true".equals(rw.get("enabled"));
                    rules.add(cr);
                }
            }
            @SuppressWarnings("unchecked")
            List<String> tableNames = (List<String>) body.get("tableNames");
            BatchCommentService.PreviewResult pr = batchCommentService.preview(rules,
                (String) body.get("dataSourceId"), (String) body.get("schema"), tableNames);
            Map<String, Object> r = new HashMap<>(); r.put("success", true); r.put("result", pr);
            return ResponseEntity.ok(r);
        } catch (Exception e) { return errorResp(e); }
    }

    @PostMapping("/batch-comment/execute")
    public ResponseEntity<Map<String, Object>> batchCommentExecute(@RequestBody Map<String, Object> body) {
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, String>> rulesRaw = (List<Map<String, String>>) body.get("rules");
            List<BatchCommentService.CommentRule> rules = new ArrayList<>();
            if (rulesRaw != null) {
                for (Map<String, String> rw : rulesRaw) {
                    BatchCommentService.CommentRule cr = new BatchCommentService.CommentRule();
                    cr.id = rw.get("id"); cr.pattern = rw.get("pattern"); cr.patternType = rw.get("patternType");
                    cr.typeFilter = rw.get("typeFilter"); cr.template = rw.get("template");
                    cr.enabled = !"false".equals(rw.get("enabled"));
                    rules.add(cr);
                }
            }
            @SuppressWarnings("unchecked")
            List<String> tableNames = (List<String>) body.get("tableNames");
            int written = batchCommentService.execute(rules,
                (String) body.get("dataSourceId"), (String) body.get("schema"), tableNames);
            Map<String, Object> r = new HashMap<>(); r.put("success", true); r.put("written", written);
            return ResponseEntity.ok(r);
        } catch (Exception e) { return errorResp(e); }
    }

    private ResponseEntity<Map<String, Object>> errorResp(Exception e) {
        Map<String, Object> r = new HashMap<>();
        r.put("success", false);
        r.put("error", "服务内部错误");
        boolean isBadRequest = e instanceof IllegalArgumentException;
        return ResponseEntity.status(isBadRequest ? 400 : 500).body(r);
    }
}
