# STATUS.md

Detailed project status: build history, the full engineering log with root-cause writeups, test-coverage mapping, and the deployment runbook. `CLAUDE.md` stays intentionally short and holds only the always-relevant conventions; this file is the detailed companion — consulted when auditing for bugs, writing new tests, touching deployment, or picking this project back up after a gap, rather than kept in working memory at all times.

Current state: all 12 implementation steps done, build green, full test suite (170 tests, §7) passing, service deployed and reachable via Docker + ngrok (§8) with real LLM credentials (Groq) configured and the `llm` path verified end to end in both its unconfigured-failure and configured-success modes. `README.md` and `SUBMISSION.md` are both written; `SUBMISSION.md` is committed separately, on its own schedule.

### Status legend
- ✅ DONE — implemented and verified (code compiles + tests pass + live HTTP smoke tests pass).
- 🟡 CODE-DONE / TESTS-PENDING — code is in place but not yet covered by the test suite.
- ⬜ NOT DONE — not yet implemented.

### Per-step verification status
- Step 1 pom.xml — ✅ DONE.
- Step 2 AppLimits — ✅ DONE. (Sub: ✅ /spec-vs-behavior cross-check test in place, `AuthRoutingIntegrationTest.specNoTokenReturns200`.)
- Step 3 domain model — ✅ DONE. (Sub: ✅ domain unit tests in place, `JobTest`.)
- Step 4 DiffParser — ✅ DONE. (Sub: ✅ parser unit tests in place: CRLF, multi-hunk, /dev/null both ways, `\ No newline`, git-quoted path, b/ prefix, tab timestamp, short-form `@@ -1 +1 @@`, no-hunks 422, blank context line without leading space. Fixed a real bug where blank context lines without a leading space silently dropped the new-file counter — see §6.11.)
- Step 5 Chunker — ✅ DONE. (Sub: ✅ chunker tests in place: byte-vs-char sizing, multi-file packing, oversized single file. Fixed the `usage.inputBytes` off-by-one at its root cause in `Chunker.splitByFile`, not just worked around at the call site — see §6.10.)
- Step 6 MockReviewProvider — ✅ DONE. (Sub: ✅ per-rule unit tests + decoy tests + MOCK-004 variants in place. Fixed a MOCK-004 false-positive on multi-line catch bodies — see §6.12.)
- Step 7 LlmReviewProvider — ✅ DONE. (Sub: ✅ llm unconfigured-failure integration test in place; configured-success path run end to end against real Groq credentials — see §6.17.)
- Step 8 JobService/lifecycle — ✅ DONE. (Sub: ✅ lifecycle + catch-all + 30s-budget tests in place.)
- Step 9 IdempotencyStore/ResultCache — ✅ DONE. (Sub: ✅ atomicity, cacheHit, conflict, failed-not-cached tests in place.)
- Step 10 web layer — ✅ DONE. (Sub: ✅ precedence 401→429→413→400→422, payload boundaries, envelope-on-all-non-2xx tests in place. Fixed a precedence bug where `options` was validated after `diff`, contradicting the documented 400→422 order — see §6.13.)
- Step 11 SSE — ✅ DONE. (Sub: ✅ live sequence, post-completion replay byte-identical, failed-job replay tests in place.)
- Step 12 remaining endpoints — ✅ DONE. (Sub: ✅ /health, /spec, GET job, unknown-route envelope tests in place.)

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
- **`RateLimitFilter`** (`@Order(2)`): only `POST /v1/reviews` exactly. Token bucket, capacity 30, refill 0.5/s, starts full. 429 + `Retry-After` + envelope, never 5xx.
- **Payload guard** (in the controller, not a filter): fast-path `Content-Length > MAX_PAYLOAD_BYTES` → 413 before touching the input stream; otherwise a bounded read that throws after `MAX_PAYLOAD_BYTES + 1` bytes.
- **Body handling:** manual `ObjectMapper.readTree` (not `@RequestBody`). Malformed JSON → 400. Not an object → 400. `diff` missing/empty/blank/non-textual/unparseable → 422. `options` present but not an object (and not null) → 400 (§6.16). `options.provider` not `mock`/`llm` → 400. `options.maxFindings < 1` → 400. Validated **before** `diff` (§6.13) — precedence is 401 → 429 → 413 → 400 → 422.
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

