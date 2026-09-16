"""Hermetic regression tests for the fixed-route gateway (factory-gateway/server.py).

No network access and no provider key are required: the approved Maven roots are
repointed at a loopback-only file server inside this process, and the model
upstream is replaced by an in-process fake opener that records what would be sent.

Regression origin (M3 evidence, run key low-002): the dependency gateway's
artifact limit rejected required 10-23 MiB FOLIO Maven artifacts, burning a live
attempt. These tests pin the corrected 64 MiB boundary and the dependency/model
route separation.
"""

from __future__ import annotations

import http.server
import json
import os
import pathlib
import shutil
import socket
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.request

REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO_ROOT / "factory-gateway"))

# Keep the import-time GatewayState evidence inside the test sandbox; the
# production default (/evidence) exists only inside the gateway container.
_TEST_EVIDENCE = pathlib.Path(tempfile.mkdtemp(prefix="factory-gateway-import."))
os.environ.setdefault("FACTORY_GATEWAY_EVIDENCE_DIR", str(_TEST_EVIDENCE))
# The coding gateway refuses to start without trusted upstream configuration.
TRUSTED_UPSTREAM = "https://upstream.example.test/api/v1"
UPSTREAM_MODEL = "upstream-model-x"
os.environ.setdefault("FACTORY_UPSTREAM_BASE_URL", TRUSTED_UPSTREAM)
os.environ.setdefault("FACTORY_UPSTREAM_MODEL", UPSTREAM_MODEL)

import server  # noqa: E402  (module under test, path set above)

SMALL = 512 * 1024          # ordinary small artifact
REGRESSION = 23 * 1024 * 1024  # largest observed required FOLIO artifact class
OVER_LIMIT = 64 * 1024 * 1024 + 1  # just past the corrected gateway cap


class ArtifactServer:
    """Loopback read-only file server standing in for the approved Maven roots."""

    def __init__(self, root: pathlib.Path) -> None:
        self.root = root
        handler = lambda *args, **kwargs: http.server.SimpleHTTPRequestHandler(
            *args, directory=str(root), **kwargs
        )
        self.httpd = http.server.ThreadingHTTPServer(("127.0.0.1", 0), handler)
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)

    @property
    def url(self) -> str:
        host, port = self.httpd.server_address
        return f"http://{host}:{port}/maven2/"

    def start(self) -> None:
        self.thread.start()

    def stop(self) -> None:
        self.httpd.shutdown()
        self.httpd.server_close()


class StatefulGateway:
    """Runs the production Handler against an injected GatewayState on loopback."""

    def __init__(self, state: server.GatewayState) -> None:
        self._previous = server.STATE
        server.STATE = state
        self.httpd = http.server.ThreadingHTTPServer(("127.0.0.1", 0), server.Handler)
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)

    @property
    def base(self) -> str:
        host, port = self.httpd.server_address
        return f"http://{host}:{port}"

    def start(self) -> None:
        self.thread.start()

    def stop(self) -> None:
        self.httpd.shutdown()
        self.httpd.server_close()
        server.STATE = self._previous


UPSTREAM_ENV = ("FACTORY_UPSTREAM_BASE_URL", "FACTORY_UPSTREAM_MODEL", "FACTORY_MODEL_ALIAS")


def make_state(mode: str, evidence_dir: pathlib.Path, token: str,
               upstream: dict[str, str] | None = None) -> server.GatewayState:
    """Build a GatewayState; ``upstream`` replaces the whole upstream environment."""
    names = ("FACTORY_GATEWAY_MODE", "FACTORY_RUN_TOKEN") + UPSTREAM_ENV
    previous = {name: os.environ.get(name) for name in names}
    try:
        os.environ["FACTORY_GATEWAY_MODE"] = mode
        os.environ["FACTORY_RUN_TOKEN"] = token
        if upstream is not None:
            for name in UPSTREAM_ENV:
                os.environ.pop(name, None)
            os.environ.update(upstream)
        state = server.GatewayState()
    finally:
        for name, value in previous.items():
            if value is None:
                os.environ.pop(name, None)
            else:
                os.environ[name] = value
    # Keep evidence inside the test sandbox instead of the container's /evidence.
    state.log_path = str(evidence_dir / f"gateway-{mode}.jsonl")
    return state


def request(url: str, token: str | None = None, method: str = "GET", body: bytes | None = None):
    req = urllib.request.Request(url, data=body, method=method)
    if token is not None:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=120) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.read()


