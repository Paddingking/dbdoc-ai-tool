package com.dbdocai.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置：CORS 与本地鉴权拦截器。
 *
 * <p>B3 安全修复：
 * <ul>
 *   <li>CORS 由通配 {@code *} 收敛为明确本地源（localhost:5173 / 127.0.0.1:5173 / Electron app://.），
 *       不再允许任意网页 drive-by 调用本地 API；</li>
 *   <li>对所有 {@code /api/**} 启用 {@link LocalAuthInterceptor}，校验请求头 {@code X-DBDoc-Token}。</li>
 * </ul>
 *
 * <p>Round 2 P3（CORS {@code file://} 收敛）：此前白名单含字面量 {@code file://}。由于
 * {@code file://} 页面的 {@code Origin} 为 {@code null}，而本配置 {@code allowCredentials(true)}，
 * 二者配合存在理论 CSRF 面（null origin 凭据请求）。故移除 {@code file://} 字面量；若需 Electron
 * 等桌面壳支持，改用显式可信 scheme（{@code app://.*}）或具体 localhost 源，避免 null origin 携带凭据。
 * 前端本地联调仍通过 localhost 源正常工作。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final LocalAuthInterceptor localAuthInterceptor;

    public WebConfig(LocalAuthInterceptor localAuthInterceptor) {
        this.localAuthInterceptor = localAuthInterceptor;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                // 明确本地源，移除通配 "*" 与理论风险的 "file://"（null origin + 凭据）
                .allowedOriginPatterns(
                        "http://localhost:5173",
                        "http://127.0.0.1:5173",
                        "app://*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(localAuthInterceptor)
                .addPathPatterns("/api/**")
                // P2-11: 健康检查探针 /api/ping 免鉴权，便于容器/进程管理器无令牌探活
                .excludePathPatterns("/api/ping");
    }
}
