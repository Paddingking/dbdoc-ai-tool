package com.dbdocai.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 轻量本地凭据加密工具（AES/GCM）。
 *
 * <p>主密钥来源（按优先级）：
 * <ol>
 *   <li>环境变量 {@code DBDOC_MASTER_KEY}（明文口令或 base64 编码的密钥材料）；</li>
 *   <li>文件 {@code ~/.dbdoc-ai/.masterkey}（首次运行时随机生成并落盘，权限仅当前用户）；</li>
 *   <li>两者都缺失时降级为明文落盘，并在日志中告警（保持向后兼容、不阻塞启动）。</li>
 * </ol>
 *
 * <p><b>安全决策点（重要）</b>：本实现为本地最小可用方案。生产环境主密钥不应长期以明文
 * 文件/环境变量形式存在，应改用操作系统凭据库（如 macOS Keychain、Windows DPAPI、
 * Linux secret-service）或密钥管理服务（KMS / Vault）。当前方案仅用于本地桌面工具，
 * 目标是在不引入外部依赖的前提下消除“明文密钥落盘”。
 *
 * <p>密文格式：{@code ENC:<ivBase64>:<cipherBase64>}，无此前缀的值视为遗留明文，原样返回。
 */
public final class CryptoUtil {
    private static final Logger log = LoggerFactory.getLogger(CryptoUtil.class);

    /** 密文前缀标记，用于区分加密值与遗留明文值。 */
    private static final String ENC_PREFIX = "ENC:";
    private static final String ALGO = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH_BITS = 16 * 8;

    // 延迟初始化：允许测试通过系统属性 dbdoc.master.key 先注入主密钥再触发首次加载。
    private static volatile SecretKeySpec masterKey;
    private static volatile boolean keyInitialized = false;
    private static volatile boolean plaintextFallback = false;

    private CryptoUtil() {
        // 工具类，禁止实例化
    }

    private static SecretKeySpec getMasterKey() {
        if (!keyInitialized) {
            synchronized (CryptoUtil.class) {
                if (!keyInitialized) {
                    SecretKeySpec k = loadMasterKey();
                    masterKey = k;
                    plaintextFallback = (k == null);
                    keyInitialized = true;
                }
            }
        }
        return masterKey;
    }

    /**
     * 仅用于测试：清除已初始化的主密钥，使下次调用重新加载（例如切换系统属性）。
     */
    static void resetForTest() {
        synchronized (CryptoUtil.class) {
            keyInitialized = false;
            masterKey = null;
            plaintextFallback = false;
        }
    }

    /**
     * 仅用于测试：强制模拟「主密钥不可用」场景，使下次 {@link #encrypt} 直接失败（fail-closed 路径）。
     * 与 {@link #resetForTest()} 不同，本方法不会重新加载真实主密钥。
     */
    static void simulateMissingKeyForTest() {
        synchronized (CryptoUtil.class) {
            keyInitialized = true;
            masterKey = null;
            plaintextFallback = true;
        }
    }

    private static SecretKeySpec loadMasterKey() {
        // 1) 环境变量（优先）
        String raw = System.getenv("DBDOC_MASTER_KEY");
        // 1.5) 系统属性（主要用于单元测试注入）
        if (raw == null || raw.isEmpty()) {
            raw = System.getProperty("dbdoc.master.key");
        }
        if (raw != null && !raw.isEmpty()) {
            try {
                return deriveKey(raw);
            } catch (Exception e) {
                log.warn("从 DBDOC_MASTER_KEY 派生主密钥失败，尝试文件回退", e);
            }
        }

        // 2) 文件 ~/.dbdoc-ai/.masterkey
        try {
            String home = System.getProperty("user.home");
            File keyFile = new File(home, ".dbdoc-ai/.masterkey");
            if (!keyFile.exists()) {
                byte[] key = new byte[16]; // AES-128（JDK8 默认策略无需额外权限）
                new SecureRandom().nextBytes(key);
                keyFile.getParentFile().mkdirs();
                Files.write(keyFile.toPath(), Base64.getEncoder().encode(key));
                // 尽力收窄文件权限（Windows 上 setReadable/setWritable 为尽力而为，非强制）
                try {
                    keyFile.setReadable(false, false);
                    keyFile.setReadable(true, true);
                    keyFile.setWritable(false, false);
                    keyFile.setWritable(true, true);
                } catch (Exception ignore) {
                    // 忽略：不同 JVM/OS 对文件权限语义不同
                }
                log.info("已生成本地主密钥文件: {}", keyFile.getAbsolutePath());
            }
            byte[] key = Base64.getDecoder().decode(
                    new String(Files.readAllBytes(keyFile.toPath()), StandardCharsets.UTF_8).trim());
            return new SecretKeySpec(normalizeKey(key), "AES");
        } catch (Exception e) {
            // 3) 全部失败 → 明文降级（不阻断启动，但告警）
            log.error("SECURITY 决策点: 主密钥不可用，凭据将以【明文】形式落盘（不安全）。"
                    + "请设置环境变量 DBDOC_MASTER_KEY 或提供 ~/.dbdoc-ai/.masterkey 文件。"
                    + "当前方案仅适用于本地开发。", e);
            return null;
        }
    }