class GatewayArtifactLimitTest(unittest.TestCase):
    """Dependency-gateway behavior that low-001/low-002 demonstrated in M3."""

    RUN_TOKEN = "unit-test-run-token"

    @classmethod
    def setUpClass(cls) -> None:
        cls.tmp = pathlib.Path(tempfile.mkdtemp(prefix="factory-gateway-test."))
        # The loopback root mirrors an approved upstream layout: the file
        # server hosts the root and /maven2/ is the repository prefix.
        artifacts = cls.tmp / "maven2" / "group" / "artifact"
        artifacts.mkdir(parents=True)
        for name, size in (
            ("small.jar", SMALL),
            ("regression-size.jar", REGRESSION),
            ("over-limit.jar", OVER_LIMIT),
        ):
            path = artifacts / name
            with open(path, "wb") as stream:
                stream.truncate(size)
        cls.files = artifacts
        cls.upstream = ArtifactServer(cls.tmp)
        cls.upstream.start()
        cls.saved_roots = server.MAVEN_ROOTS
        server.MAVEN_ROOTS = (cls.upstream.url,)
        cls.gateway = StatefulGateway(
            make_state("dependencies", cls.tmp / "evidence", cls.RUN_TOKEN)
        )
        cls.gateway.start()

    @classmethod
    def tearDownClass(cls) -> None:
        cls.gateway.stop()
        cls.upstream.stop()
        server.MAVEN_ROOTS = cls.saved_roots
        shutil.rmtree(cls.tmp, ignore_errors=True)

    @classmethod
    def url_for(cls, name: str) -> str:
        return cls.gateway.base + "/maven/repository/group/artifact/" + name

    def test_small_artifact_is_served(self) -> None:
        status, body = request(self.url_for("small.jar"))
        self.assertEqual(status, 200)
        self.assertEqual(len(body), SMALL)

    def test_required_folio_sized_artifact_passes(self) -> None:
        """The 10-23 MiB artifact class that burned live attempt low-002."""
        status, body = request(self.url_for("regression-size.jar"))
        self.assertEqual(status, 200)
        self.assertEqual(len(body), REGRESSION)

    def test_artifact_past_the_cap_is_rejected(self) -> None:
        status, body = request(self.url_for("over-limit.jar"))
        self.assertEqual(status, 502)
        self.assertIn(b"artifact exceeds gateway limit", body)

    def test_head_is_served(self) -> None:
        status, body = request(self.url_for("small.jar"), method="HEAD")
        self.assertEqual(status, 200)
        self.assertEqual(body, b"")

    def test_model_route_is_disabled_on_dependency_gateway(self) -> None:
        """No provider credentials exist on the dependency gateway at all."""
        status, body = request(
            self.gateway.base + "/v1/chat/completions",
            token=self.RUN_TOKEN,
            method="POST",
            body=b'{"model": "factory-coding", "messages": [], "max_tokens": 8}',
        )
        self.assertEqual(status, 404)
        self.assertIn(b"model route is disabled", body)

    def test_unapproved_maven_root_is_never_used(self) -> None:
        """The production roots stay fixed; tests must repoint them explicitly."""
        self.assertEqual(
            server.MAVEN_ROOTS,
            (self.upstream.url,),
            "test isolation: only the injected loopback root is active",
        )
        # The real defaults are the three approved HTTPS roots.
        self.assertTrue(all(root.startswith("https://") for root in self.saved_roots))

    def test_path_traversal_is_rejected(self) -> None:
        status, _ = request(
            self.gateway.base + "/maven/repository/../../etc/passwd"
        )
        self.assertEqual(status, 404)


