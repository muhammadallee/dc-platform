#!/usr/bin/env python3
"""A downstream for the hardening phase-06 scenario. It answers as scripted and records what actually arrived.

    python -I stub.py <port-file>

POST /__script {"default": {...}, "queue": [{...}, ...]} sets the answers: the queue first, then the default. An answer has:
status, body, body_size (that many bytes of "x"), headers, delay_ms, drop (close after reading the request), trickle_ms
(one byte at a time, this long apart).
GET /__requests lists what arrived, and resets nothing. POST /__reset forgets it.

Authorization is recorded only as a hash, so a token never reaches the evidence.
"""
import hashlib
import json
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LOCK = threading.Lock()
REQUESTS = []
SCRIPT = {"default": {"status": 200, "body": "hello"}, "queue": []}
SENT = {"bytes": 0}
CHUNK = b"x" * 65536


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def _json(self, value):
        data = json.dumps(value).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _control(self, length):
        if self.path == "/__requests":
            with LOCK:
                self._json({"requests": REQUESTS, "sent_bytes": SENT["bytes"]})
        elif self.path == "/__reset":
            with LOCK:
                REQUESTS.clear()
                SENT["bytes"] = 0
            self._json({})
        elif self.path == "/__script":
            script = json.loads(self.rfile.read(length) or b"{}")
            with LOCK:
                SCRIPT["default"] = script.get("default", {"status": 200, "body": "hello"})
                SCRIPT["queue"] = script.get("queue", [])
            self._json({})

    def _answer(self):
        length = int(self.headers.get("Content-Length") or 0)
        if self.path.startswith("/__"):
            self._control(length)
            return
        body = self.rfile.read(length) if length else b""
        headers = {k.lower(): v for k, v in self.headers.items()}
        auth = headers.pop("authorization", None)
        record = {"method": self.command, "path": self.path, "headers": headers, "body_length": len(body),
                  "auth": hashlib.sha256(auth.encode()).hexdigest()[:16] if auth else None}
        with LOCK:
            REQUESTS.append(record)
            answer = SCRIPT["queue"].pop(0) if SCRIPT["queue"] else SCRIPT["default"]
        time.sleep(answer.get("delay_ms", 0) / 1000)
        if answer.get("drop"):
            self.close_connection = True
            return
        size = answer.get("body_size")
        data = answer.get("body", "").encode()
        self.send_response(answer.get("status", 200))
        for name, value in answer.get("headers", {}).items():
            self.send_header(name, value)
        self.send_header("Content-Length", str(size if size is not None else len(data)))
        self.end_headers()
        try:
            if size is not None:
                remaining = size
                while remaining > 0:
                    chunk = CHUNK[:min(remaining, len(CHUNK))]
                    self.wfile.write(chunk)
                    self.wfile.flush()
                    remaining -= len(chunk)
                    with LOCK:
                        SENT["bytes"] += len(chunk)
            elif answer.get("trickle_ms"):
                for index in range(len(data)):
                    self.wfile.write(data[index:index + 1])
                    self.wfile.flush()
                    time.sleep(answer["trickle_ms"] / 1000)
            else:
                self.wfile.write(data)
        except (BrokenPipeError, ConnectionError):
            self.close_connection = True

    do_GET = do_POST = do_PUT = do_PATCH = do_DELETE = do_HEAD = do_OPTIONS = _answer


def main():
    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    server.daemon_threads = True
    with open(sys.argv[1] + ".tmp", "w") as out:
        out.write(str(server.server_address[1]))
    import os
    os.replace(sys.argv[1] + ".tmp", sys.argv[1])
    server.serve_forever()


if __name__ == "__main__":
    main()
