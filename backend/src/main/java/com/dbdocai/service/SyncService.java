package com.dbdocai.service;

import com.dbdocai.dto.DataSourceConfigDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.*;

@Service
public class SyncService {
    private static final Logger log = LoggerFactory.getLogger(SyncService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DataSourceStoreService storeService;
    private final DocumentService documentService;
    private final DbStore dbStore;

    public SyncService(DataSourceStoreService storeService, DocumentService documentService, DbStore dbStore) {
        this.storeService = storeService;
        this.documentService = documentService;
        this.dbStore = dbStore;
    }

    public static class SyncChange {
        public String type;
        public String tableName;
        public String description;
        public String detail;
    }

    public static class FullSnapshotDTO {
        public String dataSourceId;
        public String schema;
        public List<TableSnapshot> tables;
    }

    public static class TableSnapshot {
        public String name;
        public String comment;
        public List<ColumnSnapshot> columns;
    }

    public static class ColumnSnapshot {
        public String name;
        public String dataType;
        public String nullable;
        public String comment;
    }

    public List<SyncChange> sync(String dataSourceId, String schema) {
        DataSourceConfigDTO ds = storeService.get(dataSourceId);
        if (ds == null) throw new IllegalArgumentException("数据源不存在");
        String effectiveSchema = schema != null ? schema : ds.getSchema();

        // Generate current full document
        Map<String, Object> current = documentService.generateDocument(dataSourceId, effectiveSchema, null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> currentTables = (List<Map<String, Object>>) current.get("tables");
        if (currentTables == null) currentTables = Collections.emptyList();

        // Build current snapshot structure
        Map<String, List<ColumnSnapshot>> currentMap = extractTableColumns(currentTables);

        // Load previous full snapshot
        FullSnapshotDTO previous = loadFullSnapshot(dataSourceId, effectiveSchema);
        Map<String, List<ColumnSnapshot>> previousMap = previous == null || previous.tables == null
            ? Collections.emptyMap() : toColumnMap(previous.tables);

        List<SyncChange> changes = new ArrayList<>();
        List<Map<String, Object>> dbChanges = new ArrayList<>();

        if (previousMap.isEmpty()) {
            // First sync
            for (String tn : currentMap.keySet()) {
                SyncChange c = new SyncChange();
                c.type = "added"; c.tableName = tn;
                c.description = "首次同步";
                changes.add(c);
                dbChanges.add(toDbChange(c));
            }
        } else {
            // Current tables: detect added and modified
            for (Map.Entry<String, List<ColumnSnapshot>> entry : currentMap.entrySet()) {
                String tn = entry.getKey();
                List<ColumnSnapshot> currCols = entry.getValue();

                if (!previousMap.containsKey(tn)) {
                    SyncChange c = new SyncChange();
                    c.type = "added"; c.tableName = tn;
                    c.description = "新增表: " + tn;
                    c.detail = "{\"columnCount\":" + currCols.size() + "}";
                    changes.add(c);
                    dbChanges.add(toDbChange(c));
                } else {
                    List<ColumnSnapshot> prevCols = previousMap.get(tn);
                    Map<String, Object> diffResult = diffColumns(prevCols, currCols);
                    if (diffResult != null) {
                        SyncChange c = new SyncChange();
                        c.type = "modified"; c.tableName = tn;
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> adds = (List<Map<String, Object>>) diffResult.get("adds");
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> drops = (List<Map<String, Object>>) diffResult.get("drops");
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> modifies = (List<Map<String, Object>>) diffResult.get("modifies");
                        int a = adds.size(), d = drops.size(), m = modifies.size();
                        c.description = "字段变更: +" + a + "/-" + d + "/改" + m;
                        try { c.detail = objectMapper.writeValueAsString(diffResult); } catch (Exception ignored) {}
                        changes.add(c);
                        dbChanges.add(toDbChange(c));
                    }
                }
            }
            // Deleted tables
            for (String tn : previousMap.keySet()) {
                if (!currentMap.containsKey(tn)) {
                    SyncChange c = new SyncChange();
                    c.type = "deleted"; c.tableName = tn;
                    c.description = "表已删除: " + tn;
                    c.detail = "{\"reason\":\"table_dropped\"}";
                    changes.add(c);
                    dbChanges.add(toDbChange(c));
                }
            }
        }

        // Persist snapshot + changes if there are changes
        if (!dbChanges.isEmpty()) {
            long seq = dbStore.insertSnapshot(dataSourceId, effectiveSchema, currentTables.size());
            dbStore.insertChanges(seq, dbChanges);
        }

        // Save full snapshot for next diff
        saveFullSnapshot(dataSourceId, effectiveSchema, currentTables);
        log.info("Sync for {}/{}: {} changes", dataSourceId, effectiveSchema, changes.size());
        return changes;
    }

    // Compatibility: old sync without schema param
    public List<SyncChange> sync(String dataSourceId) {
        return sync(dataSourceId, null);
    }

    // ── Diff logic ──────────────────────────────────

    private Map<String, List<ColumnSnapshot>> extractTableColumns(List<Map<String, Object>> tables) {
        Map<String, List<ColumnSnapshot>> result = new LinkedHashMap<>();
        for (Map<String, Object> t : tables) {
            String tn = (String) t.get("name");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> cols = (List<Map<String, Object>>) t.get("columns");
            if (cols == null) continue;
            List<ColumnSnapshot> snapshots = new ArrayList<>();
            for (Map<String, Object> c : cols) {
                ColumnSnapshot cs = new ColumnSnapshot();
                cs.name = (String) c.get("name");
                cs.dataType = (String) c.get("dataType");
                cs.nullable = Boolean.TRUE.equals(c.get("nullable")) ? "YES" : "NO";
                cs.comment = c.get("comment") instanceof String ? (String) c.get("comment") : "";
                snapshots.add(cs);
            }
            result.put(tn, snapshots);
        }
        return result;
    }

    private Map<String, Object> diffColumns(List<ColumnSnapshot> prev, List<ColumnSnapshot> curr) {
        Map<String, ColumnSnapshot> prevMap = new LinkedHashMap<>();
        for (ColumnSnapshot c : prev) prevMap.put(c.name, c);
        Map<String, ColumnSnapshot> currMap = new LinkedHashMap<>();
        for (ColumnSnapshot c : curr) currMap.put(c.name, c);

        List<Map<String, String>> adds = new ArrayList<>();
        List<Map<String, String>> drops = new ArrayList<>();
        List<Map<String, String>> modifies = new ArrayList<>();

        for (ColumnSnapshot c : curr) {
            if (!prevMap.containsKey(c.name)) {
                Map<String, String> m = new LinkedHashMap<>();
                m.put("name", c.name); m.put("type", c.dataType);
                adds.add(m);
            }
        }
        for (ColumnSnapshot c : prev) {
            if (!currMap.containsKey(c.name)) {
                Map<String, String> m = new LinkedHashMap<>();
                m.put("name", c.name); m.put("type", c.dataType);
                drops.add(m);
            }
        }
        for (ColumnSnapshot c : curr) {
            ColumnSnapshot old = prevMap.get(c.name);
            if (old != null && (!Objects.equals(old.dataType, c.dataType)
                    || !Objects.equals(old.nullable, c.nullable)
                    || !Objects.equals(old.comment, c.comment))) {
                Map<String, String> m = new LinkedHashMap<>();
                m.put("name", c.name);
                m.put("oldType", old.dataType);
                m.put("newType", c.dataType);
                modifies.add(m);
            }
        }

        if (adds.isEmpty() && drops.isEmpty() && modifies.isEmpty()) return null;

        // Also detect table comment changes
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("adds", adds);
        result.put("drops", drops);
        result.put("modifies", modifies);
        return result;
    }

    private Map<String, Object> toDbChange(SyncChange c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("changeType", c.type);
        m.put("tableName", c.tableName);
        m.put("columnName", null);
        m.put("detail", c.detail != null ? c.detail : "{}");
        m.put("description", c.description);
        return m;
    }

    // ── Full snapshot persistence ─────────────────────

    private Map<String, List<ColumnSnapshot>> toColumnMap(List<TableSnapshot> tables) {
        Map<String, List<ColumnSnapshot>> result = new LinkedHashMap<>();
        for (TableSnapshot t : tables) result.put(t.name, t.columns);
        return result;
    }

    private File getFullSnapshotFile(String dataSourceId, String schema) {
        String userHome = System.getProperty("user.home");
        File dir = new File(userHome, ".dbdoc-ai/snapshots");
        if (!dir.exists()) dir.mkdirs();
        String name = schema != null && !schema.isEmpty() ? dataSourceId + "_" + schema + ".json" : dataSourceId + ".json";
        return new File(dir, name);
    }

    private FullSnapshotDTO loadFullSnapshot(String dataSourceId, String schema) {
        File file = getFullSnapshotFile(dataSourceId, schema);
        if (!file.exists()) return null;
        try {
            return objectMapper.readValue(file, FullSnapshotDTO.class);
        } catch (Exception e) {
            log.warn("Failed to load full snapshot: {}", e.getMessage());
            return null;
        }
    }

    private void saveFullSnapshot(String dataSourceId, String schema, List<Map<String, Object>> tables) {
        FullSnapshotDTO dto = new FullSnapshotDTO();
        dto.dataSourceId = dataSourceId;
        dto.schema = schema;
        dto.tables = new ArrayList<>();
        for (Map<String, Object> t : tables) {
            TableSnapshot ts = new TableSnapshot();
            ts.name = (String) t.get("name");
            ts.comment = (String) t.get("comment");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> cols = (List<Map<String, Object>>) t.get("columns");
            ts.columns = new ArrayList<>();
            for (Map<String, Object> c : cols) {
                ColumnSnapshot cs = new ColumnSnapshot();
                cs.name = (String) c.get("name");
                cs.dataType = (String) c.get("dataType");
                cs.nullable = Boolean.TRUE.equals(c.get("nullable")) ? "YES" : "NO";
                cs.comment = c.get("comment") instanceof String ? (String) c.get("comment") : "";
                ts.columns.add(cs);
            }
            dto.tables.add(ts);
        }
        try {
            objectMapper.writeValue(getFullSnapshotFile(dataSourceId, schema), dto);
        } catch (Exception e) {
            log.error("Failed to save full snapshot: {}", e.getMessage(), e);
        }
    }
}
