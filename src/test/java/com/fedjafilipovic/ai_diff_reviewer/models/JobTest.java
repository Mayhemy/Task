package com.fedjafilipovic.ai_diff_reviewer.models;

import com.fedjafilipovic.ai_diff_reviewer.dto.Finding;
import com.fedjafilipovic.ai_diff_reviewer.dto.Usage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the Job lifecycle state transitions and terminal semantics at the unit
 * level. The exact SSE event sequence (event names in order, byte-identical
 * replay) is pinned by {@code SseIntegrationTest} against a live HTTP stream;
 * here we assert the observable state those events are derived from.
 */
class JobTest {

    private static final Usage U = new Usage(10, 1, false);

    @Test
    void newJobIsQueuedWithInitialUsage() {
        Job job = new Job("id", U);
        assertThat(job.getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(job.getUsage()).isSameAs(U);
        assertThat(job.isTerminal()).isFalse();
        assertThat(job.getFindings()).isEmpty();
        assertThat(job.getErrorMessage()).isNull();
    }

    @Test
    void markRunningTransitionsToRunning() {
        Job job = new Job("id", U);
        job.markRunning();
        assertThat(job.getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(job.isTerminal()).isFalse();
    }

    @Test
    void finishSuccessStoresTruncatedFindingsAndFinalUsage() {
        Job job = new Job("id", U);
        job.markRunning();
        Finding f1 = Finding.of("MOCK-001", "a.js", 1, "critical", "security", "t", "e");
        Finding f2 = Finding.of("MOCK-007", "a.js", 1, "low", "style", "t", "e");
        job.emitFinding(f1);
        job.emitFinding(f2);
        Usage done = new Usage(10, 1, false);
        job.finishSuccess(List.of(f1, f2), done);

        assertThat(job.getStatus()).isEqualTo(JobStatus.DONE);
        assertThat(job.isTerminal()).isTrue();
        assertThat(job.getFindings()).containsExactly(f1, f2);
        assertThat(job.getUsage()).isSameAs(done);
        assertThat(job.getErrorMessage()).isNull();
    }

    @Test
    void finishFailureStoresMessageAndIsTerminal() {
        Job job = new Job("id", U);
        job.markRunning();
        job.finishFailure("boom");

        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job.isTerminal()).isTrue();
        assertThat(job.getErrorMessage()).isEqualTo("boom");
        // findings remain empty on failure
        assertThat(job.getFindings()).isEmpty();
    }

    @Test
    void finishSuccessAfterEmittingKeepsOnlyTruncatedSet() {
        // emit 3, finish with only 2 (truncation) -> getFindings() == the 2.
        Job job = new Job("id", U);
        Finding f1 = Finding.of("MOCK-001", "a.js", 1, "critical", "security", "t", "e");
        Finding f2 = Finding.of("MOCK-002", "a.js", 2, "critical", "security", "t", "e");
        Finding f3 = Finding.of("MOCK-007", "a.js", 3, "low", "style", "t", "e");
        job.emitFinding(f1);
        job.emitFinding(f2);
        job.emitFinding(f3);
        job.finishSuccess(List.of(f1, f2), U);
        assertThat(job.getFindings()).containsExactly(f1, f2);
    }

    @Test
    void usageReflectsCacheHitFlag() {
        Job job = new Job("id", new Usage(10, 1, false));
        assertThat(job.getUsage().cacheHit()).isFalse();
        job.finishSuccess(List.of(), new Usage(10, 1, true));
        assertThat(job.getUsage().cacheHit()).isTrue();
    }
}
