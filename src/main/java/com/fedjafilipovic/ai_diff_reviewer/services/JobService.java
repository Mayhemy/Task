package com.fedjafilipovic.ai_diff_reviewer.services;

import com.fedjafilipovic.ai_diff_reviewer.configuration.AppLimits;
import com.fedjafilipovic.ai_diff_reviewer.services.Chunker;
import com.fedjafilipovic.ai_diff_reviewer.services.DiffParser;
import com.fedjafilipovic.ai_diff_reviewer.models.DiffLine;
import com.fedjafilipovic.ai_diff_reviewer.dto.Finding;
import com.fedjafilipovic.ai_diff_reviewer.models.Job;
import com.fedjafilipovic.ai_diff_reviewer.dto.ReviewOptions;
import com.fedjafilipovic.ai_diff_reviewer.dto.Usage;
import com.fedjafilipovic.ai_diff_reviewer.services.LlmReviewProvider;
import com.fedjafilipovic.ai_diff_reviewer.services.MockReviewProvider;
import com.fedjafilipovic.ai_diff_reviewer.exceptions.ProviderException;
import com.fedjafilipovic.ai_diff_reviewer.services.ReviewProvider;
import com.fedjafilipovic.ai_diff_reviewer.repositories.IdempotencyStore;
import com.fedjafilipovic.ai_diff_reviewer.repositories.JobStore;
import com.fedjafilipovic.ai_diff_reviewer.repositories.ResultCache;
import com.fedjafilipovic.ai_diff_reviewer.utils.Hashing;
import com.fedjafilipovic.ai_diff_reviewer.exceptions.ApiExceptions.IdempotencyConflictException;
import com.fedjafilipovic.ai_diff_reviewer.exceptions.ApiExceptions.NotFoundException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;

/**
 * Orchestration. Diff validation/parsing/chunking happens synchronously in
 * the POST path (4xx are request-level errors and must never create a job);
 * only rule-matching / the LLM call runs on the async worker pool.
 */
@Service
public class JobService {

    /** Final ordering everywhere: path (lexicographic), line (asc), ruleId. */
    private static final Comparator<Finding> ORDER = Comparator.comparing(Finding::path)
            .thenComparingInt(Finding::line)
            .thenComparing(Finding::ruleId);

    private final JobStore jobStore;
    private final IdempotencyStore idempotencyStore;
    private final ResultCache resultCache;
    private final DiffParser diffParser;
    private final Chunker chunker;
    private final MockReviewProvider mockProvider;
    private final LlmReviewProvider llmProvider;
    private final ExecutorService jobExecutor;

    public JobService(JobStore jobStore, IdempotencyStore idempotencyStore, ResultCache resultCache,
                      DiffParser diffParser, Chunker chunker,
                      MockReviewProvider mockProvider, LlmReviewProvider llmProvider,
                      ExecutorService jobExecutor) {
        this.jobStore = jobStore;
        this.idempotencyStore = idempotencyStore;
        this.resultCache = resultCache;
        this.diffParser = diffParser;
        this.chunker = chunker;
        this.mockProvider = mockProvider;
        this.llmProvider = llmProvider;
        this.jobExecutor = jobExecutor;
    }

    public record SubmitOutcome(Job job, boolean idempotentReplay) {}

    /**
     * Validates, creates and enqueues a job. Throws the mapped ApiExceptions
     * / InvalidDiffException for request-level errors (no job created then).
     */
    public SubmitOutcome submit(byte[] rawBody, String idempotencyKey, String diff, ReviewOptions options) {
        String bodyHash = Hashing.sha256Hex(rawBody);

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            // Atomic check-validate-create-store: concurrent same-key requests
            // must never create two jobs.
            synchronized (idempotencyStore.lock()) {
                IdempotencyStore.IdemRecord existing = idempotencyStore.get(idempotencyKey);
                if (existing != null) {
                    if (existing.bodyHash().equals(bodyHash)) {
                        Job job = jobStore.get(existing.jobId());
                        if (job != null) {
                            return new SubmitOutcome(job, true);
                        }
                        // Job evicted/lost — fall through and create a fresh one.
                    } else {
                        throw new IdempotencyConflictException();
                    }
                }
                Job job = createAndEnqueue(bodyHash, diff, options);
                idempotencyStore.put(idempotencyKey, new IdempotencyStore.IdemRecord(bodyHash, job.getId()));
                return new SubmitOutcome(job, false);
            }
        }

