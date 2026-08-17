# SUBMISSION.md

Spring Boot 4, Java 21, Maven. In-memory, no database. Runs in Docker behind
an ngrok tunnel.

## Architecture

`POST /v1/reviews` validates, parses and chunks the diff **synchronously**,
before any job exists. That was deliberate: a 4xx means the request itself was
bad, and a bad request shouldn't leave a job behind for someone to poll. Only
the rule matching (or the LLM call) goes onto a background executor — four
threads with an unbounded queue, so a fifth concurrent submission waits its
turn instead of being rejected.

Jobs live in a `ConcurrentHashMap`. Each `Job` holds an append-only list of the
SSE events it has emitted, plus a list of currently-attached emitters. Every
state change appends to the log *and* pushes to the attached emitters. That one
structure is what makes replay work — connecting to a job that finished ten
minutes ago just walks the log and produces the same sequence a live client
would have seen, with no special "am I replaying?" branch anywhere.

Idempotency and caching are two separate mechanisms and I kept them separate on
purpose. `Idempotency-Key` is about job *identity*: same key + same body → same
`jobId`, same key + different body → `409`. Caching is about not redoing
*work*: the same `{diff, options}` reuses the findings whether or not there's a
key. They're even hashed differently — idempotency hashes the raw request
bytes, because the contract says "different **body**"; the cache hashes a
canonical `(provider, maxFindings, diff)` tuple, because the contract says
"byte-identical **{diff, options}**". I had them sharing one hash at first and
only noticed those are two different sentences on a re-read.

The decision that paid off most: **the line counter resets from each hunk's own
`@@` header** instead of running as a total across the whole diff. That's what
makes splitting on file boundaries safe — any subset of a diff, parsed on its
own, still yields correct line numbers. Honestly I did that because it was
simpler to write, not because I'd foreseen the consequence; it only became
obvious later, when the chunked-vs-unchunked test passed with no extra
bookkeeping at all.

## Provider design

Both providers implement one interface:
`ReviewProvider.review(chunkText, List<DiffLine>)`. They get the parsed lines
(typed added/context/removed, with new-file line numbers already resolved) and
the raw chunk text, which the LLM path wants because it needs surrounding
context rather than only the changed lines. Because both sit behind the same
interface, chunking, ordering, dedup, caching and SSE all behave identically no
matter which one produced the findings — I only had to get that machinery right
once.

**`mock`** applies its nine rules to added lines only. MOCK-004 is the
exception: you can't tell whether a `catch` block is empty by looking only at
the lines that changed, so it reads context lines too for brace matching, but
it still only reports when the `catch` line itself was added. Deterministic and
dependency-free, which is why it's the one that gets scored.

**`llm`** is a generic OpenAI-compatible chat-completions client — the same
code runs against OpenAI, Groq, OpenRouter or a local Ollama; it's configured
live against Groq. Credentials come from env vars and never leave the server.
Any failure at all — blank config, connection refused, timeout, non-2xx,
malformed JSON, right JSON but wrong shape — becomes a `ProviderException`,
which the job service turns into a `failed` job with a readable message. It
never throws at startup and never 5xxs.

Two things I only learned by actually running it. First, the model sometimes
returns values outside the spec's vocabulary — a real Groq response came back
with `severity: "warning"` and `category: "Logic"`, neither of which is a legal
value. The parser now drops any element whose severity or category falls
outside the closed sets. A prompt is a request, not a guarantee. Second, the
diff is attacker-controlled text, so wrapping it in a fixed `<diff>` tag isn't
enough — a diff can just contain `</diff>` and everything after it starts
looking like instructions. The delimiter now carries a random per-call suffix
and the system prompt says only that exact delimiter ends the data.

## How I verified the cross-cutting behaviors

The things that actually broke were all **equalities between two paths**, so
those got tests that run both paths and compare them, rather than tests that
check each path looks reasonable on its own. That distinction mattered more
than anything else I did.

- **Chunking** — the same diff run unchunked and then force-chunked through an
  artificially tiny byte limit, asserting identical findings in identical
  order. Separately, that all the chunks concatenated reproduce the original
  text byte for byte. The second one is the stronger assertion and it caught an
  off-by-one newline that the first happily hid.
- **Idempotency vs. caching** — separate cases for each: same key + same body →
  same `jobId`; same key + different body → `409`; different key or no key at
  all + identical body → a *new* `jobId` but `cacheHit: true` with identical
  findings; and five concurrent identical submissions where exactly one does
  the real work and the other four attach to it.
- **SSE replay** — connect while the job is still running and record the live
  event order; then connect twice more *after* it finished and assert both
  replays match each other and match the live sequence.
- **Rate limiting and concurrency** — burst past the declared limit and check
  the boundary is exactly where `/spec` says it is, that `Retry-After` is
  present, and that nothing ever 5xxs under burst. Separately, six simultaneous
  submissions all reaching a terminal state.