class GatewayCodingAuthTest(unittest.TestCase):
    """Coding-gateway request gating that runs before any upstream contact."""

    RUN_TOKEN = "coding-run-token"

    @classmethod
    def setUpClass(cls) -> None:
        cls.tmp = pathlib.Path(tempfile.mkdtemp(prefix="factory-gateway-coding."))
        cls.gateway = StatefulGateway(
            make_state("coding", cls.tmp / "evidence", cls.RUN_TOKEN)
        )
        cls.gateway.start()

    @classmethod
    def tearDownClass(cls) -> None:
        cls.gateway.stop()
        shutil.rmtree(cls.tmp, ignore_errors=True)

    def test_missing_token_is_rejected(self) -> None:
        status, body = request(
            self.gateway.base + "/v1/chat/completions",
            method="POST",
            body=b'{"model": "factory-coding", "messages": [], "max_tokens": 8}',
        )
        self.assertEqual(status, 401)
        self.assertIn(b"invalid or missing run token", body)

    def test_wrong_token_is_rejected(self) -> None:
        """A revoked/rotated run token must not reach the provider upstream."""
        status, body = request(
            self.gateway.base + "/v1/chat/completions",
            token="revoked-or-stale-token",
            method="POST",
            body=b'{"model": "factory-coding", "messages": [], "max_tokens": 8}',
        )
        self.assertEqual(status, 401)
        self.assertIn(b"invalid or missing run token", body)

    def test_untrusted_upstream_configuration_is_refused_at_startup(self) -> None:
        for base in (
            "",
            "http://upstream.example.test/v1",
            "https://user:secret@upstream.example.test/v1",
            "https://token@upstream.example.test/v1",
            "https://upstream.example.test/v1?target=https://attacker.example",
            "https://upstream.example.test/v1#fragment",
            "file:///etc/passwd",
            "https:///v1",
        ):
            with self.subTest(base=base), self.assertRaises(ValueError):
                make_state("coding", self.tmp / "evidence", self.RUN_TOKEN,
                           {"FACTORY_UPSTREAM_BASE_URL": base,
                            "FACTORY_UPSTREAM_MODEL": UPSTREAM_MODEL})
        with self.assertRaises(ValueError):
            make_state("coding", self.tmp / "evidence", self.RUN_TOKEN,
                       {"FACTORY_UPSTREAM_BASE_URL": TRUSTED_UPSTREAM})

    def test_trusted_upstream_configuration_is_accepted(self) -> None:
        state = make_state("coding", self.tmp / "evidence", self.RUN_TOKEN,
                           {"FACTORY_UPSTREAM_BASE_URL": "https://llm.example.test:8443/v4/",
                            "FACTORY_UPSTREAM_MODEL": UPSTREAM_MODEL})
        self.assertEqual(state.upstream_base, "https://llm.example.test:8443/v4")
        self.assertEqual(state.model_alias, "factory-coding")
        self.assertEqual(state.upstream_model, UPSTREAM_MODEL)

    def test_dependency_gateway_needs_no_upstream_configuration(self) -> None:
        state = make_state("dependencies", self.tmp / "evidence", self.RUN_TOKEN, {})
        self.assertEqual(state.upstream_base, "")
        self.assertEqual(state.upstream_model, "")

    def test_upstream_opener_does_not_follow_redirects(self) -> None:
        self.assertTrue(any(isinstance(handler, server.NoRedirect)
                            for handler in server.UPSTREAM_OPENER.handlers))
        redirect = server.NoRedirect().redirect_request(
            urllib.request.Request(TRUSTED_UPSTREAM + "/chat/completions"), None, 302,
            "Found", {}, "https://attacker.example/steal")
        self.assertIsNone(redirect)


class FakeUpstreamResponse:
    status = 200
    headers = {"Content-Type": "application/json"}

    def __init__(self) -> None:
        self.body = [b'{"model":"upstream-model-x","choices":[{"message":{"content":"ok"}}]}', b""]

    def read(self, _size: int = -1) -> bytes:
        return self.body.pop(0) if self.body else b""

    def __enter__(self):
        return self

    def __exit__(self, *_args) -> None:
        return None


class FakeUpstreamOpener:
    """Stands in for the provider so budget checks can be exercised without a network call."""

    def __init__(self) -> None:
        self.calls = 0
        self.requests: list[urllib.request.Request] = []
        self.lock = threading.Lock()

    def open(self, request, timeout=None):
        with self.lock:
            self.calls += 1
            self.requests.append(request)
        return FakeUpstreamResponse()


