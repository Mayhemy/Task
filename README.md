# XsollaTask — AI Diff Review Service

An HTTP service that reviews unified diffs asynchronously. A client submits
a diff, gets back a `jobId` immediately, and polls (or streams over SSE) for
structured findings as the review completes in the background.

Built with Spring Boot 4 (Java 21, Maven). Findings come from a pluggable
provider: a deterministic `mock` engine (regex/pattern rules — no external
dependency, fully reproducible) or a real `llm` provider behind the same
pipeline.

## Contents

- [Quick start](#quick-start)
- [Configuration](#configuration)
- [API](#api)
- [Running with Docker](#running-with-docker)
- [Running the tests](#running-the-tests)
- [Project layout](#project-layout)

## Quick start

Requires Java 21 and Maven (or use the bundled wrapper — no local Maven
install needed).

```bash
cp .env.example .env
# edit .env: at minimum set APP_BEARER_TOKEN (see Configuration below)

./mvnw spring-boot:run
```

The service listens on `:8080`.

```bash
curl http://localhost:8080/health
# {"status":"ok","version":"1.0.0","uptimeSeconds":3}

curl http://localhost:8080/spec
# {"specVersion":"1.0","providers":["mock","llm"],"limits":{...}}
```

## Configuration

All configuration comes from environment variables, loaded from a `.env`
file at the project root (gitignored — never commit real secrets; commit
`.env.example` instead, which documents every variable with a blank value).

| Variable | Required | Purpose |
|---|---|---|
| `APP_BEARER_TOKEN` | **Yes** | Bearer token required on every `/v1/**` route. The service fails to start if this is blank. Generate one with `openssl rand -hex 32`. |
| `LLM_BASE_URL` | No | Base URL of an OpenAI-compatible chat-completions API (e.g. `https://api.groq.com/openai/v1`). Leave blank to disable the `llm` provider — requests with `options.provider: "llm"` are still accepted (`202`), but the job transitions to `failed` with a clear `"llm provider not configured"` message instead of crashing. |
| `LLM_API_KEY` | No | API key for the above. Lives only on this server — never sent to or requested by a client. |
| `LLM_MODEL` | No | Model name passed to the chat-completions endpoint. |
| `LLM_TIMEOUT_SECONDS` | No | Per-request timeout for the model call. Default `20` — kept comfortably under the service's 30-second job budget. |

## API

Every route under `/v1/**` (including `GET`) requires
`Authorization: Bearer <APP_BEARER_TOKEN>`. `/health` and `/spec` are
public.

### `POST /v1/reviews`

Submit a diff for review. Returns immediately; the review runs
asynchronously.

```bash
curl -X POST http://localhost:8080/v1/reviews \
  -H "Authorization: Bearer $APP_BEARER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
        "diff": "--- a/f.js\n+++ b/f.js\n@@ -1 +1 @@\n+console.log(x)\n",
        "options": { "provider": "mock", "maxFindings": 100 }
      }'
# 202 {"jobId": "...", "status": "queued"}
```

`options` is optional and defaults to `{"provider": "mock", "maxFindings":
100}`. Optional `Idempotency-Key` header: a retried request with the same
key and byte-identical body returns the same `jobId` instead of creating a
new job; the same key with a different body returns `409`. Independently of
any key, resubmitting the same `{diff, options}` reuses the already-computed
result (`usage.cacheHit: true`) rather than redoing the work — the cache is
keyed on those values, so a resubmission still hits it if the JSON was
formatted differently or carried extra ignored fields.

### `GET /v1/reviews/{jobId}`

Poll a job's current state.

```bash
curl http://localhost:8080/v1/reviews/$JOB_ID \
  -H "Authorization: Bearer $APP_BEARER_TOKEN"
# {"jobId":"...","status":"done","findings":[...],"usage":{"inputBytes":45,"chunks":1,"cacheHit":false}}
```

`status` is `queued` → `running` → `done` or `failed`. `findings` is always
present (empty until done). A failed job still returns `200` — the failure
is a job outcome, not an HTTP error — with an `error` field describing what
went wrong.

### `GET /v1/reviews/{jobId}/stream`

The same job, pushed live over Server-Sent Events: a `status` event per
transition, one `finding` event per finding as it's determined, then a
`done` event with the total count and usage. Connecting after the job has
already finished replays the identical event sequence from the start.

### Errors

Every non-2xx response uses one shape:

```json
{ "error": { "code": "invalid_diff", "message": "diff contains no hunks" } }
```

`code` is one of: `unauthorized`, `payload_too_large`, `invalid_json`,
`invalid_diff`, `idempotency_conflict`, `not_found`, `rate_limited`,
`internal`.

## Running with Docker

```bash
docker build -t ai-diff-reviewer:1.0.0 .
docker run -d --name ai-diff-reviewer --restart unless-stopped \
  --env-file .env -p 8080:8080 ai-diff-reviewer:1.0.0
```

This is how the deployed instance runs. The image is a multi-stage build —
Maven compiles inside the builder stage, and only the jar plus a JRE end up in
the final image, so nothing about the host's Java setup matters.

## Running the tests

```bash
./mvnw test
```

200 tests. Unit tests cover the diff parser, chunker, and every mock rule
(trigger + decoy cases) in isolation. Integration tests spin up the full
service (`@SpringBootTest`, random port) and exercise auth, validation
precedence, lifecycle, idempotency/caching, rate limiting, concurrency,
content negotiation, and SSE replay against real HTTP calls.

`./mvnw nondex:nondex` additionally reruns the suite with randomized JDK
collection iteration order, to prove the documented `path → line → ruleId`
finding order comes from the explicit comparator rather than from a
coincidence of `HashMap` iteration.

## Project layout

```
src/main/java/.../
  bootstrap/      application entry point
  configuration/  limits, executor pool, startup checks
  controllers/    HTTP endpoints
  filters/        auth, rate limiting, error envelope writing
  dto/            request/response types (Finding, Usage, ReviewOptions, ...)
  models/         core domain entities (Job, JobStatus, DiffLine, ...)
  exceptions/     domain and API exception types
  services/       diff parser, chunker, mock rule engine, LLM provider, job orchestration
  repositories/   in-memory job store, idempotency store, result cache
  utils/          hashing
```
