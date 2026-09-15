"""Small fixed-route gateway for one local Factory Pi run.

The gateway deliberately has no generic proxy behavior.  It exposes one model
route and one read-only Maven mirror route, keeps the upstream provider key in
this container, and writes non-secret request evidence to the mounted run log.
"""

from __future__ import annotations

import hashlib
import hmac
import json
import os
import posixpath
import re
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


MAX_BODY_BYTES = 8 * 1024 * 1024
MAX_MAVEN_ARTIFACT_BYTES = 64 * 1024 * 1024
MAX_MODEL_EVIDENCE_BYTES = 16 * 1024 * 1024
MAVEN_ROOTS = (
    "https://repo.maven.apache.org/maven2/",
    "https://repository.folio.org/repository/maven-folio/",
    "https://maven.indexdata.com/",
)
EXECUTION_TOKEN_PREFIX = "fx1"
EXECUTION_ID_PATTERN = re.compile(r"^[A-Za-z0-9-]{1,128}$")
OPERATOR_BUCKET = "operator"
ALLOWED_UPSTREAM_BASES = frozenset({
    "https://api.z.ai/api/paas/v4",
    "https://api.z.ai/api/coding/paas/v4",
})


def env_int(name: str, default: int) -> int:
    try:
        return int(os.environ.get(name, str(default)))
    except ValueError:
        return default


def execution_token(run_token: str, execution_id: str) -> str:
    """Execution-scoped model credential, as Factory derives it (PiWorker).

    The sandbox receives only this derived token, never the run token, so it
    cannot mint a token for another execution or reach another budget.
    """
    message = f"{EXECUTION_TOKEN_PREFIX}.{execution_id}"
    mac = hmac.new(run_token.encode("utf-8"), message.encode("utf-8"), hashlib.sha256).hexdigest()
    return f"{message}.{mac}"


def trusted_upstream_base(value: str) -> str:
    normalized = (value or "").rstrip("/")
    if normalized not in ALLOWED_UPSTREAM_BASES:
        raise ValueError("upstream base URL is not an approved Z.AI endpoint")
    return normalized


class GatewayState:
    def __init__(self) -> None:
        self.mode = os.environ.get("FACTORY_GATEWAY_MODE", "coding")
        if self.mode not in {"coding", "dependencies"}:
            raise ValueError("FACTORY_GATEWAY_MODE must be coding or dependencies")
        self.token = os.environ.get("FACTORY_RUN_TOKEN", "")
        self.api_key = os.environ.get("FACTORY_UPSTREAM_API_KEY", "")
        self.upstream_base = trusted_upstream_base(os.environ.get(
            "FACTORY_UPSTREAM_BASE_URL", "https://api.z.ai/api/paas/v4"
        ))
        self.expected_model = os.environ.get("FACTORY_EXPECTED_MODEL", "glm-5.3-flash")
        # Attempt budget and lifetime apply per Developer Flow execution: each
        # execution's budget starts with its own first model call, independent
        # of other executions and of how long the gateway process has run.
        self.max_attempts = env_int("FACTORY_MAX_ATTEMPTS", 40)
        self.execution_ttl = env_int("FACTORY_EXECUTION_TTL_SECONDS", 14400)
        self.max_output_tokens = env_int("FACTORY_MAX_OUTPUT_TOKENS", 16384)
        self.lock = threading.Lock()
        self.attempts = 0
        self.buckets: dict[str, dict] = {}
        self.log_lock = threading.Lock()
        self.log_path = os.path.join(
            os.environ.get("FACTORY_GATEWAY_EVIDENCE_DIR", "/evidence"),
            "gateway-requests.jsonl",
        )
        os.makedirs(os.path.dirname(self.log_path), exist_ok=True)

    def bucket_for(self, supplied: str) -> str | None:
        """The budget bucket an Authorization header authenticates, or None."""
        if not self.token or not supplied.startswith("Bearer "):
            return None
        credential = supplied[len("Bearer "):]
        # The run token itself stays on the Factory host (operator preflight).
        if hmac.compare_digest(credential, self.token):
            return OPERATOR_BUCKET
        parts = credential.split(".")
        if len(parts) != 3 or parts[0] != EXECUTION_TOKEN_PREFIX:
            return None
        if not EXECUTION_ID_PATTERN.match(parts[1]):
            return None
        if not hmac.compare_digest(credential, execution_token(self.token, parts[1])):
            return None
        return parts[1]

    def allowed(self, bucket: str) -> tuple[bool, str]:
        if not self.token:
            return False, "run token is not configured"
        now = time.time()
        with self.lock:
            state = self.buckets.setdefault(bucket, {"attempts": 0, "firstAt": now})
            if self.execution_ttl and now - state["firstAt"] >= self.execution_ttl:
                return False, "execution token expired"
            if state["attempts"] >= self.max_attempts:
                return False, "run attempt budget exhausted"
            state["attempts"] += 1
            self.attempts += 1
        return True, ""

    def record(self, entry: dict) -> None:
        with self.log_lock:
            try:
                with open(self.log_path, "a", encoding="utf-8") as stream:
                    stream.write(json.dumps(entry, separators=(",", ":")) + "\n")
            except OSError:
                # Provider calls must remain visible to the caller even if the
                # local evidence volume is temporarily unavailable.
                pass


