package com.dbdocai.service;

import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SyncService 快照文件路径穿越防护测试（S1 / P1-1）。
 * 直接校验 getFullSnapshotFile 的 32-hex ID 校验 + schema 白名单 + canonical 越界断言。
 */
public class SyncServiceSnapshotPathTest {

    // 依赖字段在本测试中不被使用，注入 null 即可（构造器仅赋值，不触发 IO）。
    private final SyncService syncService = new SyncService(null, null, null);

    private static final String VALID_ID = "abcdef0123456789abcdef0123456789";

    @Test
    public void validIdAndSchemaResolvesInsideSnapshotsDir() throws Exception {
        File f = syncService.getFullSnapshotFile(VALID_ID, "public");
        String base = new File(System.getProperty("user.home"), ".dbdoc-ai/snapshots").getCanonicalPath();
        String canonical = f.getCanonicalPath();
        assertTrue(canonical.startsWith(base), "必须锁定在 snapshots 目录内: " + canonical);
        assertTrue(canonical.endsWith(VALID_ID + "_public.json"), "文件名应安全拼接: " + canonical);
    }

    @Test
    public void emptySchemaFallsBackToBareIdFilename() {
        File f = syncService.getFullSnapshotFile(VALID_ID, null);
        assertTrue(f.getName().equals(VALID_ID + ".json"), "空 schema 应回退为裸 id 文件名");
        assertDoesNotThrow(() -> f.getCanonicalPath());
    }

    @Test
    public void nonHexDataSourceIdRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> syncService.getFullSnapshotFile("not-a-hex-id", "public"));
    }

    @Test
    public void traversalSchemaRejectedByWhitelist() {
        assertThrows(IllegalArgumentException.class,
                () -> syncService.getFullSnapshotFile(VALID_ID, "../../etc/passwd"));
    }

    @Test
    public void schemaWithPathSeparatorRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> syncService.getFullSnapshotFile(VALID_ID, "a/b"));
    }
}
