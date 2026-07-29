package com.dbdocai.llm;

import java.util.Map;

public interface LlmAdapter {

    String generate(String systemPrompt, String userPrompt);

    boolean testConnection();

    String getProviderName();
}
