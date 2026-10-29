"""Gateway test fixture only. Never packaged in an application image."""

import base64
import hashlib
import json
import os
import struct
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *_):
        pass

    def reply(self, status, body=b""):
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path = self.path.split("?")[0]
        internal = path == "/api/v1/internal" or path.startswith("/api/v1/internal/")
        if internal != (self.server.server_port == 8081):
            self.reply(404)
            return
        if internal:
            self.reply(
                200 if self.headers.get("X-Internal-Api-Key") == "fixture-key" else 401
            )
            return
        if path.endswith("/jwks.json"):
            if os.path.exists("/tmp/disable-jwks"):
                self.reply(503)
                return
            with open("/tmp/jwks.json", "rb") as source:
                self.reply(200, source.read())
        elif self.headers.get("Upgrade", "").lower() == "websocket":
            accept = base64.b64encode(
                hashlib.sha1(
                    (
                        self.headers["Sec-WebSocket-Key"]
                        + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
                    ).encode()
                ).digest()
            ).decode()
            self.send_response(101)
            self.send_header("Upgrade", "websocket")
            self.send_header("Connection", "Upgrade")
            self.send_header("Sec-WebSocket-Accept", accept)
            self.end_headers()
            while True:
                first = self.rfile.read(2)
                if len(first) != 2:
                    break
                opcode = first[0] & 15
                length = first[1] & 127
                if length == 126:
                    length = struct.unpack("!H", self.rfile.read(2))[0]
                elif length == 127:
                    length = struct.unpack("!Q", self.rfile.read(8))[0]
                if length > 65536:
                    break
                mask = self.rfile.read(4) if first[1] & 128 else b"\0" * 4
                data = self.rfile.read(length)
                data = bytes(v ^ mask[i % 4] for i, v in enumerate(data))
                if opcode == 8:
                    break
                self.wfile.write(
                    bytes([0x80 | (10 if opcode == 9 else opcode), len(data)]) + data
                )
                self.wfile.flush()
            self.close_connection = True
        else:
            self.reply(
                200,
                json.dumps(
                    {
                        "headers": dict(self.headers),
                        "path": path,
                        "port": self.server.server_port,
                    }
                ).encode(),
            )

    def do_PUT(self):
        length = int(self.headers.get("Content-Length", "0"))
        received = 0
        while received < length:
            chunk = self.rfile.read(min(1024 * 1024, length - received))
            if not chunk:
                break
            received += len(chunk)
        self.reply(200, json.dumps({"received": received}).encode())

    do_POST = do_PUT


if __name__ == "__main__":
    for port in (8080, 8081):
        threading.Thread(
            target=ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever,
            daemon=True,
        ).start()
    threading.Event().wait()
