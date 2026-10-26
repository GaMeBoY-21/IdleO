#!/usr/bin/env python3
"""A private registry for the end-to-end tests of renters' own images (ADR 0042): HTTP basic auth in front of the development
registry, so an image can only be read with a user name and a read-only token, as on GHCR or Docker Hub's private repositories.
Standard library only. Development stack only: plain HTTP, one fixed user.

    scripts/e2e/auth-registry.py LISTEN_PORT UPSTREAM_HOST:PORT USER TOKEN [BYTES_PER_SECOND]

With BYTES_PER_SECOND, a blob is sent that slowly, so a download can be made to run past a time limit.

It answers 401 with `WWW-Authenticate: Basic` to a request without the right credentials, and forwards the rest to the upstream
registry unchanged, streaming. It writes one line per request to standard output: the method, the path and the answer; never
a header, so the token can't reach a log through it.
"""
import base64
import http.client
import http.server
import socketserver
import sys
import time

LISTEN, UPSTREAM, USER, TOKEN = int(sys.argv[1]), sys.argv[2], sys.argv[3], sys.argv[4]
SLOW = int(sys.argv[5]) if len(sys.argv) > 5 else 0
EXPECTED = "Basic " + base64.b64encode(f"{USER}:{TOKEN}".encode()).decode()
HOP = {"connection", "keep-alive", "transfer-encoding", "te", "trailer", "upgrade", "proxy-authorization", "proxy-authenticate"}


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):  # no default log: it would name the client, and nothing here needs it
        pass

    def deny(self):
        self.send_response(401)
        self.send_header("WWW-Authenticate", 'Basic realm="idleo-test-registry"')
        self.send_header("Content-Length", "0")
        self.end_headers()
        print(f"{self.command} {self.path} 401", flush=True)

    def forward(self):
        if self.headers.get("Authorization") != EXPECTED:
            self.deny()
            return
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length) if length else None
        headers = {k: v for k, v in self.headers.items() if k.lower() not in HOP | {"authorization", "host"}}
        upstream = http.client.HTTPConnection(UPSTREAM, timeout=300)
        upstream.request(self.command, self.path, body=body, headers=headers)
        answer = upstream.getresponse()
        self.send_response(answer.status)
        for key, value in answer.getheaders():
            if key.lower() not in HOP | {"content-length"}:
                self.send_header(key, value)
        data = answer.read()
        # A HEAD has no body but reports the length the GET would have: containerd reads the size from it.
        self.send_header("Content-Length", answer.getheader("Content-Length", "0") if self.command == "HEAD" else str(len(data)))
        self.end_headers()
        if self.command == "HEAD":
            pass
        elif SLOW and "/blobs/" in self.path:
            for start in range(0, len(data), max(1, SLOW // 10)):
                self.wfile.write(data[start : start + max(1, SLOW // 10)])
                self.wfile.flush()
                time.sleep(0.1)
        else:
            self.wfile.write(data)
        print(f"{self.command} {self.path} {answer.status}", flush=True)

    do_GET = do_HEAD = do_POST = do_PUT = do_PATCH = do_DELETE = forward


class Server(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
    allow_reuse_address = True


if __name__ == "__main__":
    Server(("0.0.0.0", LISTEN), Handler).serve_forever()