- **Injection inertness** — a diff with an injection phrase on the *same line*
  as a real trigger, asserting both findings appear independently and neither
  suppresses the other.
- **Error taxonomy** — all eight codes reachable through their documented
  trigger, including Spring's own framework-level errors (unknown route, wrong
  method), which are easy to leave sitting in the framework's default shape if
  you only handle the exceptions you throw yourself.

**200 tests, `./mvnw test`.** I also ran the suite through
[NonDex](https://github.com/TestingResearchIllinois/NonDex)
(`./mvnw nondex:nondex`), which reruns everything with randomized `HashMap`
iteration order. Worth doing here specifically because the required finding
order is something my code enforces with an explicit comparator, and I wanted
to know the tests were passing because of the comparator and not because a hash
map happened to iterate conveniently. 11 runs, 0 failures.

Beyond the suite, I wrote a PowerShell script that builds the image, starts the
container, and then re-runs the whole contract as real HTTP calls against
**both** `localhost` and the public URL before holding the deployment up for
the scoring window. It's operational tooling rather than part of the
deliverable, but it's how I know the deployed service and the tested service
actually agree — and it caught something no unit test could have, which is that
a browser-shaped `User-Agent` gets ngrok's free-tier HTML interstitial instead
of my API.

### The audit that found the most

Once everything above was green I did one more pass, asking a different
question: not "does every requirement have a test" but "what have all my tests
been holding constant without me noticing?" That turned out to be the most
productive hour of the whole project. Five real bugs, none of which the
then-184 passing tests could have caught:

1. **Any `Accept` header that excludes JSON turned every error into a 500.**
   Including `GET /v1/reviews/{unknown}/stream` with
   `Accept: text/event-stream`, which is exactly what a correct SSE client
   sends, and `GET /health` with `Accept: application/xml` — a 500 on the
   liveness probe. Every single test I'd written used the default
   `Accept: */*`, where everything works fine. Fixed by stating the content
   type instead of negotiating it, and by dropping `produces` from the stream
   mapping, which had quietly made `Accept` part of the *routing* decision.
2. **`+++`/`---` lines inside a hunk were being read as file headers.** If you
   add a line whose own text starts with `++ `, the diff renders it as
   `+++ ...`. My parser believed it, which corrupted the path for the rest of
   that file and shifted every line number after it. The chunker had the
   mirror-image version of the same bug and would split a file mid-hunk, and
   the second half — now with no `+++` header above it — had **every finding
   silently dropped**. One rule fixes both: those markers only count as file
   headers when they appear as an adjacent pair, or outside a hunk body.
3. **MOCK-004's brace scan ran past the end of its own file**, so an added
   `catch (e) {` whose closing brace fell outside the hunk could be "closed" by
   a brace belonging to the *next* file. That makes the same diff score
   differently depending on which files happen to share a chunk, which is
   precisely the property the chunking probes exist to test.
4. **A chunk containing no hunk failed the entire job.** "No `@@` anywhere" is
   the right rule for rejecting a submitted diff and the wrong rule for an
   individual chunk, where a pure rename or a binary-file entry is completely
   normal.
5. The caching/idempotency hash split described up in Architecture.

Each one I confirmed with a throwaway script that printed the actual behaviour
*before* I wrote any fix, then turned into a permanent test.

I ran the same question again at the end and it found two more. `GET /health`
came back as `{"version":…,"uptimeSeconds":…,"status":"ok"}` — the right
fields, in a different order than the previous boot. `Map.of` builds a map
whose iteration order comes from a value randomized once per JVM start, and
Jackson serializes in iteration order, so `/health`, `/spec` and the SSE `done`
event were quietly reshuffling their fields on every restart. Strictly nothing
was broken, since JSON objects are unordered, and I nearly left it. I fixed it
because the whole point of `/spec` is that what I declare and what I emit are
the same thing, and a response whose bytes depend on when the process started
undercuts that. Every existing assertion looked fields up *by name*, so the
tests were structurally incapable of seeing it; the new ones compare the raw
response string.

The second was in the chunker: it decided whether a payload was a git diff with
an unanchored `contains("diff --git ")` over the whole text. Those characters
are an announcement only at the start of a line — mid-line they're just data,
which a plain `diff -u` produces naturally as a section heading after the `@@`.
Such a payload flipped into git mode, found no boundaries, and came back as one
segment. The findings were still correct, which is exactly why no test noticed,
but a 200 KiB diff would then report `usage.chunks: 1` — and "declared limits
must match your actual behavior" is its own line in the brief.

## What AI tools I used

Claude Code, across the whole thing: the implementation, three separate
line-by-line reviews of the code against the spec, writing and running the test
suite, diagnosing what those runs turned up, and the Docker and tunnel setup.

The part I'd want to talk about is that the useful work wasn't generating code.
It was directing where to look. The bugs listed above came out of questions —
"which header have I never varied?", "which rule reads lines other than its
own?" — not out of asking for more tests. Ask for more tests and you get more
tests for the cases you'd already thought of, which is the coverage you already
had. I also can't say I caught everything by review alone; several of these
only showed up because I ran things and read the actual output.

## AI suggestions I rejected, and why

**Deploying it as a bare jar.** The suggested plan was to build the jar and run
it directly on my machine with a supervisor script to restart it if it died.
Simpler, and it would have worked. I pushed back and containerized it instead,
because the thing being graded isn't really the code, it's a service that has
to stay reachable for 96 hours on a Windows laptop. A jar depends on the host's
JDK and `PATH` staying where they are and on my script being the only thing
that ever restarts it. A container with `--restart unless-stopped` pins the
runtime to the app and hands supervision to something better tested than
anything I'd write in an afternoon. Locally it's also one command to throw away
and rebuild, which I did constantly.

What I did keep from that suggestion is the jar as a *fallback*, and it turned
out to matter for a reason I didn't anticipate: Docker Desktop on Windows needs
an interactive desktop session. The `com.docker.service` service on its own
does not start the WSL2 VM, so recovering the container after an unattended
reboot means boot → **someone logs in** → GUI → engine → container. That's a
human in the loop, which is exactly what you don't want in a scoring window. So
the container is the deployment, and behind it there's a SYSTEM scheduled task
with a startup trigger that runs the jar if nothing is already serving — no
session dependency at all, the same model the ngrok service already uses. If
both ever come up together, one binds port 8080 and the other just fails to
bind, so something is always answering. (A trap I hit on the way: Task
Scheduler's default execution time limit is three days, which is shorter than
the 96-hour window, so it has to be cleared explicitly or the service gets
killed at hour 72.)

**Returning a hardcoded `"queued"` on an idempotent replay.** The spec's
example shows a fresh `202` with `status: "queued"`, and the first version of
the replay path just returned that string every time. I rejected it: if the
job has actually finished, telling a client it's still queued is just wrong.
The example is showing the shape of a *fresh* response, not mandating a lie on
a replayed one. Replays now return the job's real current status.

**"That's the tunnel's problem, not mine."** An oversized body sent with
`Expect: 100-continue` — which curl adds by itself for anything over about
1 KB — came back through ngrok with the correct error envelope but a `200`
status line instead of `413`. I narrowed it down with three tests changing one
variable each, and the application was provably fine; the tunnel was mangling
the 100-continue exchange. The suggested conclusion was to document it as an
external limitation and move on, which sounded reasonable and which I nearly
accepted. I didn't, because it left my correctness depending on which HTTP
client the grader happens to use. The fix ended up being four lines of ngrok
Traffic Policy that strip the header before it reaches my service. The lesson I
took from it is that "my code isn't where the bug is" and "there's nothing I
can do" are not the same statement.

## What I'd do next with more time

1. **Track each hunk's declared extent** from the line counts in its
   `@@ -a,b +c,d @@` header, instead of inferring where a hunk ends from the
   shape of each line. That closes two things at once. MOCK-004's brace scan
   currently stops at file boundaries — the boundary that could actually make
   chunked and unchunked results differ, so it's the one that had to be right —
   but it still runs across the gap between two hunks of the same file, where
   the lines in between simply aren't in the diff. It would also retire the
   last ambiguity in header detection: treating `---`/`+++` as a file header
   when they appear as an adjacent pair is exact for everything except a
   removed line starting `-- ` immediately followed by an added line starting
   `++ `. If you know how many lines a hunk contains, nothing inside one is
   ever a guess. I left it alone deliberately — it touches the parser and the
   chunker together and they have to agree, which isn't a change I wanted to
   make the day before submitting with a green suite.
2. A real tokenizer for MOCK-003 and MOCK-004 rather than regex and brace
   counting. That's what would make braces inside string literals tractable.
3. SSE heartbeats, so a long-idle connection doesn't get dropped by something
   in between. Much more likely on a slow LLM call than on the mock path.
4. A persistent job store. Everything here is in-memory on purpose — fine for a
   time-boxed exercise, wrong for production, and a restart mid-job is where
   that shows up first.
5. Some metrics: findings per rule, chunk-count distribution, cache hit rate,
   LLM latency. Right now the only observability is the logs.

## What I skipped, and why

- **Connector-level error envelopes.** A null byte or certain encoded traversal
  sequences in the path get rejected by Tomcat before Spring ever sees the
  request, so those return Tomcat's HTML error page instead of my JSON
  envelope. I tried to fix it with a custom `ErrorReportValve`, checked whether
  it actually worked, found it didn't, and reverted the whole thing rather than
  leave in something that looks like a fix and isn't.
- **Per-caller rate limiting is implemented but doesn't really do anything
  here**, since there's one bearer token and therefore one caller. I'd rather
  say that than present it as a feature.
- **No persistence, no auth beyond the single bearer token, no
  multi-tenancy.** All deliberate scope decisions for a time-boxed task.
