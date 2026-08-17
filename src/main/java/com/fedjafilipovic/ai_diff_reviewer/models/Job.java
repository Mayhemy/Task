package com.fedjafilipovic.ai_diff_reviewer.models;

import com.fedjafilipovic.ai_diff_reviewer.dto.Finding;
import com.fedjafilipovic.ai_diff_reviewer.dto.SseEventRecord;
import com.fedjafilipovic.ai_diff_reviewer.dto.Usage;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A review job. Holds an append-only SSE event log plus a live-subscriber
 * list: every state change is appended to the log AND pushed to attached
 * emitters, so a fresh connection to a finished job replays the exact same
 * event sequence a live connection saw.
 */
public class Job {

    private final String id;
    private volatile JobStatus status = JobStatus.QUEUED;
    private volatile List<Finding> findings = List.of();
    private volatile Usage usage;
    private volatile String errorMessage;

    private final List<SseEventRecord> eventLog = new ArrayList<>();
    private final List<SseEmitter> subscribers = new CopyOnWriteArrayList<>();
    private volatile boolean terminal = false;

    public Job(String id, Usage initialUsage) {
        this.id = id;
        this.usage = initialUsage;
        // Recorded at creation even if nobody is listening yet, so replay is complete.
        appendOnly("status", Map.of("status", JobStatus.QUEUED.toJson()));
    }

    public String getId() { return id; }
    public JobStatus getStatus() { return status; }
    public List<Finding> getFindings() { return findings; }
    public Usage getUsage() { return usage; }
    public String getErrorMessage() { return errorMessage; }
    public boolean isTerminal() { return terminal; }

    public synchronized void markRunning() {
        this.status = JobStatus.RUNNING;
        appendAndBroadcast("status", Map.of("status", JobStatus.RUNNING.toJson()));
    }

    public synchronized void emitFinding(Finding f) {
        appendAndBroadcast("finding", f);
    }

    public synchronized void finishSuccess(List<Finding> truncated, Usage finalUsage) {
        this.findings = truncated;
        this.usage = finalUsage;
        this.status = JobStatus.DONE;
        appendAndBroadcast("status", Map.of("status", JobStatus.DONE.toJson()));
        appendAndBroadcast("done", ordered("total", truncated.size(), "usage", finalUsage));
        completeTerminal();
    }

    public synchronized void finishFailure(String message) {
        this.errorMessage = message;
        this.status = JobStatus.FAILED;
        // Terminal event for a failed job: status:failed carrying the error.
        // The contract's `done` schema ({total, usage}) doesn't fit failure.
        appendAndBroadcast("status", ordered("status", JobStatus.FAILED.toJson(), "error", message));
        completeTerminal();
    }

    /**
     * Two-entry event payload with a fixed field order. Map.of derives its
     * iteration order from a per-JVM-start SALT, so the `done` event's JSON
     * came out as {total, usage} on one boot and {usage, total} on the next.
     * Replay stayed self-consistent (same map instance, same JVM), but the
     * shape of a documented event should not depend on when the process
     * started. Single-entry payloads have nothing to reorder and still use
     * Map.of.
     */
    private static Map<String, Object> ordered(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k1, v1);
        m.put(k2, v2);
        return m;
    }

    /**
     * Replays the full event log, then either completes (terminal job) or
     * attaches the emitter for live events. Synchronized so a transition
     * can't interleave between replay and attach (which would lose events).
     */
    public synchronized void subscribe(SseEmitter emitter) {
        try {
            for (SseEventRecord rec : eventLog) {
                emitter.send(SseEmitter.event().name(rec.type()).data(rec.data()));
            }
            if (terminal) {
                emitter.complete();
            } else {
                subscribers.add(emitter);
                emitter.onCompletion(() -> subscribers.remove(emitter));
                emitter.onTimeout(() -> subscribers.remove(emitter));
                emitter.onError(e -> subscribers.remove(emitter));
            }
        } catch (IOException e) {
            subscribers.remove(emitter);
            emitter.completeWithError(e);
        }
    }

    private void appendOnly(String type, Object data) {
        synchronized (this) {
            eventLog.add(new SseEventRecord(type, data));
        }
    }

    private void appendAndBroadcast(String type, Object data) {
        eventLog.add(new SseEventRecord(type, data));
        for (SseEmitter em : subscribers) {
            try {
                em.send(SseEmitter.event().name(type).data(data));
            } catch (IOException | IllegalStateException e) {
                subscribers.remove(em);
            }
        }
    }

    private void completeTerminal() {
        this.terminal = true;
        for (SseEmitter em : subscribers) {
            try {
                em.complete();
            } catch (IllegalStateException ignored) {
                // already completed
            }
        }
        subscribers.clear();
    }
}
