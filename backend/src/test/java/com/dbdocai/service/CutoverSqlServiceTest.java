package com.dbdocai.service;

import com.dbdocai.dto.DataSourceConfigDTO;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CutoverSqlService 单元测试（合并验证：割接 SQL 生成）。
 *
 * <p>聚焦验证团队交付要点：
 * <ol>
 *   <li>IIF(cond,a,b) → CASE WHEN cond THEN a ELSE b END 单层翻译正确性；</li>
 *   <li>EXPR:/CAST: 规则翻译（EXPR 走 IIF 翻译，CAST 原样保留）；</li>
 *   <li>方言标识符：mysql 反引号 + START TRANSACTION/COMMIT；
 *       pg/oracle/dm/sqlite 双引号 + BEGIN/COMMIT；</li>
 *   <li>三段式文本结构（插入/行数校验/回滚提示，含注释标记）；</li>
 *   <li>边界：空 fieldMaps 不崩溃；方言 unknown / DataSource 为 null 不 NPE。</li>
 * </ol>
 *
 * <p>依赖 DataSourceStoreService 以 mock 注入，仅校验 dbType 分支，不建 JDBC 连接。
 */
public class CutoverSqlServiceTest {

    private final DataSourceStoreService storeService = mock(DataSourceStoreService.class);
    private final CutoverSqlService service = new CutoverSqlService(storeService);

    // ── 工具：构造方言 DTO ────────────────────────────────

    private static DataSourceConfigDTO dto(String dbType) {
        DataSourceConfigDTO d = new DataSourceConfigDTO();
        d.setId("ds");
        d.setName("DS");
        d.setDbType(dbType);
        return d;
    }

    /** 构造一个含单个 usable 字段映射的 MatchResult（status=matched）。 */
    private static FieldMappingService.MatchResult oneMapping(String srcCol, String tgtCol, String rule) {
        FieldMappingService.MatchResult mr = new FieldMappingService.MatchResult();
        FieldMappingService.FieldMapping fm = new FieldMappingService.FieldMapping();
        fm.sourceTable = "SRC";
        fm.targetTable = "TGT";
        fm.sourceColumn = srcCol;
        fm.targetColumn = tgtCol;
        fm.status = "matched";
        fm.transformRule = rule;
        mr.mappings = Collections.singletonList(fm);
        return mr;
    }

    private static List<Map<String, String>> oneTableMapping() {
        Map<String, String> tm = new LinkedHashMap<>();
        tm.put("sourceTable", "SRC");
        tm.put("targetTable", "TGT");
        return Collections.singletonList(tm);
    }

    // ── 1. IIF → CASE WHEN 翻译（私有方法，反射直测） ──────────

    @Test
    public void iifToCaseWhen_basicTranslation() throws Exception {
        Method m = CutoverSqlService.class.getDeclaredMethod("iifToCaseWhen", String.class);
        m.setAccessible(true);
        assertEquals("CASE WHEN x='Y' THEN 1 ELSE 0 END",
                m.invoke(null, "IIF(x='Y',1,0)"));
    }

    @Test
    public void iifToCaseWhen_withSpaces() throws Exception {
        Method m = CutoverSqlService.class.getDeclaredMethod("iifToCaseWhen", String.class);
        m.setAccessible(true);
        assertEquals("CASE WHEN a = 1 THEN b = 2 ELSE 3 END",
                m.invoke(null, "IIF( a = 1 , b = 2 , 3 )"));
    }

    @Test
    public void iifToCaseWhen_singleLevel_nestedIifNotTranslated() throws Exception {
        // 仅单层：内层 IIF 保留原样，不递归翻译
        Method m = CutoverSqlService.class.getDeclaredMethod("iifToCaseWhen", String.class);
        m.setAccessible(true);
        assertEquals("CASE WHEN a=1 THEN IIF(b=2,3,4) ELSE 5 END",
                m.invoke(null, "IIF(a=1, IIF(b=2,3,4), 5)"));
    }

    @Test
    public void iifToCaseWhen_wrongArity_returnsUnchanged() throws Exception {
        // 不是恰好 3 个顶层参数 → 原样返回，不翻译
        Method m = CutoverSqlService.class.getDeclaredMethod("iifToCaseWhen", String.class);
        m.setAccessible(true);
        assertEquals("IIF(a, b, c, d)", m.invoke(null, "IIF(a, b, c, d)"));   // 4 参数
        assertEquals("IIF(cond, a)", m.invoke(null, "IIF(cond, a)"));       // 2 参数
        assertEquals("not-iif(x)", m.invoke(null, "not-iif(x)"));           // 非 IIF
    }

