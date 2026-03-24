# Localhost HTTP Server Test Checklist

## Build / Run
1. [ ] Server starts with config:
```bash
java -jar myserver.jar -c config.json
```
2. [ ] Listening on expected ports shown in logs.

## HTTP Server Core (Code Walkthrough)
1. [ ] Explain HTTP flow (request → parse → route → response).
2. [ ] Show I/O multiplexing in `src/com/server/core/HttpEngine.java`.
3. [ ] Confirm a single `Selector` loop handles read/write.
4. [ ] Explain why single select is important (performance + simplicity).
5. [ ] One read/write per client per select iteration.
6. [ ] I/O return values checked.
7. [ ] Socket errors remove the client.
8. [ ] Read/write only through select.

## Configuration File Tests
1. [ ] Single server, single port works.
2. [ ] Multiple servers with different ports work.
3. [ ] Different hostnames resolve correctly:
```bash
curl -i --resolve test.com:8080:127.0.0.1 http://test.com:8080/
```
4. [ ] Custom error pages:
```bash
curl -i http://127.0.0.1:8080/no-such-path
```
5. [ ] Client body limit enforced:
```bash
curl -i -X POST http://127.0.0.1:8080/uploads/big.txt --data-binary @bigfile.bin
```
6. [ ] Routes are applied correctly.
7. [ ] Default file served for directories.
8. [ ] Allowed methods enforced:
```bash
curl -i -X PUT http://127.0.0.1:8080/
```

## Methods & Cookies
1. [ ] GET works:
```bash
curl -i http://127.0.0.1:8080/
```
2. [ ] POST works:
```bash
curl -i -X POST http://127.0.0.1:8080/uploads/test.txt --data "hello"
```
3. [ ] DELETE works:
```bash
curl -i -X DELETE http://127.0.0.1:8080/uploads/test.txt
```
4. [ ] Wrong request does not crash server.
5. [ ] Upload + download not corrupted:
```bash
curl -i -X POST http://127.0.0.1:8080/uploads/test-upload.bin --data-binary @somefile.bin
curl -o downloaded.bin http://127.0.0.1:8080/uploads/test-upload.bin
cmp somefile.bin downloaded.bin
```
6. [ ] Session cookie present:
```bash
curl -i http://127.0.0.1:8080/
```

## Browser Interaction
1. [ ] Open in browser: `http://127.0.0.1:8080/`
2. [ ] Headers correct in DevTools.
3. [ ] Wrong URL handled:
```bash
http://127.0.0.1:8080/does-not-exist
```
4. [ ] Directory listing handled:
```bash
http://127.0.0.1:8080/uploads/
```
5. [ ] Redirect works:
```bash
curl -i http://127.0.0.1:8080/old
```

## CGI
1. [ ] GET CGI:
```bash
curl -i http://127.0.0.1:8080/cgi/hello.py
```
2. [ ] POST CGI:
```bash
curl -i -X POST http://127.0.0.1:8080/cgi/hello.py -d "hello=world"
```
3. [ ] Test script:
```bash
curl -i http://127.0.0.1:8080/cgi/test.py?name=codex
```

## Port Issues / Config Errors
1. [ ] Duplicate port in config detected as error.
2. [ ] Multiple servers with shared ports: bad config should not kill good servers.

## Siege / Stress Test
1. [ ] Run:
```bash
siege -b http://127.0.0.1:8080/
```
2. [ ] Availability >= 99.5%.
3. [ ] No hanging connections.

## General Extras
1. [ ] More than one CGI language (Python, C++, Perl).
2. [ ] Admin dashboard or metrics endpoint.
