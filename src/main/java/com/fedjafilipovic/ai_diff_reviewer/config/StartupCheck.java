package com.fedjafilipovic.ai_diff_reviewer.config;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Fails fast at startup if APP_BEARER_TOKEN is blank — running without auth
 * is a real misconfiguration worth crashing on. LLM credentials are allowed
 * to be blank (the llm provider then fails jobs gracefully).
 */
@Component
public class StartupCheck {

    private final AppProperties props;

    public StartupCheck(AppProperties props) {
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void verify() {
        if (props.getBearerToken() == null || props.getBearerToken().isBlank()) {
            throw new IllegalStateException(
                    "APP_BEARER_TOKEN is not set. Set it in the environment or .env file.");
        }
    }
}