    // ── 2. toSqlExpression（EXPR:/CAST:/无 rule） ─────────────

    @Test
    public void toSqlExpression_exprTranslatesIif() throws Exception {
        Method m = CutoverSqlService.class.getDeclaredMethod("toSqlExpression",
                FieldMappingService.FieldMapping.class);
        m.setAccessible(true);
        FieldMappingService.FieldMapping fm = new FieldMappingService.FieldMapping();
        fm.sourceColumn = "src_col";
        fm.transformRule = "EXPR:IIF(flag='Y',1,0)";
        assertEquals("CASE WHEN flag='Y' THEN 1 ELSE 0 END", m.invoke(null, fm));
    }

    @Test
    public void toSqlExpression_castPreservedVerbatim() throws Exception {
        Method m = CutoverSqlService.class.getDeclaredMethod("toSqlExpression",
                FieldMappingService.FieldMapping.class);
        m.setAccessible(true);
        FieldMappingService.FieldMapping fm = new FieldMappingService.FieldMapping();
        fm.sourceColumn = "src_col";
        fm.transformRule = "CAST:TO_CHAR(birth)";
        assertEquals("TO_CHAR(birth)", m.invoke(null, fm));
    }

    @Test
    public void toSqlExpression_noRule_usesSourceColumn() throws Exception {
        Method m = CutoverSqlService.class.getDeclaredMethod("toSqlExpression",
                FieldMappingService.FieldMapping.class);
        m.setAccessible(true);
        FieldMappingService.FieldMapping fm = new FieldMappingService.FieldMapping();
        fm.sourceColumn = "src_col";
        fm.transformRule = null;
        assertEquals("src_col", m.invoke(null, fm));

        fm.transformRule = "   ";
        assertEquals("src_col", m.invoke(null, fm));
    }

    // ── 3. 方言标识符与事务关键字 ──────────────────────────

    @Test
    public void generateCutoverSql_mysql_usesBackticksAndStartTransaction() {
        when(storeService.get("dsA")).thenReturn(dto("mysql"));
        when(storeService.get("dsB")).thenReturn(dto("mysql"));

        String sql = service.generateCutoverSql("dsA", null, "dsB", null,
                oneTableMapping(),
                Collections.singletonMap("SRC→TGT", oneMapping("src_col", "tgt_col", null)));

        assertTrue(sql.contains("`SRC`"), "源表应反引号");
        assertTrue(sql.contains("`TGT`"), "目标表应反引号");
        assertTrue(sql.contains("`tgt_col`"), "目标列应反引号");
        // 注意：SELECT 中的源列表达式按设计不引用（仅目标列/表名引用），与桌面原版一致
        assertTrue(sql.contains("src_col"), "SELECT 表达式中的源列应原样出现（不引用，符合设计）");
        assertTrue(sql.contains("START TRANSACTION;"), "B 库 mysql 应用 START TRANSACTION");
        assertTrue(sql.contains("COMMIT;"), "应含 COMMIT");
        assertTrue(sql.contains("INSERT INTO"), "应含 INSERT INTO");
    }

    @Test
    public void generateCutoverSql_postgresql_usesDoubleQuotesAndBeginCommit() {
        when(storeService.get("dsA")).thenReturn(dto("postgresql"));
        when(storeService.get("dsB")).thenReturn(dto("postgresql"));

        String sql = service.generateCutoverSql("dsA", null, "dsB", null,
                oneTableMapping(),
                Collections.singletonMap("SRC→TGT", oneMapping("src_col", "tgt_col", null)));

        assertTrue(sql.contains("\"SRC\""), "源表应双引号");
        assertTrue(sql.contains("\"TGT\""), "目标表应双引号");
        assertTrue(sql.contains("\"tgt_col\""), "目标列应双引号");
        assertTrue(sql.contains("BEGIN;"), "B 库非 mysql 应用 BEGIN");
        assertTrue(sql.contains("COMMIT;"), "应含 COMMIT");
    }

