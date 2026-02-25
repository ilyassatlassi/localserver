# LocalServer Learning Roadmap (Step-by-Step)

This roadmap turns the project into small, practical tasks so you can build confidence progressively.

---

## Start Here (Your first task right now)

If you want one concrete task to begin immediately, do this:

### Starter Task #1: Add 405 Method Not Allowed handling
**Goal:** If a route exists but the HTTP method is not in its allowed methods, return `405 Method Not Allowed`.

**Why start here:**
- Small, focused, and high-value behavior.
- Helps you practice config reading + routing + response status correctness.
- Easy to test quickly with `curl`.

**Implementation checklist:**
1. In routing/protocol logic, detect when path matches but method is not permitted.
2. Return status `405` and a proper error body/page.
3. Add `Allow` header listing valid methods for that route (good HTTP practice).
4. Keep existing behavior for unknown path (`404`) unchanged.

**Manual test plan (copy/paste):**
1. Configure a route that allows only `GET`.
2. Send a `POST` request to that route and expect `405`.
3. Send a `GET` request to the same route and expect `200`.

**Success criteria:**
- POST -> `405`
- GET -> `200`
- Server stays responsive after repeated invalid requests

---

## How to use this roadmap

- Complete one step at a time.
- Do **not** move to the next step until the current step has:
  - working code,
  - at least one test/check,
  - notes on what you learned.
- Keep a `NOTES.md` log with bugs, fixes, and lessons.

---

## Phase 0 — Foundation (1 day)

### Step 0.1: Understand the current code layout
**Task:** Read `Main`, `HttpEngine`, and config classes.

**Why:** You need to know where the event loop, parsing, and routing logic belong.

**Done when:** You can explain the request lifecycle from socket accept -> parse -> route -> response.

### Step 0.2: Define your HTTP scope (v1)
**Task:** Write a tiny scope document (`docs/http-v1-scope.md`) with only required features for a first pass.

**Why:** Prevents overengineering and keeps learning focused.

**Done when:** Scope includes GET/POST/DELETE, basic error pages, simple routing, and config loading.

### Step 0.3: Setup repeatable local checks
**Task:** Add scripts/commands for build + run + smoke test.

**Why:** Fast feedback loops are critical when building protocol code.

**Done when:** One command compiles and one command sends a test request.

---

## Phase 1 — Core HTTP Engine (2–3 days)

### Step 1.1: Non-blocking server loop
**Task:** Ensure Selector-based event loop accepts connections and reads/writes without blocking.

**Why:** This is the backbone of the project requirement (single process/thread + event-driven I/O).

**Mini-checks:**
- Start server and open multiple browser tabs.
- Confirm connections are handled without freezing.

### Step 1.2: Request line + headers parser
**Task:** Parse method, path, version, and headers manually.

**Why:** You must understand HTTP format instead of using frameworks.

**Mini-checks:**
- Valid request returns parsed fields.
- Invalid first line returns 400.

### Step 1.3: Response builder
**Task:** Build status line, headers, body serialization.

**Why:** Correct response formatting is required for browser compatibility.

**Mini-checks:**
- Return 200 text response.
- Return 404 with error page file.

### Step 1.4: Connection timeout and cleanup
**Task:** Add idle/request timeout logic and close stale sockets.

**Why:** “Never crashes” depends on robust cleanup.

**Mini-checks:**
- Simulate slow client; connection should timeout cleanly.

---

## Phase 2 — Routing + Static Content (2 days)

### Step 2.1: Parse configuration file
**Task:** Load host, ports, routes, methods, root directories, error pages, and body size limit.

**Why:** Config-driven behavior is a key requirement.

**Mini-checks:**
- Invalid config fails startup with clear message.
- Valid config boots all listed ports.

### Step 2.2: Route matching
**Task:** Match URL path to configured route and method permissions.

**Why:** Enables proper 404/405 behavior and future CGI integration.

**Mini-checks:**
- Unknown route -> 404.
- Method not allowed -> 405.

### Step 2.3: Static file + directory index behavior
**Task:** Serve files from route roots and optional index file.

**Why:** Core web-server behavior.

**Mini-checks:**
- Existing file serves 200.
- Directory without index and listing disabled -> 403.

### Step 2.4: Error pages
**Task:** Add default/custom pages for 400, 403, 404, 405, 413, 500.

**Why:** Requirement + better debug UX.

---