    private static SecretKeySpec deriveKey(String raw) throws Exception {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(raw.trim());
        } catch (IllegalArgumentException e) {
            bytes = raw.trim().getBytes(StandardCharsets.UTF_8);
        }
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return new SecretKeySpec(normalizeKey(md.digest(bytes)), "AES");
    }

    /** 将任意长度密钥材料归一化为合法 AES 密钥长度（16/24/32 字节），不足则右补零。 */
    private static byte[] normalizeKey(byte[] in) {
        if (in.length == 16 || in.length == 24 || in.length == 32) {
            return in;
        }
        int len = in.length < 16 ? 16 : (in.length < 24 ? 24 : 32);
        byte[] out = new byte[len];
        System.arraycopy(in, 0, out, 0, Math.min(len, in.length));
        return out;
    }

    /**
     * 加密明文。返回带 {@code ENC:} 前缀的密文。
     *
     * <p><b>fail-closed</b>：若主密钥不可用或加密过程异常，一律抛出 {@link IllegalStateException}，
     * 绝不降级为明文落盘（违反 CLAUDE.md #3「密码不落盘明文」）。解密路径保留向后兼容（见 {@link #decrypt}）。
     *
     * @param plain 明文（可为 null，此时返回 null）
     * @return 密文
     */
    public static String encrypt(String plain) {
        if (plain == null) {
            return null;
        }
        SecretKeySpec key = getMasterKey();
        if (key == null) {
            // P1-4 安全决策（fail-closed）：主密钥不可用时拒绝以明文存储凭据，
            // 避免违反 CLAUDE.md #3「密码不落盘明文」。解密路径保持向后兼容（见 decrypt）。
            throw new IllegalStateException(
                "SECURITY: 主密钥不可用，拒绝以明文存储凭据。请设置环境变量 DBDOC_MASTER_KEY"
                + " 或允许生成 ~/.dbdoc-ai/.masterkey 文件。");
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGO);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] enc = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return ENC_PREFIX
                    + Base64.getEncoder().encodeToString(iv)
                    + ":"
                    + Base64.getEncoder().encodeToString(enc);
        } catch (Exception e) {
            // P1-2 安全决策（fail-closed）：加密路径绝不降级为明文落盘，失败直接抛异常。
            throw new IllegalStateException("SECURITY: 凭据加密失败，拒绝以明文存储。请检查主密钥配置。", e);
        }
    }

    /**
     * 解密密文。无 {@code ENC:} 前缀的值（遗留明文）原样返回；解密失败返回原值并在日志告警。
     *
     * @param stored 存储值（可为 null）
     * @return 明文
     */
    public static String decrypt(String stored) {
        if (stored == null) {
            return null;
        }
        if (!stored.startsWith(ENC_PREFIX)) {
            return stored; // 遗留明文或非机密字段
        }
        SecretKeySpec key = getMasterKey();
        if (key == null) {
            log.error("SECURITY: 密文存在但主密钥不可用，无法解密。");
            return stored;
        }
        try {
            String body = stored.substring(ENC_PREFIX.length());
            int idx = body.indexOf(':');
            byte[] iv = Base64.getDecoder().decode(body.substring(0, idx));
            byte[] enc = Base64.getDecoder().decode(body.substring(idx + 1));
            Cipher cipher = Cipher.getInstance(ALGO);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(enc), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("解密失败（主密钥可能不匹配），返回存储原值", e);
            return stored;
        }
    }

    /** 判断给定字符串是否为本工具加密后的密文。 */
    public static boolean isEncrypted(String value) {
        return value != null && value.startsWith(ENC_PREFIX);
    }
}