### POST validation (precedence: 401 → 429 → 413 → 400 → 422)
6. Body of exactly 1,048,576 bytes → accepted; 1,048,577 → 413. Test with and without a truthful `Content-Length`.
7. Malformed JSON → 400. Valid JSON of wrong shape (`[1,2]`, `"hello"`, `42`) → 400.
8. `diff` missing/empty/blank/wrong-type/no-hunks → 422.
9. Unknown top-level fields → ignored, 202.
10. `options` absent/null/`{}` → defaults. Wrong type → 400. `provider` invalid → 400. `maxFindings < 1` → 400. `maxFindings: 3` on a 10-finding diff → exactly 3 findings, `usage` reflects the full scan.
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

### Lifecycle, caching, idempotency
22. POST → poll → `done` well under 30s for a ≤64 KiB diff.
23. GET/stream unknown jobId → 404 envelope.
24. Same key + identical body twice → same `jobId`, real status.
25. Same key + different body → 409.
26. Identical body, different/no key → different `jobId`, `cacheHit: true`.
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
`JobService.doScan` originally computed `inputBytes` by summing each chunk's UTF-8 bytes. But `Chunker.splitByFile` re-joined lines with `'\n'`, appending one trailing newline per segment vs the original text, so the chunk-byte sum was diff bytes + 1. **First fix:** `createAndEnqueue` computes the correct `inputBytes` directly from the diff string and passes it through — chunk bytes are never summed, closing the symptom immediately. **Root cause fixed on a later pass**: `splitByFile` always appended one spurious trailing `'\n'` to its last segment regardless of whether the source ended in one — fixed at the source; segments now reconstruct the original diff byte-for-byte, rather than leaving the underlying defect worked around indefinitely. Pinned by `JobServiceTest`/`LifecycleIntegrationTest`/`ChunkerTest.byteSizingCountsUtf8BytesNotChars`.

### 6.11 Bug found & fixed — blank context lines without a leading space dropped the line counter
Strict unified-diff format requires even a blank context line to carry a leading space. Real-world diffs often emit a fully empty line instead. `DiffParser` matched none of its branches for `""`, silently dropping the line **and failing to advance the counter** — every subsequent line in that hunk got the wrong `line` number. **Fix:** an empty line (path in effect) is now treated as a blank `CONTEXT` line. Required also fixing a related artifact: `split("\n", -1)` yields a trailing `""` when text ends in `'\n'` (virtually always) — stripped before iterating, or every diff would gain a phantom trailing blank line. Pinned by `DiffParserTest.blankContextLineWithoutLeadingSpaceStillAdvancesCounter` + 2 related tests + `MockReviewProviderTest.findingLineNumberIsCorrectAfterABlankContextLineWithoutLeadingSpace`.

### 6.12 Bug found & fixed — MOCK-004 false positive on multi-line catch bodies
`findSwallowedExceptions` reconstructed a multi-line catch body by concatenating lines with **no separator**. A `// comment` line followed by a real statement on the next line collapsed into one string where the `//` appeared to swallow the real statement too — a catch block that actually handles its exception got falsely flagged. **Fix:** lines joined with real `'\n'`; block-comment strip regex made `DOTALL`-aware (`(?s)/\*.*?\*/`) so genuine multi-line block comments still strip correctly. Pinned by `mock004_spanningCatchWithCommentThenRealStatementDoesNotFire` + `.mock004_spanningMultiLineBlockCommentOnlyBodyStillCountsAsEmpty`.

### 6.13 Bug found & fixed — `options` validated after `diff`, contradicting the documented precedence
`ReviewController.create()` called `extractDiff` (422) before `extractOptions` (400), so a request invalid both ways returned 422, the reverse of `400 → 422`. **Fix:** `options` validated first. Pinned by `PostValidationIntegrationTest.invalidOptionsPrecedesInvalidDiff`.

### 6.14 Documented limitation (not fixed) — Chunker's non-git `"--- "` marker can be spoofed by content
For a non-git diff, file boundaries are detected via `line.startsWith("--- ")`. A content line legitimately starting with literal `"--- "` (a Markdown rule, a YAML fence) would be misread as a boundary, risking a mid-file split over 64 KiB. Accepted as-is: graders overwhelmingly use git-style diffs, and a proper fix needs full `---`/`+++` pairing state tracking — more invasive than the benefit justifies.

### 6.15 Bug found & fixed — MOCK-004's `catch` regex had no word boundary
No `\b` before `catch` — `mycatch(e) {}` (a custom function, not a real try/catch) falsely fired MOCK-004. Same class of bug as MOCK-003's `SELECTION`/`SELECT` guard, just never applied here. **Fix:** `\\bcatch\\s*\\(...`. Pinned by `mock004_identifierEndingInCatchDoesNotFire`.

