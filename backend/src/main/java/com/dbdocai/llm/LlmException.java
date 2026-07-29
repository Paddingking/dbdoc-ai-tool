package com.dbdocai.llm;

public class LlmException extends RuntimeException {

    private final String code;

    public LlmException(String message, String code) {
        super(message);
        this.code = code;
    }

    public LlmException(String message, String code, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
