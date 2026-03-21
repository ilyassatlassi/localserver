#!/usr/bin/env python3
import os
import sys

# Read body
content_length = int(os.environ.get('CONTENT_LENGTH', 0))
body = sys.stdin.read(content_length) if content_length > 0 else "no body"

# Write response
print("Status: 201 Created\r")
print("Content-Type: text/plain\r")
print("X-Custom-Header: CGI-Works\r")
print("\r")
print("Hello from Python CGI!")
print(f"Method: {os.environ.get('REQUEST_METHOD')}")
print(f"Path: {os.environ.get('PATH_INFO')}")
print(f"Body: {body}")
