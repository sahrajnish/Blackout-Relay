#!/usr/bin/env python3
"""Minimal S3-compatible PUT/GET mock for M2 testing (not for production).

Usage: python3 tools/mock_s3.py <port> <root-dir>
"""
import os
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = sys.argv[2] if len(sys.argv) > 2 else "/tmp/mock_s3"
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 9000


class Handler(BaseHTTPRequestHandler):
    def do_PUT(self):
        n = int(self.headers.get("Content-Length", 0))
        data = self.rfile.read(n)
        path = self.path.split("?")[0].lstrip("/")
        fp = os.path.join(ROOT, path)
        os.makedirs(os.path.dirname(fp), exist_ok=True)
        if os.path.exists(fp):
            self.send_response(412)
            self.end_headers()
            return
        with open(fp, "wb") as f:
            f.write(data)
        print(f"PUT {path} ({len(data)} bytes)", flush=True)
        self.send_response(200)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_GET(self):
        path = self.path.split("?")[0].lstrip("/")
        fp = os.path.join(ROOT, path)
        if os.path.exists(fp):
            data = open(fp, "rb").read()
            self.send_response(200)
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
        else:
            self.send_response(404)
            self.end_headers()

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    print(f"mock S3 on 0.0.0.0:{PORT}, root={ROOT}", flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
