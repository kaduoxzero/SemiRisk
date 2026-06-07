import mimetypes
import os
import posixpath
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


STATIC_ROOT = Path(os.getenv("STATIC_ROOT", "/usr/share/nginx/html")).resolve()
UPSTREAM_BASE = os.getenv("UPSTREAM_BASE", "http://127.0.0.1:8080").rstrip("/")
SERVER_PORT = int(os.getenv("SERVER_PORT", "80"))


class FrontendHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self):
        self.route()

    def do_HEAD(self):
        self.route(head_only=True)

    def do_POST(self):
        self.proxy_api()

    def do_PUT(self):
        self.proxy_api()

    def do_DELETE(self):
        self.proxy_api()

    def do_PATCH(self):
        self.proxy_api()

    def route(self, head_only=False):
        parsed = urllib.parse.urlsplit(self.path)
        if parsed.path.startswith("/prod-api/"):
            self.proxy_api()
            return
        self.serve_static(parsed.path, head_only)

    def serve_static(self, request_path, head_only=False):
        normalized = posixpath.normpath(urllib.parse.unquote(request_path)).lstrip("/")
        candidate = (STATIC_ROOT / normalized).resolve()
        if not str(candidate).startswith(str(STATIC_ROOT)) or not candidate.is_file():
            candidate = STATIC_ROOT / "index.html"
        content_type = mimetypes.guess_type(candidate.name)[0] or "application/octet-stream"
        data = b"" if head_only else candidate.read_bytes()
        length = candidate.stat().st_size
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(length))
        self.send_header("Cache-Control", "no-cache" if candidate.name == "index.html" else "public, max-age=31536000")
        self.end_headers()
        if not head_only:
            self.wfile.write(data)

    def proxy_api(self):
        parsed = urllib.parse.urlsplit(self.path)
        upstream_path = "/" + parsed.path[len("/prod-api/"):]
        target = UPSTREAM_BASE + upstream_path
        if parsed.query:
            target += "?" + parsed.query

        body = None
        if self.command in {"POST", "PUT", "PATCH", "DELETE"}:
            length = int(self.headers.get("Content-Length", "0") or "0")
            body = self.rfile.read(length) if length else None

        headers = {
            key: value
            for key, value in self.headers.items()
            if key.lower() not in {"host", "connection", "content-length", "accept-encoding"}
        }
        if body is not None and "Content-Type" in self.headers:
            headers["Content-Type"] = self.headers["Content-Type"]

        request = urllib.request.Request(target, data=body, headers=headers, method=self.command)
        try:
            with urllib.request.urlopen(request, timeout=120) as response:
                payload = response.read()
                self.send_response(response.status)
                self.copy_response_headers(response.headers, len(payload))
                self.end_headers()
                self.wfile.write(payload)
        except urllib.error.HTTPError as exc:
            payload = exc.read()
            self.send_response(exc.code)
            self.copy_response_headers(exc.headers, len(payload))
            self.end_headers()
            self.wfile.write(payload)
        except Exception as exc:
            payload = f'{{"code":502,"msg":"Gateway proxy failed: {exc}"}}'.encode("utf-8")
            self.send_response(502)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

    def copy_response_headers(self, headers, length):
        blocked = {"connection", "transfer-encoding", "content-encoding", "content-length"}
        for key, value in headers.items():
            if key.lower() not in blocked:
                self.send_header(key, value)
        self.send_header("Content-Length", str(length))


if __name__ == "__main__":
    mimetypes.add_type("application/javascript", ".js")
    mimetypes.add_type("text/css", ".css")
    server = ThreadingHTTPServer(("0.0.0.0", SERVER_PORT), FrontendHandler)
    server.serve_forever()
