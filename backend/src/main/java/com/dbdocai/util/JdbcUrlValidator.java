package com.dbdocai.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JDBC URL 严格校验与净化器。
 *
 * <p>目的：在建立数据库连接前，对来自用户/前端的 JDBC URL 做白名单协议校验、剥离危险连接参数、
 * 并强制设置连接超时，以缓解零认证 + CORS 通配场景下由恶意 JDBC URL 触发的潜在 RCE
 * （例如 MySQL {@code autoDeserialize=true} 反序列化 gadget、{@code socketFactory} /
 * {@code *Interceptors} 反射实例化 classpath 内任意类等）。
 *
 * <p>用法：在调用 {@code DriverManager.getConnection(url, ...)} 之前先调用
 * {@link #validate(String)}，使用其返回的净化后 URL。协议不在白名单或解析异常时抛出异常。
 */
public final class JdbcUrlValidator {
    private static final Logger log = LoggerFactory.getLogger(JdbcUrlValidator.class);

    /** 支持的数据库协议白名单（与产品支持的库一致）。 */
    private static final String[] ALLOWED_PREFIXES = {
            "jdbc:mysql:", "jdbc:postgresql:", "jdbc:sqlite:"
    };

    /** 需要被剥离的危险连接参数（小写匹配）。 */
    // P1-3：新增 PostgreSQL SSL 类危险参数（sslfactory / sslhostnameverifier 可加载类/自定义校验，
    // sslkey/sslcert/sslrootcert/sslpassword 指向本地文件，存在文件披露风险）。
    // 合法 ssl=true、sslmode=* 不在黑名单内，正常 SSL 连接不受影响。
    // 注：SSRF host 白名单为后续加固项，本次保持协议白名单 + 超时 + 危险参数剥离。
    private static final String[] DANGEROUS_PARAMS = {
            "autodeserialize",
            "socketfactory",
            "connectionimpl",
            "connectionproperties",
            "allowloadlocalfile",
            "allowurlinlocalfile",
            "createdatabaseifnotexist",
            "sslfactory",
            "sslhostnameverifier",
            "sslkey",
            "sslcert",
            "sslrootcert",
            "sslpassword",
            "sslpasswordcallback"
    };

    private JdbcUrlValidator() {
        // 工具类，禁止实例化
    }

    /**
     * 校验并净化 JDBC URL。
     *
     * @param url 原始 JDBC URL（非空）
     * @return 净化后的 URL（强制附带 connectTimeout / socketTimeout）
     * @throws IllegalArgumentException 协议不在白名单或 URL 为空
     */
    public static String validate(String url) {
        if (url == null || url.trim().isEmpty()) {
            throw new IllegalArgumentException("JDBC URL 不能为空");
        }
        String u = url.trim();

        boolean allowed = false;
        for (String prefix : ALLOWED_PREFIXES) {
            if (u.toLowerCase().startsWith(prefix)) {
                allowed = true;
                break;
            }
        }
        if (!allowed) {
            throw new IllegalArgumentException(
                    "不支持的 JDBC 协议，仅允许 mysql / postgresql / sqlite");
        }

        int q = u.indexOf('?');
        String base = q >= 0 ? u.substring(0, q) : u;
        String paramsPart = q >= 0 ? u.substring(q + 1) : "";

        Map<String, String> params = new LinkedHashMap<>();
        if (!paramsPart.isEmpty()) {
            for (String pair : paramsPart.split("&")) {
                if (pair.isEmpty()) {
                    continue;
                }
                int eq = pair.indexOf('=');
                String key = eq >= 0 ? pair.substring(0, eq) : pair;
                String value = eq >= 0 ? pair.substring(eq + 1) : "";
                if (isDangerousParam(key)) {
                    log.warn("拒绝危险 JDBC 连接参数: {}", key);
                    continue;
                }
                params.put(key, value);
            }
        }

        // 强制连接超时，避免恶意/慢速端点挂死工作线程
        if (!params.containsKey("connectTimeout")) {
            params.put("connectTimeout", "5000");
        }
        if (!params.containsKey("socketTimeout")) {
            params.put("socketTimeout", "5000");
        }

        StringBuilder sb = new StringBuilder(base);
        if (!params.isEmpty()) {
            sb.append('?');
            boolean first = true;
            for (Map.Entry<String, String> e : params.entrySet()) {
                if (!first) {
                    sb.append('&');
                }
                sb.append(e.getKey()).append('=').append(e.getValue());
                first = false;
            }
        }
        return sb.toString();
    }

    private static boolean isDangerousParam(String rawKey) {
        String k = rawKey.toLowerCase().trim();
        if (k.isEmpty()) {
            return false;
        }
        for (String dangerous : DANGEROUS_PARAMS) {
            if (k.equals(dangerous)) {
                return true;
            }
        }
        // *Interceptors / *Interceptor（如 statementInterceptors、serverConfigInterceptors 等）
        return k.endsWith("interceptor") || k.endsWith("interceptors");
    }
}
