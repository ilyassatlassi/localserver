# Change Report - Shared Ports Without Threads

## Goal of This Change

Implement support for multiple server configurations at the same time, including common/shared ports, without running one thread per config.

Main requirement addressed:

- If one configuration is invalid, other valid configurations should still start and work.
- If one bind fails, it should not stop all listeners.

---

## High-Level Architecture Change

Before:

- `Main` started one `HttpEngine` thread per `ServerConfig`.
- Each engine tried to bind its own ports.
- Shared ports between server blocks caused bind conflicts.

After:

- `Main` starts **one** `HttpEngine` with all server configs.
- `HttpEngine` runs one `Selector` event loop.
- `HttpEngine` deduplicates `(host, port)` binds and binds each address once.
- Request routing is done per request using:
  - socket local port
  - HTTP `Host` header

This is closer to virtual hosting behavior.

---

## Files Changed

- `src/com/server/core/Main.java`
- `src/com/server/core/HttpEngine.java`
- `src/com/server/config/ConfigParser.java`

---

## Detailed Changes

## 1) `Main.java`

### What changed

- Removed thread-per-server startup logic.
- Replaced it with a single call:
  - `new HttpEngine(root.getServers()).start();`

### Why

- Avoids creating one event loop per config.
- Allows one central engine to manage shared ports and request-based server selection.

### Current flow in `Main`

1. Read `-c <config-file>`.
2. Parse config into `ConfigRoot`.
3. Start one engine with all parsed server blocks.

---

## 2) `ConfigParser.java`

### What changed in `parseServers`

- Wrapped each individual server-block parse in `try/catch`.
- On `ConfigException`, log and skip that block:
  - `"[ConfigParser] Skipping invalid server config: ..."`

### Why

- A single bad server block should not fail the whole startup.
- This directly supports the audit requirement: partial config validity.

### Behavior

- If all server blocks are invalid, parse still fails later (`servers.isEmpty()` check).
- If at least one server is valid, server starts with valid subset.

---

## 3) `HttpEngine.java`

This file contains the core behavior changes.

## Constructor Changes

Old constructor:

- `HttpEngine(ServerConfig server)`

New constructor:

- `HttpEngine(List<ServerConfig> servers)`

### New fields

- `servers`: immutable list of all server configs
- `processors`: `ServerConfig -> RequestProcessor`
- `serversByPort`: `port -> list of ServerConfig`

### Why these fields exist

- `processors`: each virtual server keeps its own config-based response behavior (routes, error pages, limits).
- `serversByPort`: quickly find candidate server blocks for the incoming socket port.

### Body-limit parser setup

- Parser is built with the maximum body limit across configs.
- Then per-request, final body-limit check is done against selected server config.

Reason:

- Parsing happens before target server is known.
- Selection happens after request parse and local port inspection.

---

## `start()` changes

### What changed

- It now binds all unique addresses from all server configs.
- Uses `uniqueBindAddresses()` to avoid duplicate bind attempts.
- Tracks successful binds with `boundCount`.

### Why `boundCount` is used

`boundCount` tells whether at least one listener is alive.

- If `boundCount > 0`: server can run.
- If `boundCount == 0`: no socket is listening, so running event loop makes no sense.
  - Throws `RuntimeException("No valid listener could be started")`.

Without `boundCount`, the process could appear "running" but accept nothing.

### Bind error isolation

- Bind failures are caught per address and logged.
- The engine continues trying other addresses.

This means one bad port/host bind does not kill all listeners.

---

## Per-request processing flow (new behavior)

Inside `processAllCompleteRequests(...)`:

1. Parse request from connection buffer.
2. If parse error: build error response using fallback processor and close connection.
3. If valid request:
   - get local port from client socket (`resolveLocalPort`)
   - choose target server config (`selectServer`)
   - pick matching `RequestProcessor`
   - enforce selected server body limit
   - execute handler and enqueue serialized response
4. If connection must close, mark it and stop request loop.

This is the key change that makes one engine support multiple server configs.

---

## New/Updated Helper Functions in `HttpEngine`

## `uniqueBindAddresses()`

### What it does

- Builds a deduplicated list of `(host, port)` from all server configs.

### Why

- Prevent duplicate bind calls for same address.
- Needed when multiple server blocks intentionally share same listener.

---

## `selectServer(Request request, int localPort)`

### What it does

- Gets server candidates by local port.
- Reads normalized `Host` header.
- Tries to match candidate by:
  - `server.name` (virtual hostname style), or
  - `server.host` (IP/host style)
- Fallback order:
  1. default server on that port
  2. first candidate
  3. first global server (if no candidates)

### Why

- Implements virtual-server selection for shared ports.
- Ensures deterministic fallback when no exact host match.

---

## `resolveLocalPort(SocketChannel client)`

### What it does

- Reads local socket address and extracts port.
- Returns `-1` when unavailable.

### Why

- Same engine can listen on many ports; routing starts by identifying which port this request came through.

---

## `normalizeHostHeader(String hostHeader)`

### What it does

- Trims and lowercases host header.
- Removes `:port` suffix if present.

Example:

- `test.com:8080` -> `test.com`

### Why

- Host matching should be stable regardless of explicit port in header.

---

## `BindAddress` (inner class)

### What it does

- Represents bind key `(host, port)`.
- Implements `equals/hashCode` so it works in `LinkedHashSet`.

### Why

- Needed for reliable deduplication in `uniqueBindAddresses()`.

---

## Existing functions kept (same role)

- `handleAccept`: accept and register new non-blocking client.
- `handleRead`: read once per ready event and parse requests.
- `readIntoState`: one non-blocking read call; appends bytes.
- `handleWrite`: one write call per ready event; handles partial writes.
- `closeTimedOutConnections`: close idle clients safely.
- `closeConnection`: close channel and cancel key.

These preserve the one-selector non-blocking design.

---

## Why this meets your requested behavior

- No thread-per-config startup.
- One bad server config is skipped at parse time.
- One bad bind does not kill all listeners.
- Shared ports are supported by request-time host selection.

---

## Notes / Current Limitations

- Host matching currently compares against `server.name` or `server.host`.
  - For best results, set `server.name` to your virtual hostname (example: `test.com`).
- Parser body limit is initialized with max limit, then stricter per-server check is applied after server selection.
- If you want stricter config validation (for example duplicate server names per port), this can be added later.

---

## Quick Example Config Idea (Matches `config-shared-port.json`)

Two servers on the same port:

- server A: `name = "site-a.local"`, `ports = [8080]`, `root = "www_site_a"`
- server B: `name = "site-b.local"`, `ports = [8080]`, `root = "www_site_b"`

Then test:

- `curl --resolve site-a.local:8080:127.0.0.1 http://site-a.local:8080/`
- `curl --resolve site-b.local:8080:127.0.0.1 http://site-b.local:8080/`

Expected: different server configs selected on the same bound port.
