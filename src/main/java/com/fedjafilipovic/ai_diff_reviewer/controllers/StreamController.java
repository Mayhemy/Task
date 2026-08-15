package com.fedjafilipovic.ai_diff_reviewer.controllers;

import com.fedjafilipovic.ai_diff_reviewer.models.Job;
import com.fedjafilipovic.ai_diff_reviewer.services.JobService;
import com.fedjafilipovic.ai_diff_reviewer.exceptions.ApiExceptions.NotFoundException;
import org.springframework.http.MediaType;
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

    @GetMapping(value = "/v1/reviews/{jobId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String jobId) {
        Job job = jobService.getJob(jobId); // throws NotFoundException (404) if unknown
        SseEmitter emitter = new SseEmitter(0L); // no server-side timeout
        job.subscribe(emitter);
        return emitter;
    }
}