STATE = GatewayState()


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


MAVEN_OPENER = urllib.request.build_opener(NoRedirect())
UPSTREAM_OPENER = urllib.request.build_opener(NoRedirect())


def safe_maven_path(path: str) -> str | None:
    prefix = "/maven/repository/"
    if not path.startswith(prefix):
        return None
    relative = urllib.parse.unquote(path[len(prefix) :])
    if not relative or "\\" in relative or "\x00" in relative:
        return None
    parts = relative.split("/")
    if any(part in ("", ".", "..") for part in parts):
        return None
    normalized = posixpath.normpath(relative)
    if normalized != relative or normalized.startswith("../"):
        return None
    return relative


def approved_maven_url(relative: str, root: str) -> str:
    return root + urllib.parse.quote(relative, safe="/._-+$")


def response_metadata(body: bytes | bytearray) -> tuple[object | None, object | None]:
    """Extract model and usage from JSON responses and OpenAI-style SSE chunks."""
    try:
        text = bytes(body).decode("utf-8")
    except UnicodeDecodeError:
        return None, None

    reported_model = None
    usage = None
    try:
        payload = json.loads(text)
    except json.JSONDecodeError:
        payload = None
    if isinstance(payload, dict):
        reported_model = payload.get("model")
        usage = payload.get("usage")

    for line in text.splitlines():
        if not line.startswith("data:"):
            continue
        data = line[5:].strip()
        if not data or data == "[DONE]":
            continue
        try:
            chunk = json.loads(data)
        except json.JSONDecodeError:
            continue
        if not isinstance(chunk, dict):
            continue
        if chunk.get("model") is not None:
            reported_model = chunk.get("model")
        if isinstance(chunk.get("usage"), dict):
            usage = chunk.get("usage")
    return reported_model, usage