        return new SubmitOutcome(createAndEnqueue(bodyHash, diff, options), false);
    }

    public Job getJob(String jobId) {
        Job job = jobStore.get(jobId);
        if (job == null) {
            throw new NotFoundException();
        }
        return job;
    }

    private Job createAndEnqueue(String bodyHash, String diff, ReviewOptions options) {
        // Synchronous validation: throws InvalidDiffException (422) before any job exists.
        diffParser.parse(diff);
        List<String> chunks = chunker.chunk(diff, AppLimits.CHUNK_BYTES);
        long inputBytes = diff.getBytes(StandardCharsets.UTF_8).length;

        Job job = jobStore.create(new Usage(inputBytes, chunks.size(), false));
        jobExecutor.submit(() -> runJob(job, bodyHash, chunks, options, inputBytes));
        return job;
    }

    private void runJob(Job job, String bodyHash, List<String> chunks, ReviewOptions options, long inputBytes) {
        job.markRunning();
        try {
            ResultCache.Lookup lookup = resultCache.getOrCreate(bodyHash);
            ResultCache.ScanResult scan;
            boolean cacheHit;
            if (lookup.created()) {
                try {
                    scan = doScan(chunks, options, inputBytes);
                    lookup.future().complete(scan);
                } catch (Throwable t) {
                    // Never cache failures — a later retry must re-run.
                    resultCache.remove(bodyHash, lookup.future());
                    lookup.future().completeExceptionally(t);
                    throw t;
                }
                cacheHit = false;
            } else {
                try {
                    // Attach to the in-flight scan; reuse its result (cacheHit).
                    scan = lookup.future().join();
                    cacheHit = true;
                } catch (CompletionException ce) {
                    // The original creator failed (and removed itself from the
                    // cache). Re-run this scan fresh rather than failing the job.
                    scan = doScan(chunks, options, inputBytes);
                    cacheHit = false;
                }
            }

            List<Finding> truncated = scan.findings().stream().limit(options.maxFindings()).toList();
            for (Finding f : truncated) {
                job.emitFinding(f);
            }
            job.finishSuccess(truncated, scan.usage().withCacheHit(cacheHit));
        } catch (ProviderException e) {
            job.finishFailure(e.getMessage());
        } catch (Throwable t) {
            // Catch-all: a job left forever `running` is a scored failure.
            job.finishFailure("internal error: " + t.getMessage());
        }
    }

    /**
     * @param inputBytes UTF-8 byte length of the ORIGINAL diff string (computed
     *                   synchronously in createAndEnqueue). We do NOT sum chunk
     *                   bytes here: the chunker re-joins lines with '\n', which
     *                   adds one trailing newline vs the original text, so a
     *                   chunk-byte sum would over-report inputBytes by one.
     */
    private ResultCache.ScanResult doScan(List<String> chunks, ReviewOptions options, long inputBytes) throws ProviderException {
        ReviewProvider provider = ReviewOptions.PROVIDER_LLM.equals(options.provider()) ? llmProvider : mockProvider;
        List<Finding> collected = new ArrayList<>();
        for (String chunkText : chunks) {
            List<DiffLine> lines = diffParser.parse(chunkText);
            collected.addAll(provider.review(chunkText, lines));
        }
        return new ResultCache.ScanResult(finalizeFindings(collected), new Usage(inputBytes, chunks.size(), false));
    }

    /** Dedupe by id, then sort by (path, line, ruleId). Truncation happens per job, not here. */
    static List<Finding> finalizeFindings(List<Finding> raw) {
        Map<String, Finding> byId = new LinkedHashMap<>();
        for (Finding f : raw) {
            byId.putIfAbsent(f.id(), f);
        }
        List<Finding> list = new ArrayList<>(byId.values());
        list.sort(ORDER);
        return list;
    }
}
