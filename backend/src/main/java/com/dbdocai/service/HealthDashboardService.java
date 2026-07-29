package com.dbdocai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class HealthDashboardService {
    private static final Logger log = LoggerFactory.getLogger(HealthDashboardService.class);
    private final DocumentService documentService;

    public HealthDashboardService(DocumentService documentService) {
        this.documentService = documentService;
    }

    public static class HealthSummary {
        public String tableName;
        public int columnCount;
        public int commentCount;
        public boolean hasPk;
        public int fkCount;
        public int indexCount;
    }

    public static class HealthDashboard {
        public String dataSourceId;
        public String schema;
        public int totalTables;
        public int totalColumns;
        public int healthScore;
        public String grade;
        public double commentCoverage;
        public double pkCoverage;
        public double indexCoverage;
        public int fkCount;
        public List<HealthSummary> topCommented;
        public List<HealthSummary> needAttention;
        public List<HealthSummary> widestTables;
        public List<HealthSummary> mostConnected;
        public String generatedAt;
    }

    public HealthDashboard analyze(String dataSourceId, String schema) {
        Map<String, Object> doc = documentService.generateDocument(dataSourceId, schema, null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tables = (List<Map<String, Object>>) doc.get("tables");

        int totalColumns = 0, commentedColumns = 0, pkCount = 0, indexCount = 0, fkTotal = 0;
        List<HealthSummary> summaries = new ArrayList<>();

        for (Map<String, Object> t : tables) {
            String tn = (String) t.get("name");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> cols = (List<Map<String, Object>>) t.get("columns");
            if (cols == null) cols = Collections.emptyList();
            @SuppressWarnings("unchecked")
            List<Map<String, String>> fks = (List<Map<String, String>>) t.get("foreignKeys");
            @SuppressWarnings("unchecked")
            List<Map<String, String>> idxs = (List<Map<String, String>>) t.get("indexes");

            int colCount = cols.size();
            int comCount = (int) cols.stream().filter(c -> {
                String com = (String) c.get("comment");
                return com != null && !com.isEmpty();
            }).count();
            boolean hasPk = cols.stream().anyMatch(c -> Boolean.TRUE.equals(c.get("primaryKey")));
            long idxCnt = idxs.stream().map(i -> i.get("name")).distinct().count();

            totalColumns += colCount;
            commentedColumns += comCount;
            if (hasPk) pkCount++;
            if (idxCnt > 0) indexCount++;
            fkTotal += fks.size();

            HealthSummary hs = new HealthSummary();
            hs.tableName = tn; hs.columnCount = colCount; hs.commentCount = comCount;
            hs.hasPk = hasPk; hs.fkCount = fks.size(); hs.indexCount = (int) idxCnt;
            summaries.add(hs);
        }

        HealthDashboard db = new HealthDashboard();
        db.dataSourceId = dataSourceId; db.schema = schema;
        db.totalTables = tables.size(); db.totalColumns = totalColumns;
        db.commentCoverage = totalColumns > 0 ? (double) commentedColumns / totalColumns : 0;
        db.pkCoverage = tables.size() > 0 ? (double) pkCount / tables.size() : 0;
        db.indexCoverage = tables.size() > 0 ? (double) indexCount / tables.size() : 0;
        db.fkCount = fkTotal;

        double fkDensity = tables.size() > 0 ? Math.min((double) fkTotal / tables.size() / 3, 1.0) : 0;
        db.healthScore = (int) (db.commentCoverage * 30 + db.pkCoverage * 30 + db.indexCoverage * 20 + fkDensity * 20);
        if (db.healthScore >= 80) db.grade = "excellent";
        else if (db.healthScore >= 60) db.grade = "good";
        else if (db.healthScore >= 40) db.grade = "fair";
        else db.grade = "poor";

        db.topCommented = summaries.stream()
            .sorted((a, b) -> Integer.compare(b.commentCount, a.commentCount)).limit(10).collect(Collectors.toList());
        db.widestTables = summaries.stream()
            .sorted((a, b) -> Integer.compare(b.columnCount, a.columnCount)).limit(10).collect(Collectors.toList());
        db.mostConnected = summaries.stream()
            .sorted((a, b) -> Integer.compare(b.fkCount, a.fkCount)).limit(10).collect(Collectors.toList());
        db.needAttention = summaries.stream()
            .filter(s -> !s.hasPk || s.commentCount == 0)
            .sorted(Comparator.comparingInt(s -> (s.hasPk ? 1 : 0) + s.commentCount)).limit(10).collect(Collectors.toList());

        db.generatedAt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        return db;
    }
}
