package com.fedjafilipovic.ai_diff_reviewer.services;

import com.fedjafilipovic.ai_diff_reviewer.configuration.AppLimits;
import com.fedjafilipovic.ai_diff_reviewer.services.Chunker;
import com.fedjafilipovic.ai_diff_reviewer.services.DiffParser;
import com.fedjafilipovic.ai_diff_reviewer.dto.Finding;
import com.fedjafilipovic.ai_diff_reviewer.models.Job;
import com.fedjafilipovic.ai_diff_reviewer.models.JobStatus;
import com.fedjafilipovic.ai_diff_reviewer.dto.ReviewOptions;
import com.fedjafilipovic.ai_diff_reviewer.dto.Usage;
import com.fedjafilipovic.ai_diff_reviewer.services.LlmReviewProvider;
import com.fedjafilipovic.ai_diff_reviewer.services.MockReviewProvider;
import com.fedjafilipovic.ai_diff_reviewer.repositories.IdempotencyStore;
import com.fedjafilipovic.ai_diff_reviewer.repositories.JobStore;
import com.fedjafilipovic.ai_diff_reviewer.repositories.ResultCache;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Pins finalizeFindings (dedupe + sort) and the §6.10 inputBytes regression:
 * usage.inputBytes must equal the diff's UTF-8 byte length, not a chunk-byte
 * sum (which is off by one due to the chunker's trailing newline).
 */
class JobServiceTest {

    private static Finding finding(String ruleId, String path, int line) {
        return Finding.of(ruleId, path, line, "sev", "cat", "title", "evidence");
    }

    @Test
    void finalizeDedupesById() {
        // Same id (rule+path+line) appears twice -> one.
        Finding a = finding("MOCK-001", "a.js", 1);
        Finding b = finding("MOCK-001", "a.js", 1);
        assertThat(JobService.finalizeFindings(List.of(a, b))).hasSize(1);
    }

    @Test
    void finalizeSortsByPathThenLineThenRuleId() {
        Finding f1 = finding("MOCK-007", "b.js", 5);
        Finding f2 = finding("MOCK-001", "a.js", 3);
        Finding f3 = finding("MOCK-001", "a.js", 1);
        Finding f4 = finding("MOCK-002", "a.js", 1);
        List<Finding> out = JobService.finalizeFindings(List.of(f1, f2, f3, f4));
        assertThat(out).containsExactly(
                finding("MOCK-001", "a.js", 1),
                finding("MOCK-002", "a.js", 1),
                finding("MOCK-001", "a.js", 3),
                finding("MOCK-007", "b.js", 5));
    }

    @Test
    void finalizeKeepsFirstOccurrenceOnDuplicateId() {
        Finding first = new Finding("MOCK-001:a.js:1", "MOCK-001", "a.js", 1, "critical", "security", "first", "ev1");
        Finding dup = new Finding("MOCK-001:a.js:1", "MOCK-001", "a.js", 1, "critical", "security", "dup", "ev2");
        List<Finding> out = JobService.finalizeFindings(List.of(first, dup));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).title()).isEqualTo("first"); // putIfAbsent keeps first
    }

    @Test
    void finalizeEmptyInput() {
        assertThat(JobService.finalizeFindings(List.of())).isEmpty();
    }

    // ---- §6.10 inputBytes regression ----

    private JobService newService() {
        return new JobService(
                new JobStore(), new IdempotencyStore(), new ResultCache(),
                new DiffParser(), new Chunker(),
                new MockReviewProvider(),
                new LlmReviewProvider(new com.fedjafilipovic.ai_diff_reviewer.configuration.AppProperties(),
                        new ObjectMapper()),
                Executors.newSingleThreadExecutor());
    }

    @Test
    void usageInputBytesEqualsExactDiffByteLength() {
        String diff = "--- a/f.js\n+++ b/f.js\n@@ -1 +1 @@\n+eval(x)\n";
        long expected = diff.getBytes(StandardCharsets.UTF_8).length;
        JobService svc = newService();
        byte[] body = ("{\"diff\":\"" + diff.replace("\n", "\\n").replace("\"", "\\\"") + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        Job job = svc.submit(body, null, diff, ReviewOptions.defaults()).job();
        await().untilAsserted(() -> assertThat(job.getStatus()).isEqualTo(JobStatus.DONE));
        assertThat(job.getUsage().inputBytes()).isEqualTo(expected);
        assertThat(job.getUsage().chunks()).isEqualTo(1);
    }

    @Test
    void usageInputBytesForMultibyteDiff() {
        // 'é' is 2 UTF-8 bytes; inputBytes must reflect bytes, not chars.
        String diff = "--- a/f.js\n+++ b/f.js\n@@ -1 +1 @@\n+café\n";
        long expected = diff.getBytes(StandardCharsets.UTF_8).length;
        JobService svc = newService();
        byte[] body = diff.getBytes(StandardCharsets.UTF_8);
        Job job = svc.submit(body, null, diff, ReviewOptions.defaults()).job();
        await().untilAsserted(() -> assertThat(job.getStatus()).isEqualTo(JobStatus.DONE));
        assertThat(job.getUsage().inputBytes()).isEqualTo(expected);
        // sanity: the multibyte char really does add a byte
        assertThat(expected).isGreaterThan(diff.length());
    }

    @Test
    void maxFindingsTruncatesButUsageReflectsFullScan() {
        // 3 findings, maxFindings=2 -> 2 returned, usage still reports the full scan.
        String diff = "--- a/f.js\n+++ b/f.js\n@@ -1 +1 @@\n+eval(a); console.log(b); // TODO\n";
        JobService svc = newService();
        byte[] body = diff.getBytes(StandardCharsets.UTF_8);
        Job job = svc.submit(body, null, diff, new ReviewOptions("mock", 2)).job();
        await().untilAsserted(() -> assertThat(job.getStatus()).isEqualTo(JobStatus.DONE));
        assertThat(job.getFindings()).hasSize(2);
        // inputBytes reflects the full diff regardless of truncation
        assertThat(job.getUsage().inputBytes()).isEqualTo(diff.getBytes(StandardCharsets.UTF_8).length);
    }
}
