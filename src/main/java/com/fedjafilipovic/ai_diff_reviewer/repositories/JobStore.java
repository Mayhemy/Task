package com.fedjafilipovic.ai_diff_reviewer.repositories;

import com.fedjafilipovic.ai_diff_reviewer.models.Job;
import com.fedjafilipovic.ai_diff_reviewer.dto.Usage;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory job registry. jobId -> Job. */
@Component
public class JobStore {

    private final ConcurrentHashMap<String, Job> jobs = new ConcurrentHashMap<>();

    public Job create(Usage initialUsage) {
        String id = UUID.randomUUID().toString();
        Job job = new Job(id, initialUsage);
        jobs.put(id, job);
        return job;
    }

    public Job get(String id) {
        return jobs.get(id);
    }

    public boolean exists(String id) {
        return jobs.containsKey(id);
    }
}