class GatewayExecutionBudgetTest(unittest.TestCase):
    """Each Developer Flow execution has its own attempt budget and lifetime."""

    RUN_TOKEN = "budget-run-token"
    BODY = b'{"model": "factory-coding", "messages": [], "max_tokens": 8}'

    def setUp(self) -> None:
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix="factory-gateway-budget."))
        state = make_state("coding", self.tmp / "evidence", self.RUN_TOKEN)
        state.api_key = "provider-key-stays-in-gateway"
        state.max_attempts = 2
        self.state = state
        self.saved_opener = server.UPSTREAM_OPENER
        self.upstream = FakeUpstreamOpener()
        server.UPSTREAM_OPENER = self.upstream
        self.gateway = StatefulGateway(state)
        self.gateway.start()

    def tearDown(self) -> None:
        self.gateway.stop()
        server.UPSTREAM_OPENER = self.saved_opener
        shutil.rmtree(self.tmp, ignore_errors=True)

    def call(self, token: str):
        return request(self.gateway.base + "/v1/chat/completions", token=token,
                       method="POST", body=self.BODY)

    def test_two_executions_do_not_share_the_attempt_counter(self) -> None:
        first = server.execution_token(self.RUN_TOKEN, "execution-a")
        second = server.execution_token(self.RUN_TOKEN, "execution-b")

        self.assertEqual(self.call(first)[0], 200)
        self.assertEqual(self.call(first)[0], 200)
        status, body = self.call(first)
        self.assertEqual(status, 429)
        self.assertIn(b"run attempt budget exhausted", body)

        # A later execution does not inherit the exhausted budget.
        self.assertEqual(self.call(second)[0], 200)
        self.assertEqual(self.call(second)[0], 200)
        self.assertEqual(self.call(second)[0], 429)
        self.assertEqual(self.upstream.calls, 4)

    def test_parallel_executions_each_get_their_full_budget(self) -> None:
        tokens = [server.execution_token(self.RUN_TOKEN, f"parallel-{i}") for i in range(4)]
        results: dict[str, list[int]] = {token: [] for token in tokens}

        def spend(token: str) -> None:
            for _ in range(3):
                results[token].append(self.call(token)[0])

        threads = [threading.Thread(target=spend, args=(token,)) for token in tokens]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join()

        for token in tokens:
            self.assertEqual(sorted(results[token]), [200, 200, 429])

    def test_forged_or_foreign_execution_tokens_are_rejected(self) -> None:
        genuine = server.execution_token(self.RUN_TOKEN, "execution-a")
        forged = genuine[:-1] + ("0" if genuine[-1] != "0" else "1")
        for token in (
            forged,
            server.execution_token("another-run-token", "execution-a"),
            "fx1.execution-a",
            "fx1.bad id." + "0" * 64,
        ):
            status, body = self.call(token)
            self.assertEqual(status, 401, token)
            self.assertIn(b"invalid or missing run token", body)
        self.assertEqual(self.upstream.calls, 0)

    def test_lifetime_starts_at_the_executions_first_call_not_gateway_start(self) -> None:
        self.state.execution_ttl = 3600
        paused = server.execution_token(self.RUN_TOKEN, "resumed-after-decision")
        old = server.execution_token(self.RUN_TOKEN, "old-execution")
        self.assertEqual(self.call(old)[0], 200)
        # Simulate a gateway that has run far longer than the lifetime and an
        # execution whose own first call was more than the lifetime ago.
        with self.state.lock:
            self.state.buckets["old-execution"]["firstAt"] -= 7200

        status, body = self.call(old)
        self.assertEqual(status, 429)
        self.assertIn(b"execution token expired", body)
        # An execution resumed after a long pause starts its own lifetime now.
        self.assertEqual(self.call(paused)[0], 200)

    def test_run_token_is_accepted_only_as_the_operator_bucket(self) -> None:
        self.assertEqual(self.call(self.RUN_TOKEN)[0], 200)
        self.assertEqual(self.call(self.RUN_TOKEN)[0], 200)
        self.assertEqual(self.call(self.RUN_TOKEN)[0], 429)
        self.assertEqual(self.call(server.execution_token(self.RUN_TOKEN, "execution-a"))[0], 200)


