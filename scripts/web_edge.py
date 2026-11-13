"""Stand-in for the web container on a host without Docker: serves the production Angular build and applies the
security headers and /api proxy rules READ FROM deploy/docker/nginx.conf, so the CSP being tested is the one that
ships. Usage: python3 -I scripts/web_edge.py <port> <gateway-url>"""
import http.server, pathlib, re, socketserver, sys, urllib.error, urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
DIST = ROOT / "railops-web/dist/railops-web/browser"
CONF = (ROOT / "deploy/docker/nginx.conf").read_text()
server_block = CONF[CONF.index("server {"):]
# server-level add_header lines (before the first location): applied to every response, as nginx inherits them
head = server_block[:re.search(r"\n\s*location\s", server_block).start()]   # the directive, not "geolocation"
HEADERS = re.findall(r'add_header\s+([\w-]+)\s+"([^"]*)"\s+always;', head)
PORT, GATEWAY = int(sys.argv[1]), sys.argv[2]
HOP = {"connection", "keep-alive", "transfer-encoding", "te", "trailer", "upgrade", "proxy-authorization", "host",
       "forwarded", "x-forwarded-for", "x-forwarded-proto", "content-length"}


class Edge(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def send(self, status, body=b"", headers=()):
        self.send_response(status)
        for k, v in HEADERS:
            self.send_header(k, v)
        for k, v in headers:
            self.send_header(k, v)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def proxy(self):
        n = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(n) if n else None
        req = urllib.request.Request(GATEWAY + self.path, data=body, method=self.command)
        for k, v in self.headers.items():
            if k.lower() not in HOP:
                req.add_header(k, v)
        prior = self.headers.get("X-Forwarded-For")                       # nginx: $proxy_add_x_forwarded_for
        req.add_header("X-Forwarded-For", (prior + ", " if prior else "") + self.client_address[0])
        req.add_header("X-Forwarded-Proto", "http")
        opener = urllib.request.build_opener(urllib.request.HTTPRedirectHandler())
        opener.handlers = [h for h in opener.handlers if not isinstance(h, urllib.request.HTTPRedirectHandler)]
        try:
            r = urllib.request.urlopen(req, timeout=15)
            status, hdrs, data = r.status, r.headers, r.read()
        except urllib.error.HTTPError as e:
            status, hdrs, data = e.code, e.headers, e.read()
        self.send(status, data, [(k, v) for k, v in hdrs.items() if k.lower() not in HOP and k.lower() != "content-length"])

    def static(self):
        path = self.path.split("?")[0]
        if path == "/healthz":
            return self.send(200, b"ok\n", [("Content-Type", "text/plain")])
        f = (DIST / path.lstrip("/")).resolve()
        if DIST in f.parents and f.is_file():
            ctype = {".js": "text/javascript", ".css": "text/css", ".ico": "image/x-icon", ".html": "text/html"}.get(f.suffix, "application/octet-stream")
            cache = "public, max-age=31536000, immutable" if f.suffix in (".js", ".css") else "no-store"
            return self.send(200, f.read_bytes(), [("Content-Type", ctype), ("Cache-Control", cache)])
        self.send(200, (DIST / "index.html").read_bytes(), [("Content-Type", "text/html"), ("Cache-Control", "no-store")])

    def handle_any(self):
        if self.path.startswith("/api/") or self.path == "/.well-known/jwks.json":
            return self.proxy()
        if self.command in ("GET", "HEAD"):
            return self.static()
        self.send(405)

    do_GET = do_POST = do_PUT = do_DELETE = do_PATCH = do_HEAD = handle_any

    def log_message(self, *a):
        pass


class Server(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True


print(f"web_edge on :{PORT} -> {GATEWAY}; {len(HEADERS)} security headers from nginx.conf", flush=True)
Server(("127.0.0.1", PORT), Edge).serve_forever()
