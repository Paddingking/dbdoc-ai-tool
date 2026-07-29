package com.dbdocai.util;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CryptoUtil 单元测试（JDK8 / JUnit5）。
 *
 * <p>通过系统属性 {@code dbdoc.master.key} 注入确定性的主密钥，并在每个测试前
 * {@link CryptoUtil#resetForTest()} 重置延迟初始化的主密钥，确保测试可重复。
 */
public class CryptoUtilTest {

    @BeforeAll
    public static void setUp() {
        // 注入确定性测试主密钥（CryptoUtil 优先读取该属性）
        System.setProperty("dbdoc.master.key", "unit-test-master-key-1234567890");
        CryptoUtil.resetForTest();
    }

    @Test
    public void encryptShouldNotEqualPlaintext() {
        String plain = "s3cret-p@ss/word";
        String enc = CryptoUtil.encrypt(plain);
        assertNotNull(enc);
        assertNotEquals(plain, enc, "密文不应等于明文");
        assertTrue(CryptoUtil.isEncrypted(enc), "密文应带 ENC: 前缀");
    }

    @Test
    public void decryptShouldRestorePlaintext() {
        String plain = "s3cret-p@ss/word";
        String enc = CryptoUtil.encrypt(plain);
        assertEquals(plain, CryptoUtil.decrypt(enc), "解密后应还原原文");
    }

    @Test
    public void roundTripWithSpecialCharacters() {
        String plain = "p@ss/word:with;special'chars\"and<xml>&💡";
        String enc = CryptoUtil.encrypt(plain);
        assertEquals(plain, CryptoUtil.decrypt(enc));
    }

    @Test
    public void nullIsHandledSafely() {
        assertNull(CryptoUtil.encrypt(null));
        assertNull(CryptoUtil.decrypt(null));
    }

    @Test
    public void legacyPlaintextIsReturnedAsIs() {
        String legacy = "plaintext-not-encrypted";
        // 无 ENC: 前缀的值应原样返回（向后兼容）
        assertEquals(legacy, CryptoUtil.decrypt(legacy));
    }

    @Test
    public void emptyStringRoundTrip() {
        String enc = CryptoUtil.encrypt("");
        assertEquals("", CryptoUtil.decrypt(enc));
    }

    @Test
    public void encryptFailsClosedWhenMasterKeyUnavailable() {
        // P1-2 验证：主密钥不可用时 encrypt 必须抛异常，绝不降级返回明文落盘。
        CryptoUtil.simulateMissingKeyForTest();
        try {
            String plain = "s3cret-p@ss";
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> CryptoUtil.encrypt(plain), "主密钥不可用时应抛异常而非返回明文");
            assertNotNull(ex.getMessage());
            assertFalse(ex.getMessage().contains(plain), "异常信息不得包含明文凭据");
        } finally {
            CryptoUtil.resetForTest();
        }
    }
}
