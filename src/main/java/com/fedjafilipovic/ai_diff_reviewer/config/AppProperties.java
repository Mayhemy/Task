package com.fedjafilipovic.ai_diff_reviewer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Environment-bound configuration. All values come from env vars (or the
 * optional .env file at the project root). LLM credentials may legitimately
 * be blank — the llm provider then fails jobs gracefully with
 * "llm provider not configured".
 */
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    /** Bearer token required on all /v1/** routes. Must be set; see StartupCheck. */
    private String bearerToken;

    /** OpenAI-compatible base URL, e.g. https://api.openai.com/v1 */
    private String llmBaseUrl;

    /** Model API key — lives only on this server. */
    private String llmApiKey;

    /** Model name passed to the chat completions endpoint. */
    private String llmModel;

    /** Model call timeout; must stay well under the 30 s job budget. */
    private int llmTimeoutSeconds = 20;

    public String getBearerToken() { return bearerToken; }
    public void setBearerToken(String bearerToken) { this.bearerToken = bearerToken; }

    public String getLlmBaseUrl() { return llmBaseUrl; }
    public void setLlmBaseUrl(String llmBaseUrl) { this.llmBaseUrl = llmBaseUrl; }

    public String getLlmApiKey() { return llmApiKey; }
    public void setLlmApiKey(String llmApiKey) { this.llmApiKey = llmApiKey; }

    public String getLlmModel() { return llmModel; }
    public void setLlmModel(String llmModel) { this.llmModel = llmModel; }

    public int getLlmTimeoutSeconds() { return llmTimeoutSeconds; }
    public void setLlmTimeoutSeconds(int llmTimeoutSeconds) { this.llmTimeoutSeconds = llmTimeoutSeconds; }

    public boolean llmConfigured() {
        return isNonBlank(llmBaseUrl) && isNonBlank(llmApiKey) && isNonBlank(llmModel);
    }

    private static boolean isNonBlank(String s) {
        return s != null && !s.isBlank();
    }
}