def summarize_request(payload: dict, request_id: str) -> dict:
    messages = payload.get("messages")
    return {
        "requestId": request_id,
        "requestedModel": payload.get("model"),
        "messageCount": len(messages) if isinstance(messages, list) else None,
        "messageRoles": [message.get("role") for message in messages if isinstance(message, dict)]
        if isinstance(messages, list)
        else [],
        "hasTools": bool(payload.get("tools")),
        "toolNames": [
            tool.get("function", {}).get("name")
            for tool in payload.get("tools", [])
            if isinstance(tool, dict) and isinstance(tool.get("function"), dict)
        ] if isinstance(payload.get("tools"), list) else [],
        "stream": bool(payload.get("stream")),
        "maxTokens": payload.get("max_tokens"),
        "thinkingFields": sorted(
            key for key in payload.keys() if "think" in key.lower() or "reason" in key.lower()
        ),
    }


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *_args) -> None:
        return

    def do_GET(self) -> None:
        if self.path == "/health":
            self.send_json(200, {"status": "UP", "attempts": STATE.attempts,
                                 "executions": len(STATE.buckets)})
            return
        relative = safe_maven_path(self.path)
        if relative is not None:
            self.serve_maven(relative, head=False)
            return
        self.send_json(404, {"error": "fixed route not found"})

    def do_HEAD(self) -> None:
        relative = safe_maven_path(self.path)
        if relative is not None:
            self.serve_maven(relative, head=True)
            return
        self.send_response(404)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_POST(self) -> None:
        if self.path != "/v1/chat/completions":
            self.send_json(404, {"error": "fixed route not found"})
            return
        if STATE.mode != "coding":
            self.send_json(404, {"error": "model route is disabled on the dependency gateway"})
            return
        self.serve_model()

    def do_PUT(self) -> None:
        self.send_json(405, {"error": "write methods are not supported"})

    def do_PATCH(self) -> None:
        self.send_json(405, {"error": "write methods are not supported"})

    def do_DELETE(self) -> None:
        self.send_json(405, {"error": "write methods are not supported"})

    def do_CONNECT(self) -> None:
        self.send_json(405, {"error": "CONNECT is not supported"})

    def read_body(self) -> bytes:
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            raise ValueError("invalid Content-Length")
        if length < 0 or length > MAX_BODY_BYTES:
            raise ValueError("request body exceeds the gateway limit")
        return self.rfile.read(length)

    def authorized(self) -> str | None:
        bucket = STATE.bucket_for(self.headers.get("Authorization", ""))
        if bucket is None:
            self.send_json(401, {"error": "invalid or missing run token"})
        return bucket

    def serve_model(self) -> None:
        bucket = self.authorized()
        if bucket is None:
            return
        try:
            raw = self.read_body()
            payload = json.loads(raw.decode("utf-8"))
            if not isinstance(payload, dict):
                raise ValueError("request must be a JSON object")
        except (ValueError, UnicodeDecodeError, json.JSONDecodeError) as error:
            self.send_json(400, {"error": str(error)})
            return
        if payload.get("model") != STATE.expected_model:
            self.send_json(400, {"error": "requested model is not the configured model"})
            return
        if not STATE.api_key:
            self.send_json(503, {"error": "provider key is not configured in the gateway"})
            return
        max_tokens = payload.get("max_tokens")
        if "max_completion_tokens" in payload or type(max_tokens) is not int or max_tokens <= 0:
            self.send_json(400, {"error": "a positive integer max_tokens is required"})
            return
        if max_tokens > STATE.max_output_tokens:
            self.send_json(400, {"error": "requested output exceeds the run cap"})
            return
        allowed, reason = STATE.allowed(bucket)
        request_id = uuid.uuid4().hex
        if not allowed:
            self.send_json(429, {"error": reason})
            return

        started = time.time()
        entry = summarize_request(payload, request_id)
        entry.update({"kind": "model", "execution": bucket, "startedAt": started,
                      "requestSent": False})
        status = 502
        response_body = bytearray()
        response_bytes = 0
        response_truncated = False
        reported_model = None
        error_message = None
        try:
            request = urllib.request.Request(
                STATE.upstream_base + "/chat/completions",
                data=raw,
                method="POST",
                headers={
                    "Authorization": "Bearer " + STATE.api_key,
                    "Content-Type": "application/json",
                    "Accept": self.headers.get("Accept", "application/json"),
                    "User-Agent": "factory-fixed-gateway/1",
                },
            )
            entry["requestSent"] = True
            with UPSTREAM_OPENER.open(request, timeout=300) as upstream:
                status = upstream.status
                self.send_response(status)
                content_type = upstream.headers.get("Content-Type", "application/octet-stream")
                self.send_header("Content-Type", content_type)
                self.send_header("Cache-Control", "no-cache")
                self.send_header("Connection", "close")
                self.end_headers()
                while True:
                    chunk = upstream.read(8192)
                    if not chunk:
                        break
                    response_bytes += len(chunk)
                    if len(response_body) < MAX_MODEL_EVIDENCE_BYTES:
                        remaining = MAX_MODEL_EVIDENCE_BYTES - len(response_body)
                        response_body.extend(chunk[:remaining])
                    if response_bytes > MAX_MODEL_EVIDENCE_BYTES:
                        response_truncated = True
                    self.wfile.write(chunk)
                    self.wfile.flush()
        except urllib.error.HTTPError as error:
            status = error.code
            error_body = error.read(MAX_MODEL_EVIDENCE_BYTES + 1)
            response_bytes = len(error_body)
            response_truncated = response_bytes > MAX_MODEL_EVIDENCE_BYTES
            response_body = bytearray(error_body[:MAX_MODEL_EVIDENCE_BYTES])
            self.send_response(status)
            self.send_header("Content-Type", error.headers.get("Content-Type", "application/json"))
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(response_body)
        except (urllib.error.URLError, TimeoutError, OSError) as error:
            error_message = str(error)
            response_body = bytearray(json.dumps({"error": "upstream request failed"}).encode("utf-8"))
            response_bytes = len(response_body)
            self.send_json(502, {"error": "upstream request failed"})
        finally:
            reported_model, usage = response_metadata(response_body)
            entry.update(
                {
                    "endedAt": time.time(),
                    "durationMs": round((time.time() - started) * 1000),
                    "status": status,
                    "reportedModel": reported_model,
                    "usage": usage,
                    "responseBytes": response_bytes,
                    "responseTruncated": response_truncated,
                }
            )
            if error_message:
                entry["error"] = error_message[:512]
            STATE.record(entry)

    def serve_maven(self, relative: str, head: bool) -> None:
        for root in MAVEN_ROOTS:
            url = approved_maven_url(relative, root)
            try:
                request = urllib.request.Request(
                    url, method="HEAD" if head else "GET", headers={"User-Agent": "factory-maven-gateway/1"}
                )
                with MAVEN_OPENER.open(request, timeout=60) as upstream:
                    body = b"" if head else upstream.read(MAX_MAVEN_ARTIFACT_BYTES + 1)
                    if len(body) > MAX_MAVEN_ARTIFACT_BYTES:
                        self.send_json(502, {"error": "artifact exceeds gateway limit"})
                        return
                    self.send_response(upstream.status)
                    self.send_header("Content-Type", upstream.headers.get("Content-Type", "application/octet-stream"))
                    self.send_header("Content-Length", str(len(body)))
                    self.send_header("Cache-Control", "no-store")
                    self.end_headers()
                    if not head:
                        self.wfile.write(body)
                    return
            except urllib.error.HTTPError as error:
                if error.code != 404:
                    self.send_json(error.code, {"error": "approved Maven route failed"})
                    return
            except (urllib.error.URLError, TimeoutError, OSError):
                continue
        self.send_json(404, {"error": "artifact is not available from approved Maven roots"})

    def send_json(self, status: int, payload: dict) -> None:
        body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)


def main() -> None:
    server = ThreadingHTTPServer(("0.0.0.0", 8080), Handler)
    server.daemon_threads = True
    server.serve_forever()


if __name__ == "__main__":
    main()
