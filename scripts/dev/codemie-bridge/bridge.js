/**
 * Local bridge between the Podman VM and the CodeMie proxy daemon.
 *
 *   coding container --> VM relay :4102 --> VM sshd forward :4101 --> this bridge (Windows 127.0.0.1:4101)
 *                                                                  --> CodeMie proxy daemon (127.0.0.1:4001)
 *
 * Security properties:
 * - The container receives only a generated client key, never the SSO session or the
 *   daemon's own key.
 * - Binds one narrow address, never a wildcard.
 * - Keys arrive by environment variable, never as command arguments.
 * - Requests are streamed, never buffered, so SSE responses stay incremental.
 * - Only method, path and status are logged. Never headers, bodies, or keys.
 */

import http from 'node:http';
import { timingSafeEqual } from 'node:crypto';

const BIND_HOST = process.env.BRIDGE_HOST;
const BIND_PORT = Number.parseInt(process.env.BRIDGE_PORT ?? '', 10);
const UPSTREAM_HOST = process.env.BRIDGE_UPSTREAM_HOST ?? '127.0.0.1';
const UPSTREAM_PORT = Number.parseInt(process.env.BRIDGE_UPSTREAM_PORT ?? '', 10);
const CLIENT_KEY = process.env.BRIDGE_CLIENT_KEY;
const UPSTREAM_KEY = process.env.BRIDGE_UPSTREAM_KEY;

for (const [name, value] of Object.entries({
  BRIDGE_HOST: BIND_HOST,
  BRIDGE_PORT: BIND_PORT,
  BRIDGE_UPSTREAM_PORT: UPSTREAM_PORT,
  BRIDGE_CLIENT_KEY: CLIENT_KEY,
  BRIDGE_UPSTREAM_KEY: UPSTREAM_KEY,
})) {
  if (value === undefined || value === '' || Number.isNaN(value)) {
    process.stderr.write(`[bridge] missing or invalid env var: ${name}\n`);
    process.exit(1);
  }
}

if (BIND_HOST === '0.0.0.0' || BIND_HOST === '::') {
  process.stderr.write('[bridge] refusing to bind a wildcard address\n');
  process.exit(1);
}

const expected = Buffer.from(`Bearer ${CLIENT_KEY}`);

function authorized(header) {
  if (typeof header !== 'string') return false;
  const given = Buffer.from(header);
  // timingSafeEqual throws on length mismatch, so compare lengths first.
  return given.length === expected.length && timingSafeEqual(given, expected);
}

const server = http.createServer((req, res) => {
  const label = `${req.method} ${req.url}`;

  if (!authorized(req.headers['authorization'])) {
    console.log(`[bridge] 401 ${label}`);
    res.writeHead(401, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({
      type: 'error',
      error: { type: 'authentication_error', message: 'Invalid API key' },
    }));
    return;
  }

  // Swap the client key for the daemon's key. The daemon strips this header in turn and
  // injects the real SSO credential.
  const headers = { ...req.headers, authorization: `Bearer ${UPSTREAM_KEY}` };
  delete headers['x-api-key'];
  headers.host = `${UPSTREAM_HOST}:${UPSTREAM_PORT}`;

  const upstream = http.request(
    { host: UPSTREAM_HOST, port: UPSTREAM_PORT, method: req.method, path: req.url, headers },
    (upstreamRes) => {
      console.log(`[bridge] ${upstreamRes.statusCode} ${label}`);
      res.writeHead(upstreamRes.statusCode ?? 502, upstreamRes.headers);
      upstreamRes.pipe(res);
    },
  );

  upstream.on('error', (err) => {
    console.log(`[bridge] 502 ${label} (${err.code ?? 'upstream error'})`);
    if (!res.headersSent) res.writeHead(502, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({
      type: 'error',
      error: { type: 'api_error', message: 'Bridge could not reach the CodeMie proxy' },
    }));
  });

  req.pipe(upstream);
});

// SSE responses must stay open; do not let Node time them out.
server.timeout = 0;
server.headersTimeout = 0;
server.requestTimeout = 0;

server.listen(BIND_PORT, BIND_HOST, () => {
  console.log(`[bridge] listening on ${BIND_HOST}:${BIND_PORT} -> ${UPSTREAM_HOST}:${UPSTREAM_PORT}`);
});
