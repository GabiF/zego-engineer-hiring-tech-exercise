# web-crawler

A command-line web crawler that, given a base URL, crawls a **single domain** and prints every page it
visits together with all the links found on that page. Built in **Java 21**, with the emphasis the brief
asks for: clean design, a real test strategy, and an honest write-up of *how* it was built.

> The dedicated design write-up is **[`DESIGN.md`](DESIGN.md)** (decisions, options & trade-offs, the
> concurrency model, testing strategy, and extensions). The long-form working notes and diagrams live in
> **[`learning.md`](learning.md)**; the sections below are a condensed tour.

---

## Contents
- [What it does](#what-it-does)
- [Quick start](#quick-start)
- [Architecture](#architecture)
- [Testing](#testing)
- [How I used AI to learn, build, and verify](#how-i-used-ai-to-learn-build-and-verify)
- [What I wrote myself vs. what AI generated (test-first)](#what-i-wrote-myself-vs-what-ai-generated-test-first)
- [Design decisions](#design-decisions)
- [How the work evolved](#how-the-work-evolved)
- [Trade-offs (and how I'd mitigate them)](#trade-offs-and-how-id-mitigate-them)
- [Limitations](#limitations)
- [How I would extend this project](#how-i-would-extend-this-project)
- [Production setup & what I'd refine with more time](#production-setup--what-id-refine-with-more-time)

---

## What it does
- Accepts **one seed URL** on the command line.
- Crawls **only the seed's exact host** — no other domains, no subdomains.
- For every page fetched, prints the page URL and **all** links discovered on it (in-scope or not).
- Runs **concurrently but bounded**, for speed without exhausting resources.
- Emits **plain text** or **JSON Lines**.
- Reports **skipped (non-HTML) and failed fetches to `stderr`** with an end-of-run summary, and **exits
  non-zero when zero pages were crawled** — so a broken run is distinguishable from a clean one.

**Explicit non-goals (future improvement candidates):** it is a *static* crawler (no JavaScript
execution); it crawls a single host; **politeness — `robots.txt` enforcement and request rate-limiting
(`--delay`) — is not implemented.** Those flags are accepted but only emit a warning; wiring up a proper
per-host rate limiter and a `RobotsPolicy` is noted as future work (see [Limitations](#limitations)).

---

## Quick start

### Prerequisites
- **JDK 21+** (the Gradle build pins a Java 21 toolchain and can auto-provision it).

### Build & test
```bash
./gradlew build              # compile + run the whole test suite
./gradlew test               # tests only
./gradlew jacocoTestReport   # coverage report -> build/reports/jacoco/
```

### Run
```bash
./gradlew run --args="https://example.com"
./gradlew run --args="https://example.com --concurrency 32 --max-pages 500 --format json"
./gradlew run --args="--help"
```

Or build a self-contained distribution:
```bash
./gradlew installDist
./build/install/web-crawler/bin/web-crawler https://example.com
```

### CLI options
| Flag | Default | Description |
|------|---------|-------------|
| `<URL>` (positional) | — | Seed URL — must be an absolute `http`/`https` URL with a host (**required**) |
| `--concurrency` | `16` | Max concurrent HTTP requests |
| `--timeout` | `10` | Per-request timeout (seconds) |
| `--max-pages` | `0` | Safety cap on pages **fetched**; `0` = unlimited |
| `--user-agent` | `web-crawler/0.1` | `User-Agent` header sent with each request |
| `--format` | `text` | Output format: `text` or `json` |
| `--respect-robots` | `false` | Honour `robots.txt` — **not yet implemented**; warns if enabled |
| `--delay` | `0` | Politeness delay before each request, ms — **not yet implemented** (future work); warns if set |
| `--help`, `--version` | | Standard picocli help/version |

### Output
**Text** — each page, then its links indented:
```
https://example.com/
  -> https://example.com/about
  -> https://example.com/products
  -> https://twitter.com/example
https://example.com/about
  -> https://example.com/
```
**JSON Lines** (`--format json`) — one self-contained object per page, pipe-friendly (not yet implemented):
```json
{"page":"https://example.com/","links":["https://example.com/about","https://twitter.com/example"]}
```

---

## Architecture

The crawler is a small pipeline of single-responsibility components wired together once in the CLI. The
engine depends only on **interfaces**, so every stage is testable in isolation with fakes — no network.

```
seed -> Crawler engine
          |  for each claimed URL:
          v
        Fetcher -> FetchResponse        (sealed: Html | Skipped | Failed)
          |            | Html(finalUrl, body)
          v            v
        LinkExtractor  -- resolve every <a href> to an absolute URL (jsoup)
          |
          v
        ResultSink  <-- print "page + ALL links"      (text / json)
          |
          v  for each discovered link:
        UrlNormalizer -> Scope -> visited-set    (normalize -> in-scope? -> unseen? -> enqueue)
```

Two filters are kept deliberately distinct: the **reporter prints every link**, but only **in-scope,
unseen** links are **followed**.

| Package | Key type(s) | Responsibility |
|---------|-------------|----------------|
| `cli` | `CrawlCommand` | picocli entrypoint; validates the seed; **dependency wiring** (the one place concrete types are chosen) |
| `core` | `ConcurrentCrawler`, `Crawler`, `CrawlerConfig`, `CrawlResult` | the engine + immutable config/result records |
| `fetch` | `HttpClientFetcher`, `Fetcher`, sealed `FetchResponse` | HTTP via the JDK `HttpClient`; outcomes as values, never exceptions |
| `parse` | `JsoupLinkExtractor`, `LinkExtractor` | HTML to absolute, de-duplicated links (jsoup) |
| `url` | `StandardUrlNormalizer`, `HostScope`, `UrlNormalizer`, `Scope` | the accuracy-critical logic: canonicalisation + scope |
| `output` | `TextSink`, `JsonLinesSink`, `ResultSink` | thread-safe reporting |

**Concurrency & termination.** The engine uses **one virtual thread per claimed URL**. A `Semaphore`
bounds the number of *concurrent HTTP requests* (decoupled from thread count). The `visited` set is a
`ConcurrentHashMap.newKeySet()`, so a URL is claimed atomically at enqueue time and never processed twice.
Termination is precise — an `AtomicInteger pending` counter plus a `CountDownLatch` mean the crawl stops
exactly when the **last** in-flight task finishes, so it never exits early and never hangs.

### Project layout
```
src/main/java/com/example/crawler/
  cli/     - picocli entrypoint + wiring
  core/    - crawl engine + config/result records
  fetch/   - HTTP fetching (Fetcher + sealed FetchResponse)
  parse/   - HTML link extraction
  url/     - URL normalization + scope (accuracy-critical)
  output/  - result reporting (text / json)
src/test/java/com/example/crawler/   - mirrors the above
```

---

## Testing

I worked test-first: each component's test was written as an executable spec before the production
code, and the implementation was driven until the spec went green.

- **Pure-logic units** (the highest-value targets, no I/O): `StandardUrlNormalizerTest` (a big
  input-to-canonical table + dropped schemes + idempotency), `HostScopeTest` (exact-host in/out, subdomain
  exclusion, the classic `endsWith` look-alike bug), `JsoupLinkExtractorTest` (relative / protocol-relative
  / `<base href>` resolution, de-dup, the "faithful" scheme policy, skip-unparseable, immutability).
- **Engine** `ConcurrentCrawlerTest`: drives the real engine with an in-memory fake `Fetcher`/`LinkExtractor`
  page-graph — verifies dedupe/cycles, the print-all-vs-follow-in-scope split, the `--max-pages` cap, and
  termination, with order-independent assertions (it's concurrent).
- **Fetcher**, split into the test pyramid:
    - `HttpClientFetcherUnitTest` — fast, no sockets: the pure `classify(...)` mapping (every status /
      content-type branch) and the exception paths (`IOException`/timeout/interrupt) via an injected fake.
    - `HttpClientFetcherTest` — a **WireMock** integration test for what mocks can't fake: real
      redirect-following + final-URL, status/content-type over a real socket.
- **Tools:** JUnit 5, AssertJ, Mockito, WireMock, and JaCoCo for coverage.

---

## How I used AI to learn, build, verify & document

I used an AI assistant (GitHub Copilot) as a **pair-programmer and tutor**, not an autopilot — I owned the
decisions; it accelerated research, scaffolding, tests, and review.

- **To learn the domain.** Before writing code I had the AI explain crawler fundamentals (graph traversal,
  the frontier + visited model), the genuinely hard parts (URL normalization & resolution, exact-domain
  scoping, concurrency/termination), and standards I was unfamiliar with (e.g. `robots.txt` / RFC 9309). I
  also used it to **deconstruct the brief clause-by-clause** into explicit *and* hidden requirements.
- **To choose the stack.** I had it compare my realistic options (Java vs JS) **by the hard parts** rather
  than the easy ones — landing on Java 21 + jsoup (best-in-class lenient HTML parsing with base-aware link
  resolution) and the JDK `HttpClient` (no extra dependency, pairs naturally with virtual threads).
- **To produce code.** It scaffolded the Gradle project and package layout, drafted the component
  **interfaces/contracts**, authored the **test suites first**, and provided **reference implementations** I
  diffed my own against. It also maintained the long-form design log (`learning.md`) as we went along.
- **To verify the work.** Beyond the tests, I used it as a **reviewer** — e.g. it surfaced the
  `--respect-robots`-defaults-true-but-inert inconsistency and the redirect-then-failure URL-identity
  question, both of which changed the code/contract.
- **To help generate this write-up.** I used it to draft the initial outline and some of the prose, 
  but I rewrote parts of it and reorganised it to reflect my own voice and the actual decisions made.
---

## What I wrote myself vs. what AI generated (test-first)

I drove the design and wrote the main business logic myself; AI's biggest contribution was writing 
the tests first so I had a precise target to implement against.

- **I implemented:** the URL normalizer (`StandardUrlNormalizer`), the link extractor
  (`JsoupLinkExtractor`), the HTTP fetcher (`HttpClientFetcher`), and the crawl engine — building a
  **single-threaded version first** (see `SingleThreadedCrawler`) to nail correctness, 
  then the concurrent `ConcurrentCrawler`.
- **AI generated (under my direction):** the Gradle/CI scaffolding, the **interfaces**, the **test suites**
  (written before the implementations, as the spec to satisfy), the design doc, and reference
  implementations for comparison, that I only used when seriously stuck (edge cases when normalising 
  the path in the `StandardUrlNormalizer`, as well as scaffolding of `ConcurrentCrawler`).
- **The loop (red -> green -> compare):** for each component the AI first wrote a failing test — e.g. the
  `StandardUrlNormalizerTest` canonicalisation table or the `JsoupLinkExtractorTest` resolution cases — I
  implemented the production code until it passed, then diffed against an AI reference to catch edge cases I
  had missed (and sometimes to reject its choices in favour of mine). Working from a concrete test made the
  requirements unambiguous and kept the implementations honest.

---

## Design decisions

Each is a deliberate choice with a considered alternative (the full reasoning is in `learning.md`).

1. **Single domain = exact host match** (`HostScope`). The brief says "no other domains **or subdomains**,"
   so `community.example.com` and `www.example.com` are out of scope. This is the literal reading and it
   avoids the classic `host.endsWith("example.com")` bug (which would wrongly match `notexample.com`).
   *Alternative:* registrable-domain (eTLD+1) matching — but that *includes* subdomains, so it's the
   multi-domain **extension**, not the strict requirement.
2. **Print all links, follow only in-scope ones.** "Print all the URLs it finds" and "only crawl this
   domain" are two *different* filters; conflating them is the easy mistake. The reporter sees every link;
   only normalized, in-scope, unseen links enter the frontier.
3. **Resolution and normalization are separate jobs.** Resolving `../about` needs the page's base URL, so it
   lives in the **extractor** (jsoup, which also honours `<base href>`). The **normalizer** is then a *pure,
   context-free* function — the single highest-value unit-test target.
4. **Conservative URL canonicalisation.** Lower-case scheme+host, strip the scheme's default port, drop the
   fragment, collapse `.`/`..`, empty path to `/`; **keep the trailing slash and query verbatim**. Rationale:
   produce a reliable dedupe/scope key *without* merging genuinely distinct resources (`/a` vs `/a/`).
5. **"Faithful" extractor.** It returns *every* resolved `<a href>` regardless of scheme (`mailto:`/`tel:`/
   `javascript:` included) — the literal reading of "all the URLs on that page." Following is filtered
   downstream, so the extractor makes no policy calls. *Trade-off:* noisier output (see below).
6. **Errors as values, not exceptions** (sealed `FetchResponse = Html | Skipped | Failed`; `Fetcher` never
   throws for ordinary failures). The engine pattern-matches exhaustively, and one bad page can never abort
   the crawl.
7. **A testable fetcher.** The status/content-type mapping is a *pure* `classify(...)` method, and the
   blocking send sits behind a one-method `HttpSend` seam — so the mapping **and** the exception branches are
   unit-tested with no socket and **without mocking `HttpClient`** (a type I don't own). `Failed` keeps the
   *requested* URL as its identity (traceable to the enqueued link, stays in-scope) and appends the
   post-redirect URL to the reason when they differ.
8. **Concurrency = virtual-thread-per-task + `Semaphore` + counter/latch.** Cheap threads let me write
   simple blocking code; the semaphore bounds *requests* (resources/politeness) independent of thread count;
   the `pending` counter + latch give precise termination. *Alternative:* a fixed worker pool + blocking
   queue — valid, but its idle-detection/poison-pill termination is the usual source of "exits early / hangs"
   bugs.
9. **`--max-pages` counts *fetches*, any outcome.** It's a **safety cap** on work/requests, so non-HTML and
   failed fetches must count too — otherwise a site full of PDFs or dead links could blow past the budget.
10. **Dependency injection behind interfaces.** The only place concrete types are chosen is the CLI wiring;
    everything else is testable with fakes and never touches the network.

---

## How the work evolved

The project came together as a series of deliberate iterations rather than one big bang:

1. **Validate the requirements.** Deconstructed the prompt clause-by-clause into a checklist of explicit +
   hidden requirements (URL robustness, cycle/dedup/termination, error/timeout/non-HTML handling,
   politeness). Surfaced the print-vs-follow distinction and the redirect/subdomain edge cases up front.
2. **Pick the architecture.** Settled on interfaces + DI, a sealed `FetchResponse`, and an engine that
   orchestrates injected seams — chosen specifically so the tricky logic is unit-testable without a network.
3. **Shape the code structure.** Package-by-feature; extracted the pure functions (`normalize`, `classify`)
   as the high-leverage test targets; kept the reporter and the follow-filter separate.
4. **Build correctness-first, then concurrency.** Implemented a single-threaded engine to nail the
   frontier/visited/termination logic against fast, deterministic tests, then swapped in the concurrent
   engine behind the same interface.
5. **Harden the test suite.** Replaced an early placeholder smoke test with real unit + WireMock integration
   tests; **refactored the fetcher** (pure `classify` + `HttpSend` seam) specifically to make the mapping and
   exception branches unit-testable; and tightened the `Failed`-URL contract (requested vs. final URL) after
   a review question exposed the ambiguity.
6. **Product documentation.** Wrote this README and the design doc, while continuously keeping and 
   updating a `learning.md` log to capture the long-form reasoning and diagrams.
---

## Trade-offs (and how I'd mitigate them)

| Trade-off | Why I chose it | Cost | Mitigation |
|-----------|----------------|------|------------|
| **Exact-host scope** | literal brief; predictable | excludes subdomains; the apex-to-www redirect trap can stop a crawl after ~1 page | `--treat-www-as-apex`; adopt the post-redirect host as scope; eTLD+1 for multi-domain |
| **Faithful extractor** (all schemes) | literal "all URLs" | noisy output (`mailto:`/`javascript:` etc.) | optional scheme filter for *printing*; the follow-filter already drops non-http |
| **Static crawler** (no JS) | fast, simple, no browser | misses JS-rendered links | an optional headless-render `Fetcher` behind the existing interface |
| **robots.txt not enforced** | scope/time | not production-polite (the flags warn, but don't enforce) | implement the already-designed `RobotsPolicy` (+ crawl-delay, sitemap seeding) |
| **Whole-body buffering** (`ofString()`) | simplest, and HTML parsing needs the full document anyway | unbounded memory — a huge/hostile response could OOM; non-HTML is fetched before it's skipped | bounded body subscriber: cap HTML size + `discard` non-HTML by `Content-Type` |
| **In-memory frontier & visited** | simple, fast | single-process, memory-bound | external frontier + shared visited set (e.g. Redis) for horizontal scale |
| **No retry/backoff** | simpler | transient 5xx/timeouts are lost | bounded retry with jitter on idempotent GETs |
| **CLI interface** | perfect for a one-off run | poor for orchestration, resumability, scale | a service/API + job model (see below) |

---

## Limitations

Called out honestly (these map to "future work"):
1. **`robots.txt` is not yet honoured**, and request rate-limiting (`--delay`) is not implemented — both
   flags are accepted but only emit a warning (they never silently claim compliance).
2. **Response bodies are buffered fully into memory** (`HttpResponse.BodyHandlers.ofString()`), with no
   size cap, and non-HTML is downloaded before it is skipped — so a very large or hostile response could
   waste bandwidth or exhaust memory. **Deferred by choice:** the intended fix is a bounded body subscriber
   that caps HTML and `discard`s non-HTML by `Content-Type` (`--max-pages` already bounds request *count*).
3. **No retry/backoff** on transient errors.
4. **No persistence/resumability** — a crawl is a single in-memory run.

---

## How I would extend this project

### Crawl multiple domains at once
Swap `HostScope` for a scope backed by a **set of hosts** or **registrable-domain (eTLD+1)** matching
(Guava's `InternetDomainName`, already a dependency). Give each domain its **own politeness budget and
rate-limiter**, and partition the frontier per host so one slow site can't starve the others. The engine and
interfaces don't change — only the `Scope` implementation and the per-host scheduling do.

### Beyond the CLI — why it's the wrong interface at scale, and what's better
A CLI is ideal for a single ad-hoc run, but it's a poor fit once crawling becomes a *workload*: it's a
long-running blocking process with no job lifecycle (submit / status / cancel / resume), output is glued to
stdout, and the in-memory frontier dies with the process. Better interfaces:
- **A service / HTTP API:** `POST /crawls` returns a job id; `GET /crawls/{id}` reports progress; results
  stream to a store (object storage / database) or a message topic. This gives concurrency across *jobs*,
  back-pressure, retries, and observability.
- **A queue-based worker system** for horizontal scale: externalise the two pieces of engine state — the
  **frontier** (a durable queue: SQS/Kafka/Redis) and the **visited set** (a shared store: Redis/a DB) —
  behind `Frontier` and `VisitedSet` interfaces. Workers become stateless and scale out; the crawl becomes
  resumable and fault-tolerant. The current `ConcurrentCrawler` is essentially the single-node version of
  this, so the refactor is mostly "extract those two interfaces."

### Make it production-ready and robust
Implement the designed `RobotsPolicy` (fetch/cache `robots.txt`, honour `Disallow`/`Allow` longest-match and
`Crawl-delay`), per-host rate limiting (token bucket), and bounded retry with exponential backoff on
5xx/timeouts.

### Other enhancements
- Optional JS rendering via a headless fetcher (behind the `Fetcher` interface)
- depth / page / time limits and URL include/exclude filters
- sitemap.xml seeding
- content de-duplication (hash bodies to skip mirror pages)
- observability (structured logging + metrics: pages/sec, error rates, frontier size; 
  as well as alerts when error rates increase or when frontier depth is continuously growing, 
  suggesting that the workers are falling behind and needing investigation).

---

## Production setup & what I'd refine with more time

The repo is set up as a buildable, testable Gradle project (toolchain-pinned Java 21, `application` +
`jacoco` plugins, a layered package structure, and a real test suite). With more time I would add:
- **CI** (GitHub Actions: build + test + a coverage gate on PRs).
- **Formatting/linting** (Spotless + Checkstyle) and **dependency/vulnerability scanning**.
- **A `Dockerfile`** for a reproducible runtime.
- The **functional gaps** in [Limitations](#limitations) (robots.txt enforcement, rate-limiting, bounded
  response bodies, retry/backoff).
