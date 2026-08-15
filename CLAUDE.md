# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository. Keep it small — this is loaded into every session automatically, unlike `STATUS.md`. Detailed build history, the full bug log, test-coverage mapping, and the deployment runbook live there instead; read it on demand when actually relevant, not by default.

> **CRITICAL INSTRUCTION FOR TOOL CALLING:** You MUST use exact PascalCase for all tool names (e.g., `Glob`, `Read`, `Bash`, `Edit`, `Grep`, `Write`). NEVER use lowercase tool names.

## What this project is

An **AI diff review service** (take-home task, scored by automated probes against the running service): clients `POST /v1/reviews` with a unified diff, the service analyzes it **asynchronously** through a pluggable provider, and returns structured findings via polling (`GET /v1/reviews/{jobId}`) and SSE (`GET /v1/reviews/{jobId}/stream`).

**Spec file roles — treat them differently:**
- **`specs/CANDIDATE-TASK.md` = the requirements.** The scored contract. Where it and the guide disagree, the task wins.
- **`specs/ai-diff-review-service-guide.md` = a suggested implementation.** Mostly correct in shape, but has concrete bugs and questionable interpretations (see `STATUS.md` §Known bugs). Don't follow it blindly.

Fully implemented, tested, and deployed — see `STATUS.md` for the detailed status, bug history, and deployment runbook.

## Commands (Windows — `mvnw.cmd` in cmd/PowerShell; `./mvnw` in Git Bash)

```bash
./mvnw spring-boot:run                    # run the service (port 8080)
./mvnw test                               # all tests
./mvnw test -Dtest=ClassName              # one test class
./mvnw test -Dtest=ClassName#methodName   # one test method
./mvnw package                            # build jar (target/ai-diff-reviewer-<v>.jar)
```

Base package: `com.fedjafilipovic.ai_diff_reviewer` (underscores — the dashed name is an invalid Java package; see HELP.md).

## Configuration & secrets

All secrets/config come from a **`.env` file** (`KEY=value`, gitignored) at the project root, imported via `spring.config.import=optional:file:./.env[.properties]`. Commit `.env.example` (blank values) instead.

| Var | Required | Notes |
|---|---|---|
| `APP_BEARER_TOKEN` | **Yes** | fails startup fast if blank — the only startup-fatal misconfiguration |
| `LLM_BASE_URL` / `LLM_API_KEY` / `LLM_MODEL` | No | blank → `llm` provider still returns 202, then fails the job gracefully with `"llm provider not configured"`. Never throws at startup, never 5xxs. |
| `LLM_TIMEOUT_SECONDS` | No | default `20`, must stay well under the 30s job budget |

## Architecture in one paragraph

POST validates and parses **synchronously** (auth → rate limit → size → JSON → field validation → diff parse+chunk) — 4xx are request-level errors and must never create a job. Only rule-matching / the LLM call runs async, on a **bounded 4-thread executor with an unbounded queue** (5th+ concurrent job waits, never rejected). Jobs live in an in-memory `ConcurrentHashMap`; each `Job` holds an **append-only SSE event log** plus a live-subscriber list so `/stream` replays finished jobs identically. **Idempotency-Key and body-hash result caching are two separate mechanisms** — don't conflate them. The mock provider is pure line-scan/regex logic; the LLM provider is one HTTPS call behind the same interface, and any failure becomes a `failed` job, never a crash.

```
config/    AppLimits (single source of truth for every limit), ExecutorConfig, AppProperties
web/       HealthController, SpecController, ReviewController, StreamController,
           BearerAuthFilter, RateLimitFilter, ApiExceptionHandler, EnvelopeErrorController
domain/    Finding, Job, JobStatus, Usage, ReviewOptions, SseEventRecord, DiffLine, LineType
diff/      DiffParser, Chunker, InvalidDiffException
provider/  ReviewProvider, MockReviewProvider, LlmReviewProvider, ProviderException
service/   JobService, JobStore, IdempotencyStore, ResultCache
util/      Hashing
```

## Conventions worth knowing before touching this code

- **`AppLimits`** is the single source of truth for every declared limit — `/spec`, the payload guard, the rate limiter, the chunker, and the executor pool all read from it. Never hardcode a limit twice; the grader cross-checks `/spec` against actual enforced behavior.
- **`DiffParser`** resets its new-file line counter from *each hunk's own* `@@` header, never a running total across the diff — this is what makes chunking safe (splitting between files can never shift a line number). A blank context line with **no** leading space is still meaningful (treated as `CONTEXT`, advances the counter) — this was a real bug, not a hypothetical; see `STATUS.md` §6.11 before touching this logic.
- **Mock rules apply to ADDED lines only** (MOCK-004 also reads `CONTEXT` lines, for brace matching). Quick reference — full rationale for each in `STATUS.md` §4 Step 6:

  | Rule | Trigger | Note |
  |---|---|---|
  | MOCK-001 eval usage | `contains("eval(")` | literal — `myeval(` fires on purpose |
  | MOCK-002 hardcoded credential | brief's exact regex, case-insensitive | used verbatim, no "improvements" |
  | MOCK-003 SQL string concat | regex with `\b` word boundary | **case-sensitive** — deliberate, deviates from the guide |
  | MOCK-004 swallowed exception | multi-line brace scan | `\b` before `catch`; body lines joined with real `\n` (both were real bugs, see §6.12/§6.15) |
  | MOCK-005 loose null comparison | `(?<![=!])(==\|!=) null` | excludes `===`/`!==` via lookbehind |
  | MOCK-006 deep-clone via JSON | literal contains | case-sensitive |
  | MOCK-007 console.log left in | literal contains | — |
  | MOCK-008 unresolved marker | literal contains | case-sensitive |
  | MOCK-INJ prompt injection | case-insensitive contains | content must stay inert everywhere, including inside the LLM prompt |

- **Idempotency-Key and result caching are independent mechanisms.** Same key → same job identity, conflict if body differs. Body hash → same computed result, regardless of key (or no key at all). Don't merge them into one map.
- **Integration test classes sharing an identical `@SpringBootTest(properties = ...)` value share one cached Spring context** — and therefore one singleton `RateLimitFilter` bucket. A new test class under `properties = "app.bearer-token=tok"` needs `@DirtiesContext` (see any existing integration test's class javadoc for the pattern) or its own unique marker property, or it will silently drain/be-drained-by sibling test classes.
- **`SUBMISSION.md` and `NOTES-my-approach.md` are intentionally gitignored** — held out of the repo until the user commits them deliberately.

## Deployment

Runs in Docker (`D:\Docker`, image `ai-diff-reviewer:1.0.0`, `--restart unless-stopped`), tunneled via ngrok's permanent free dev domain (`armless-dispersed-spinout.ngrok-free.dev`). ngrok config lives at `C:\ProgramData\ngrok\ngrok.yml` — **not** the per-user path (the Windows service runs as `LocalSystem` and can't see per-user MSIX-virtualized paths) — and carries a Traffic Policy stripping the `Expect` request header to close a proxy-layer status-code bug (`STATUS.md` §6.18). Full runbook, gotchas, and verification steps in `STATUS.md` §8.
