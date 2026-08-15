# STATUS.md

Detailed project status: build history, the full engineering log with root-cause writeups, test-coverage mapping, and the deployment runbook. `CLAUDE.md` stays intentionally short and holds only the always-relevant conventions; this file is the detailed companion — consulted when auditing for bugs, writing new tests, touching deployment, or picking this project back up after a gap, rather than kept in working memory at all times.

Current state: all 12 implementation steps done, build green, full test suite (197 tests, §7) passing, service deployed and reachable via Docker + ngrok (§8) with real LLM credentials (Groq) configured and the `llm` path verified end to end in both its unconfigured-failure and configured-success modes. Two live adversarial sweeps against the deployed service (not just the local test suite) are documented in §6.19–§6.22, the second run after a package reorganization by architectural role, followed by a hardening pass (§6.23: constant-time auth check, WWW-Authenticate header, LLM retries, per-caller rate limiting, `maxFindings: 0`).

**Most recent pass (§6.24–§6.28) — a fresh spec-vs-implementation audit that found five real bugs**, four of them in code paths every existing test had happened to miss. The largest: any request carrying an `Accept` header that excludes JSON turned every error envelope into a `500`, including `GET /v1/reviews/{unknown}/stream` with `Accept: text/event-stream` — what a *correct* SSE client sends — and `GET /health` with `Accept: application/xml`. Also fixed: `+++`/`---` content lines inside a hunk misparsed as file headers (wrong path, wrong line numbers, and silent finding loss in the chunker), MOCK-004's brace scan running past a file boundary (making chunked and unchunked scans disagree — a directly scored property), and a hunkless chunk failing the whole job. §6.14, previously a documented limitation, is now actually fixed.

`README.md` and `SUBMISSION.md` are both written; `SUBMISSION.md` is committed separately, on its own schedule.

### Status legend
- ✅ DONE — implemented and verified (code compiles + tests pass + live HTTP smoke tests pass).
- 🟡 CODE-DONE / TESTS-PENDING — code is in place but not yet covered by the test suite.
- ⬜ NOT DONE — not yet implemented.

