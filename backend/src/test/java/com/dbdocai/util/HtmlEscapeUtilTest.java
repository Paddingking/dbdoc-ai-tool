package com.dbdocai.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HtmlEscapeUtil 单元测试（P1-2：HTML 导出存储型 XSS）。
 */
public class HtmlEscapeUtilTest {

    @Test
    public void scriptTagIsEscaped() {
        String input = "<script>alert(1)</script>";
        String output = HtmlEscapeUtil.escape(input);
        assertEquals("&lt;script&gt;alert(1)&lt;/script&gt;", output,
                "脚本标签必须被转义，不得原样输出");
        assertFalse(output.contains("<script>"), "转义后不得包含原始标签");
    }

    @Test
    public void nullBecomesEmpty() {
        assertEquals("", HtmlEscapeUtil.escape(null));
    }

    @Test
    public void plainTextUnchanged() {
        String input = "用户表";
        assertEquals(input, HtmlEscapeUtil.escape(input));
    }

    @Test
    public void quotesAndAmpersandEscaped() {
        String output = HtmlEscapeUtil.escape("a&b<c>d\"e'f");
        assertTrue(output.contains("&amp;"));
        assertTrue(output.contains("&lt;"));
        assertTrue(output.contains("&gt;"));
        assertTrue(output.contains("&quot;"));
        assertTrue(output.contains("&#39;"));
    }
}
