package com.dbdocai.util;

import org.springframework.web.util.HtmlUtils;

/**
 * HTML 转义工具，用于防止导出文档（HTML）中的存储型 XSS。
 *
 * <p>所有拼接到 HTML 文档中的“动态值”（表名、列名、注释、枚举值、LLM 输出片段等，
 * 这些内容可由数据库元数据或 LLM 间接控制）都必须先经过 {@link #escape(String)} 处理；
 * 静态模板标签/样式不应转义。
 */
public final class HtmlEscapeUtil {
    private HtmlEscapeUtil() {
        // 工具类，禁止实例化
    }

    /**
     * 对字符串做 HTML 转义（&lt; &gt; &amp; &quot; &#39; 等）。
     *
     * @param value 待转义内容（可为 null）
     * @return 转义后的安全字符串；null 返回空字符串
     */
    public static String escape(String value) {
        if (value == null) {
            return "";
        }
        String escaped = HtmlUtils.htmlEscape(value);
        if (escaped.indexOf('\'') >= 0) {
            escaped = escaped.replace("'", "&#39;");
        }
        return escaped;
    }
}
