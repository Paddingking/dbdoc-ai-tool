package com.dbdocai.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JdbcUrlValidator 单元测试（B4：JDBC URL 潜在 RCE）。
 */
public class JdbcUrlValidatorTest {

    @Test
    public void validMysqlUrlPassesAndGetsTimeouts() {
        String url = "jdbc:mysql://localhost:3306/mydb?useSSL=false";
        String result = JdbcUrlValidator.validate(url);
        assertTrue(result.startsWith("jdbc:mysql:"), "应保留协议前缀");
        assertTrue(result.contains("useSSL=false"), "正常参数应保留");
        assertTrue(result.contains("connectTimeout=5000"), "应强制连接超时");
        assertTrue(result.contains("socketTimeout=5000"), "应强制 socket 超时");
    }

    @Test
    public void validPostgresUrlPasses() {
        String url = "jdbc:postgresql://localhost:5432/mydb";
        String result = JdbcUrlValidator.validate(url);
        assertTrue(result.startsWith("jdbc:postgresql:"));
        assertTrue(result.contains("socketTimeout=5000"));
    }

    @Test
    public void validSqliteUrlPasses() {
        String url = "jdbc:sqlite:/tmp/test.db";
        String result = JdbcUrlValidator.validate(url);
        assertTrue(result.startsWith("jdbc:sqlite:"));
    }

    @Test
    public void autoDeserializeParamIsRejected() {
        String url = "jdbc:mysql://localhost:3306/mydb?autoDeserialize=true";
        String result = JdbcUrlValidator.validate(url);
        assertFalse(result.contains("autoDeserialize"), "autoDeserialize 必须被剥离");
    }

    @Test
    public void socketFactoryParamIsRejected() {
        String url = "jdbc:mysql://localhost:3306/mydb?socketFactory=com.evil.Poc";
        String result = JdbcUrlValidator.validate(url);
        assertFalse(result.toLowerCase().contains("socketfactory"), "socketFactory 必须被剥离");
    }

    @Test
    public void interceptorsParamIsRejected() {
        String url = "jdbc:mysql://localhost:3306/mydb?statementInterceptors=com.evil.Poc";
        String result = JdbcUrlValidator.validate(url);
        assertFalse(result.toLowerCase().contains("interceptor"), "*Interceptors 必须被剥离");
    }

    @Test
    public void allowLoadLocalInfileParamIsRejected() {
        String url = "jdbc:mysql://localhost:3306/mydb?allowLoadLocalInfile=true";
        String result = JdbcUrlValidator.validate(url);
        assertFalse(result.toLowerCase().contains("allowloadlocalfile"), "allowLoadLocalInfile 必须被剥离");
    }

    @Test
    public void nonWhitelistedProtocolIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> JdbcUrlValidator.validate("jdbc:oracle:thin:@localhost:1521:orcl"));
        assertThrows(IllegalArgumentException.class,
                () -> JdbcUrlValidator.validate("jdbc:h2:mem:test"));
    }

    @Test
    public void emptyUrlIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> JdbcUrlValidator.validate("  "));
    }
}
