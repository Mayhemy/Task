package com.fedjafilipovic.ai_diff_reviewer.controllers;

import com.fedjafilipovic.ai_diff_reviewer.models.Job;
import com.fedjafilipovic.ai_diff_reviewer.services.JobService;
import com.fedjafilipovic.ai_diff_reviewer.exceptions.ApiExceptions.NotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE stream. Unknown jobId -> 404 envelope JSON (resolved before opening the
 * stream). Auth is enforced by BearerAuthFilter (this is under /v1/).
 *
 * Job.subscribe replays the full event log, then completes (terminal) or
 * attaches for live events — so a connection to a finished job replays
 * byte-identically to a live connection.
 */
@RestController
public class StreamController {

    private final JobService jobService;

    public StreamController(JobService jobService) {
        this.jobService = jobService;
    }

    /**
     * Deliberately no {@code produces = text/event-stream}. Declaring it makes
     * the Accept header part of the routing decision, so a client asking for
     * anything else stops matching this handler at all and gets a negotiation
     * failure instead of the stream. SseEmitter sets Content-Type:
     * text/event-stream on the response by itself, so the declaration bought
     * nothing and cost us a whole class of client. Pinned by
     * SseIntegrationTest.streamContentTypeIsEventStream.
     */
    @GetMapping("/v1/reviews/{jobId}/stream")
    public SseEmitter stream(@PathVariable String jobId) {
        Job job = jobService.getJob(jobId); // throws NotFoundException (404) if unknown
        SseEmitter emitter = new SseEmitter(0L); // no server-side timeout
        job.subscribe(emitter);
        return emitter;
    }
}
