"""Hermetic regression tests for the fixed-route gateway (factory-gateway/server.py).

No network access and no provider key are required: the approved Maven roots are
repointed at a loopback-only file server inside this process, and the model
upstream is never contacted (authentication and mode checks reject earlier).

Regression origin (M3 evidence, run key low-002): the dependency gateway's
artifact limit rejected required 10-23 MiB FOLIO Maven artifacts, burning a live
attempt. These tests pin the corrected 64 MiB boundary and the dependency/model
route separation.
"""

from __future__ import annotations

import http.server
import os
import pathlib
import shutil
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


def make_state(mode: str, evidence_dir: pathlib.Path, token: str) -> server.GatewayState:
    previous = {
        name: os.environ.get(name)
        for name in ("FACTORY_GATEWAY_MODE", "FACTORY_RUN_TOKEN")
    }
    try:
        os.environ["FACTORY_GATEWAY_MODE"] = mode
        os.environ["FACTORY_RUN_TOKEN"] = token
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
            body=b'{"model": "glm-5.3-flash", "messages": [], "max_tokens": 8}',
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
            body=b'{"model": "glm-5.3-flash", "messages": [], "max_tokens": 8}',
        )
        self.assertEqual(status, 401)
        self.assertIn(b"invalid or missing run token", body)

    def test_wrong_token_is_rejected(self) -> None:
        """A revoked/rotated run token must not reach the provider upstream."""
        status, body = request(
            self.gateway.base + "/v1/chat/completions",
            token="revoked-or-stale-token",
            method="POST",
            body=b'{"model": "glm-5.3-flash", "messages": [], "max_tokens": 8}',
        )
        self.assertEqual(status, 401)
        self.assertIn(b"invalid or missing run token", body)

    def test_unapproved_upstream_base_is_refused_at_startup(self) -> None:
        with self.assertRaises(ValueError):
            server.trusted_upstream_base("https://attacker.example/api")


if __name__ == "__main__":
    unittest.main()
