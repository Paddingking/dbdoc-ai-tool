package com.dbdocai.service;

import com.dbdocai.dto.DataSourceConfigDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.util.*;

@Service
public class CrossDbCompareService {
    private static final Logger log = LoggerFactory.getLogger(CrossDbCompareService.class);
    private final DocumentService documentService;
    private final DataSourceStoreService storeService;

    public CrossDbCompareService(DocumentService documentService, DataSourceStoreService storeService) {
        this.documentService = documentService;
        this.storeService = storeService;
    }

    public static class ColumnDiff {
        public String type;  // added / removed / modified
        public String columnName;
        public String dataTypeA;
        public String dataTypeB;
        public String commentA;
        public String commentB;
    }

    public static class TableDiff {
        public String tableName;
        public int columnCountA;
        public int columnCountB;
        public boolean identical;
        public List<ColumnDiff> columnDiffs;
    }

    public static class CrossDbReport {
        public String sourceA;
        public String sourceB;
        public int tableCountA;
        public int tableCountB;
        public int commonTables;
        public int identicalTables;
        public int differentTables;
        public int onlyATables;
        public int onlyBTables;
        public List<TableDiff> commonTableDiffs;
        public List<String> onlyATableNames;
        public List<String> onlyBTableNames;
        public List<Map<String, String>> similarTables;  // {tableA, tableB, distance}
        public String generatedAt;
    }

    public CrossDbReport compare(String dsIdA, String schemaA, String dsIdB, String schemaB) {
        DataSourceConfigDTO dsa = storeService.get(dsIdA);
        DataSourceConfigDTO dsb = storeService.get(dsIdB);
        if (dsa == null) throw new IllegalArgumentException("数据源A不存在: " + dsIdA);
        if (dsb == null) throw new IllegalArgumentException("数据源B不存在: " + dsIdB);
        String nameA = dsa.getName() + " (" + schemaA + ")";
        String nameB = dsb.getName() + " (" + schemaB + ")";

        Map<String, Object> docA = documentService.generateDocument(dsIdA, schemaA, null);
        Map<String, Object> docB = documentService.generateDocument(dsIdB, schemaB, null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tablesA = (List<Map<String, Object>>) docA.get("tables");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tablesB = (List<Map<String, Object>>) docB.get("tables");

        Map<String, List<Map<String, Object>>> mapA = new LinkedHashMap<>();
        Map<String, List<Map<String, Object>>> mapB = new LinkedHashMap<>();
        for (Map<String, Object> t : tablesA) {
            List<Map<String, Object>> cols = (List<Map<String, Object>>) t.get("columns");
            mapA.put((String) t.get("name"), cols != null ? cols : Collections.emptyList());
        }
        for (Map<String, Object> t : tablesB) {
            List<Map<String, Object>> cols = (List<Map<String, Object>>) t.get("columns");
            mapB.put((String) t.get("name"), cols != null ? cols : Collections.emptyList());
        }

        Set<String> allNames = new LinkedHashSet<>();
        allNames.addAll(mapA.keySet());
        allNames.addAll(mapB.keySet());

        List<TableDiff> commonDiffs = new ArrayList<>();
        int identical = 0, different = 0, onlyA = 0, onlyB = 0, common = 0;
        List<String> onlyANames = new ArrayList<>();
        List<String> onlyBNames = new ArrayList<>();

        for (String tn : allNames) {
            List<Map<String, Object>> colsA = mapA.get(tn);
            List<Map<String, Object>> colsB = mapB.get(tn);

            if (colsA == null) { onlyBNames.add(tn); onlyB++; continue; }
            if (colsB == null) { onlyANames.add(tn); onlyA++; continue; }

            common++;
            TableDiff diff = compareColumns(tn, colsA, colsB);
            commonDiffs.add(diff);
            if (diff.identical) identical++; else different++;
        }

        // Similar table detection (limit to 500 names each side for performance)
        List<Map<String, String>> simTables = new ArrayList<>();
        List<String> aSub = onlyANames.size() > 500 ? onlyANames.subList(0, 500) : onlyANames;
        List<String> bSub = onlyBNames.size() > 500 ? onlyBNames.subList(0, 500) : onlyBNames;
        for (String nameA1 : aSub) {
            for (String nameB1 : bSub) {
                int dist = levenshtein(nameA1.toLowerCase(), nameB1.toLowerCase());
                if (dist <= 3 && Math.abs(nameA1.length() - nameB1.length()) <= 4) {
                    Map<String, String> m = new LinkedHashMap<>();
                    m.put("tableA", nameA1); m.put("tableB", nameB1); m.put("distance", String.valueOf(dist));
                    simTables.add(m);
                }
            }
        }

        CrossDbReport report = new CrossDbReport();
        report.sourceA = nameA; report.sourceB = nameB;
        report.tableCountA = tablesA.size(); report.tableCountB = tablesB.size();
        report.commonTables = common; report.identicalTables = identical; report.differentTables = different;
        report.onlyATables = onlyA; report.onlyBTables = onlyB;
        report.commonTableDiffs = commonDiffs;
        report.onlyATableNames = onlyANames; report.onlyBTableNames = onlyBNames;
        report.similarTables = simTables;
        report.generatedAt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        return report;
    }

    private TableDiff compareColumns(String tn, List<Map<String, Object>> colsA, List<Map<String, Object>> colsB) {
        Map<String, Map<String, Object>> mapB = new LinkedHashMap<>();
        for (Map<String, Object> c : colsB) mapB.put((String) c.get("name"), c);

        List<ColumnDiff> diffs = new ArrayList<>();
        for (Map<String, Object> ca : colsA) {
            Map<String, Object> cb = mapB.remove(ca.get("name"));
            ColumnDiff d = new ColumnDiff();
            d.columnName = (String) ca.get("name");
            if (cb == null) {
                d.type = "removed"; d.dataTypeA = (String) ca.get("dataType");
                d.commentA = (String) ca.get("comment");
            } else if (!Objects.equals(ca.get("dataType"), cb.get("dataType"))) {
                d.type = "modified";
                d.dataTypeA = (String) ca.get("dataType"); d.dataTypeB = (String) cb.get("dataType");
                d.commentA = (String) ca.get("comment"); d.commentB = (String) cb.get("comment");
            }
            if (d.type != null) diffs.add(d);
        }
        for (Map<String, Object> cb : mapB.values()) {
            ColumnDiff d = new ColumnDiff();
            d.type = "added"; d.columnName = (String) cb.get("name");
            d.dataTypeB = (String) cb.get("dataType"); d.commentB = (String) cb.get("comment");
            diffs.add(d);
        }

        boolean identical = diffs.isEmpty() && colsA.size() == colsB.size();
        TableDiff td = new TableDiff();
        td.tableName = tn; td.columnCountA = colsA.size(); td.columnCountB = colsB.size();
        td.identical = identical; td.columnDiffs = diffs;
        return td;
    }

    private int levenshtein(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= b.length(); j++) dp[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
            }
        }
        return dp[a.length()][b.length()];
    }
}
