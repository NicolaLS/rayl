#!/usr/bin/env python3
"""HTTP access to bin/fixture for Maestro flows, whose scripts can only make HTTP requests.

POST /fixture {"args": ["user", "5000"]} runs `bin/fixture user 5000` and returns its JSON
(or 500 with its error). Listens on 127.0.0.1 only; started and stopped by bin/up and bin/down.
"""
import json
import os
import subprocess
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

FIXTURE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "fixture")


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        self.reply(200, {"ok": True}) if self.path == "/health" else self.reply(404, {})

    def do_POST(self):
        if self.path != "/fixture":
            return self.reply(404, {"error": "POST /fixture"})
        length = int(self.headers.get("content-length", "0"))
        args = json.loads(self.rfile.read(length) or b"{}").get("args", [])
        if not all(isinstance(arg, str) for arg in args):
            return self.reply(400, {"error": "args must be strings"})
        result = subprocess.run([FIXTURE, *args], capture_output=True, text=True, timeout=180)
        if result.returncode != 0:
            return self.reply(500, {"error": result.stderr.strip()})
        self.reply(200, json.loads(result.stdout))

    def reply(self, status, body):
        raw = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("content-type", "application/json")
        self.send_header("content-length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1])), Handler).serve_forever()
