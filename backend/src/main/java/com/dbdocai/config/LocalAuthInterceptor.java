package com.dbdocai.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 最小本地鉴权拦截器：对所有 {@code /api/**} 请求校验请求头 {@code X-DBDoc-Token}。
 *
 * <p>fullcheck 报告 P0-2 修复（fail-closed）：此前为 fail-open（无令牌即放行）且保留明文 dev 默认令牌，
 * 等于未鉴权。现改为：
 * <ul>
 *   <li>默认开启鉴权（{@code dbdoc.auth.enabled:true}）；仅当显式设为 {@code false} 才放行并告警（纯本地开发）。</li>
 *   <li>期望令牌来源（按优先级）：环境变量 {@code DBDOC_LOCAL_TOKEN} ＞ 本地文件 {@code ~/.dbdoc-ai/.local-token}
 *       （首次自动随机生成并写入，权限仅当前用户可读写）。</li>
 *   <li>无有效令牌的请求一律拒绝（401），不再 fail-open。</li>
 *   <li>令牌比对使用 {@link MessageDigest#isEqual(byte[], byte[])} 做恒定时间比较，消除时序侧信道。</li>
 * </ul>
 *
 * <p>前端（含 Electron）须在请求中携带正确令牌，详见 {@code frontend/src/services/api.ts} 与 Electron 主进程配合。
 */
@Component
public class LocalAuthInterceptor implements HandlerInterceptor {
    private static final Logger log = LoggerFactory.getLogger(LocalAuthInterceptor.class);
    private static final String HEADER = "X-DBDoc-Token";
    private static final String TOKEN_FILE_NAME = ".local-token";

    @Value("${dbdoc.auth.enabled:true}")
    private boolean authEnabled;

    /** 解析后缓存的令牌（运行时 env/文件不变，懒加载一次）。 */
    private volatile String cachedExpectedToken;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true; // CORS 预检由 WebConfig 的 CORS 配置处理，不在此鉴权
        }
        if (!authEnabled) {
            log.warn("SECURITY: 本地鉴权已通过 dbdoc.auth.enabled=false 显式关闭，API 处于未鉴权状态"
                    + "（仅限纯本地无外部访问的开发环境）。");
            return true;
        }
        String expected = resolveExpectedToken();
        String provided = request.getHeader(HEADER);
        if (expected != null && provided != null && constantTimeEquals(provided, expected)) {
            return true;
        }
        log.warn("拒绝未授权请求: {} {}", request.getMethod(), request.getRequestURI());
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"success\":false,\"message\":\"未授权：缺少或错误的 X-DBDoc-Token\"}");
        return false;
    }

    /** 解析期望令牌：env 优先，否则本地令牌文件（首次自动生成）。失败返回 null（fail-closed，请求将被拒）。 */
    private String resolveExpectedToken() {
        if (cachedExpectedToken != null) {
            return cachedExpectedToken;
        }
        synchronized (this) {
            if (cachedExpectedToken != null) {
                return cachedExpectedToken;
            }
            String env = System.getenv("DBDOC_LOCAL_TOKEN");
            if (env != null && !env.trim().isEmpty()) {
                cachedExpectedToken = env.trim();
                return cachedExpectedToken;
            }
            try {
                Path tokenFile = tokenFilePath();
                if (!Files.exists(tokenFile)) {
                    String generated = generateToken();
                    Files.write(tokenFile, generated.getBytes(StandardCharsets.UTF_8));
                    // 尽力收窄文件权限（Windows 上为尽力而为，非强制）
                    try {
                        File f = tokenFile.toFile();
                        f.setReadable(false, false);
                        f.setReadable(true, true);
                        f.setWritable(false, false);
                        f.setWritable(true, true);
                    } catch (Exception ignore) {
                        // 不同 JVM/OS 对文件权限语义不同，忽略
                    }
                    log.info("已生成本地鉴权令牌并写入: {}。请将其设为环境变量 DBDOC_LOCAL_TOKEN，"
                            + "或由前端/Electron 从该文件读取后随请求携带 X-DBDoc-Token。",
                            tokenFile.toAbsolutePath());
                }
                cachedExpectedToken = new String(Files.readAllBytes(tokenFile), StandardCharsets.UTF_8).trim();
                return cachedExpectedToken;
            } catch (Exception e) {
                log.error("SECURITY: 无法解析/生成本地鉴权令牌，API 将以 fail-closed 拒绝所有请求。", e);
                return null;
            }
        }
    }

    private Path tokenFilePath() {
        String home = System.getProperty("user.home");
        return new File(home, ".dbdoc-ai/" + TOKEN_FILE_NAME).toPath();
    }

    private static String generateToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 恒定时间比较，避免时序侧信道泄露令牌内容。 */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        byte[] ab = a.getBytes(StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(ab, bb);
    }
}