    @Test
    public void generateCutoverSql_oracle_dm_sqlite_useDoubleQuotes() {
        for (String dialect : new String[]{"oracle", "dm", "sqlite"}) {
            when(storeService.get("dsA")).thenReturn(dto(dialect));
            when(storeService.get("dsB")).thenReturn(dto(dialect));

            String sql = service.generateCutoverSql("dsA", null, "dsB", null,
                    oneTableMapping(),
                    Collections.singletonMap("SRC→TGT", oneMapping("src_col", "tgt_col", null)));

            assertTrue(sql.contains("\"" + "SRC\""), dialect + ": 源表应双引号");
            assertTrue(sql.contains("\"" + "TGT\""), dialect + ": 目标表应双引号");
            assertTrue(sql.contains("BEGIN;"), dialect + ": 应用 BEGIN");
        }
    }

    @Test
    public void generateCutoverSql_dialectCaseInsensitive() {
        // dbType 大小写不敏感（toLowerCase 分支）
        when(storeService.get("dsA")).thenReturn(dto("MySQL"));
        when(storeService.get("dsB")).thenReturn(dto("PostgreSQL"));

        String sql = service.generateCutoverSql("dsA", null, "dsB", null,
                oneTableMapping(),
                Collections.singletonMap("SRC→TGT", oneMapping("src_col", "tgt_col", null)));

        assertTrue(sql.contains("`SRC`"), "MySQL(大写) 应视为 mysql → 反引号");
        assertTrue(sql.contains("\"TGT\""), "PostgreSQL(大写) 应视为 postgresql → 双引号");
        assertTrue(sql.contains("BEGIN;"), "A 为 mysql 不影响 B；B 为 postgresql → BEGIN/COMMIT");
    }

    @Test
    public void generateCutoverSql_crossDialect_sourceMysqlTargetPg() {
        when(storeService.get("dsA")).thenReturn(dto("mysql"));
        when(storeService.get("dsB")).thenReturn(dto("postgresql"));

        String sql = service.generateCutoverSql("dsA", null, "dsB", null,
                oneTableMapping(),
                Collections.singletonMap("SRC→TGT", oneMapping("src_col", "tgt_col", null)));

        assertTrue(sql.contains("`SRC`"), "源(mysql)表反引号");
        assertTrue(sql.contains("\"TGT\""), "目标(pg)表双引号");
        assertTrue(sql.contains("BEGIN;"), "B(pg) → BEGIN");
    }

    // ── 4. 三段式结构（含 EXPR/CAST 全流程） ────────────────

    @Test
    public void generateCutoverSql_threePartStructure_present() {
        when(storeService.get("dsA")).thenReturn(dto("mysql"));
        when(storeService.get("dsB")).thenReturn(dto("mysql"));

        String sql = service.generateCutoverSql("dsA", null, "dsB", null,
                oneTableMapping(),
                Collections.singletonMap("SRC→TGT", oneMapping("src_col", "tgt_col", null)));

        // ① 全量 INSERT…SELECT
        assertTrue(sql.contains("INSERT INTO"), "应含 ① 全量 INSERT");
        assertTrue(sql.contains("SELECT"), "应含 SELECT 源数据");
        // ② 行数校验
        assertTrue(sql.contains("-- 行数校验"), "应含 ② 行数校验注释");
        assertTrue(sql.contains("src_count"), "行数校验应含 src_count");
        assertTrue(sql.contains("tgt_count"), "行数校验应含 tgt_count");
        // ③ 回滚提示
        assertTrue(sql.contains("-- 字段覆盖报告"), "应含 ③ 覆盖报告");
        assertTrue(sql.contains("-- ROLLBACK;"), "应含 ③ -- ROLLBACK; 提示注释（不包裹事务）");
    }

    @Test
    public void generateCutoverSql_exprRule_translatedInSelect() {
        when(storeService.get("dsA")).thenReturn(dto("mysql"));
        when(storeService.get("dsB")).thenReturn(dto("mysql"));

        String sql = service.generateCutoverSql("dsA", null, "dsB", null,
                oneTableMapping(),
                Collections.singletonMap("SRC→TGT", oneMapping("src_col", "tgt_col", "EXPR:IIF(x='Y',1,0)")));

        assertTrue(sql.contains("CASE WHEN x='Y' THEN 1 ELSE 0 END"),
                "EXPR 规则应在 SELECT 中翻译为 CASE WHEN");
    }

    @Test
    public void generateCutoverSql_castRule_preservedInSelect() {
        when(storeService.get("dsA")).thenReturn(dto("postgresql"));
        when(storeService.get("dsB")).thenReturn(dto("postgresql"));

        String sql = service.generateCutoverSql("dsA", null, "dsB", null,
                oneTableMapping(),
                Collections.singletonMap("SRC→TGT", oneMapping("src_col", "tgt_col", "CAST:TO_CHAR(birth)")));

        assertTrue(sql.contains("TO_CHAR(birth)"), "CAST 规则应原样保留于 SELECT");
    }

