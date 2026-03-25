# localserver

A compact, educational HTTP/1.1 server in Java built around a single `Selector` event loop. It focuses on clarity and correctness while supporting practical features like static files, uploads, CGI, redirects, and custom error pages.

## Features
- Non-blocking I/O with a single `Selector` loop.
- HTTP/1.1 request parsing with Host validation, `Content-Length`, and chunked bodies.
- GET, POST, DELETE.
- Static file serving, directory listing (`autoIndex`), and per-route roots.
- File uploads and DELETE for stored files.
- CGI execution (e.g., Python) via per-route mapping.
- Route-based redirects.
- Per-server body size limits and custom error pages.
- Multiple virtual servers, including shared ports with host-based routing.

## Quick Start
Prerequisites: JDK 11+ (or a recent JDK with `javac`/`java` available).

Build:
```bash
make build
```

Run with the default config:
```bash
make run
```

Run with a specific config:
```bash
java -cp out com.server.core.Main -c config.json
```

## Configuration
Configuration is JSON. Two example configs are included:
- `config.json` for a single server with multiple routes.
- `config-shared-port.json` for two virtual servers on the same port.

Key fields:
- `servers[]` top-level list of servers.
- `name`, `host`, `ports[]`, `default`.
- `clientBodyLimitBytes`.
- `errorPages` mapping HTTP code to file path.
- `routes[]`:
  - `path`, `methods[]`, `root`, `index`, `autoIndex`.
  - `uploadDir` for upload routes.
  - `cgi` mapping extension to interpreter path.
  - `redirect` with `status` and `to`.

## Example Requests
Static files:
```bash
curl -i http://127.0.0.1:8080/
```

Upload:
```bash
curl -i -X POST http://127.0.0.1:8080/uploads/test.txt --data "hello"
```

Delete:
```bash
curl -i -X DELETE http://127.0.0.1:8080/uploads/test.txt
```

CGI:
```bash
curl -i http://127.0.0.1:8080/cgi/hello.py
```

## Project Layout
```
src/
  com/server/core/        Entry point and event loop
  com/server/config/      Config parsing and models
  com/server/http/        HTTP parsing and protocol logic
  com/server/handlers/    Static, upload, and CGI handlers
  com/server/utils/       Logging and error utilities
www/                      Default static site
uploads/                  Upload destination
cgi-bin/                  CGI scripts
error_pages/              Custom error responses
```

## Testing
See `TESTS.md` for a manual test checklist and curl examples.

## Notes
- The main entry point is `com.server.core.Main`.
- The Makefile’s `run` target uses `config-shared-port.json` by default. Update `CONFIG` in `Makefile` if you want to change the default config.

