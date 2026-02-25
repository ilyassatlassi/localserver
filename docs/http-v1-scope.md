# HTTP v1 Scope (Initial)

## Methods
- `GET`, `POST`, `DELETE`

## Request Parsing
- Request line: method, path, version
- Headers: simple key/value parsing
- Body: only when `Content-Length` is present (no chunked yet)

## Response
- Status line + headers + body
- Basic `Allow` header on `405 Method Not Allowed`

## Routing
- Exact match or prefix match on path segment boundary
- Longest matching route wins

## Errors / Status Codes
- `400`, `403`, `404`, `405`, `413`, `500`

## Limits
- Client body size enforced by `clientBodyLimitBytes` in config