    @Test
    public void generateCutoverSql_schemaQualifiedTables() {
        when(storeService.get("dsA")).thenReturn(dto("postgresql"));
        when(storeService.get("dsB")).thenReturn(dto("postgresql"));

        String sql = service.generateCutoverSql("dsA", "sch_a", "dsB", "sch_b",
                oneTableMapping(),
                Collections.singletonMap("SRC→TGT", oneMapping("src_col", "tgt_col", null)));

        assertTrue(sql.contains("\"sch_a\".\"SRC\""), "源表应带 schema 限定");
        assertTrue(sql.contains("\"sch_b\".\"TGT\""), "目标表应带 schema 限定");
    }

    // ── 5. 边界：空 / unknown / null ─────────────────────

    @Test
    public void generateCutoverSql_emptyFieldMaps_noCrash() {
        when(storeService.get("dsA")).thenReturn(dto("mysql"));
        when(storeService.get("dsB")).thenReturn(dto("mysql"));

        // fieldMaps 为空 → 每个表对 mr=null → 跳过，不应抛异常或 NPE
        String sql = service.generateCutoverSql("dsA", null, "dsB", null,
                oneTableMapping(), new LinkedHashMap<>());

        assertNotNull(sql, "空 fieldMaps 应返回非空文本");
        assertFalse(sql.contains("INSERT INTO"), "无可用映射不应生成 INSERT");
        assertTrue(sql.contains("-- 数据割接脚本"), "应仍含头部注释");
    }

    @Test
    public void generateCutoverSql_unknownDialect_noNpe() {
        // dbType 为 null → dialectOf 返回 "unknown" → 走双引号分支
        when(storeService.get("dsA")).thenReturn(dto(null));
        when(storeService.get("dsB")).thenReturn(dto(null));

        String sql = service.generateCutoverSql("dsA", null, "dsB", null,
                oneTableMapping(),
                Collections.singletonMap("SRC→TGT", oneMapping("src_col", "tgt_col", null)));

        assertNotNull(sql);
        assertTrue(sql.contains("\"SRC\""), "unknown 方言应回退双引号");
        assertTrue(sql.contains("BEGIN;"), "unknown 方言应回退 BEGIN/COMMIT");
        assertFalse(sql.contains("START TRANSACTION;"), "unknown 非 mysql → 不应 START TRANSACTION");
    }

    @Test
    public void generateCutoverSql_nullDataSource_noNpe() {
        // storeService.get 返回 null（记录不存在）→ 头部回退 dsId，dialect unknown
        when(storeService.get("dsA")).thenReturn(null);
        when(storeService.get("dsB")).thenReturn(null);

        String sql = service.generateCutoverSql("dsA", null, "dsB", null,
                oneTableMapping(),
                Collections.singletonMap("SRC→TGT", oneMapping("src_col", "tgt_col", null)));

        assertNotNull(sql);
        assertTrue(sql.contains("dsA"), "头部应回退显示 dsId");
        assertTrue(sql.contains("dsB"), "头部应回退显示 dsId");
    }

    @Test
    public void generateCutoverSql_skipsOnlySourceOrTargetColumns() {
        // 全部字段为 source_only / target_only → 无 usable → 跳过该表对
        when(storeService.get("dsA")).thenReturn(dto("mysql"));
        when(storeService.get("dsB")).thenReturn(dto("mysql"));

        FieldMappingService.MatchResult mr = new FieldMappingService.MatchResult();
        FieldMappingService.FieldMapping so = new FieldMappingService.FieldMapping();
        so.sourceTable = "SRC"; so.sourceColumn = "s1"; so.status = "source_only";
        FieldMappingService.FieldMapping to = new FieldMappingService.FieldMapping();
        to.targetTable = "TGT"; to.targetColumn = "t1"; to.status = "target_only";
        mr.mappings = Arrays.asList(so, to);

        String sql = service.generateCutoverSql("dsA", null, "dsB", null,
                oneTableMapping(), Collections.singletonMap("SRC→TGT", mr));

        assertTrue(sql.contains("（无可用字段映射）"), "应跳过并标注无可用字段映射");
        assertFalse(sql.contains("INSERT INTO"), "无 usable 字段不应生成 INSERT");
    }
}
