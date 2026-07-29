package com.dbdocai.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.xwpf.usermodel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * 数据库文档导出服务（PDF / Word）。
 *
 * <p>Round 2 安全修复（P0：导出路径穿越 + 标识符长度崩溃）：
 * <ul>
 *   <li>{@code dataSourceId} 来自请求体，曾直接拼入文件名（{@code "dbdoc-" + dataSourceId.substring(0,8)}），
 *       既存在路径穿越风险（含 {@code ../} 可逃出 exports 目录），又会在 id 长度不足 8 时抛
 *       {@link StringIndexOutOfBoundsException} 导致 500。</li>
 *   <li>现改为：① 对 {@code dataSourceId} 做严格格式校验（正则 {@code ^[A-Za-z0-9_-]{1,64}$}），
 *       不合法直接抛 {@link IllegalArgumentException}（由 {@link com.dbdocai.config.GlobalExceptionHandler}
 *       映射为 400）；② 文件名不再拼接任何用户输入，而是使用 {@code dataSourceId} 的 SHA-256 哈希前 16 位
 *       （仅含 {@code [0-9a-f]}，天然满足 {@code [A-Za-z0-9_-]}），保证确定性且不可被注入路径；
 *       ③ 输出文件经 {@code getCanonicalPath()} 做"是否仍位于 exports 目录内"的范围断言（纵深防御），
 *       越界直接抛 {@link SecurityException} 拒绝写入。</li>
 * </ul>
 */
@Service
public class DocExportService {
    private static final Logger log = LoggerFactory.getLogger(DocExportService.class);
    private final DocumentService documentService;

    /** dataSourceId 格式白名单：仅允许字母/数字/下划线/连字符，长度 1-64。 */
    private static final String DATA_SOURCE_ID_PATTERN = "^[A-Za-z0-9_-]{1,64}$";

    /** 文件名使用的哈希前缀长度（SHA-256 hex 的前 N 位，仅含 [0-9a-f]）。 */
    private static final int HASH_PREFIX_LEN = 16;

    public DocExportService(DocumentService documentService) {
        this.documentService = documentService;
    }

    public String exportPdf(String dataSourceId, List<String> tableNames) {
        Map<String, Object> doc = documentService.generateDocument(dataSourceId, null, tableNames);
        File outFile;
        try {
            outFile = resolveExportFile(dataSourceId, ".pdf");
        } catch (IOException e) {
            throw new RuntimeException("导出目录准备失败: " + e.getMessage(), e);
        }

        try (PDDocument pdfDoc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            pdfDoc.addPage(page);

            PDPageContentStream cs = new PDPageContentStream(pdfDoc, page);
            cs.beginText();
            cs.setFont(PDType1Font.HELVETICA, 9);
            cs.setLeading(12);
            cs.newLineAtOffset(50, 750);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> tables = (List<Map<String, Object>>) doc.get("tables");

            cs.showText("Database Documentation");
            cs.newLine();
            cs.showText("Generated: " + doc.get("generatedAt") + "  Tables: " + tables.size());
            cs.newLine();
            cs.newLine();

            for (Map<String, Object> table : tables) {
                String tn = (String) table.get("name");
                String comment = (String) table.get("comment");
                String title = "Table: " + tn + (comment != null && !comment.isEmpty() ? " - " + comment : "");
                cs.showText(title.length() > 80 ? title.substring(0, 77) + "..." : title);
                cs.newLine();

                @SuppressWarnings("unchecked")
                List<Map<String, Object>> cols = (List<Map<String, Object>>) table.get("columns");
                if (cols != null) {
                    for (Map<String, Object> col : cols) {
                        String marker = Boolean.TRUE.equals(col.get("primaryKey")) ? "PK" : "  ";
                        String cn = (String) col.get("name");
                        String type = (String) col.get("dataType");
                        String nullable = Boolean.TRUE.equals(col.get("nullable")) ? " " : "NOT NULL";
                        String colComment = (String) col.get("comment");
                        String line = String.format("  %s %-20s %-15s %-8s %s",
                            marker, cn != null ? cn : "", type != null ? type : "",
                            nullable, colComment != null ? colComment : "");
                        cs.showText(line.length() > 100 ? line.substring(0, 97) + "..." : line);
                        cs.newLine();
                    }
                }
                cs.newLine();
            }

            cs.endText();
            cs.close();
            pdfDoc.save(outFile);
            return outFile.getAbsolutePath();
        } catch (Exception e) {
            throw new RuntimeException("PDF导出失败: " + e.getMessage(), e);
        }
    }