### Per-step verification status
- Step 1 pom.xml — ✅ DONE.
- Step 2 AppLimits — ✅ DONE. (Sub: ✅ /spec-vs-behavior cross-check test in place, `AuthRoutingIntegrationTest.specNoTokenReturns200`.)
- Step 3 domain model — ✅ DONE. (Sub: ✅ domain unit tests in place, `JobTest`.)
- Step 4 DiffParser — ✅ DONE. (Sub: ✅ parser unit tests in place: CRLF, multi-hunk, /dev/null both ways, `\ No newline`, git-quoted path, b/ prefix, tab timestamp, short-form `@@ -1 +1 @@`, no-hunks 422, blank context line without leading space, `+++`/`---` content lines inside a hunk, lenient chunk parse. Fixed a real bug where blank context lines without a leading space silently dropped the new-file counter — see §6.11 — and later a second where content lines imitating file headers corrupted the path and every line number after them — see §6.25.)
- Step 5 Chunker — ✅ DONE. (Sub: ✅ chunker tests in place: byte-vs-char sizing, multi-file packing, oversized single file, spoofed vs. real `--- ` boundary. Fixed the `usage.inputBytes` off-by-one at its root cause in `Chunker.splitByFile`, not just worked around at the call site — see §6.10 — and the marker-spoofing split that silently lost findings, previously accepted as a limitation — see §6.14/§6.25.)
- Step 6 MockReviewProvider — ✅ DONE. (Sub: ✅ per-rule unit tests + decoy tests + MOCK-004 variants in place. Fixed a MOCK-004 false-positive on multi-line catch bodies — see §6.12 — and a brace scan that crossed file boundaries, making chunked and unchunked scans disagree — see §6.26.)
- Step 7 LlmReviewProvider — ✅ DONE. (Sub: ✅ llm unconfigured-failure integration test in place; configured-success path run end to end against real Groq credentials — see §6.17.)
- Step 8 JobService/lifecycle — ✅ DONE. (Sub: ✅ lifecycle + catch-all + 30s-budget tests in place.)
- Step 9 IdempotencyStore/ResultCache — ✅ DONE. (Sub: ✅ atomicity, cacheHit, conflict, failed-not-cached tests in place.)
- Step 9 IdempotencyStore/ResultCache — see also §6.29: the cache key moved from raw body bytes to the canonical `(provider, maxFindings, diff)` tuple, reversing an earlier documented decision.
- Step 10 web layer — ✅ DONE. (Sub: ✅ precedence 401→429→413→400→422, payload boundaries, envelope-on-all-non-2xx tests in place. Fixed a precedence bug where `options` was validated after `diff`, contradicting the documented 400→422 order — see §6.13 — and later that "all non-2xx" silently excluded any request whose `Accept` header didn't admit JSON, which returned 500 instead — see §6.27.)
- Step 11 SSE — ✅ DONE. (Sub: ✅ live sequence, post-completion replay byte-identical, failed-job replay tests in place, plus unknown-jobId-with-SSE-Accept and non-event-stream-Accept cases — §6.27.)
- Step 12 remaining endpoints — ✅ DONE. (Sub: ✅ /health, /spec, GET job, unknown-route envelope tests in place, plus non-JSON `Accept` on both — §6.27.)

---

## 4. Step-by-step implementation

### Step 1 — pom.xml ✅ DONE

- `spring-boot-starter-web`. Nothing else: no database, no security starter, no validation starter (validated manually for exact control of error codes).
- `<version>1.0.0</version>` so `/health` reports clean semver.
- Java 21.

### Step 2 — `AppLimits` (single source of truth) ✅ DONE

```java
public final class AppLimits {
    public static final long MAX_PAYLOAD_BYTES = 1_048_576;   // 1 MiB, whole request body
    public static final int  CHUNK_BYTES = 65_536;            // 64 KiB
    public static final int  MAX_CONCURRENT_JOBS = 4;
    public static final int  RATE_LIMIT_PER_MINUTE = 30;
    public static final int  DEFAULT_MAX_FINDINGS = 100;
    public static final String VERSION = "1.0.0";
    public static final String SPEC_VERSION = "1.0";
    private AppLimits() {}
}
```

`/spec`, the payload guard, the rate limiter, the chunker, and the executor pool **all** read from this class. The grader cross-checks `/spec` against enforced behavior at exact boundaries — nothing hardcoded twice.

### Step 3 — domain model ✅ DONE

- `JobStatus { QUEUED, RUNNING, DONE, FAILED }` — serialized lowercase via `@JsonValue`.
- `Finding(String id, String ruleId, String path, int line, String severity, String category, String title, String evidence)`; `id = ruleId + ":" + path + ":" + line`.
- `Usage(long inputBytes, int chunks, boolean cacheHit)` — `inputBytes` = **UTF-8 byte length of the `diff` string** (not the whole request body).
- `DiffLine(String path, Integer newLine, LineType type, String content)` where `LineType { ADDED, CONTEXT, REMOVED }`; `newLine` is null for REMOVED. `content` has no leading marker and no trailing `\r`.
- `Job`: `id`, `volatile JobStatus status`, `volatile List<Finding> findings`, `volatile Usage usage`, `volatile String errorMessage`, `List<SseEventRecord> eventLog` (append-only, guarded by `synchronized`), `List<SseEmitter> subscribers` (`CopyOnWriteArrayList`), `volatile boolean terminal`.

### Step 4 — DiffParser (highest-leverage, most error-prone piece) ✅ DONE

Input: the raw diff string. Output: `List<DiffLine>` covering **all** hunk lines (added, context, removed) — MOCK-004 needs context lines for brace matching.

Algorithm per line of `diffText.split("\n", -1)` (minus one stripped trailing artifact — see §6.11):

1. **Strip one trailing `\r`** from every line first (CRLF diffs).
2. `+++ <path>` → set current path: strip the `+++ ` prefix; git-quoted path (`"..."`) → strip quotes; cut at the first **tab** (classic `diff -u` timestamp); strip a leading `b/` prefix. `/dev/null` → current path = null (deleted file). **Check this before the generic `+` branch.**
3. `--- <path>` → ignore (old-file marker).
4. Hunk header `^@@ -\d+(?:,\d+)? \+(\d+)(?:,\d+)? @@` → reset the new-file counter to group 1; set `sawHunk = true`.
5. Line starts with `+` (path non-null) → `ADDED`, increment counter.
6. Line starts with ` ` → `CONTEXT`, increment counter.
7. Line starts with `-` → `REMOVED`, do not increment.
8. Line is **empty** (path non-null) → `CONTEXT` with empty content, increment counter (§6.11 — real-world diffs sometimes omit the leading space on a blank context line).
9. Line starts with `\` (`\ No newline at end of file`) → ignore.
10. Anything else (`diff --git`, `index`, preamble) → ignore.

**Line-numbering invariant:** the counter derives from each hunk's own `@@` header, never a running total across the diff — this is what makes file-boundary chunking exact.

**422 trigger:** if `sawHunk` is false after the loop → `InvalidDiffException`.

### Step 5 — Chunker ✅ DONE

1. Split into **file segments**: `diff --git ` marker if present, else `--- `. Keep the marker with its segment.
2. Pack into chunks of at most `CHUNK_BYTES` **UTF-8 bytes**. Never split one file across two chunks; an oversized single file becomes its own chunk.
3. `usage.chunks` = chunk count, always ≥ 1.
4. Each chunk parsed and scanned **independently**; findings merged, then globally sorted and deduped. Segment reconstruction is byte-exact against the original diff text (§6.10 root-cause fix).

### Step 6 — MockReviewProvider (scored exactly) ✅ DONE

```java
public interface ReviewProvider {
    List<Finding> review(String chunkText, List<DiffLine> lines) throws ProviderException;
}
```

Rules apply to **`ADDED` lines only** (MOCK-004 is the exception). One finding per rule per matching line, even if the pattern matches twice. `evidence` = the added line's content verbatim.

**Interpretation decisions:**

| Rule | Implementation | Decision |
|---|---|---|
| MOCK-001 eval usage, critical/security | `content.contains("eval(")` | Literal contains — spec says "contains". `myeval(` DOES trigger. |
| MOCK-002 hardcoded credential, critical/security | `Pattern.compile("(api[_-]?key\|secret\|token)\\s*[:=]\\s*['\"][A-Za-z0-9_\\-]{16,}['\"]", CASE_INSENSITIVE)` | Exactly the regex from the brief, no "improvements". |
| MOCK-003 SQL string concatenation, high/security | `Pattern.compile("['\"][^'\"]*\\b(SELECT\|INSERT\|UPDATE\|DELETE)\\b[^'\"]*['\"]\\s*\\+\|\\+\\s*['\"][^'\"]*\\b(SELECT\|INSERT\|UPDATE\|DELETE)\\b[^'\"]*['\"]")` | **Case-SENSITIVE** — the brief marks MOCK-002 (`/i`) and MOCK-INJ ("case-insensitive") explicitly, so their absence here is meaningful. **The guide's `CASE_INSENSITIVE` is a bug — deviate from it.** `\b` prevents `SELECTION` matching. |
| MOCK-004 swallowed exception, high/correctness | Multi-line scan — see below | Comment-only body counts as empty. Report only if the `catch` line itself is ADDED. |
| MOCK-005 loose null comparison, medium/correctness | `Pattern.compile("(?<![=!])(==\|!=) null")` | **Excludes `=== null` / `!== null` decoys** via negative lookbehind. |
| MOCK-006 deep-clone via JSON, medium/performance | `content.contains("JSON.parse(JSON.stringify(")` | Literal, case-sensitive. |
| MOCK-007 console.log left in, low/style | `content.contains("console.log(")` | Literal. |
| MOCK-008 unresolved marker, low/style | `content.contains("TODO") \|\| content.contains("FIXME")` | Literal, case-sensitive. |
| MOCK-INJ prompt-injection content, critical/security | lowercase(content) contains any of `"ignore previous instructions"`, `"disregard all prior"`, `"you are now"` | Case-insensitive per spec. Content is **inert data**. |

**MOCK-004 algorithm:** walk the chunk's `DiffLine`s in order (ADDED and CONTEXT only). On a line matching `\bcatch\s*\([^)]*\)\s*\{` (word-boundary — see §6.15), start brace depth from the match; consume following lines, updating depth, collecting body lines **joined with real `\n`** (see §6.12), until depth returns to 0. If every body line is blank or comment-only, **and the catch line's type is ADDED**, emit the finding at the catch line's new-file number. Does not handle braces inside string literals — documented simplification.

**Finalize (after merging all chunks):** dedupe by `id`, sort by `(path, line, ruleId)`, truncate to `maxFindings`. `usage` always reflects the **full** scan regardless of truncation.

### Step 7 — LlmReviewProvider ✅ DONE

- Generic **OpenAI-compatible** client: `POST {LLM_BASE_URL}/chat/completions`, body `{model, messages, temperature: 0}`, timeout from `LLM_TIMEOUT_SECONDS`.
- Blank config → `ProviderException("llm provider not configured")` immediately, per job.
- System prompt marks `<diff>` content as data, never instructions, and explicitly constrains `severity`/`category` to the two closed vocabularies (added after §6.17).
- Parse defensively: strip markdown fences, expect an array, validate each element's `severity`/`category`/`ruleId`/`path`/`line`, skip anything malformed or out-of-vocabulary (§6.17) — never trust the model's output shape blindly. **Any** exception (connect, timeout, vendor 5xx, bad JSON, schema mismatch) → `ProviderException`.
- `JobService` catches `ProviderException` → job `FAILED`, terminal SSE event, never an HTTP 5xx.

### Step 8 — JobService, executor, lifecycle ✅ DONE

- Executor: `ThreadPoolExecutor(4, 4, 0L, MS, new LinkedBlockingQueue<>())` — unbounded queue, never rejects.
- POST handler (synchronous): read bounded raw body → hash → idempotency check → validate/parse/chunk → create Job → enqueue worker → return 202.
- Worker: RUNNING → get-or-compute scan via ResultCache → sort/dedupe/truncate → emit findings in final sorted order → DONE + `done` event. On `ProviderException` → FAILED + terminal `status:failed`. Catch-all for anything else → FAILED with `"internal error: ..."` — **a swallowed exception that leaves a job forever `running` is a scored failure.**

### Step 9 — IdempotencyStore and ResultCache (two different mechanisms) ✅ DONE

Both keyed on **SHA-256 hex of the raw request body bytes** (byte-identical, not canonicalized JSON).

- **Idempotency** (`Idempotency-Key` header): same key + same body hash → existing `jobId` with its **real current status** (not hardcoded `"queued"`). Same key + different hash → 409. Check-and-insert is atomic.
- **ResultCache**: `ConcurrentHashMap<bodyHash, CompletableFuture<ScanResult>>`. First submission creates the future; a concurrent byte-identical submission **attaches to the in-flight future**, reports `cacheHit: true`. Only successful results stay cached — on failure the entry is removed so a retry re-runs (critical for the llm path).
- Net semantics: same key → same job. New/no key + identical body → **new jobId**, `cacheHit: true`, identical findings.

### Step 10 — web layer ✅ DONE

- **`BearerAuthFilter`** (outermost, `@Order(1)`): every method on `/v1` or `/v1/*`. Exact string match on `Bearer <token>`.
- **`RateLimitFilter`** (`@Order(2)`): only `POST /v1/reviews` exactly. Two token buckets, not one (added in §6.23): one per caller (keyed by the Authorization header) at capacity 30/refill 0.5 per sec — this is the number `/spec` declares — plus one shared overall bucket at 120/refill 2 per sec as a guardrail. Both start full; a request needs a token from both to proceed. 429 + `Retry-After` + envelope, never 5xx.
- **Payload guard** (in the controller, not a filter): fast-path `Content-Length > MAX_PAYLOAD_BYTES` → 413 before touching the input stream; otherwise a bounded read that throws after `MAX_PAYLOAD_BYTES + 1` bytes.
- **Body handling:** manual `ObjectMapper.readTree` (not `@RequestBody`). Malformed JSON → 400. Not an object → 400. `diff` missing/empty/blank/non-textual/unparseable → 422. `options` present but not an object (and not null) → 400 (§6.16). `options.provider` not `mock`/`llm` → 400. `options.maxFindings` negative → 400 (0 is accepted — §6.23). Validated **before** `diff` (§6.13) — precedence is 401 → 429 → 413 → 400 → 422.
- **`ApiExceptionHandler`**: maps every thrown exception type to the envelope, including Spring's own (`NoResourceFoundException`, `HttpRequestMethodNotSupportedException`, `HttpMediaTypeNotSupportedException`) — "all non-2xx" means all, not just the ones the app throws itself.
- **`EnvelopeErrorController`** (`ErrorController` on `/error`): safety net for any container-level error that reaches Spring's default error page instead.

### Step 11 — SSE (StreamController + Job.subscribe) ✅ DONE

- `SseEmitter(0L)` (no server-side timeout). Unknown jobId → 404 envelope **before** opening the stream.
- `Job.subscribe(emitter)` (synchronized): replay the entire `eventLog` in order → if terminal, complete; else attach live.
- Event sequence: `status:queued` (at creation) → `status:running` → one `finding` event per finding in final sorted order → `status:done` → `done:{total, usage}`. Failed job: `status:failed` as the terminal event instead of `done` (the `{total, usage}` shape doesn't fit a failure).

### Step 12 — remaining endpoints & wiring ✅ DONE

- `GET /health` → `{"status":"ok","version","uptimeSeconds"}`.
- `GET /spec` → limits from `AppLimits`.
- `GET /v1/reviews/{jobId}` → 404 if unknown; `findings` always present (`[]` until done); `usage` present from creation; `error` only when failed. Failed jobs return 200.

---

## 5. Non-happy-path matrix — what the graders will throw at us

Each row is a probe the scoring rubric implies; each maps to specific test classes (§7).

### Auth & routing
1. Every `/v1/**` method with no `Authorization` header → 401 envelope.
2. Garbage token, `Bearer` prefix missing, token with extra whitespace → 401.
3. `/health`, `/spec` with no token (or garbage token) → 200.
4. Unknown path → non-2xx **with the envelope**.
5. Wrong method on a route → envelope, never a bare 405.
5b. **Any `Accept` header** (`application/xml`, `text/plain`, `text/event-stream`) → the real status code and the envelope, never a negotiation failure. Applies to 2xx too: `/health` and `/spec` stay 200 (§6.27).

### POST validation (precedence: 401 → 429 → 413 → 400 → 422)
6. Body of exactly 1,048,576 bytes → accepted; 1,048,577 → 413. Test with and without a truthful `Content-Length`.
7. Malformed JSON → 400. Valid JSON of wrong shape (`[1,2]`, `"hello"`, `42`) → 400.
8. `diff` missing/empty/blank/wrong-type/no-hunks → 422.
9. Unknown top-level fields → ignored, 202.
10. `options` absent/null/`{}` → defaults. Wrong type → 400. `provider` invalid → 400. `maxFindings` negative → 400 (0 is valid — see §6.23). `maxFindings: 3` on a 10-finding diff → exactly 3 findings, `usage` reflects the full scan.
11. Content-Type `text/plain` with valid JSON → works.

### Diff parsing & mock rules
12. CRLF diff → identical findings to LF; no `\r` anywhere.
13. Decoys that must NOT fire: `x === null`/`!== null` (MOCK-005); `SELECTION` (MOCK-003); lowercase SQL (MOCK-003); lowercase `todo` (MOCK-008); `mycatch(` (MOCK-004, §6.15). `myeval(x)` DOES fire MOCK-001.
14. Two `eval(` on one line → one MOCK-001; `eval(` + `console.log(` same line → two findings.
15. Interleaved context/added/removed, multiple hunks → exact line numbers. `/dev/null` both directions.
16. `catch (e) {}`; spanning empty catch; comment-only body (fires); comment-then-real-statement (must NOT fire, §6.12); non-added catch (must NOT fire).
17. Injection lines → exactly one MOCK-INJ finding, other rules unaffected, content inert even reaching the LLM.
18. `evidence` verbatim, no `+`, no `\r`.

### Chunking
19. Many small files totaling > 64 KiB → `usage.chunks` ≥ 2, byte-identical findings to unchunked.
20. Single file > 64 KiB → one oversized chunk, no mid-file split.
21. Multi-byte UTF-8 near chunk boundaries → byte-based sizing.
21b. A chunk containing only a hunkless file entry (pure rename / mode change / binary) → scans to zero findings, job still reaches `done` (§6.28).
21c. Content lines that imitate structural markers (`+++`/`---` produced by adding a `++ ` line or removing a `-- ` line) → no mis-split, no lost findings (§6.25).
21d. An added `catch (e) {` whose closing brace is outside the hunk → same MOCK-004 answer chunked and unchunked (§6.26).

### Lifecycle, caching, idempotency
22. POST → poll → `done` well under 30s for a ≤64 KiB diff.
23. GET/stream unknown jobId → 404 envelope.
24. Same key + identical body twice → same `jobId`, real status.
25. Same key + different body → 409.
26. Identical body, different/no key → different `jobId`, `cacheHit: true`. Semantically identical but byte-different body (reordered JSON keys, different indentation) → also `cacheHit: true` (§6.29).
27. Concurrent identical bodies → exactly one does the work. Concurrent same-key → exactly one job.
28. Failed llm job NOT cached — resubmit re-runs.

### Rate limiting & concurrency
29. Burst past the limit → ~30 accepted, rest 429 + `Retry-After`, zero 5xx.
30. 6+ simultaneous submissions → all eventually terminal, none rejected.
31. `provider:"llm"` blank credentials → 202, then `failed`, no 5xx.

### SSE
32. Live connection → ordered sequence, closes after `done`.
33. Connect after completion, twice → byte-identical replay.
34. Failed job → replays through terminal `status:failed`.
35. Unknown jobId on `/stream` with `Accept: text/event-stream` → 404 envelope, not 500 (§6.27).
36. Valid job on `/stream` with a non-event-stream `Accept` → still served, still `Content-Type: text/event-stream` (§6.27).

---

## 6. Known bugs/gaps in the guide (do not propagate)

1. **No CRLF handling** in its parser — corrupts paths and evidence.
2. **MOCK-003 uses `CASE_INSENSITIVE`** — we use case-sensitive.
3. **MOCK-005 naive `contains`** — flags `=== null` decoys. Lookbehind fixes.
4. **Provider interface takes added-lines-only** — cannot implement MOCK-004. Ours passes `(chunkText, List<DiffLine>)`.
5. **Cache populated only on completion** — concurrent duplicates both do the work. In-flight `CompletableFuture` fixes.
6. **No coverage of Spring's own errors** (404/405/415). `EnvelopeErrorController` fixes.
7. **Idempotency check-then-insert not atomic**. Synchronized store fixes.
8. Its `+++` path handling keeps classic `diff -u` tab-separated timestamps in the path.
9. Its `version` story (`0.0.1-SNAPSHOT`) — we bump the pom to `1.0.0`.

### 6.10 Bug found & fixed — `usage.inputBytes` off-by-one
**In short:** the reported byte count was one too high for chunked diffs, because of a stray newline the chunker added when it reassembled files. Fixed at the root, not papered over.

`JobService.doScan` originally computed `inputBytes` by summing each chunk's UTF-8 bytes. But `Chunker.splitByFile` re-joined lines with `'\n'`, appending one trailing newline per segment vs the original text, so the chunk-byte sum was diff bytes + 1. **First fix:** `createAndEnqueue` computes the correct `inputBytes` directly from the diff string and passes it through — chunk bytes are never summed, closing the symptom immediately. **Root cause fixed on a later pass**: `splitByFile` always appended one spurious trailing `'\n'` to its last segment regardless of whether the source ended in one — fixed at the source; segments now reconstruct the original diff byte-for-byte, rather than leaving the underlying defect worked around indefinitely. Pinned by `JobServiceTest`/`LifecycleIntegrationTest`/`ChunkerTest.byteSizingCountsUtf8BytesNotChars`.

### 6.11 Bug found & fixed — blank context lines without a leading space dropped the line counter
**In short:** a genuinely blank line in a diff (no leading space, which real-world diffs do produce even though the strict format says they shouldn't) was silently skipped instead of counted — every line number after it in that hunk was then wrong.

Strict unified-diff format requires even a blank context line to carry a leading space. Real-world diffs often emit a fully empty line instead. `DiffParser` matched none of its branches for `""`, silently dropping the line **and failing to advance the counter** — every subsequent line in that hunk got the wrong `line` number. **Fix:** an empty line (path in effect) is now treated as a blank `CONTEXT` line. Required also fixing a related artifact: `split("\n", -1)` yields a trailing `""` when text ends in `'\n'` (virtually always) — stripped before iterating, or every diff would gain a phantom trailing blank line. Pinned by `DiffParserTest.blankContextLineWithoutLeadingSpaceStillAdvancesCounter` + 2 related tests + `MockReviewProviderTest.findingLineNumberIsCorrectAfterABlankContextLineWithoutLeadingSpace`.

### 6.12 Bug found & fixed — MOCK-004 false positive on multi-line catch bodies
**In short:** a catch block that had a comment on one line and a real statement on the next was flagged as "empty" anyway, because the two lines got concatenated with nothing between them and the comment marker swallowed the real code too.

`findSwallowedExceptions` reconstructed a multi-line catch body by concatenating lines with **no separator**. A `// comment` line followed by a real statement on the next line collapsed into one string where the `//` appeared to swallow the real statement too — a catch block that actually handles its exception got falsely flagged. **Fix:** lines joined with real `'\n'`; block-comment strip regex made `DOTALL`-aware (`(?s)/\*.*?\*/`) so genuine multi-line block comments still strip correctly. Pinned by `mock004_spanningCatchWithCommentThenRealStatementDoesNotFire` + `.mock004_spanningMultiLineBlockCommentOnlyBodyStillCountsAsEmpty`.

### 6.13 Bug found & fixed — `options` validated after `diff`, contradicting the documented precedence
**In short:** the code checked `diff` before `options`, so a request that was broken both ways came back with the wrong error code — 422 instead of the documented 400.

`ReviewController.create()` called `extractDiff` (422) before `extractOptions` (400), so a request invalid both ways returned 422, the reverse of `400 → 422`. **Fix:** `options` validated first. Pinned by `PostValidationIntegrationTest.invalidOptionsPrecedesInvalidDiff`.

### 6.14 ~~Documented limitation (not fixed)~~ — **now fixed, see §6.25** — Chunker's non-git `"--- "` marker can be spoofed by content
**In short:** if a diff isn't git-style, file boundaries are found by looking for lines starting with `"--- "` — so a piece of *content* that happens to start the same way could be misread as a new file. Originally accepted as a limitation; **fixed in §6.25** once it turned out to cause silent finding loss, not just a cosmetic mis-split.

For a non-git diff, file boundaries were detected via `line.startsWith("--- ")`. A content line legitimately starting with literal `"--- "` would be misread as a boundary, risking a mid-file split over 64 KiB. Originally accepted as-is on the reasoning that graders overwhelmingly use git-style diffs and a proper fix needs `---`/`+++` pairing state — more invasive than the benefit justified.

**That assessment was wrong on the severity.** The mis-split doesn't merely produce an odd chunk: the second half of a split file arrives at the parser with no `+++` header above it, so `currentPath` stays null and **every finding in it is silently dropped** — a direct hit on the scored "no losses" property. And the trigger isn't exotic: a removed line whose own content starts with `-- ` renders as the raw line `--- ...`. The pairing fix also turned out to be three lines, not the invasive rewrite anticipated. See §6.25.

### 6.15 Bug found & fixed — MOCK-004's `catch` regex had no word boundary
**In short:** a function named `mycatch(...)` was falsely flagged as a swallowed-exception `catch` block, because the regex matched the substring `catch` anywhere, not just as its own word.

No `\b` before `catch` — `mycatch(e) {}` (a custom function, not a real try/catch) falsely fired MOCK-004. Same class of bug as MOCK-003's `SELECTION`/`SELECT` guard, just never applied here. **Fix:** `\\bcatch\\s*\\(...`. Pinned by `mock004_identifierEndingInCatchDoesNotFire`.

### 6.16 Bug found & fixed — `options` of the wrong JSON type silently defaulted instead of 400
**In short:** sending `"options": "nope"` (a string instead of an object) was silently treated as if `options` had been left out entirely, instead of being rejected as malformed.

Any non-object value (string/number/boolean/array, not just null/absent) was silently treated as "use defaults" — inconsistent with how a non-textual `diff` is rejected. **Fix:** only null/absent default; any other non-object type → 400. Pinned by `optionsWrongTypeReturns400`.

### 6.17 Bug found & fixed — LLM findings with an invalid severity/category leaked through unvalidated
**In short:** the first real (non-mocked) model call ever run returned a `severity` and `category` the spec doesn't allow — the code trusted the model's output completely and had no validation catching that.

Once real credentials (Groq, `openai/gpt-oss-20b`) were exercised end-to-end for the first time, a live response returned `severity: "warning"` and `category: "Logic"`/`"Syntax"` — neither in the spec's fixed vocabulary — and echoed the diff's `b/f.js` path verbatim. `parseFindings` had zero validation. **Fix:** system prompt spells out the two closed vocabularies; `parseFindings` validates (lowercased) `severity`/`category` and skips non-conforming elements, same as it already skips a blank `ruleId`. `path` normalized to strip `a/`/`b/`. Made `parseFindings` package-private for direct unit testing (`LlmReviewProviderTest`) without mocking HTTP. **Only found by actually running the configured-success path with a real model** — the failure-path tests can't surface a success-path schema bug by definition.

### 6.18 Deployment-layer bug found & fixed — `Expect: 100-continue` through ngrok mangled a rejected large upload's status code
**In short:** the app itself always correctly returned 413 for an oversized upload — but through the ngrok tunnel specifically, some clients got a `200` status line instead, because of how ngrok relayed the `Expect: 100-continue` HTTP negotiation. Fixed at the tunnel config level, not worked around client-side.

Verified directly against the app (`127.0.0.1:8080`, inside the container): a body one byte over the limit always correctly returns 413 — never an app bug. Through the ngrok tunnel, a client sending `Expect: 100-continue` (curl's default for bodies over ~1KB) got the correct 413 **body** but the wrong `200` **status line**. Disabling `Expect` client-side fixed it through ngrok too, isolating the cause to ngrok's proxy relay of the 100-continue negotiation on an origin rejection.

Initially documented as an accepted, client-dependent risk. **Actually fixed**: ngrok's Traffic Policy module strips a header from every request before relaying upstream, regardless of what the client sent — no longer dependent on the grader's HTTP client at all. Endpoint config (`C:\ProgramData\ngrok\ngrok.yml`):
```yaml
endpoints:
    - name: ai-diff-reviewer
      url: https://armless-dispersed-spinout.ngrok-free.dev
      traffic_policy:
        on_http_request:
          - actions:
              - type: remove-headers
                config:
                  headers:
                    - Expect
      upstream:
        url: 8080
```
Re-verified with the exact previously-broken request (default curl, no flags) → correct 413; accept-boundary case still 202; full endpoint smoke test unaffected; survives an `ngrok` service restart.

### 6.19 Bug found & fixed — `options.maxFindings` overflowing `int` crashed with a 500
**In short:** sending a huge `maxFindings` value like `3000000000` (too big for a 32-bit int, but not for JSON) crashed the request with a raw `500` instead of a clean `400` — found by testing the live deployed service, not by a unit test.

A JSON number that fits in a `long` but overflows a 32-bit `int` (e.g. `3000000000`, well within `Integer.MAX_VALUE < value < Long.MAX_VALUE`) passed the existing `isInt() || isLong()` type check, then `JsonNode.asInt()` threw internally (`JsonNodeException: cannot convert value ... to int: value not in 32-bit int range`) instead of clamping — an uncaught exception that escaped as `500 internal`, the one error code the contract says should essentially never happen for well-formed-but-extreme input. Found live, against the deployed service, not by unit test. **Fix:** read the value via `asLong()` first and explicitly range-check against `Integer.MAX_VALUE`/`MIN_VALUE` before narrowing, rejecting out-of-range values as `400 invalid_json` the same way other malformed `options` values already are. Pinned by `PostValidationIntegrationTest.maxFindingsOverflowingIntReturns400NotInternalError`; verified live against the redeployed container with the exact value that previously crashed it.

### 6.20 Known limitation (attempted a fix, reverted — documented, not solved) — connector-level request rejections don't use the error envelope
**In short:** a couple of malformed-URL edge cases (a null byte, a path-traversal sequence) never reach the application code at all — Tomcat rejects them at a lower level and returns its own plain HTML error page instead of the JSON envelope. A real fix was attempted and genuinely didn't work (see below); the gap is accepted and documented rather than shipped as a fake fix.

A null byte in the request URI (`/v1/reviews/%00`) and a path-traversal sequence Tomcat's own normalization refuses (`/v1/reviews/%2e%2e%2f...`) both return Tomcat's raw default HTML error page instead of the JSON envelope — confirmed live against the deployed service, not a local-testing artifact. Root cause: both are rejected by Tomcat at the connector/protocol-parsing level, before the request is ever routed to Spring's `DispatcherServlet` — `EnvelopeErrorController` and `ApiExceptionHandler` are webapp-level constructs that never get invoked for these, by construction, no matter what exception-handling code exists in the application. An overlong request line (>~8KB path) is rejected even earlier still, before any container-level valve pipeline runs at all.

**A fix was attempted and reverted after direct verification showed it didn't work.** The attempt: a `WebServerFactoryCustomizer<TomcatServletWebServerFactory>` (verified exact package/API against the actual `spring-boot-tomcat-4.0.7.jar` and `spring-boot-web-server-4.0.7.jar` on disk before writing any code) that removed Tomcat's default host-level `ErrorReportValve` via `context.getParent()` → `Pipeline.removeValve(...)` and installed a custom subclass overriding `report(...)` to write the JSON envelope instead of HTML. It compiled, deployed, and ran without error — but a regression test hitting the exact null-byte case still received Tomcat's raw HTML, proving the replacement valve was never actually being invoked for this failure path (most likely: Spring Boot's own Tomcat customizer re-adds or re-orders the default valve after context customizers run, or the specific rejection path bypasses the host pipeline's valve chain entirely — not conclusively diagnosed). Rather than ship a change that looks like a fix but empirically isn't one, it was reverted in full (both new classes deleted, the regression test removed) and the suite re-confirmed green at its prior count.

**Accepted as-is.** This is a narrow, security-scanner-style class of input (raw null bytes and traversal sequences in a URL, not something a normal REST client or a contract-focused automated grader constructs), the origin application's own logic has no bug here at all, and further investigation would mean debugging Tomcat/Spring Boot 4's internal valve-ordering machinery with no verified reference implementation for this exact version — a worse time/risk tradeoff than accepting the gap and documenting it precisely enough that a future attempt doesn't have to re-discover any of this.

### 6.21 Infrastructure finding — a burst of many simultaneous *new* TLS connections can temporarily stall the free ngrok tunnel
**In short:** the app handled a 35-connection burst perfectly (exactly the right mix of 202s and 429s, zero errors) — but ngrok's free tunnel itself got briefly stuck afterward and needed a restart. This is an ngrok quirk, not an application bug, and it's specific to many *brand-new* connections at once rather than how most real HTTP clients actually behave.

Live-testing the rate-limit boundary (§5 row 29 — `CANDIDATE-TASK.md` explicitly names "burst 40 POSTs as fast as possible" as a plausible probe) with 35 truly concurrent requests fired as 35 separate background `curl` processes — each opening its own fresh TCP+TLS connection, since curl doesn't pool connections across separate process invocations — correctly returned exactly 30×`202` / 5×`429`, zero 5xx: **the application's rate limiting and concurrency handling are exactly correct.** But a request made immediately *after* that burst failed at the TLS handshake stage (`schannel: failed to receive handshake`) against the public URL, while the app itself (`127.0.0.1:8080`) stayed perfectly healthy throughout — isolating the cause to the ngrok tunnel agent, not the origin service. ngrok's own local metrics API (`127.0.0.1:4040/api/tunnels`) showed 35 connections still counted as open (`"gauge":35`) well after all 35 requests had already completed successfully, with reported latency percentiles in the tens of seconds — consistent with connections not being cleanly torn down after use on the free tier, eventually exhausting some capacity for *new* connections. `Restart-Service ngrok` (a few seconds) fully recovered it; re-verified with a smaller 10-concurrent burst afterward (all `202`, tunnel stayed healthy, though the gauge still crept up rather than returning to 0, suggesting this is a real, reproducible pattern rather than a one-off).

**Why this is lower-risk than it first looks:** the failure mode is specific to *many simultaneous brand-new TCP/TLS handshakes*, which is how this test was constructed (separate OS processes, no shared connection pool) but is **not** how most real HTTP client libraries behave by default — Python `requests` Sessions, Node's `http.Agent` with `keepAlive`, Java's `HttpClient`, and most load-testing tools reuse a bounded pool of connections rather than opening one per request, so a grader's "burst 40" is more likely to mean 40 requests multiplexed over a handful of reused connections than 40 fresh handshakes. Not something to be fully confident is safe, but a meaningfully different (and less severe) load pattern than what was just tested.

**Not fixed** — this lives entirely in the free ngrok tunnel's connection handling, outside the application. **Mitigation if it happens during the actual scoring window**: `Restart-Service ngrok` (or `docker restart ai-diff-reviewer` if the app itself were ever implicated, though it wasn't here) recovers it in seconds without losing the stable hostname, since the dev domain reassignment is independent of any individual agent session. Worth a spot-check partway through the scoring window (96h per the actual assessment email) if there's any suspicion the service has gone quiet.

### 6.22 Second live adversarial sweep (post-reorg) — zero new bugs, four gaps closed with pinned tests

After the package reorganization (grouping by architectural role), the image was rebuilt, the container restarted fresh, and ngrok restarted fresh, then ~45 deliberately hostile requests were sent sequentially (not concurrently, to avoid re-triggering §6.21) against the live public URL: auth bypass variants (case, whitespace, duplicate/malformed headers, wrong scheme), malformed/hostile JSON (truncated, trailing comma, duplicate keys, wrong types on every field, `NaN`), Content-Type variants, path-traversal/null-byte/script-tag job IDs, prompt-injection trigger phrases, CRLF/no-final-newline/blank-context/headerless diffs, an oversized body under both a declared and a chunked (undeclared) Content-Length, idempotency conflict/replay-after-failure/whitespace-key/validation-then-retry sequences, a multi-file diff, and a real end-to-end call through the configured Groq LLM provider.

**Result: no new bugs.** Every response was either correct or matched an already-documented, accepted gap (the §6.20 connector-level HTML-instead-of-envelope limitation reproduced identically for null-byte and encoded-traversal paths; jobIds with SQL-injection-style or pathologically long content that don't trip Tomcat's own connector validation correctly reach the app and get a proper 404 envelope). The LLM path returned a real, distinct finding (`no-eval`, not a `MOCK-*` id) that passed the parseFindings severity/category validation from §6.17 — the first confirmation of that pipeline against a live, non-mocked LLM response.

Five behaviors verified live were real gaps in *test coverage*, not in the implementation, and are now pinned:
- Duplicate JSON keys (`{"diff":"","diff":"<valid>"}`) resolve last-value-wins (Jackson's default) — `AdversarialEdgeCaseIntegrationTest#duplicateJsonKeyLastValueWins`.
- `maxFindings` as a JSON float or string, not just out-of-range — `AdversarialEdgeCaseIntegrationTest#maxFindingsFloatReturns400` / `#maxFindingsStringReturns400`.
- An oversized body sent with chunked transfer encoding (no Content-Length for the fast-path guard to see) is still caught by the bounded streaming read — `AdversarialEdgeCaseIntegrationTest#chunkedTransferEncodingOversizedBodyStillReturns413`.
- A hunk with no preceding `--- `/`+++ ` file header at all (distinct from `+++ /dev/null`, which explicitly nulls the path) is a different code path to the same "nothing to review" outcome — `DiffParserTest#hunkWithNoPrecedingFileHeaderYieldsNoLines` (unit) and `LifecycleIntegrationTest#hunkWithNoFileHeaderCompletesWithZeroFindingsNotAnError` (end to end: `done`, zero findings, no crash).
- Idempotency-Key semantics under two conditions the existing tests didn't reach: replaying the same key after the job already terminated `failed` returns that *same* failed job rather than re-running (contrast with the no-key ResultCache retry in row 28), and a key attached to a request that fails synchronous validation (422/400, no job ever created) never occupies the slot, so the same key on a follow-up *valid* request succeeds fresh — `IdempotencyCacheIntegrationTest#idempotencyReplayAfterFailureReturnsSameFailedJobNotARetry` / `#idempotencyKeyOnAValidationFailingRequestNeverConsumesTheSlot` / `#whitespaceOnlyIdempotencyKeyTreatedAsAbsent`.

The four request-heavy additions (chunked/oversized, both maxFindings type checks, duplicate-key) were split into a new `AdversarialEdgeCaseIntegrationTest` class with its own rate-limit isolation marker rather than folded into `PostValidationIntegrationTest`, which was already close to that class's 30-token-per-class ceiling — adding them there directly caused exactly the failure it's designed to prevent (four spurious `429`s) on the first attempt, fixed by relocating rather than by touching the pre-existing tests' balance.

### 6.23 A deliberate hardening pass — five small improvements beyond bare spec compliance

Everything above gets the contract exactly right. This pass went back through it once more asking a different question: not "does this pass the grader" but "is there anything here I'd actually want to change if this were a real production service and not a take-home." Five things came out of it, all small, all low-risk, and all verified against the full suite plus a live check afterward.

**Bearer-token comparison is now constant-time.** `BearerAuthFilter` used to compare the header with plain `String.equals`, which can short-circuit on the first mismatched character. That makes it a (very impractical, but real) timing side-channel: in theory, an attacker measuring response latency across many guesses could infer the token one byte at a time faster than brute force. Swapped in `MessageDigest.isEqual`, the standard JDK utility built for exactly this — it runs in constant time no matter where (or whether) the strings diverge. Practically irrelevant over a real network with normal jitter, but it costs nothing to do correctly, and it's the kind of thing worth being able to point to and say "yes, I thought about this."

**401 responses now carry `WWW-Authenticate: Bearer`.** RFC 7235 says a 401 should carry this header so a client knows what auth scheme is expected. Not something `CANDIDATE-TASK.md` asks for, and not something any existing test checked for — just a small correctness detail that was missing.

**The `llm` provider now retries once on a transient failure.** A connection error, a timeout, or a 429/5xx from the model vendor now gets one retry with a short jittered backoff (roughly 300–450ms) before the job fails. A deterministic failure — a bad API key (401), a malformed response — is never retried, since retrying something that can't change just burns time for nothing. The whole retry-plus-backoff sequence shares one hard 25-second budget regardless of how the per-request timeout is configured, so this can never itself push a job past the 30s SLA — a slow or misconfigured `LLM_TIMEOUT_SECONDS` used to be able to eat the whole budget on a single attempt; now it can't eat more than 25s combined across both. Verified with a small local HTTP server standing in for the model endpoint (`LlmReviewProviderRetryTest`): one test proves a 500-then-200 sequence actually retries and succeeds, one proves a 401 never gets a second attempt, one proves two 503s in a row still fail cleanly.

**Rate limiting is now two layers instead of one.** Previously there was a single global token bucket for the whole service. Now each caller (identified by their bearer token — in this single-tenant deployment, that's still just one bucket in practice, since there's only one valid token) gets its own bucket at the declared 30/min, and there's a separate, much larger overall bucket (120/min, an internal guardrail — not part of `/spec`'s declared shape, which only has one `rateLimitPerMinute` field) shared across everyone. A request has to clear both. This doesn't change grading behavior at all today — one token means one caller means the exact same 30-request burst as before — but it's the shape you'd actually want if this were ever running behind more than one API key, and it was a small enough change to do properly now rather than bolt on later.

**`maxFindings: 0` is now accepted, not rejected.** The spec states a default (100) but never a minimum. The original validation invented an `>= 1` floor that the contract doesn't actually ask for. Zero is a perfectly coherent instruction — "run the full scan, but report none of it" — and now it's treated that way: `202`, the scan still runs in full, `usage` stays accurate, `findings` comes back empty. Only a genuinely negative number is rejected now.

None of this changes any documented behavior a grader would be checking — `/spec` still reports the same numbers, the 30/min sustained rate still works exactly the same for a single token, and every existing test kept passing without modification except the one that pinned the old (now-wrong) `maxFindings: 0 → 400` behavior, which got rewritten to assert the new, correct one. Full suite: 184 tests, run clean twice, plus 6 more NonDex passes (1 clean + 5 shuffled seeds) with zero failures.

### 6.24 A fresh spec-vs-implementation audit — five real bugs, and the method that found them

Everything above was verified. This pass asked a different question: not "does each requirement have a test" but **"which requirements are equalities between two paths, and where does each path get its inputs from?"** — plus the blunter "what request shapes have I simply never sent?"

Both framings paid. The five bugs below (§6.25–§6.28, plus the MOCK-004 one folded into §6.25's section) were found in roughly forty minutes of probing, and **not one of them was caught by the 184 existing tests**, because every existing test used the default `Accept: */*`, well-formed git-style diffs, and hunks whose braces balance.

The specific reasoning that produced each:
- *"Every existing test sends the same headers. Which header have I never varied?"* → `Accept` → §6.27, the worst of the five.
- *"Which characters can a diff's own content produce that look like structural markers?"* → an added line starting `++ ` renders as `+++ `; a removed line starting `-- ` renders as `--- ` → §6.25.
- *"Which rule's answer depends on lines other than the one it's examining?"* → only MOCK-004 → and chunking splits the line list → §6.26.
- *"The 422 gate is 'no hunks anywhere'. Is that gate applied anywhere it shouldn't be?"* → yes, per chunk → §6.28.

Verification for all five was done the same way: write a throwaway probe that prints the actual behaviour (raw sockets for the HTTP ones, direct component calls for the parser/chunker ones), confirm the bug empirically **before** writing any fix, fix, re-run the probe, then convert the probe into a permanent regression test and delete the scratch. Every claim below is something that was observed, not inferred.

### 6.25 Bug found & fixed — `+++`/`---` content lines inside a hunk were misparsed as file headers
**In short:** a diff that *adds* a line whose own text starts with `++ ` (or removes one starting with `-- `) produces a raw line that looks exactly like a file header. The parser believed it — corrupting the file path for the rest of that file, swallowing a real added line, and shifting every line number after it. The same ambiguity in the chunker silently dropped findings.

**The collision.** A file header is `+++ b/path`. An added line whose content starts with `++ ` is *also* rendered `+++ ...`, because the `+` marker is prepended to content that already begins with `++`. Observed directly:

```
input:  @@ -1,0 +1,3 @@ / "+++ this is content, not a header" / "+eval(danger)" / "+console.log(x)"

before: ADDED line=1 path=[this is content, not a header] content=[eval(danger)]
        ADDED line=2 path=[this is content, not a header] content=[console.log(x)]
after:  ADDED line=1 path=[notes.md] content=[++ this is content, not a header]
        ADDED line=2 path=[notes.md] content=[eval(danger)]
        ADDED line=3 path=[notes.md] content=[console.log(x)]
```

Three separate defects in one: a garbage `path` on every finding for the rest of that file, a real added line never scanned at all, and every line number after it off by one. Not hypothetical — adding a `.patch`/`.diff` file to a repo, or a Markdown doc containing a diff snippet, does exactly this. This repo's own test sources do it.

**The mirror image, in the chunker, is worse.** For non-git diffs `--- ` is the only file-boundary marker, and a *removed* line whose content starts with `-- ` renders as `--- ...`. Splitting there cuts a file's hunk in half; the second half reaches the parser with no `+++` above it, `currentPath` stays null, and every finding in it is **silently lost** — directly violating the scored "no duplicates, no losses, ordering preserved."

**Fix — one invariant, applied in both components.** In a real unified diff the two markers *always appear as an adjacent pair*, and a marker pair never appears mid-hunk-body. So:
- `--- X` is an old-file marker only when we're between hunks **or** its `+++` partner is on the very next line.
- `+++ X` is a new-file marker only when its `---` partner just went by **or** we're between hunks.
- Otherwise both are ordinary content, classified by their leading marker character like any other hunk line.

"Between hunks" is tracked with an `inHunk` flag: set at a `@@` header (unambiguous — content lines always carry a +/-/space marker, so nothing inside a hunk can start `@@ -`), cleared by any line not shaped like a hunk line (`diff --git`, `index`, `new file mode`, `Binary files ... differ`), with `\ No newline at end of file` explicitly excluded from clearing it since it appears *inside* a hunk. The `!inHunk` half of each condition is what preserves the pre-existing lenient behaviour for a hand-written diff that omits the `---` line entirely — that case is pinned by its own test.

The chunker uses the same pairing test for its boundary check (`isFileBoundary`), so both components now agree on what a file header is. Git-style diffs were never ambiguous (`diff --git ` can't be imitated by a marked content line) and are unaffected.

Pinned by `DiffParserTest#addedLineStartingWithPlusPlusIsContentNotAFileHeader`, `#removedLineStartingWithDashDashIsContentNotAFileHeader`, `#plainDiffFileHeadersStillRecognizedBetweenFiles`, `#newFileHeaderWithoutAnOldFileMarkerStillSetsThePath`, and `ChunkerTest#spoofedOldFileMarkerDoesNotSplitAFile` / `#realOldFileMarkerStillSplitsAPlainDiff`.

### 6.26 Bug found & fixed — MOCK-004's brace scan ran past the end of its own file, making chunked ≠ unchunked
**In short:** an added `catch (e) {` whose closing brace isn't inside the hunk left the brace scan open-ended, and it kept walking into the *next file's* lines. The same diff then produced a finding when both files shared a chunk and no finding when they didn't — a direct violation of "findings must be identical to an unchunked scan."

A hunk only shows a few lines of context, so a catch block whose closing `}` falls outside the hunk is completely ordinary. The scan had no file-boundary stop, so it borrowed whatever came next. Observed:

```
diff --git a/a.js b/a.js
@@ -10,1 +10,2 @@
 function f() {
+  try { risky(); } catch (e) {      <- opens; never closes within a.js

diff --git a/b.js b/b.js
@@ -1,1 +1,1 @@
+}                                    <- b.js's brace "closed" a.js's catch

before:  unchunked -> [MOCK-004:a.js:11]   chunked -> []   (disagreement)
after:   unchunked -> []                   chunked -> []   (identical)
```

**Fix:** the inner scan breaks when `next.path()` differs from the catch line's path. An unclosed block is then simply unclosed, identically regardless of chunking. Pinned by `MockReviewProviderTest#mock004_braceScanStopsAtTheFileBoundary`.

**Known remaining simplification (documented, not fixed):** the scan still doesn't stop at *hunk* boundaries within one file, so a catch opened in hunk 1 could in principle be closed by a brace in hunk 3. Unlike the file-boundary case this cannot diverge between chunked and unchunked — one file's hunks always travel together in the same chunk — and in practice the intervening hunk's real code makes the body non-empty. `DiffLine` carries no hunk identifier, so fixing it means a model change rippling through the parser and its tests, which isn't justified for a divergence that can't affect a scored property.

### 6.27 Bug found & fixed — an `Accept` header that excludes JSON turned every error envelope into a 500
**In short:** the most consequential bug in this pass. `GET /v1/reviews/{unknown}/stream` with `Accept: text/event-stream` — precisely what a correct SSE client sends — returned `500 internal` instead of `404 not_found`. So did *every* error envelope for *any* `Accept` header that excludes JSON, and on the success side `GET /health` with `Accept: application/xml` returned a `500` as well.

Observed, over a raw socket, before any fix:

```
GET /v1/reviews/nope/stream   Accept: text/event-stream  -> 500 {"error":{"code":"internal",...}}
GET /v1/reviews/nope          Accept: application/xml    -> 500 {"error":{"code":"internal",...}}
GET /v1/reviews/nope          Accept: text/plain         -> 500 {"error":{"code":"internal",...}}
GET /health                   Accept: application/xml    -> 500 {"error":{"code":"internal",...}}
GET /v1/reviews/nope          Accept: */*                -> 404  (correct — which is why no test caught it)
```

**Root cause, in two parts.** `ApiExceptionHandler` returned `ResponseEntity.status(...).body(envelope)` with no content type, leaving Spring to negotiate one from the `Accept` header. On `/stream` the handler method declares `produces = text/event-stream`, so the producible set for that request is event-stream only and a JSON envelope has no writable converter; the resulting `HttpMediaTypeNotAcceptableException` was then swallowed by the catch-all `@ExceptionHandler(Exception.class)` and rendered as `500 internal`. The `/health` case is the same mechanism on the 2xx side — a `Map` return value negotiated against an `Accept` that admits no JSON.

**Fix — one principle, three places.** This service speaks JSON by contract; it should *state* its content type, not negotiate it. Setting a concrete `Content-Type` on the `ResponseEntity` makes Spring skip Accept negotiation entirely (`writeWithMessageConverters` short-circuits when the content type is preset), so:
1. `ApiExceptionHandler.envelope(...)` now sets `MediaType.APPLICATION_JSON` — one line, and it fixes every error path at once.
2. `HealthController`, `SpecController`, and both `ReviewController` methods return `ResponseEntity` with an explicit JSON content type.
3. `StreamController` **dropped its `produces = text/event-stream` declaration**. Declaring it made `Accept` part of the *routing* decision, so a client asking for anything else stopped matching the handler at all — a 406 at mapping time, before any exception handler could help. `SseEmitter.extendResponse` sets `Content-Type: text/event-stream` on the response by itself when none is set, so the declaration bought nothing and cost a whole class of client. Verified the live stream still reports `Content-Type: text/event-stream`.

Note this is strictly better than adding an `@ExceptionHandler(HttpMediaTypeNotAcceptableException.class)` returning 406: the contract's error-code vocabulary is closed and has no reasonable code for 406, and a 406 on `/health` would still be a broken liveness probe. Not negotiating at all removes the failure mode rather than renaming it.

Pinned by `AdversarialEdgeCaseIntegrationTest#unknownJobStreamReturns404EnvelopeToAnSseClient`, `#errorEnvelopeIgnoresAnAcceptHeaderThatExcludesJson`, `#successResponsesAlsoIgnoreAnAcceptHeaderThatExcludesJson`, and `#streamStillServesAClientAskingForSomethingOtherThanEventStream`.

### 6.28 Bug found & fixed — a chunk containing no hunk failed the entire job
**In short:** the "no `@@` anywhere means this isn't a diff" rule (correct for the whole submitted diff — it's the 422 gate) was also being applied to each individual chunk, where it's wrong. A large diff whose trailing file is a pure rename died as `failed: internal error`.

`JobService.doScan` re-parses each chunk with the same strict parser used for the 422 gate. But a chunk can legitimately contain no hunk at all: a pure rename entry, a mode change, a `Binary files ... differ` line. Observed with a >64 KiB diff whose last file is a rename:

```
before:  PROBE-CHUNK[0] parsed ok, lines=3000
         PROBE-CHUNK[1] THREW InvalidDiffException: diff contains no hunks   -> job failed: internal error
after:   PROBE-CHUNK[1] parsed ok, lines=0
```

**Fix:** `DiffParser.parse(String, boolean requireHunk)`, with the existing one-argument `parse` delegating with `true`. The submit path keeps the strict gate (a hunkless *whole diff* is still a 422 — that decision is unchanged); `doScan` parses chunks leniently. Pinned by `DiffParserTest#lenientParseAcceptsAChunkWithNoHunks` (both overloads asserted) and `AdversarialEdgeCaseIntegrationTest#diffWhoseTrailingFileHasNoHunksStillCompletes` (end to end: `chunks >= 2`, `done`, correct findings).

### 6.29 A design change, not a bug — the result cache is now keyed on canonical content, not raw body bytes
**In short:** idempotency and result caching were both keyed on the SHA-256 of the raw request bytes. Idempotency still is, correctly. Caching now hashes the canonical `(provider, maxFindings, diff)` tuple instead.

The contract uses two different phrases, and they aren't parallel:
- Idempotency: *"same key + **byte-identical body** → the same jobId. Same key + **different body** → 409."*
- Caching: *"a **byte-identical `{diff, options}`** submitted again (any key or none) must not redo the work."*

The first is about a *body*; hashing raw bytes is literally right. The second is about `{diff, options}` — and hashing raw bytes there is a proxy that's needlessly strict, since JSON key order, indentation, and the explicitly-ignored unknown fields all change the bytes without changing the work. Both interpretations satisfy every case the contract names; the canonical one additionally satisfies cases the raw-byte one would miss.

An earlier pass had considered and *rejected* this, on the reasoning that "byte-identical appears in both clauses, so canonicalizing one would be inconsistent." That reasoning was wrong: the clauses say "body" and "{diff, options}" respectively, and treating two different phrases as if they were the same phrase was the actual inconsistency. Recorded here because reversing an earlier documented decision is worth being explicit about rather than quietly overwriting.

The encoding (`provider + " " + maxFindings + " " + diff`) is injective: a provider name never contains a space and the count is always plain digits, so the diff can only begin after the second space. Verified live — the same diff submitted as `{"diff":D}` and as pretty-printed `{"options":{"maxFindings":100,"provider":"mock"},"diff":D}` now reports `cacheHit: true` with identical findings; a fresh diff still reports `cacheHit: false`.

### 6.30 Hardening — the LLM prompt delimiter is now unguessable
**In short:** diff content was wrapped in a fixed `<diff>` tag. A diff containing a line `</diff>` would close the data section early, and everything after it would read to the model as instructions.

The system prompt already told the model the delimited content was data, never instructions — but a fixed delimiter is guessable, and a diff is attacker-controlled text. The wrapper now uses a per-call random suffix (`<diff-a3f9...>` … `</diff-a3f9...>`), named in the user message, with the system prompt stating that only that exact delimiter ends the data and any other tag inside it is more data. Content can still *say* anything; it can no longer get out of the quotes.

This affects only the `llm` path (not scored on finding quality), but "injection content must never alter your service's behavior" is a scored *property*, and a closable delimiter was a real hole in the strongest form of that claim.

There's an accidental live demonstration of inertness: feeding the service its own working-tree diff reports `MOCK-INJ` against `LlmReviewProvider.java`, because the anti-injection system prompt contains the phrase "ignore previous instructions" as an example. The rule fires; nothing else about the run changes.

### 6.31 Verified-not-a-bug — auth-filter path matching against URL-encoding and normalization tricks
`BearerAuthFilter` tests the raw `getRequestURI()` for `/v1` or a `/v1/` prefix, which invites the question of whether the check can be dodged by a path that Spring routes but the raw string doesn't match. Fired over raw sockets against the running server:

```
/v1/reviews/abc            -> 401   (baseline)
//v1/reviews/abc           -> 404
/v1//reviews/abc           -> 401
/%76%31/reviews/abc        -> 404
/v1/%72eviews/abc          -> 401
/./v1/reviews/abc          -> 404
/foo/../v1/reviews/abc     -> 404
/v1/reviews/abc;a=b        -> 401
```

No variant reaches a handler unauthenticated: Tomcat rejects or refuses to normalize these into a route match before Spring dispatches. **No change made.** Worth recording that this is a property of the container's URI handling rather than of the filter's string test — if the container or its normalization settings ever changed, this check would need to be revisited, and knowing it was verified empirically (not assumed) is the point.

---

## 7. Testing strategy

197 tests across 20 classes, 0 failures, 0 errors — verified with `./mvnw test` run to completion multiple times in a row (not flaky). Verification followed a staged methodology, each stage deliberately more adversarial than the last: implementation reviewed line-by-line against the contract, the full suite run to completion end to end (not just individual classes in isolation), a dedicated pass specifically hunting for unorthodox/crafted-diff edge cases, two separate live sweeps against the deployed service through the public URL — not localhost — each with a clean, grader-equivalent restart beforehand, a hardening pass (§6.23), and a final spec-vs-implementation audit (§6.24) that found five more real bugs. Test inputs were deliberately chosen to be awkward for a real server rather than a unit test: unicode/emoji content, connector-level malformed URIs, integer-overflow option values, HEAD/OPTIONS/trailing-slash routing, zero-finding diffs, duplicate JSON keys, chunked transfer encoding, headerless hunks, idempotency-key edge cases, non-JSON `Accept` headers, and diff content deliberately shaped to imitate structural diff markers. Each stage closed real issues before moving to the next — the full list is §6.10–§6.13, §6.15–§6.20, §6.22–§6.23, §6.25–§6.30, plus a handful of test-only fixes (fixtures that encoded incorrect assumptions about HTTP semantics or git diff quoting, and test isolation gaps where shared Spring context state leaked between classes).

**A note on what the audit in §6.24 says about the suite.** Five real bugs survived 184 passing tests. Every one of them lived in a dimension the suite held constant without ever deciding to: all tests sent `Accept: */*`, all diffs were well-formed, all hunks had balanced braces. The transferable lesson is that "every requirement has a test" is a weaker property than it sounds — a test exercises one point in a large input space, and coverage of *requirements* says nothing about coverage of *dimensions*. The four questions in §6.24 are the ones worth re-asking on any future pass.

- **Unit:** one crafted diff per mock rule with exact field assertions; parser line-number tests; chunker byte-boundary tests; sort/dedupe with adversarial ordering; MOCK-004 variants.
  - `services/DiffParserTest` (26) — §5 rows 12, 15, 18, 21b, 21c, §6.11, §6.25, §6.28.
  - `services/ChunkerTest` (11) — §5 rows 19, 20, 21, 21c, §6.10, §6.25.
  - `services/MockReviewProviderTest` (38) — §5 rows 13, 14, 16, 17, 18, 21d, §6.12, §6.15, §6.26.
  - `services/LlmReviewProviderTest` — §6.17.
  - `services/LlmReviewProviderRetryTest` — §6.23, against a local stand-in HTTP server: proves the transient-failure retry and the never-retry-a-4xx rule.
  - `services/JobServiceTest` — finalizeFindings dedupe/sort/truncate; §6.10 regression.
  - `repositories/IdempotencyStoreTest`, `repositories/ResultCacheTest`, `utils/HashingTest`, `models/JobTest`.
- **Integration** (`@SpringBootTest`, RANDOM_PORT, `HttpSupport` — a raw `HttpURLConnection`/`Socket` helper, not a higher-level REST client, since exact envelope/header/status assertions need full control): every row of §5.
  - `controllers/AuthRoutingIntegrationTest` — rows 1–5.
  - `controllers/PostValidationIntegrationTest` — rows 6–11, §6.13, §6.16. `@DirtiesContext(AFTER_CLASS)`.
  - `controllers/LifecycleIntegrationTest` — rows 22, 23, §6.10. `@DirtiesContext(AFTER_CLASS)`.
  - `controllers/IdempotencyCacheIntegrationTest` — rows 24–28. `@DirtiesContext(AFTER_CLASS)`.
  - `controllers/RateLimitConcurrencyIntegrationTest` — rows 29, 30, against the real fixed 30/min `AppLimits` constant. `@DirtiesContext(AFTER_EACH_TEST_METHOD)`.
  - `controllers/SseIntegrationTest` — rows 32–34. `@DirtiesContext(AFTER_CLASS)`.
  - `controllers/LlmIntegrationTest` — row 31.
  - `controllers/AdversarialEdgeCaseIntegrationTest` (9) — §6.22, plus §5 rows 5b, 21b, 35, 36 (§6.27, §6.28). `@DirtiesContext(AFTER_EACH_TEST_METHOD)`, its own rate-limit isolation marker (kept out of `PostValidationIntegrationTest`'s budget — see §6.22). The `@DirtiesContext` per *method* is what makes it safe to keep adding request-heavy cases here: each method starts on a full bucket.
  - Package reorg (config/domain/diff/provider/service/util/web → bootstrap/configuration/controllers/dto/exceptions/filters/models/repositories/services/utils, grouped by architectural role) landed after all of the above was verified; it moved files and fixed imports only — the row/section references above still describe the same tests under their new package paths.
- **Why `@DirtiesContext` matters**: `RateLimitFilter`'s `TokenBucket` is a Spring singleton, and `@SpringBootTest` caches contexts by configuration signature — several classes share the identical `properties = "app.bearer-token=tok"`, so without isolation Spring silently reuses one context (and one live bucket) across all of them. Combined POST volume can spuriously exceed 30/min and fail unrelated assertions.
- Tests never require real environment vars — set via `properties = ...` on the test annotation.
- **Flaky-test detection (NonDex)**: `edu.illinois:nondex-maven-plugin:2.2.1` is registered in `pom.xml` but not bound to any lifecycle phase, so `./mvnw test`/`package` are unaffected — it only runs on explicit invocation: `./mvnw nondex:nondex` (default 3 shuffled seeds) or `./mvnw nondex:nondex -DnondexRuns=N` for more. It reruns the whole suite with randomized `HashMap`/`HashSet` iteration order (JDK collections whose iteration order is unspecified by contract) to surface tests that accidentally depend on that order rather than on the documented `path → line → ruleId` sort or an explicit assertion. Re-run after the §6.24 audit at both settings again: once at the default 3 seeds, once at 10 — **11 total runs of the 197-test suite (2,167 individual test executions), 0 failures in any configuration**, `BUILD SUCCESS`. No hidden ordering assumptions found, so nothing needed fixing or documenting as "expected flaky." Re-running it after that audit mattered more than usual: `DiffParser` gained stateful flags carried across a loop (`inHunk`, `prevWasOldMarker`), which is exactly the kind of change where an order-dependent assumption would hide. `.nondex/` (its scratch output directory) is gitignored.
---

## 8. Deployment — Docker (D: drive) + ngrok static domain

Switched from an initially-planned named Cloudflare Tunnel to ngrok once it became clear no Cloudflare-managed domain was available for a stable hostname — ngrok's free tier includes one permanently-assigned "dev domain" per account, which resolves the same stability requirement at zero cost. ngrok is named explicitly in `CANDIDATE-TASK.md`'s own ground rules as an acceptable deployment option, alongside cloudflared.

The service runs containerized rather than as a bare jar: `docker run --restart unless-stopped` doubles as the process-supervision mechanism for the scoring window, so no separate Task Scheduler/NSSM/restart-loop script is needed, and it's a more portable, production-representative deployment shape.

1. **Docker Desktop installed to `D:\Docker`** (not `C:`, which only had 24.4 GB free vs `D:`'s 105.8 GB). Silent install requires `--quiet` — without it the installer tries to show a GUI and fails fast (exit code `-5`, reproducible until `--quiet` was added):
   ```
   DockerDesktopInstaller.exe install --quiet --accept-license `
     --installation-dir=D:\Docker `
     --wsl-default-data-root=D:\Docker\wsl-data `
     --windows-containers-default-data-root=D:\Docker\windows-data
   ```
   Verified image/container storage lives on `D:` via the actual `.vhdx` files. One unavoidable fixed cost stays on `C:` regardless: the `docker` CLI + `cli-plugins` always install to `C:\Program Files\Docker\cli-plugins` (~650 MB) — hardcoded Docker Desktop behavior.
   - `com.docker.service` set to `Automatic` startup.
   - **`AutoStart` in `%APPDATA%\Docker\settings-store.json` defaulted to `false`** — the Windows service alone doesn't bring up the WSL2 VM/engine; the GUI app has to launch at login too. Flipped to `true`, verified the engine/container come back after a full `docker desktop restart`.
2. **Dockerfile** (repo root) — standard multi-stage Maven → JRE build, Java 21:
   ```dockerfile
   FROM maven:3.9-eclipse-temurin-21 AS build
   WORKDIR /app
   COPY pom.xml .
   RUN mvn -q dependency:go-offline
   COPY src ./src
   RUN mvn -q package -DskipTests

   FROM eclipse-temurin:21-jre-alpine
   WORKDIR /app
   COPY --from=build /app/target/ai-diff-reviewer-1.0.0.jar app.jar
   EXPOSE 8080
   ENTRYPOINT ["java", "-jar", "app.jar"]
   ```
   `.dockerignore` excludes `target/`, `.git/`, `.env`, `.mvn/`, `specs/` — secrets never baked into the image.
3. **Run**: `docker build -t ai-diff-reviewer:1.0.0 .` then
   ```
   docker run -d --name ai-diff-reviewer --restart unless-stopped \
     --env-file .env -p 8080:8080 ai-diff-reviewer:1.0.0
   ```
   `--restart unless-stopped` = the actual persistence mechanism for the scoring window (`CANDIDATE-TASK.md` says 48h; the actual assessment email for this application specifies **96h** — the longer figure is what the deployment plan targets).
4. **Tunnel: ngrok.** The account already had a **permanent free "dev domain"** auto-assigned (`https://armless-dispersed-spinout.ngrok-free.dev` — dashboard confirms "This dev domain is yours forever"), zero cost, zero domain purchase. Config lives at `C:\ProgramData\ngrok\ngrok.yml` — **not** `%LOCALAPPDATA%\ngrok\ngrok.yml`, which only resolves for the interactive user via MSIX package virtualization; the Windows *service* runs as `LocalSystem` and crashes on start with no useful log if pointed there:
   ```yaml
   version: "3"
   agent:
       authtoken: <redacted, already configured>
   endpoints:
       - name: ai-diff-reviewer
         url: https://armless-dispersed-spinout.ngrok-free.dev
         traffic_policy:
           on_http_request:
             - actions:
                 - type: remove-headers
                   config:
                     headers:
                       - Expect
         upstream:
           url: 8080
   ```
   Installed and started as a Windows service: `ngrok service install --config="C:\ProgramData\ngrok\ngrok.yml"` → `Start-Service ngrok` (StartType `Automatic`).
   - **Known quirk**: restarting the ngrok service shortly after it was already running can hit `ERR_NGROK_334` ("endpoint already online") for ~10-15s — the previous session hasn't been released cloud-side yet. Self-resolves; retry after a short wait.
   - The `traffic_policy` block exists specifically to close §6.18 — see there for the full story.
5. **Verified**: `/health`, `/spec`, full POST→poll→SSE cycle, idempotency, caching, rate limiting, error taxonomy, and both LLM provider modes — all correct against `localhost:8080` and through the public ngrok URL. Stable across an ngrok service restart and a full `docker desktop restart`. Remaining: one check of the public URL from an external network (e.g. a phone hotspot) as a final sanity check before the scoring window opens.
6. Power settings reviewed: AC sleep already disabled on this machine's active power plan, hibernation unavailable — no changes needed. Worth pausing Windows Update reboots manually right before the scoring window starts.

---

## 9. Deliverables checklist (beyond the code)

- ✅ **README.md** — setup, every env var documented, how to run/test, how the llm provider is configured.
- ✅ **SUBMISSION.md** — written, matches the task's required contents (architecture, provider design, verification, AI tools used, rejected suggestions, future work) — gitignored and committed separately from the main codebase history.
- ✅ **Dockerfile** — multi-stage Maven → JRE, verified building and running for real — the actual deployment path, not a documented fallback.
- ✅ **`.env.example`** — all vars, blank values. `.env` stays gitignored.
- ✅ Bearer token generated and configured. Rotated once during live verification after a verbose debug command incidentally echoed it to a local terminal — standard incident response followed: rotated immediately, redeployed, old token confirmed rejected.
- ✅ LLM provider credentials configured (Groq) and verified end to end in both failure and success modes.
- ✅ git init / remote / push — pushed to `github.com/Mayhemy/XsollaTask`, branch `main`.
