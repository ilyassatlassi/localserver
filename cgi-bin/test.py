#!/usr/bin/env python3
import os
import sys
import json

# Read body (if any)
content_length = int(os.environ.get("CONTENT_LENGTH", "0") or "0")
body = sys.stdin.read(content_length) if content_length > 0 else ""

payload = {
    "message": "Hello from test.py",
    "method": os.environ.get("REQUEST_METHOD"),
    "path": os.environ.get("PATH_INFO"),
    "query": os.environ.get("QUERY_STRING"),
    "content_length": content_length,
    "body": body,
}

print("Status: 200 OK\r")
print("Content-Type: application/json\r")
print("\r")
print(json.dumps(payload, indent=2))
