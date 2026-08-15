package com.fedjafilipovic.ai_diff_reviewer.services;

import com.fedjafilipovic.ai_diff_reviewer.configuration.AppProperties;
import com.fedjafilipovic.ai_diff_reviewer.dto.Finding;
import com.fedjafilipovic.ai_diff_reviewer.exceptions.ProviderException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the real HTTP retry path against a local stand-in server —
 * LlmReviewProviderTest only exercises parseFindings directly. Proves a
 * transient failure (429/5xx) actually gets one retry and a deterministic
 * 4xx never wastes a second attempt on a result that can't change.
 */
class LlmReviewProviderRetryTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private AppProperties propsFor(int port) {
        AppProperties props = new AppProperties();
        props.setLlmBaseUrl("http://localhost:" + port);
        props.setLlmApiKey("test-key");
        props.setLlmModel("test-model");
        props.setLlmTimeoutSeconds(5);
        return props;
    }

    private static String chatCompletionsBody(String findingsJson) {
        String escaped = findingsJson.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"choices\":[{\"message\":{\"content\":\"" + escaped + "\"}}]}";
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    @Test
    void retriesOnceOnA500ThenSucceeds() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            if (calls.incrementAndGet() == 1) {
                respond(exchange, 500, "server error");
            } else {
                String findings = "[{\"ruleId\":\"X-1\",\"path\":\"f.js\",\"line\":1,"
                        + "\"severity\":\"high\",\"category\":\"security\",\"title\":\"t\",\"evidence\":\"e\"}]";
                respond(exchange, 200, chatCompletionsBody(findings));
            }
        });
        server.start();

        LlmReviewProvider provider = new LlmReviewProvider(propsFor(server.getAddress().getPort()), new ObjectMapper());
        List<Finding> findings = provider.review("+eval(x)\n", List.of());

        assertThat(calls.get()).isEqualTo(2);
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).ruleId()).isEqualTo("X-1");
    }

    @Test
    void retriesOnceOn429ThenSucceeds() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            if (calls.incrementAndGet() == 1) {
                respond(exchange, 429, "rate limited");
            } else {
                respond(exchange, 200, chatCompletionsBody("[]"));
            }
        });
        server.start();

        LlmReviewProvider provider = new LlmReviewProvider(propsFor(server.getAddress().getPort()), new ObjectMapper());
        List<Finding> findings = provider.review("+eval(x)\n", List.of());

        assertThat(calls.get()).isEqualTo(2);
        assertThat(findings).isEmpty();
    }

    @Test
    void doesNotRetryOnADeterministic4xx() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            calls.incrementAndGet();
            respond(exchange, 401, "bad key");
        });
        server.start();

        LlmReviewProvider provider = new LlmReviewProvider(propsFor(server.getAddress().getPort()), new ObjectMapper());
        assertThatThrownBy(() -> provider.review("+eval(x)\n", List.of()))
                .isInstanceOf(ProviderException.class);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void givesUpAfterTwoTransientFailures() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            calls.incrementAndGet();
            respond(exchange, 503, "still down");
        });
        server.start();

        LlmReviewProvider provider = new LlmReviewProvider(propsFor(server.getAddress().getPort()), new ObjectMapper());
        assertThatThrownBy(() -> provider.review("+eval(x)\n", List.of()))
                .isInstanceOf(ProviderException.class);
        assertThat(calls.get()).isEqualTo(2);
    }
}