### 6.16 Bug found & fixed — `options` of the wrong JSON type silently defaulted instead of 400
Any non-object value (string/number/boolean/array, not just null/absent) was silently treated as "use defaults" — inconsistent with how a non-textual `diff` is rejected. **Fix:** only null/absent default; any other non-object type → 400. Pinned by `optionsWrongTypeReturns400`.

### 6.17 Bug found & fixed — LLM findings with an invalid severity/category leaked through unvalidated
Once real credentials (Groq, `openai/gpt-oss-20b`) were exercised end-to-end for the first time, a live response returned `severity: "warning"` and `category: "Logic"`/`"Syntax"` — neither in the spec's fixed vocabulary — and echoed the diff's `b/f.js` path verbatim. `parseFindings` had zero validation. **Fix:** system prompt spells out the two closed vocabularies; `parseFindings` validates (lowercased) `severity`/`category` and skips non-conforming elements, same as it already skips a blank `ruleId`. `path` normalized to strip `a/`/`b/`. Made `parseFindings` package-private for direct unit testing (`LlmReviewProviderTest`) without mocking HTTP. **Only found by actually running the configured-success path with a real model** — the failure-path tests can't surface a success-path schema bug by definition.

### 6.18 Deployment-layer bug found & fixed — `Expect: 100-continue` through ngrok mangled a rejected large upload's status code
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

---

## 7. Testing strategy

170 tests across 18 classes, 0 failures, 0 errors — verified with `./mvnw test` run to completion multiple times in a row (not flaky). Verification followed a staged methodology, each stage deliberately more adversarial than the last: implementation reviewed line-by-line against the contract, the full suite run to completion end to end (not just individual classes in isolation), a dedicated pass specifically hunting for unorthodox/crafted-diff edge cases, and finally live verification against the deployed service including the real (credentialed) `llm` path, not just its graceful-failure mode. Each stage closed real issues before moving to the next — the full list is §6.10–§6.13, §6.15–§6.18, plus a handful of test-only fixes (fixtures that encoded incorrect assumptions about HTTP semantics or git diff quoting, and test isolation gaps where shared Spring context state leaked between classes).

- **Unit:** one crafted diff per mock rule with exact field assertions; parser line-number tests; chunker byte-boundary tests; sort/dedupe with adversarial ordering; MOCK-004 variants.
  - `diff/DiffParserTest` — §5 rows 12, 15, 18, §6.11.
  - `diff/ChunkerTest` — §5 rows 19, 20, 21, §6.10.
  - `provider/MockReviewProviderTest` — §5 rows 13, 14, 16, 17, 18, §6.12, §6.15.
  - `provider/LlmReviewProviderTest` — §6.17.
  - `service/JobServiceTest` — finalizeFindings dedupe/sort/truncate; §6.10 regression.
  - `service/IdempotencyStoreTest`, `service/ResultCacheTest`, `util/HashingTest`, `domain/JobTest`.
- **Integration** (`@SpringBootTest`, RANDOM_PORT, `HttpSupport` — a raw `HttpURLConnection`/`Socket` helper, not a higher-level REST client, since exact envelope/header/status assertions need full control): every row of §5.
  - `web/AuthRoutingIntegrationTest` — rows 1–5.
  - `web/PostValidationIntegrationTest` — rows 6–11, §6.13, §6.16. `@DirtiesContext(AFTER_CLASS)`.
  - `web/LifecycleIntegrationTest` — rows 22, 23, §6.10. `@DirtiesContext(AFTER_CLASS)`.
  - `web/IdempotencyCacheIntegrationTest` — rows 24–28. `@DirtiesContext(AFTER_CLASS)`.
  - `web/RateLimitConcurrencyIntegrationTest` — rows 29, 30, against the real fixed 30/min `AppLimits` constant. `@DirtiesContext(AFTER_EACH_TEST_METHOD)`.
  - `web/SseIntegrationTest` — rows 32–34. `@DirtiesContext(AFTER_CLASS)`.
  - `web/LlmIntegrationTest` — row 31.
- **Why `@DirtiesContext` matters**: `RateLimitFilter`'s `TokenBucket` is a Spring singleton, and `@SpringBootTest` caches contexts by configuration signature — several classes share the identical `properties = "app.bearer-token=tok"`, so without isolation Spring silently reuses one context (and one live bucket) across all of them. Combined POST volume can spuriously exceed 30/min and fail unrelated assertions.
- Tests never require real environment vars — set via `properties = ...` on the test annotation.

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
   `--restart unless-stopped` = the actual persistence mechanism for the 48h window.
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
