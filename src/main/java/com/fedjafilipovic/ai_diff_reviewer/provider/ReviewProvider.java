package com.fedjafilipovic.ai_diff_reviewer.provider;

import com.fedjafilipovic.ai_diff_reviewer.domain.DiffLine;
import com.fedjafilipovic.ai_diff_reviewer.domain.Finding;

import java.util.List;

public interface ReviewProvider {

    /**
     * Reviews one chunk of a diff.
     *
     * @param chunkText raw chunk text (the LLM provider needs full context)
     * @param lines     parsed hunk lines of this chunk, including CONTEXT lines
     *                  (MOCK-004 needs them for brace matching)
     */
    List<Finding> review(String chunkText, List<DiffLine> lines) throws ProviderException;
}