class GatewayModelAliasTest(unittest.TestCase):
    """The sandbox names only the alias; the gateway owns the upstream model and URL."""

    RUN_TOKEN = "alias-run-token"

    def setUp(self) -> None:
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix="factory-gateway-alias."))
        (self.tmp / "evidence").mkdir()
        state = make_state("coding", self.tmp / "evidence", self.RUN_TOKEN,
                           {"FACTORY_UPSTREAM_BASE_URL": TRUSTED_UPSTREAM,
                            "FACTORY_UPSTREAM_MODEL": UPSTREAM_MODEL})
        state.api_key = "provider-key-stays-in-gateway"
        self.state = state
        self.saved_opener = server.UPSTREAM_OPENER
        self.upstream = FakeUpstreamOpener()
        server.UPSTREAM_OPENER = self.upstream
        self.gateway = StatefulGateway(state)
        self.gateway.start()
        self.token = server.execution_token(self.RUN_TOKEN, "alias-execution")

    def tearDown(self) -> None:
        self.gateway.stop()
        server.UPSTREAM_OPENER = self.saved_opener
        shutil.rmtree(self.tmp, ignore_errors=True)

    def post(self, payload: dict, path: str = "/v1/chat/completions",
             headers: dict[str, str] | None = None):
        req = urllib.request.Request(self.gateway.base + path,
                                     data=json.dumps(payload).encode("utf-8"), method="POST")
        req.add_header("Authorization", f"Bearer {self.token}")
        for name, value in (headers or {}).items():
            req.add_header(name, value)
        try:
            with urllib.request.urlopen(req, timeout=60) as response:
                return response.status, response.read()
        except urllib.error.HTTPError as error:
            return error.code, error.read()

    def evidence(self) -> list[dict]:
        with open(self.state.log_path, encoding="utf-8") as stream:
            return [json.loads(line) for line in stream]

    def test_alias_is_rewritten_to_the_configured_upstream_model(self) -> None:
        status, _ = self.post({"model": "factory-coding", "max_tokens": 8, "stream": False,
                               "messages": [{"role": "user", "content": "hi"}]})
        self.assertEqual(status, 200)
        self.assertEqual(self.upstream.calls, 1)
        sent = self.upstream.requests[0]
        self.assertEqual(sent.full_url, TRUSTED_UPSTREAM + "/chat/completions")
        body = json.loads(sent.data)
        self.assertEqual(body["model"], UPSTREAM_MODEL)
        self.assertEqual(body["messages"], [{"role": "user", "content": "hi"}])
        self.assertEqual(sent.get_header("Authorization"), "Bearer provider-key-stays-in-gateway")
        [entry] = self.evidence()
        self.assertEqual(entry["requestedModel"], "factory-coding")
        self.assertEqual(entry["upstreamModel"], UPSTREAM_MODEL)
        self.assertEqual(entry["reportedModel"], UPSTREAM_MODEL)
        self.assertTrue(entry["requestSent"])

    def test_arbitrary_model_names_are_rejected_without_an_upstream_call(self) -> None:
        for model in (UPSTREAM_MODEL, "gpt-4o", "Factory-Coding", "factory-coding ", "", None, 7):
            with self.subTest(model=model):
                status, body = self.post({"model": model, "max_tokens": 8, "messages": []})
                self.assertEqual(status, 400)
                self.assertIn(b"not the configured model alias", body)
        status, _ = self.post({"max_tokens": 8, "messages": []})
        self.assertEqual(status, 400)
        self.assertEqual(self.upstream.calls, 0)
        self.assertEqual(self.state.attempts, 0)

    def test_request_supplied_upstream_urls_cannot_change_the_trusted_upstream(self) -> None:
        payload = {
            "model": "factory-coding", "max_tokens": 8, "messages": [],
            "base_url": "https://attacker.example/v1",
            "api_base": "https://attacker.example/v1",
            "baseUrl": "https://attacker.example/v1",
            "url": "https://attacker.example/v1/chat/completions",
        }
        status, _ = self.post(payload, headers={
            "Host": "attacker.example",
            "X-Forwarded-Host": "attacker.example",
            "X-Upstream-Base-Url": "https://attacker.example/v1",
        })
        self.assertEqual(status, 200)
        status, _ = self.post({"model": "factory-coding", "max_tokens": 8, "messages": []},
                              path="/v1/chat/completions?base_url=https://attacker.example")
        self.assertEqual(status, 404)
        self.assertEqual(self.upstream.calls, 1)
        sent = self.upstream.requests[0]
        self.assertEqual(sent.full_url, TRUSTED_UPSTREAM + "/chat/completions")
        self.assertEqual(sent.host, "upstream.example.test")
        self.assertNotIn("attacker", " ".join(f"{k}: {v}" for k, v in sent.header_items()))
        self.assertEqual(json.loads(sent.data)["model"], UPSTREAM_MODEL)

    def test_absolute_form_request_target_is_not_proxied(self) -> None:
        with socket.create_connection(self.gateway.httpd.server_address, timeout=10) as conn:
            body = json.dumps({"model": "factory-coding", "max_tokens": 8, "messages": []})
            conn.sendall((
                "POST https://attacker.example/v1/chat/completions HTTP/1.1\r\n"
                "Host: attacker.example\r\n"
                f"Authorization: Bearer {self.token}\r\n"
                "Content-Type: application/json\r\n"
                f"Content-Length: {len(body)}\r\n\r\n{body}"
            ).encode("utf-8"))
            status_line = conn.recv(64).split(b"\r\n", 1)[0]
        self.assertIn(b" 404 ", status_line)
        self.assertEqual(self.upstream.calls, 0)


if __name__ == "__main__":
    unittest.main()
