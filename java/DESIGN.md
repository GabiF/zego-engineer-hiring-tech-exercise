# Design

A detailed discussion of the design decisions, the options considered, the trade-offs made, and what I
would refine with more time. It is written to stand on its own, but:

- **[`README.md`](README.md)** is the front door — quick start, CLI flags, output formats, and a condensed
  version of this discussion.
- **[`learning.md`](learning.md)** is the long-form working log — how I researched the problem, the full
  rationale, and the diagrams this document distills.

---

## Contents
1. [Problem analysis](#1-problem-analysis)
2. [Architecture](#2-architecture)
3. [Key design decisions (options & trade-offs)](#3-key-design-decisions-options--trade-offs)
4. [Concurrency & termination](#4-concurrency--termination)
5. [Robustness & error handling](#5-robustness--error-handling)
6. [Testing strategy](#6-testing-strategy)
7. [Known limitations & consciously deferred work](#7-known-limitations--consciously-deferred-work)
8. [How I would extend it](#8-how-i-would-extend-it)
9. [What I'd refine with more time](#9-what-id-refine-with-more-time)

---

## 1. Problem analysis

The task: a command-line crawler that, given a base URL, visits every page on **that single host** and
prints each page together with **all** the links found on it — fast, but without sacrificing accuracy or
compute resources, and without a crawling framework or headless browser.

Two reframings drove the design:

- **It's a graph traversal.** Each page is a node, each `<a href>` a directed edge, and the graph is
  discovered as you go. The whole engine is just moving URLs through three states — *unknown → frontier
  (discovered, not done) → visited (done)* — until the frontier drains. Cycles (`A → B → A`) are the norm,
  so a **visited set** is mandatory.
- **It's I/O-bound.** Almost all wall-clock time is spent waiting on the network. That single fact is what
  makes concurrency the right "pattern to run as quickly as possible": while one request waits, dozens of
  others can be in flight. CPU is never the bottleneck; the network (and politeness) is.

The subtle requirement hiding in the prompt is the **print-vs-follow distinction**: you *print* **all**
links discovered on a page (in-scope or not), but you only *follow* in-scope, unseen ones. Conflating the
two is the most common way to get this wrong. They are two different filters applied at two different
points.

### Requirements (explicit + hidden)

Deconstructing the prompt clause by clause surfaces hidden requirements alongside the stated ones:

| #   | Requirement | Type |
|-----|-------------|------|
| R1  | CLI accepting a base URL | Explicit |
| R2  | Print each page's URL + **all** links on it | Explicit |
| R3  | Crawl only the exact same host (no other domains **or subdomains**) | Explicit |
| R4  | Concurrent / fast, but bounded (don't waste compute) | Explicit |
| R5  | No Scrapy/Playwright; libraries for HTTP/parsing are fine | Constraint |
| R6  | Clean structure + tests | Explicit (graded) |
| R7  | Written design discussion | Explicit (graded) |
| R8  | Extensions discussion (multi-domain, non-CLI) | Explicit |
| R9  | Production repo setup | Explicit |
| R10 | Robust URL handling (normalization, relative, non-HTTP) | Hidden |
| R11 | Avoid cycles / dedupe / terminate correctly | Hidden |
| R12 | Handle errors, timeouts, non-HTML content | Hidden |
| R13 | Politeness (robots.txt, rate limiting) | Hidden / bonus |

R10–R12 are where the exercise is really won or lost; R13 is a documented, deliberate deferral (see §7).

---

## 2. Architecture

The crawler is a small pipeline of single-responsibility components, wired together **once** in the CLI.
The engine depends only on **interfaces**, so every stage is testable in isolation with in-memory fakes and
the engine never touches the real network in tests.

```
seed ─► Crawler engine
          │  for each claimed URL:
          ▼
        Fetcher ─────────► FetchResponse     (sealed: Html | Skipped | Failed)
          │                     │ Html(finalUrl, body)
          ▼                     ▼
        LinkExtractor  ── resolve every <a href> to an absolute URL (jsoup, <base>-aware)
          │
          ▼
        ResultSink  ◄── print "page + ALL links"   (text / json, to stdout)
          │
          ▼  for each discovered link:
        UrlNormalizer ─► Scope ─► visited-set     (normalize → in-scope? → unseen? → enqueue)
```

| Package  | Key type(s) | Responsibility |
|----------|-------------|----------------|
| `cli`    | `CrawlCommand` | picocli entrypoint; validate the seed; **dependency wiring** (the one place concrete types are chosen); map the run outcome to an exit code |
| `core`   | `Crawler`, `ConcurrentCrawler`, `CrawlerConfig`, `CrawlResult`, `CrawlSummary` | the engine + immutable config/result/summary records |
| `fetch`  | `Fetcher`, `HttpClientFetcher`, sealed `FetchResponse` | HTTP via the JDK `HttpClient`; outcomes returned as values, never thrown |
| `parse`  | `LinkExtractor`, `JsoupLinkExtractor` | HTML → absolute, de-duplicated links |
| `url`    | `UrlNormalizer`/`StandardUrlNormalizer`, `Scope`/`HostScope` | the accuracy-critical canonicalisation + scope logic (pure functions) |
| `output` | `ResultSink`, `TextSink`, `JsonLinesSink` | thread-safe result reporting |

**Why dependency injection behind interfaces?** It serves the graded "clean structure + tests" requirement
directly. The engine is constructed from `Fetcher`, `LinkExtractor`, `UrlNormalizer`, `Scope`, `ResultSink`
and a diagnostics `Consumer<String>`. Tests inject fakes (a `Map`-backed fetcher, a pure normalizer) and
assert behaviour deterministically; production wires the real implementations. The boundary that matters
most — the network — is the easiest to fake.

---

## 3. Key design decisions (options & trade-offs)

### 3.1 Language: Java 21

**Chosen:** Java 21. **Considered:** Go (best raw fit — goroutines/channels — but no current experience),
Python (skills stale), Node/TypeScript (strong contender).

The decision came down to the *hard* parts, not the easy ones (HTTP/args/IO are a wash):

- **HTML + resolution → Java.** jsoup is best-in-class and `Element.absUrl("href")` resolves relative URLs
  against the document base *and honours `<base href>`* — solving part of the hardest accuracy problem
  during extraction.
- **URL normalization → Node** (WHATWG `URL` is the browser algorithm; `java.net.URI` is RFC-3986-strict).
  This is Java's one real weakness here, and I neutralise it (§3.5).
- **Concurrency → split.** Node's single thread means the visited set needs *no locks*; Java's virtual
  threads (21+) make blocking I/O cheap **and** let me demonstrate the explicit concurrency patterns the
  brief rewards (`Semaphore`, `ConcurrentHashMap.newKeySet()`, atomic counter + latch).
- **Testing (graded) → Java.** JUnit 5 + AssertJ + Mockito + **WireMock** is a mature stack for standing up
  a fake site and asserting crawl behaviour.

Net: Java is the strongest language on hand, code quality is graded, and modern Java showcases the
concurrency engineering well — at the cost of doing URL canonicalisation by hand.

### 3.2 Concurrency model: one virtual thread per claimed URL

**Chosen:** `Executors.newVirtualThreadPerTaskExecutor()`, a `Semaphore` to bound concurrent requests,
`ConcurrentHashMap.newKeySet()` for the visited set, and an `AtomicInteger pending` + `CountDownLatch` for
termination. **Considered:** a classic fixed worker pool draining a `BlockingQueue`.

The worker-pool design is valid but its termination needs an idle-detector (`active == 0 && queue empty`)
or poison pills — the usual source of "exits early / hangs forever" bugs. With virtual threads the executor
*is* the frontier (no explicit queue), blocking `fetch()` calls are cheap, and the counter-based
termination is both simpler and provably correct (§4). The trade-off is that I must get the shared-state
atomics right — which is exactly the engineering the brief is probing.

Crucially, the **`Semaphore` bounds *network requests*, decoupled from the thread count.** Thousands of
virtual threads can exist; only `--concurrency` of them hit the network at once. That's the "fast but
bounded / don't waste compute resources" requirement, expressed as one knob.

### 3.3 Correctness-first build order

The engine was built **single-threaded first** (an `ArrayDeque` frontier + a plain `HashSet`), where
termination is trivial and the logic — normalization, scope, dedupe, the print-vs-follow split — could be
nailed against fast, deterministic tests. The concurrent engine was then swapped in **behind the same
`Crawler` interface**: the per-URL work is identical, only the executor/semaphore/atomics are added. This
de-risked the hard concurrency by separating "is the logic right?" from "is the concurrency right?".

### 3.4 Scope: exact-host matching

**Chosen:** exact, case-insensitive host equality (`HostScope`). **Considered:** `endsWith` suffix
matching (a classic bug), and registrable-domain / eTLD+1 matching (Guava `InternetDomainName`).

The brief says "not … other domains **or subdomains**", which is the strict reading: seed `example.com`
excludes `www.example.com` and `blog.example.com`. Exact equality delivers that and avoids the
`host.endsWith("example.com")` trap that wrongly matches `notexample.com`.

> **Documented trade-off — the apex/`www` redirect trap.** Exact-host matching means seeding the apex when
> the site canonicalises to `www` (a `301` to `www.example.com`) crawls one page and stops: the redirect is
> followed, that page is printed, but every internal link is on the now-out-of-scope `www` host. This is a
> *correct* consequence of the strict reading. Mitigations, in increasing scope: (a) keep strict (default,
> predictable); (b) a `--treat-www-as-apex` flag that strips a leading `www.` on both sides; (c) adopt the
> seed's *post-redirect* host as the scope after the first fetch; (d) eTLD+1 matching — which is really the
> multi-domain extension (§8), since it includes subdomains.

### 3.5 URL normalization (the accuracy core)

To use a URL as a visited-set key it must be canonicalised. `StandardUrlNormalizer.normalize(URI)` is a
**pure function** that:

1. requires an `http`/`https` scheme (else `Optional.empty()` — this is what drops `mailto:`, `tel:`,
   `javascript:`, `data:`, and bare `#fragment` links);
2. lower-cases scheme + host;
3. strips the scheme's default port (`80`/`443`);
4. drops the fragment;
5. collapses `.`/`..` path segments (`URI.normalize()`); empty path → `/`;
6. **keeps the trailing slash** verbatim; and
7. **keeps the query** verbatim.

Decisions 6 and 7 are deliberate **accuracy-first** choices: treating `/a` and `/a/` as identical, or
stripping query strings, risks merging genuinely distinct resources. The cost is more duplicate-looking
crawls; the mitigations (a trailing-slash rule driven by observed redirects; a `--strip-tracking-params`
flag for `utm_*`/`fbclid`) are noted but not built. The function is **idempotent**
(`normalize(normalize(x)) == normalize(x)`), which the test suite pins — important because normalized URLs
re-enter the pipeline as discovered links.

This is the single highest-value unit-test target, which is *why* it is a context-free pure function.

### 3.6 Resolution vs. normalization are different jobs

Two link-handling steps are easy to conflate but live in different components and run in a fixed order:

```
fetch ─► [RESOLVE: relative href + base → absolute]  ─► [CANONICALIZE: absolute → key] ─► scope ─► visited
            (LinkExtractor — needs the page's base)        (UrlNormalizer — pure, context-free)
```

- **Resolution lives in the extractor** because you cannot resolve `../about` or `//cdn/x` without knowing
  *where you are*; the base (the page's final URL plus any `<base href>`) is only known at extraction time.
  jsoup does this correctly, including protocol-relative and dot-segments.
- **Normalization stays pure** so it remains the highest-value, deterministic unit target. A *relative* URI
  that somehow reaches it (`/about` has a null scheme) is **dropped, not resolved** — the normalizer never
  guesses a base.

### 3.7 Errors as values: a sealed `FetchResponse`

`Fetcher.fetch` **never throws** for ordinary failures; it returns a sealed
`FetchResponse = Html | Skipped | Failed`. The engine `switch`es over it with exhaustive pattern matching,
so the compiler guarantees every outcome is handled and error handling stays out of exceptions (a single
bad page can never abort the crawl).

`HttpClientFetcher` is built for testability: the status/content-type → result mapping is the **pure static
`classify(...)`**, and the blocking exchange sits behind a one-method `HttpSend` seam injected via a
package-private constructor. So the mapping *and* the exception branches are unit-tested **with no socket
and without mocking `HttpClient`** (a type I don't own); WireMock covers the real-I/O behaviour separately.

### 3.8 A faithful link extractor

`JsoupLinkExtractor` returns **every** resolved `<a href>`, regardless of scheme (`mailto:`, `tel:`,
`javascript:` included), de-duplicated in first-seen order, as an immutable list. Rationale: the brief says
print *all* the URLs on a page, and the extractor's output **is** that printed list. *Following* is filtered
separately downstream (the normalizer drops non-HTTP, scope drops off-host), so the extractor stays a
faithful "what's on the page" reporter and makes no policy calls. Unparseable hrefs are skipped (never
thrown) so one malformed link can't abort a page.

### 3.9 `Failed` keeps the *requested* URL as its identity

`Html`/`Skipped` are keyed by the post-redirect `finalUrl` (needed as the link-resolution base and dedupe
key). `Failed` instead keeps the **requested** URL — traceable to the enqueued link, and still in-scope even
if a redirect left the domain — with the final URL appended to the reason (`HTTP 404 @ …`) when it differs.
This keeps diagnostics meaningful without polluting scope/identity.

### 3.10 Output: results to stdout, diagnostics to stderr

- **Results** (`page + ALL links`) go to **stdout** via a `ResultSink` — `TextSink` (indented) or
  `JsonLinesSink` (one JSON object per line, pipe-friendly). Each page's block is built then written under
  one lock, so concurrent workers never interleave a page mid-block.
- **Diagnostics** (skipped/failed/off-domain-redirect) go to **stderr** via an injected
  `Consumer<String>`. Keeping them off stdout means the JSON Lines stream stays clean for piping — the
  standard CLI contract.
- `crawl()` returns a **`CrawlSummary(pagesCrawled, skipped, failed)`**; the CLI prints a one-line summary
  to stderr and returns a **non-zero exit code when zero pages were crawled** (e.g. the seed failed or was
  non-HTML). A broken run is therefore distinguishable from a clean one in scripts/CI — the seed being a
  404 or a PDF no longer looks like success.

### 3.11 `--max-pages` counts *fetches*, any outcome

The safety cap counts every *claimed* URL, not just successful HTML pages — otherwise a site full of PDFs
or dead links could blow past the budget. It is a guard on **work/requests**, not on output rows.

---

## 4. Concurrency & termination

Every URL passes through one **`submit()` gate**, which is the dedup choke-point:

```
normalize(url)  →  inScope?  →  visited.add()  →  dispatch (pending++, pool.execute(process))
                    drop ✗       seen ✗ (atomic claim — false if already present)
```

`ConcurrentHashMap.newKeySet().add()` is atomic and returns `false` if the URL was already present, so
**dedupe and claim happen in a single step** — two workers can never grab the same URL, with no lock and no
check-then-act race.

```
                main ── submit(seed) ──►  [ submit() gate ]  ◄── EVERY url passes here (dedup once)
                                                │ one virtual thread per *claimed* url
                       ┌──────────┬─────────────┼─────────────┬──────────┐
                       ▼          ▼             ▼             ▼          ▼
                    [task]     [task]        [task]        [task]     [task]   …1000s of cheap VTs
                       └──────────┴───►  Semaphore(N)  ◄───┴──────────┘   only N hit the network at once
                                              │
                                    fetch → switch(outcome):
                                      Html  → re-scope finalUrl → print page + ALL links → submit() each link ──┐
                                      Skip/Fail → count + stderr diagnostic                                     │
                                              │                                                                 │
                                       finally: pending--                                  (links loop back to the gate)
                                              │
                                    if pending == 0 → done.countDown() ─────► main wakes, shuts the pool, returns summary
```

**Why termination is correct (and this is the part most naive crawlers get wrong):** "frontier empty" is
*not* sufficient — a worker could be mid-fetch, about to enqueue 50 children. So I count *in-flight work*.
A parent increments `pending` for each child (inside `submit → dispatch`) **before** its own `finally`
decrements `pending` for itself. Therefore `pending` can only reach zero when **no task is running and none
is pending**, and the one-shot `CountDownLatch` trips exactly once. The result: the crawl never exits early
(work is always accounted for before a parent finishes) and never hangs (the last task always trips the
latch). There is no busy-wait — `done.await()` blocks and `Semaphore.acquire()` blocks.

**Redirects are re-checked here, not trusted (R3).** Scope is enforced *before* a fetch, but the fetcher
follows redirects, so the post-redirect `finalUrl` is re-normalized, re-scoped, and re-claimed in `visited`
before the page is reported or its links followed. An off-domain redirect target is dropped (with an stderr
note); an in-scope redirect target is reported under its canonical URL and claimed once, so the same page
reached via both a direct link and a redirect is crawled exactly once.

---

## 5. Robustness & error handling

- **Timeouts:** a per-request connect/read timeout; a slow or dead server becomes a `Failed`, never a hang.
- **Status & content-type:** only `2xx` + `text/html`/`application/xhtml+xml` is parsed; non-2xx/timeout/IO
  → `Failed`, 2xx-non-HTML → `Skipped`.
- **Visibility:** `Failed`/`Skipped` are counted and streamed to stderr, and surfaced in the run summary +
  exit code — failures are observable, not silently dropped.
- **Isolation:** the per-task body catches `RuntimeException`, so one bad page can never bring down the
  crawl; `InterruptedException` re-sets the interrupt flag.
- **Lenient parsing:** jsoup tolerates real-world malformed HTML; unparseable hrefs are skipped.
- **Runaway guard:** `--max-pages` caps total fetches.

---

## 6. Testing strategy

The seams exist so the tricky logic is tested without a network, deterministically:

- **Pure units (highest value):**
  - `StandardUrlNormalizerTest` — fragment/default-port stripping, host/scheme lower-casing, `.`/`..`
    collapsing, trailing-slash + query policy, non-HTTP drops, protocol-relative drops, and **idempotency**.
  - `HostScopeTest` — exact host in; `www.`/`blog.` subdomains out; `notexample.com` out (no `endsWith`
    bug); different TLD out; case-insensitive.
  - `JsoupLinkExtractorTest` — relative/absolute/protocol-relative resolution, `<base href>`, within-page
    dedupe, faithful schemes, unparseable-skip, immutable empty-not-null result.
  - `HttpClientFetcherUnitTest` — the pure `classify(...)` status/content-type branches and the exception
    handling, via the `HttpSend` seam (no socket).
- **Component (engine + fakes):** `ConcurrentCrawlerTest` drives the **real** engine with a `Map`-backed
  fetcher/extractor — asserting (order-independently, since virtual-thread completion order is
  non-deterministic): every in-scope page reported once, off-domain links printed but never crawled, cycles
  terminate, `--max-pages` caps, off-domain **redirects** dropped, in-scope redirects reported under their
  final URL and de-duplicated, and `Failed`/`Skipped` outcomes counted + surfaced.
- **Integration (real I/O):** `HttpClientFetcherTest` exercises the real `HttpClient` against **WireMock** —
  HTML, non-HTML, 404, and redirect-to-final-URL.

**Known test gaps** (called out honestly): `CrawlCommand` itself (seed validation, exit codes, the new
warnings) has no automated test; `TextSink` and the `JsonLinesSink` escaping path are under-covered. These
are the first things I'd add (§9).

---

## 7. Known limitations & consciously deferred work

These are deliberate scope decisions for a time-boxed exercise, documented rather than hidden:

- **robots.txt is not enforced, and rate-limiting (`--delay`) is not implemented.** Both flags are accepted
  but only **emit a warning** — they never silently claim compliance (`--respect-robots` defaults to
  `false`). The designed approach is a cached `RobotsPolicy` consulted in `submit()` plus a per-host rate
  limiter; I started a naive `--delay` throttle and **reverted it** as overengineered and semantically muddy
  (a per-worker sleep is not a true inter-request interval, and it conflated the rate limit with the
  concurrency bound). The correct version is a clock-injectable limiter applied via a `ThrottledFetcher`
  decorator, keeping concurrency and rate orthogonal.
- **Response bodies are buffered fully into memory** (`HttpResponse.BodyHandlers.ofString()`), with no size
  cap, and non-HTML is downloaded before it is skipped — a very large or hostile response could waste
  bandwidth or exhaust memory. **Deferred by choice;** the fix is a bounded body subscriber that caps HTML
  and `discard`s non-HTML by `Content-Type`. (`--max-pages` already bounds request *count*.)
- **Static crawler:** no JavaScript execution, so JS-rendered links are missed (an accepted consequence of
  "no Playwright").
- **No retry/backoff** on transient `5xx`/timeouts; **no persistence/resumability** (a crawl is one
  in-memory run).

---

## 8. How I would extend it

- **Multiple domains at once.** Swap `HostScope` for a `Scope` backed by a *set* of hosts or registrable
  domains (Guava `InternetDomainName.topPrivateDomain()`). Give each host its own politeness budget and
  rate limiter, and partition the frontier per host so one slow site can't starve the others. The engine and
  interfaces don't change — only the `Scope` and per-host scheduling do.
- **Distributed / horizontally scaled.** Externalise the engine's two pieces of state behind `Frontier` and
  `VisitedSet` interfaces: a durable queue (SQS/Kafka/Redis) for the frontier and a shared store
  (Redis / a DB / a Bloom filter) for visited. Workers become stateless and scale out; the crawl becomes
  resumable and fault-tolerant. The current `ConcurrentCrawler` is essentially the single-node version of
  this.
- **Beyond the CLI.** A CLI is ideal for a one-off run but a poor fit once crawling is a *workload*: it's a
  long-running blocking process with no job lifecycle and output glued to stdout. Better: a **service / HTTP
  API** (`POST /crawls` → job id; `GET /crawls/{id}` → progress; results streamed to object storage or a
  topic), which gives concurrency across *jobs*, back-pressure, retries, and observability — i.e. a
  queue-based worker model.
- **Politeness at scale.** robots.txt fetch/cache + `Crawl-delay`, adaptive rate limiting, and `sitemap.xml`
  seeding for faster, more complete discovery.
- **Other:** optional JS rendering via a headless `Fetcher` behind the existing interface; depth/page/time
  limits and include/exclude filters; content de-duplication (hash bodies to skip mirror pages);
  observability (structured logs + metrics: pages/sec, error rates, frontier size).

---

## 9. What I'd refine with more time

Roughly in priority order:

1. **Close the functional gaps** above — `RobotsPolicy`, a proper per-host rate limiter, bounded response
   bodies, and retry-with-backoff on idempotent GETs.
2. **Test coverage** — a `CrawlCommand` test (seed validation, exit codes, warnings), `TextSink`, and the
   JSON-escaping path; plus a larger generated-graph concurrency stress test.
3. **Production hygiene** — CI (GitHub Actions: build + test + a coverage gate), formatting/linting
   (Spotless + Checkstyle) and dependency/vulnerability scanning, and a `Dockerfile`. (Guava is currently
   declared but unused in `src/`; it is **kept intentionally** for the eTLD+1 multi-domain extension in §8.)
4. **URL handling polish** — an optional trailing-slash rule driven by observed redirects and a
   `--strip-tracking-params` flag, to cut duplicate crawls without sacrificing default accuracy.