    public String exportWord(String dataSourceId, List<String> tableNames) {
        Map<String, Object> doc = documentService.generateDocument(dataSourceId, null, tableNames);
        File outFile;
        try {
            outFile = resolveExportFile(dataSourceId, ".docx");
        } catch (IOException e) {
            throw new RuntimeException("导出目录准备失败: " + e.getMessage(), e);
        }

        try (XWPFDocument word = new XWPFDocument()) {
            XWPFParagraph title = word.createParagraph();
            title.setAlignment(ParagraphAlignment.CENTER);
            XWPFRun titleRun = title.createRun();
            titleRun.setText("数据库文档");
            titleRun.setBold(true);
            titleRun.setFontSize(18);

            XWPFParagraph meta = word.createParagraph();
            XWPFRun metaRun = meta.createRun();
            metaRun.setText("生成时间: " + doc.get("generatedAt"));
            metaRun.setFontSize(10);
            metaRun.setColor("888888");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> modules = (List<Map<String, Object>>) doc.get("modules");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> tables = (List<Map<String, Object>>) doc.get("tables");

            for (Map<String, Object> mod : modules) {
                XWPFParagraph h2 = word.createParagraph();
                XWPFRun h2Run = h2.createRun();
                h2Run.setText((String) mod.get("name"));
                h2Run.setBold(true);
                h2Run.setFontSize(14);

                @SuppressWarnings("unchecked")
                List<String> modTableNames = (List<String>) mod.get("tableNames");

                for (String tn : modTableNames) {
                    Map<String, Object> table = null;
                    for (Map<String, Object> t : tables) {
                        if (tn.equals(t.get("name"))) { table = t; break; }
                    }
                    if (table == null) continue;

                    XWPFParagraph h3 = word.createParagraph();
                    XWPFRun h3Run = h3.createRun();
                    String tableTitle = tn;
                    if (table.get("comment") != null && !((String) table.get("comment")).isEmpty()) {
                        tableTitle += " — " + table.get("comment");
                    }
                    h3Run.setText(tableTitle);
                    h3Run.setBold(true);
                    h3Run.setFontSize(12);

                    XWPFTable wt = word.createTable();
                    XWPFTableRow hdr = wt.getRow(0);
                    setCell(hdr.getCell(0), "#", true);
                    setCell(hdr.createCell(), "字段名", true);
                    setCell(hdr.createCell(), "类型", true);
                    setCell(hdr.createCell(), "必填", true);
                    setCell(hdr.createCell(), "说明", true);

                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> columns = (List<Map<String, Object>>) table.get("columns");
                    if (columns != null) {
                        for (Map<String, Object> col : columns) {
                            XWPFTableRow row = wt.createRow();
                            setCell(row.getCell(0), String.valueOf(col.get("ordinalPosition")), false);
                            String cn = (String) col.get("name");
                            if (Boolean.TRUE.equals(col.get("primaryKey"))) cn = "PK " + cn;
                            setCell(row.getCell(1), cn, false);
                            setCell(row.getCell(2), String.valueOf(col.get("dataType")), false);
                            setCell(row.getCell(3), Boolean.TRUE.equals(col.get("nullable")) ? "" : "NOT NULL", false);
                            setCell(row.getCell(4), col.get("comment") != null ? (String) col.get("comment") : "-", false);
                        }
                    }
                    word.createParagraph();
                }
            }

            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                word.write(fos);
            }
            return outFile.getAbsolutePath();
        } catch (Exception e) {
            throw new RuntimeException("Word导出失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解析并校验导出目标文件，执行路径穿越纵深防御。
     *
     * <p>关键安全点：
     * <ol>
     *   <li>校验 {@code dataSourceId} 格式，非法直接抛 {@link IllegalArgumentException}（→ 400）；</li>
     *   <li>文件名完全由 {@code dataSourceId} 的 SHA-256 哈希派生，绝不拼接用户输入，
     *       因此无法注入 {@code ../} 等路径片段；</li>
     *   <li>对输出目录与目标文件取 canonical 路径，断言目标文件确实位于输出目录内，
     *       任何越界（理论上已被哈希文件名杜绝）一律抛 {@link SecurityException} 拒绝。</li>
     * </ol>
     *
     * @param dataSourceId 请求体传入的数据源标识
     * @param extension    文件扩展名（含点，如 {@code .pdf}）
     * @return 校验通过且范围安全的导出目标文件
     * @throws IllegalArgumentException dataSourceId 格式非法时
     * @throws SecurityException       目标文件路径越出允许目录时（纵深防御）
     * @throws IOException             解析 canonical 路径失败时
     */
    private File resolveExportFile(String dataSourceId, String extension) throws IOException {
        if (dataSourceId == null || !dataSourceId.matches(DATA_SOURCE_ID_PATTERN)) {
            throw new IllegalArgumentException(
                    "非法的数据源标识（dataSourceId），已拒绝导出: " + dataSourceId);
        }

        // 文件名仅由哈希前缀构成（[0-9a-f]，天然满足 [A-Za-z0-9_-]），不拼接任何用户输入
        String safeName = "dbdoc-" + sha256Hex(dataSourceId).substring(0, HASH_PREFIX_LEN) + extension;

        String userHome = System.getProperty("user.home");
        File outDir = new File(userHome, ".dbdoc-ai/exports");
        if (!outDir.exists()) {
            outDir.mkdirs();
        }

        File outFile = new File(outDir, safeName);
        assertWithinDirectory(outDir, outFile);
        return outFile;
    }

    /**
     * 纵深防御：断言目标文件的 canonical 路径确实位于输出目录的 canonical 路径之内。
     *
     * @param dir  输出目录
     * @param file 目标文件
     * @throws SecurityException 越出目录范围时
     * @throws IOException       解析 canonical 路径失败时
     */
    private static void assertWithinDirectory(File dir, File file) throws IOException {
        String canonicalDir = dir.getCanonicalPath();
        String canonicalFile = file.getCanonicalPath();
        boolean inside = canonicalFile.equals(canonicalDir)
                || canonicalFile.startsWith(canonicalDir + File.separator);
        if (!inside) {
            throw new SecurityException(
                    "导出目标文件路径未落在允许的目录内，已拒绝写入: " + canonicalFile);
        }
    }

    /** 计算输入字符串的 SHA-256 hex 摘要（JDK8 内置算法，理论上不会缺失）。 */
    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 算法不可用，无法安全生成导出文件名", e);
        }
    }

    private void setCell(XWPFTableCell cell, String text, boolean bold) {
        XWPFParagraph p = cell.getParagraphs().get(0);
        XWPFRun run = p.createRun();
        run.setText(text);
        if (bold) run.setBold(true);
        run.setFontSize(10);
    }
}