## Phase 3 — Request Bodies, Uploads, and Methods (2–3 days)

### Step 3.1: POST body support (unchunked)
**Task:** Read body using `Content-Length` and enforce max body size.

**Why:** Needed for uploads and API-style POST.

**Mini-checks:**
- Small payload accepted.
- Oversized payload returns 413.

### Step 3.2: Chunked transfer decoding
**Task:** Implement chunk parser (`Transfer-Encoding: chunked`).

**Why:** Mandatory protocol requirement.

**Mini-checks:**
- Chunked request reconstructs exact body.
- Bad chunk format -> 400.

### Step 3.3: File upload endpoint
**Task:** Save uploaded body to configured directory.

**Why:** Required project feature and good file I/O practice.

### Step 3.4: DELETE support
**Task:** Delete target resource (under allowed route root only).

**Why:** Required method + path safety practice.

**Mini-checks:**
- Successful deletion -> 200/204.
- Missing file -> 404.

---

## Phase 4 — Cookies, Sessions, CGI (2–3 days)

### Step 4.1: Cookie parser + Set-Cookie
**Task:** Parse incoming cookies and send `Set-Cookie` headers.

**Why:** Base for session management.

### Step 4.2: In-memory session store
**Task:** Map `session_id -> state` with expiration.

**Why:** Required feature and teaches state handling in single-thread server.

### Step 4.3: CGI execution by extension
**Task:** For one extension (e.g., `.py`), execute script via `ProcessBuilder`.

**Why:** Dynamic content requirement.

**Mini-checks:**
- CGI GET works.
- CGI POST body forwarded to stdin until EOF.
- PATH_INFO set correctly.

### Step 4.4: CGI safety
**Task:** Add execution timeout + input/path sanitization.

**Why:** Prevent hangs and security issues.

---

## Phase 5 — Reliability and Stress (2 days)

### Step 5.1: Crash-proofing pass
**Task:** Audit all parsing and I/O paths for unchecked exceptions.

**Why:** Requirement says server should never crash.

### Step 5.2: Stress test with siege
**Task:** Run repeated stress tests and track availability.

**Why:** Target availability ~99.5% during audits.

### Step 5.3: Regression test suite
**Task:** Build test matrix: good/bad config, methods, errors, upload, CGI, redirects.

**Why:** Lets you prove behavior in audit quickly.

### Step 5.4: Memory/resource checks
**Task:** Validate no FD leaks, no runaway memory.

**Why:** Production readiness and project requirement.

---

## Suggested learning cadence (example)

- Week 1: Phase 0 + 1
- Week 2: Phase 2 + 3
- Week 3: Phase 4 + 5

---

## Scalable Project Track (after MVP)

If your goal is to learn “how real servers scale,” add these as separate epics.

### Epic S1: Architecture scalability
- Introduce clear layers: `transport`, `http`, `routing`, `handlers`, `storage`.
- Add interface boundaries so each module can be tested independently.

### Epic S2: Performance scalability
- Add keep-alive connection reuse.
- Implement zero-copy/static file optimizations where possible.
- Add simple in-memory cache with TTL for static assets.

### Epic S3: Operational scalability
- Structured logs (JSON), request IDs, latency metrics.
- Health endpoint and lightweight metrics endpoint.
- Config validation report at startup.

### Epic S4: Security scalability
- Path traversal protections.
- Upload type/size restrictions and safe upload dir permissions.
- Basic rate limiting per client IP.

### Epic S5: Product scalability
- Virtual host support refinement (multiple server blocks).
- Better routing precedence and redirect rules.
- Optional admin panel for route/config diagnostics.

---

## Deliverables checklist for audit readiness

- [ ] Server runs with one process and one thread.
- [ ] Non-blocking event-driven reads/writes.
- [ ] GET/POST/DELETE supported.
- [ ] Chunked + unchunked request handling.
- [ ] Uploads and body size limits.
- [ ] Cookies and sessions.
- [ ] One CGI implementation.
- [ ] Default/custom error pages for required status codes.
- [ ] Stress test evidence and test scripts.
- [ ] No crash/leak evidence in test report.

---

## Quick “next task” starter list (if you feel stuck)

1. Implement/verify `Content-Length` body parsing.
2. Add 405 logic for route-level method restrictions.
3. Add 413 logic from config body size limit.
4. Add timeout cleanup for half-open connections.
5. Build one CGI demo endpoint with `.py`.

