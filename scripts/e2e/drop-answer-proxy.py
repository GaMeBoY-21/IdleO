"""A proxy for the end-to-end run that loses the API's answer to a booking: every request goes on to the API as it
is, but the answers to the first DROPS `POST /v1/sessions` are read and thrown away, and the connection is closed, as
if the network had failed after the request arrived. So the booking happens and the client never hears of it.

    drop-answer-proxy.py PORT API_URL DROPS COUNT_FILE

COUNT_FILE gets the number of answers dropped so far, for the script to read.
"""

from __future__ import annotations

import http.client
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

PORT, API, DROPS, COUNT_FILE = int(sys.argv[1]), urlsplit(sys.argv[2]), int(sys.argv[3]), sys.argv[4]
dropped = 0
lock = threading.Lock()
HOP_BY_HOP = {"connection", "keep-alive", "transfer-encoding", "te", "trailer", "upgrade", "proxy-connection"}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, format: str, *args: object) -> None:  # noqa: A002 - the base class's name
        pass

    def forward(self) -> None:
        global dropped
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length) if length else None
        headers = {k: v for k, v in self.headers.items() if k.lower() not in HOP_BY_HOP | {"host"}}
        upstream = http.client.HTTPConnection(API.hostname, API.port or 80, timeout=120)
        upstream.request(self.command, self.path, body=body, headers=headers)
        answer = upstream.getresponse()
        data = answer.read()
        upstream.close()
        if self.command == "POST" and self.path.split("?")[0] == "/v1/sessions":
            with lock:
                if dropped < DROPS:
                    dropped += 1
                    with open(COUNT_FILE, "w") as out:
                        out.write(str(dropped))
                    # The API has booked it; the client hears nothing.
                    self.close_connection = True
                    return
        self.send_response(answer.status)
        for key, value in answer.getheaders():
            if key.lower() not in HOP_BY_HOP | {"content-length"}:
                self.send_header(key, value)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    do_GET = do_POST = do_PUT = do_PATCH = do_DELETE = forward


ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
