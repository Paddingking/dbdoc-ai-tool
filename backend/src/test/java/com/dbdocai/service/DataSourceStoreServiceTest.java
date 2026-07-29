package com.dbdocai.service;

import com.dbdocai.dto.DataSourceConfigDTO;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DataSourceStoreService 单元测试（P0-1：密码脱敏阻断带密码库重连）。
 *
 * <p>使用真实的 {@link DbStore}（SQLite，位于 user.home/.dbdoc-ai/dbdoc.db）。
 * 验证：对外 {@code get()}/{@code list()} 返回脱敏密码 "***"，
 * 而内部 {@code getWithSecret()} 返回真实密码供重连使用。
 */
public class DataSourceStoreServiceTest {

    private DataSourceStoreService newService() {
        DbStore db = new DbStore();
        db.init();
        return new DataSourceStoreService(db);
    }

    @Test
    public void getMasksPassword_and_getWithSecretReturnsReal() {
        DataSourceStoreService svc = newService();

        String id = "ut-" + UUID.randomUUID().toString().replace("-", "");
        DataSourceConfigDTO dto = new DataSourceConfigDTO();
        dto.setId(id);
        dto.setName("unit-test-ds");
        dto.setDbType("mysql");
        dto.setUrl("jdbc:mysql://localhost:3306/test");
        dto.setUsername("root");
        dto.setPassword("s3cret-p@ss");

        svc.add(dto);

        // 对外接口必须脱敏
        DataSourceConfigDTO masked = svc.get(id);
        assertNotNull(masked);
        assertEquals("***", masked.getPassword(), "对外 get() 密码必须脱敏为 ***");

        // 内部重连必须拿到真实密码
        DataSourceConfigDTO secret = svc.getWithSecret(id);
        assertNotNull(secret);
        assertEquals("s3cret-p@ss", secret.getPassword(), "getWithSecret() 必须返回真实密码");

        // 列表接口同样脱敏
        boolean foundMasked = false;
        for (DataSourceConfigDTO d : svc.list()) {
            if (id.equals(d.getId())) {
                assertEquals("***", d.getPassword());
                foundMasked = true;
            }
        }
        assertTrue(foundMasked, "列表应含有该数据源且密码脱敏");
    }
}
