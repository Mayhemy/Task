package com.fedjafilipovic.ai_diff_reviewer.provider;

import com.fedjafilipovic.ai_diff_reviewer.config.AppProperties;
import com.fedjafilipovic.ai_diff_reviewer.domain.Finding;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins LlmReviewProvider.parseFindings' defensive validation — a model is not
 * a guarantee, even with a system prompt constraining the vocabulary. Found
 * via a live run against a real model, which returned severity:"warning" and
 * category:"Logic"/"Syntax" — neither in the spec's fixed vocabulary.
 */
class LlmReviewProviderTest {

    private final LlmReviewProvider provider =
            new LlmReviewProvider(new AppProperties(), new ObjectMapper());

    @Test
    void validFindingPassesThrough() throws Exception {
        String json = """
                [{"ruleId":"X-1","path":"src/f.js","line":3,"severity":"high",
                  "category":"security","title":"t","evidence":"e"}]
                """;
        List<Finding> findings = provider.parseFindings(json);
        assertThat(findings).hasSize(1);
        Finding f = findings.get(0);
        assertThat(f.ruleId()).isEqualTo("X-1");
        assertThat(f.path()).isEqualTo("src/f.js");
        assertThat(f.line()).isEqualTo(3);
        assertThat(f.severity()).isEqualTo("high");
        assertThat(f.category()).isEqualTo("security");
        assertThat(f.id()).isEqualTo("X-1:src/f.js:3");
    }

    @Test
    void invalidSeverityIsSkippedNotCrashed() throws Exception {
        // Observed in practice from a real model response.
        String json = """
                [{"ruleId":"X-1","path":"f.js","line":1,"severity":"warning",
                  "category":"security","title":"t","evidence":"e"}]
                """;
        assertThat(provider.parseFindings(json)).isEmpty();
    }

    @Test
    void invalidCategoryIsSkippedNotCrashed() throws Exception {
        // Observed in practice: "Logic" and "Syntax" instead of the fixed vocabulary.
        String json = """
                [{"ruleId":"X-1","path":"f.js","line":1,"severity":"high",
                  "category":"Logic","title":"t","evidence":"e"}]
                """;
        assertThat(provider.parseFindings(json)).isEmpty();
    }

    @Test
    void severityAndCategoryAreNormalizedToLowercase() throws Exception {
        String json = """
                [{"ruleId":"X-1","path":"f.js","line":1,"severity":"HIGH",
                  "category":"Security","title":"t","evidence":"e"}]
                """;
        List<Finding> findings = provider.parseFindings(json);
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).severity()).isEqualTo("high");
        assertThat(findings.get(0).category()).isEqualTo("security");
    }

    @Test
    void leadingBPrefixIsStrippedFromPath() throws Exception {
        // Observed in practice: a model echoing the diff's "b/f.js" path verbatim.
        String json = """
                [{"ruleId":"X-1","path":"b/f.js","line":1,"severity":"high",
                  "category":"security","title":"t","evidence":"e"}]
                """;
        assertThat(provider.parseFindings(json).get(0).path()).isEqualTo("f.js");
    }

    @Test
    void leadingAPrefixIsStrippedFromPath() throws Exception {
        String json = """
                [{"ruleId":"X-1","path":"a/f.js","line":1,"severity":"high",
                  "category":"security","title":"t","evidence":"e"}]
                """;
        assertThat(provider.parseFindings(json).get(0).path()).isEqualTo("f.js");
    }

    @Test
    void markdownFenceIsStripped() throws Exception {
        String json = "```json\n[{\"ruleId\":\"X-1\",\"path\":\"f.js\",\"line\":1,"
                + "\"severity\":\"low\",\"category\":\"style\",\"title\":\"t\",\"evidence\":\"e\"}]\n```";
        assertThat(provider.parseFindings(json)).hasSize(1);
    }

    @Test
    void emptyArrayIsFine() throws Exception {
        assertThat(provider.parseFindings("[]")).isEmpty();
    }

    @Test
    void nonJsonContentThrowsProviderException() {
        assertThatThrownBy(() -> provider.parseFindings("not json at all"))
                .isInstanceOf(ProviderException.class);
    }

    @Test
    void nonArrayJsonThrowsProviderException() {
        assertThatThrownBy(() -> provider.parseFindings("{\"not\":\"an array\"}"))
                .isInstanceOf(ProviderException.class);
    }

    @Test
    void blankRuleIdIsSkipped() throws Exception {
        String json = """
                [{"ruleId":"","path":"f.js","line":1,"severity":"high",
                  "category":"security","title":"t","evidence":"e"}]
                """;
        assertThat(provider.parseFindings(json)).isEmpty();
    }

    @Test
    void lineLessThanOneIsSkipped() throws Exception {
        String json = """
                [{"ruleId":"X-1","path":"f.js","line":0,"severity":"high",
                  "category":"security","title":"t","evidence":"e"}]
                """;
        assertThat(provider.parseFindings(json)).isEmpty();
    }
}
