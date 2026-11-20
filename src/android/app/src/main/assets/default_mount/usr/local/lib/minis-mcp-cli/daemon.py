#!/usr/bin/env python3
"""daemon.py — the forked, long-lived background process behind minis-mcp-cli.

Responsibilities:
  1. Listen on 127.0.0.1 loopback TCP, accept CLI connections (thread-per-conn).
  2. Maintain an MCPPool that owns each MCP server child process / HTTP session
     and reuses it across calls (no per-call spawn/kill).
  3. Each server has an independent 10-minute idle TTL; an idle server is killed.
  4. Once the pool is empty for a grace period the daemon exits on its own.

IPC is 127.0.0.1 loopback TCP, NOT a Unix-domain socket: iSH's fakefs cannot
create an AF_UNIX socket inode (bind() -> EPERM), so the daemon binds an
ephemeral 127.0.0.1 port and publishes it in a port file the CLI reads.

The daemon writes nothing to stdout (stdio is redirected to /dev/null by the
forking parent in main.py); all diagnostics go to the daemon log file. STDIO MCP
servers are held by their proc.stdin/proc.stdout fds directly — no FIFO, no
intermediate files. Each child gets ONE resident reader thread (started at
spawn) that readline()s stdout and dispatches replies by msg["id"] to
per-request queues; a timeout only abandons the waiter, never the reader, so a
stale per-request thread can never steal a later reply (signal.alarm is
unreliable under iSH/PRoot). Child stderr is drained into a ring buffer and
attached to crash errors. Shared iOS (iSH) and Android (PRoot); keep both
copies byte-identical.
"""

import collections
import json
import logging
import os
import queue
import secrets
import signal
import socket
import subprocess
import threading
import time

# The daemon is launched as a fresh interpreter (fork of the CLI) whose cwd /
# sys.path may not include the package dir, so anchor imports explicitly.
import sys
sys.path.insert(0, "/usr/local/lib/minis-mcp-cli")

from transport.http import HTTPTransport, MCPError, expand_env  # noqa: E402
from utils import config  # noqa: E402
from utils import deps  # noqa: E402

TTL_SECONDS = 600          # per-server idle TTL: 10 minutes
WATCHDOG_INTERVAL = 30     # TTL scan cadence
DAEMON_EXIT_GRACE = 60     # after the pool is empty, wait this long before exit
RPC_TIMEOUT = 300.0        # per-RPC reply timeout (matches transport modules)
CONN_TIMEOUT = 310.0       # socket recv timeout (slightly above RPC_TIMEOUT)
STDERR_RING_LINES = 40     # child stderr lines kept for crash context
# [audit2-A5] Hard cap on one newline-delimited IPC request. 127.0.0.1 is a
# device-global namespace on Android, so ANY local process can connect and
# stream bytes that never contain a newline: without a cap that is a trivial
# memory-exhaustion primitive (CONN_TIMEOUT bounds idle time, not size, and the
# auth token is only checked once the whole body has been buffered). 8 MiB
# matches the app-side DebugServer.MAX_BODY_BYTES so the two loopback servers
# agree. Oversized requests get BODY_TOO_LARGE and the connection is closed.
MAX_REQUEST_BYTES = 8 * 1024 * 1024
# After refusing an oversized request we half-close and discard what the client
# is still sending for at most this long. Closing a socket that still has
# unread data queued emits a TCP RST, which would destroy the BODY_TOO_LARGE
# reply on the way out; the budget also bounds how long one hostile connection
# can hold a handler thread.
OVERSIZE_DRAIN_SECONDS = 2.0

log = logging.getLogger("mcp-daemon")


# --- resident stdio reader ---------------------------------------------------

class _ReplyTimeout(Exception):
    """Raised by _StdioReader.wait when no reply arrives within the timeout."""


class _ChildEOF(Exception):
    """Raised by _StdioReader.wait when the child's stdout hit EOF."""


class _StdioReader:
    """One resident reader thread per child process.

    Reads newline-delimited JSON with readline() (never for-iter: iteration
    pulls C-level read-ahead chunks that a later, fresh reader thread cannot
    reliably pick up) and dispatches each reply by msg["id"] to a per-request
    queue. A timeout only abandons the waiter — the reader keeps running, so a
    late reply lands in its queue or is dropped, but can never be consumed by
    a stale per-request thread racing on the same pipe. EOF fails all pending
    waiters. Child stderr is drained on a second thread into a ring buffer so
    crash errors can carry the last lines (stderr=PIPE without a drain thread
    would deadlock a chatty child on a full pipe).
    """

    def __init__(self, proc):
        self.proc = proc
        self._pending = {}          # msg id -> queue.Queue (1-slot)
        self._pending_lock = threading.Lock()
        self._eof = False
        self._stderr_lines = collections.deque(maxlen=STDERR_RING_LINES)
        self._stderr_lock = threading.Lock()
        self._thread = threading.Thread(target=self._loop, daemon=True)
        self._thread.start()
        self._stderr_thread = threading.Thread(target=self._drain_stderr, daemon=True)
        self._stderr_thread.start()

    def _loop(self):
        try:
            while True:
                raw = self.proc.stdout.readline()
                if not raw:  # EOF
                    break
                raw = raw.strip()
                if not raw:
                    continue
                try:
                    msg = json.loads(raw)
                except ValueError:
                    continue
                box = self._pending.get(msg.get("id"))
                if box is not None:
                    box.put(msg)
                # replies with no waiter (late after a timeout, unsolicited,
                # notifications) are dropped — same as the old reader ignoring
                # non-matching ids
        except (OSError, ValueError):
            pass
        finally:
            with self._pending_lock:
                pending = list(self._pending.values())
                self._pending.clear()
                self._eof = True
            for box in pending:
                box.put(None)  # EOF sentinel: fail every pending waiter

    def _drain_stderr(self):
        try:
            while True:
                line = self.proc.stderr.readline()
                if not line:
                    break
                with self._stderr_lock:
                    self._stderr_lines.append(line.rstrip("\n"))
        except (OSError, ValueError):
            pass

    def register(self, want_id):
        """[rv1014-C1] Register the pending box for want_id BEFORE the request
        is written to the pipe (register-then-send). A reply that arrives
        while the requester sits between _send() and wait() then lands in
        this box instead of being dropped by the reader loop (no waiter yet
        → drop → timeout + retry that can re-execute a side-effecting tool).
        Raises _ChildEOF when stdout already hit EOF."""
        box = queue.Queue()
        with self._pending_lock:
            if self._eof:
                raise _ChildEOF()
            self._pending[want_id] = box
        return box

    def cancel(self, want_id):
        """Drop a registration whose request was never sent (send failure);
        keeps a failed _rpc from leaving a stale box in _pending."""
        with self._pending_lock:
            self._pending.pop(want_id, None)

    def wait(self, want_id, timeout, box=None):
        """Wait for the reply to want_id. When [box] is given it must already
        be registered (see register(); register-then-send, [rv1014-C1]);
        otherwise a box is registered here. Raises _ReplyTimeout on timeout
        (the reader keeps running) and _ChildEOF when stdout has hit EOF."""
        if box is None:
            box = queue.Queue()
            with self._pending_lock:
                if self._eof:
                    raise _ChildEOF()
                self._pending[want_id] = box
        try:
            msg = box.get(timeout=timeout)
        except queue.Empty:
            raise _ReplyTimeout()
        finally:
            with self._pending_lock:
                self._pending.pop(want_id, None)
        if msg is None:
            raise _ChildEOF()
        return msg

    def stderr_tail(self):
        """Last stderr lines for crash context; empty string when none."""
        with self._stderr_lock:
            lines = list(self._stderr_lines)
        if not lines:
            return ""
        return "\nchild stderr (last %d lines):\n%s" % (
            len(lines), "\n".join(lines))


# --- STDIO MCP server subprocess --------------------------------------------

class MCPServerProcess:
    """A long-lived STDIO MCP server child. The daemon holds proc.stdin /
    proc.stdout directly and serializes RPCs on this server via _lock."""

    def __init__(self, name, cfg):
        self.name = name
        self.cfg = cfg
        self.proc = None
        self._lock = threading.Lock()
        self._id = 0
        self._reader = None
        self.last_activity = time.time()

    def _next_id(self):
        self._id += 1
        return self._id

    def start(self):
        command = self.cfg.get("command", "")
        args = self.cfg.get("args", []) or []
        env = dict(os.environ)
        for k, v in (self.cfg.get("env") or {}).items():
            env[k] = expand_env(v)
        deps.ensure_command(command)
        try:
            self.proc = subprocess.Popen(
                [command] + list(args),
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                env=env,
                text=True,
                bufsize=1,
                start_new_session=True,  # detach from the daemon's process group
            )
        except FileNotFoundError:
            raise MCPError("CONNECTION_ERROR", "command not found: %s" % command)
        except OSError as exc:
            raise MCPError("CONNECTION_ERROR", str(exc))
        # One resident reader thread per child: dispatches by id, drains
        # stderr, survives timeouts (unlike the old per-request thread).
        self._reader = _StdioReader(self.proc)
        log.info("[%s] spawned pid=%s", self.name, self.proc.pid)
        try:
            self._handshake()
        except BaseException:
            # start() owns the child until the handshake completes.  A failed
            # start never reaches MCPPool, so no later pool eviction can clean
            # up this process or its resident reader.
            try:
                self.stop()
            except BaseException:
                # Preserve the handshake MCPError even if best-effort cleanup
                # encounters an unrelated platform/process error.
                log.exception("[%s] cleanup after failed handshake failed", self.name)
            raise
        log.info("[%s] initialized", self.name)

    def _send(self, method, params=None, notify=False, want_id=None):
        body = {"jsonrpc": "2.0", "method": method}
        if not notify:
            # [rv1014-C1] want_id may be preallocated by _rpc (register-then-
            # send); fall back to allocating here for notify-less direct calls.
            body["id"] = self._next_id() if want_id is None else want_id
        if params is not None:
            body["params"] = params
        try:
            self.proc.stdin.write(json.dumps(body) + "\n")
            self.proc.stdin.flush()
        except (BrokenPipeError, ValueError, OSError):
            raise MCPError(
                "STDIO_CRASH",
                "[%s] child closed stdin%s" % (self.name, self._stderr_tail()))
        return body.get("id")

    def _stderr_tail(self):
        reader = self._reader
        return reader.stderr_tail() if reader else ""

    def _read_reply(self, want_id, timeout=RPC_TIMEOUT, box=None):
        """Wait for the reply to want_id. The resident reader keeps running on
        timeout, so this never leaves a stale thread racing on the pipe.
        [box] must already be registered when given (register-then-send)."""
        try:
            return self._reader.wait(want_id, timeout, box=box)
        except _ReplyTimeout:
            raise MCPError("TIMEOUT", "[%s] no reply after %ss" % (self.name, timeout))
        except _ChildEOF:
            raise MCPError(
                "STDIO_CRASH",
                "[%s] process exited before replying%s"
                % (self.name, self._stderr_tail()))

    def _rpc(self, method, params=None, timeout=RPC_TIMEOUT):
        # [rv1014-C1] register-then-send: the pending box MUST be registered
        # before the request hits the pipe. The old order (_send flush, then
        # _read_reply registers) had a preemption window — a reply arriving
        # before the registration found no waiter and was dropped by the
        # reader, turning into a timeout + respawn + retry that can re-run a
        # side-effecting tool.
        want_id = self._next_id()
        reader = self._reader
        if reader is None:
            raise MCPError(
                "STDIO_CRASH", "[%s] process exited before replying" % self.name)
        try:
            box = reader.register(want_id)
        except _ChildEOF:
            raise MCPError(
                "STDIO_CRASH",
                "[%s] process exited before replying%s"
                % (self.name, self._stderr_tail()))
        try:
            self._send(method, params, want_id=want_id)
        except BaseException:
            reader.cancel(want_id)
            raise
        reply = self._read_reply(want_id, timeout=timeout, box=box)
        if reply.get("error"):
            err = reply["error"]
            raise MCPError("MCP_ERROR", err.get("message", json.dumps(err)))
        return reply.get("result")

    def _handshake(self):
        # Startup/handshake timeout is per-server configurable (Minis config,
        # not MCP protocol). resolve_startup_timeout returns the effective
        # seconds plus a warning for any invalid value (which we log rather
        # than silently swallow). Default is DEFAULT_STARTUP_TIMEOUT.
        timeout, warning = config.resolve_startup_timeout(self.cfg)
        if warning:
            log.warning("[%s] %s", self.name, warning)
        try:
            self._rpc("initialize", {
                "protocolVersion": "2025-06-18",
                "capabilities": {},
                "clientInfo": {"name": "minis-mcp-cli", "version": "1.0.0"},
            }, timeout=timeout)
        except MCPError as exc:
            if exc.code == "TIMEOUT":
                raise MCPError("TIMEOUT", (
                    "[%s] initialization timed out after %ss. This server may "
                    "still be starting. Set mcpServers.%s.startupTimeoutSeconds "
                    "to e.g. %d for slow-starting servers."
                    % (self.name, timeout, self.name, max(timeout * 2, 120)))
                )
            raise
        self._send("notifications/initialized", notify=True)

    def is_alive(self):
        return self.proc is not None and self.proc.poll() is None

    def list_tools(self):
        with self._lock:
            self.last_activity = time.time()
            return (self._rpc("tools/list") or {}).get("tools", [])

    def call_tool(self, tool, arguments):
        with self._lock:
            self.last_activity = time.time()
            return self._rpc("tools/call", {"name": tool, "arguments": arguments or {}})

    def ping(self):
        with self._lock:
            self.last_activity = time.time()
            self._rpc("tools/list", timeout=10)
            return True

    def stop(self):
        """Graceful close: shut stdin so the server reads EOF and exits; fall
        back to terminate/kill (SIGTERM can be unreliable under PRoot)."""
        if not self.proc:
            return
        try:
            self.proc.stdin.close()
        except OSError:
            pass
        try:
            self.proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            self.proc.terminate()
            try:
                self.proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.proc.kill()
        reader = self._reader
        if reader is not None:
            # stdout EOF (or kill) ends the reader loop; join briefly so a
            # respawn never races a lingering thread on this server's pipes.
            reader._thread.join(timeout=2)
        log.info("[%s] stopped", self.name)


# --- HTTP MCP session (wraps the existing stateless HTTPTransport) ----------

class MCPHTTPSession:
    """Interface-compatible with MCPServerProcess so the pool treats both the
    same. HTTP is stateless, so there is no child process to keep alive."""

    def __init__(self, name, cfg):
        self.name = name
        self._transport = HTTPTransport(cfg, name)
        self._lock = threading.Lock()
        self.last_activity = time.time()

    def is_alive(self):
        return True

    def list_tools(self):
        with self._lock:
            self.last_activity = time.time()
            return self._transport.list_tools()

    def call_tool(self, tool, arguments):
        with self._lock:
            self.last_activity = time.time()
            return self._transport.call_tool(tool, arguments)

    def ping(self):
        with self._lock:
            self.last_activity = time.time()
            return self._transport.ping()

    def stop(self):
        pass


# --- connection pool --------------------------------------------------------

class MCPPool:
    """Owns one live session per server. STDIO_CRASH / TIMEOUT evict + respawn
    with a single retry; other errors propagate. When the pool empties it calls
    on_empty so the daemon can start its exit grace timer."""

    def __init__(self, on_empty):
        self._pool = {}
        self._lock = threading.Lock()
        self._on_empty = on_empty
        # [verify-1015 BUG-07] name -> fingerprint of the config the cached
        # session was built from, so config edits evict a live session.
        self._cfg_fp = {}
        # [audit2-A5] name -> lock serializing session *creation* for that
        # server. Building a session is the slow part (spawn + dependency
        # install + handshake, tens of seconds for a cold `uvx`), so it must
        # never run under the pool-wide lock — see get(). Bounded by the number
        # of distinct server names this daemon has been asked about.
        self._name_locks = {}

    def _make_session(self, name, cfg):
        if config.is_stdio(cfg):
            session = MCPServerProcess(name, cfg)
            session.start()
            return session
        return MCPHTTPSession(name, cfg)

    def _name_lock(self, name):
        """[audit2-A5] The per-server lock that serializes session creation.

        Only the dict bookkeeping happens under the pool lock; the caller must
        never hold the pool lock while waiting on the returned lock, because
        that wait is exactly what used to block unrelated servers.
        """
        with self._lock:
            lock = self._name_locks.get(name)
            if lock is None:
                lock = threading.Lock()
                self._name_locks[name] = lock
            return lock

    def _resolve_locked(self, name):
        """[audit2-A5] Resolve + validate the cached session for `name`.

        MUST be called with the pool lock held. Returns (session, cfg, fp) where
        session is None when the caller has to build one (that part is done
        outside the pool lock). Never builds anything itself.
        """
        # [verify-1015 BUG-07] Re-validate the config on EVERY get, not only
        # when creating a session. The pool cached a live session by name
        # for its whole 10-minute idle TTL, so disabling or deleting a
        # server, or editing its url/command/args, left the OLD session
        # answering: a disabled server stayed callable. Fingerprinting the
        # resolved config evicts on every kind of edit (one mechanism, not
        # just the `enabled` flag). Costs one config file read per call,
        # negligible next to the tool call itself.
        cfg = config.get_server(name)
        if cfg is None:
            self._evict_locked(name)
            raise MCPError("NOT_FOUND", "server '%s' not found" % name)
        if not cfg.get("enabled", True):
            self._evict_locked(name)
            raise MCPError("DISABLED", "server '%s' is disabled" % name)
        fp = json.dumps(cfg, sort_keys=True, default=str)
        session = self._pool.get(name)
        if session is not None and self._cfg_fp.get(name) != fp:
            log.info("[%s] config changed, evicting cached session", name)
            self._evict_locked(name)
            session = None
        if session is not None and not session.is_alive():
            log.warning("[%s] process died, evicting", name)
            session.stop()
            del self._pool[name]
            self._cfg_fp.pop(name, None)
            session = None
        return session, cfg, fp

    def get(self, name):
        with self._lock:
            session, cfg, fp = self._resolve_locked(name)
        if session is not None:
            return session
        # [audit2-A5] Creation runs OUTSIDE the pool lock. It used to run
        # inside it, so one server's cold start (spawn + dependency install +
        # handshake — 30s+ for a cold `uvx`) serialized every OTHER server's
        # calls behind it. The pool lock now only guards dict state; a
        # per-name lock keeps two callers racing on the same cold server from
        # building two child processes. The double-check re-runs the full
        # BUG-07 validation inside the per-name lock, so "every get re-checks
        # the config fingerprint" still holds on the creation path too.
        with self._name_lock(name):
            with self._lock:
                session, cfg, fp = self._resolve_locked(name)
            if session is not None:
                return session
            log.info("[%s] creating new session", name)
            session = self._make_session(name, cfg)
            with self._lock:
                self._pool[name] = session
                self._cfg_fp[name] = fp
            return session

    def call_with_retry(self, name, fn):
        for attempt in range(2):
            session = self.get(name)
            try:
                return fn(session)
            except MCPError as exc:
                if exc.code in ("STDIO_CRASH", "TIMEOUT") and attempt == 0:
                    log.warning("[%s] %s, retrying", name, exc.code)
                    self.evict(name)
                    continue
                raise

    def _evict_locked(self, name):
        """[verify-1015 BUG-07] Evict without re-acquiring the lock: get() already
        holds it and threading.Lock is not reentrant, so get() must not call the
        public evict(). Keeps the stop-inside-lock behaviour the pool already had
        for dead processes."""
        session = self._pool.pop(name, None)
        self._cfg_fp.pop(name, None)
        if session:
            session.stop()

    def evict(self, name):
        with self._lock:
            self._evict_locked(name)

    def gc(self):
        """TTL scan: evict idle-too-long or dead servers. Returns the live count
        and fires on_empty when the pool just became empty."""
        now = time.time()
        to_evict = []
        with self._lock:
            for name, session in list(self._pool.items()):
                if now - session.last_activity > TTL_SECONDS:
                    log.info("[%s] TTL expired, killing", name)
                    to_evict.append(name)
                elif not session.is_alive():
                    log.warning("[%s] process died, evicting", name)
                    to_evict.append(name)
        for name in to_evict:
            self.evict(name)
        with self._lock:
            alive = len(self._pool)
        if alive == 0:
            self._on_empty()
        return alive

    def alive_servers(self):
        with self._lock:
            return list(self._pool.keys())

    def shutdown(self):
        with self._lock:
            sessions = list(self._pool.values())
            self._pool.clear()
        for session in sessions:
            session.stop()


# --- daemon server ----------------------------------------------------------

class DaemonServer:
    def __init__(self, port_file, pid_file):
        self.port_file = port_file
        self.pid_file = pid_file
        # [verify-1015 BUG-07] Loopback IPC auth token lives next to the port
        # file (same derivation rule as main.py, so the two can never drift).
        # See run() for why: AF_INET loopback is a device-global namespace.
        self.token_file = port_file + ".token"
        self.token = None
        self._running = True
        self._empty_since = None
        self.pool = MCPPool(on_empty=self._on_pool_empty)

    def _on_pool_empty(self):
        if self._empty_since is None:
            self._empty_since = time.time()
            log.info("pool empty, daemon will exit in %ss if no new calls", DAEMON_EXIT_GRACE)

    def handle_request(self, data):
        cmd = data.get("cmd", "")
        server = data.get("server", "")
        try:
            if cmd == "list":
                servers = config.get_servers()
                alive = self.pool.alive_servers()
                rows = []
                for name, cfg in servers.items():
                    enabled = cfg.get("enabled", True)
                    if not data.get("all") and not enabled:
                        continue
                    rows.append({
                        "name": name,
                        "enabled": enabled,
                        "transport": "http" if config.is_http(cfg) else ("stdio" if config.is_stdio(cfg) else "unknown"),
                        "target": cfg.get("url") or cfg.get("command"),
                        "note": cfg.get("note"),
                        "alive": name in alive,
                    })
                return {"ok": True, "result": {"servers": rows, "count": len(rows)}}

            if cmd == "tools":
                # Optional force-refresh: evict the live session first so the
                # next call re-handshakes (fresh initialize + tools/list) —
                # picks up tools the remote server added after we connected.
                # [T-mcp-tools-refresh]
                if data.get("refresh"):
                    self.pool.evict(server)
                tools = self.pool.call_with_retry(server, lambda s: s.list_tools())
                return {"ok": True, "result": {"server": server, "tools": tools, "count": len(tools)}}

            if cmd == "call":
                tool = data.get("tool", "")
                args = data.get("args") or {}
                result = self.pool.call_with_retry(server, lambda s: s.call_tool(tool, args))
                return {"ok": True, "result": {"server": server, "tool": tool, "result": result}}

            if cmd == "ping":
                start = time.time()
                self.pool.call_with_retry(server, lambda s: s.ping())
                elapsed = int((time.time() - start) * 1000)
                return {"ok": True, "result": {"server": server, "ok": True, "elapsed_ms": elapsed}}

            if cmd == "shutdown":
                self._running = False
                return {"ok": True, "result": {"shutdown": True}}

            return {"ok": False, "error": "unknown cmd: %s" % cmd, "code": "PARSE_ERROR", "server": server}

        except MCPError as exc:
            code = exc.code
            if code == "MCP_ERROR" and "not found" in (exc.message or "").lower():
                code = "TOOL_NOT_FOUND"
            return {"ok": False, "error": exc.message, "code": code, "server": server}
        except Exception as exc:  # noqa: BLE001 - never crash the connection thread
            log.exception("unexpected error handling %s", cmd)
            return {"ok": False, "error": str(exc), "code": "MCP_ERROR", "server": server}

    def _authorized(self, data):
        """[verify-1015 BUG-07] Constant-time token check for loopback IPC.

        Missing/blank tokens are rejected outright; a daemon started before this
        existed cannot authenticate (the CLI retires it and re-forks, see
        main.py::_retire_stale_daemon).
        """
        got = data.get("token") if isinstance(data, dict) else None
        if not isinstance(got, str) or not got or not self.token:
            return False
        return secrets.compare_digest(got, self.token)

    def _handle_conn(self, conn):
        try:
            conn.settimeout(CONN_TIMEOUT)
            buf = b""
            # [audit2-A5] Bounded read: the cap is evaluated as bytes accumulate,
            # so one connection can buffer at most MAX_REQUEST_BYTES + one recv
            # chunk. An over-cap client is answered and dropped without ever
            # reaching json.loads (and therefore without the O(n^2) growth of an
            # unbounded `buf += chunk` loop).
            too_large = False
            while b"\n" not in buf:
                chunk = conn.recv(65536)
                if not chunk:
                    break
                buf += chunk
                if len(buf) > MAX_REQUEST_BYTES:
                    too_large = True
                    break
            if too_large:
                log.warning("request exceeds %d bytes, closing connection", MAX_REQUEST_BYTES)
                try:
                    conn.sendall((json.dumps({
                        "ok": False,
                        "error": "request exceeds %d bytes" % MAX_REQUEST_BYTES,
                        "code": "BODY_TOO_LARGE",
                    }) + "\n").encode())
                    # Give the client a chance to READ that reply: half-close
                    # (so it sees EOF for its own write side) and discard the
                    # bytes it is still streaming, for a bounded budget. A
                    # plain close() with unread data queued becomes a TCP RST
                    # and the BODY_TOO_LARGE line never arrives.
                    conn.shutdown(socket.SHUT_WR)
                    conn.settimeout(OVERSIZE_DRAIN_SECONDS)
                    deadline = time.time() + OVERSIZE_DRAIN_SECONDS
                    while time.time() < deadline:
                        if not conn.recv(65536):
                            break
                except OSError:
                    pass
                return
            if not buf.strip():
                return
            try:
                req = json.loads(buf.split(b"\n")[0])
            except ValueError:
                conn.sendall((json.dumps({"ok": False, "error": "invalid JSON", "code": "PARSE_ERROR"}) + "\n").encode())
                return
            if not self._authorized(req):
                # [verify-1015 BUG-07] Reject before any dispatch: the token is
                # the only thing standing between a hostile local app and the
                # user's configured MCP servers (and `shutdown`).
                log.warning("rejected unauthenticated request: %r", str(req)[:120])
                conn.sendall((json.dumps({
                    "ok": False,
                    "error": "unauthorized: missing or bad daemon token",
                    "code": "AUTH",
                }) + "\n").encode())
                return
            resp = self.handle_request(req)
            conn.sendall((json.dumps(resp, ensure_ascii=False) + "\n").encode())
        except (OSError, socket.timeout) as exc:
            log.warning("connection error: %s", exc)
        finally:
            try:
                conn.close()
            except OSError:
                pass

    def _watchdog(self):
        while self._running:
            time.sleep(WATCHDOG_INTERVAL)
            alive = self.pool.gc()
            if alive > 0:
                self._empty_since = None
            elif self._empty_since is not None:
                if time.time() - self._empty_since >= DAEMON_EXIT_GRACE:
                    log.info("pool empty past grace, daemon exiting")
                    self._running = False
                    break

    def run(self):
        # Loopback TCP on an ephemeral port (AF_UNIX is unavailable on iSH
        # fakefs). Publish the chosen port so the CLI knows where to connect.
        srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        srv.bind(("127.0.0.1", 0))
        port = srv.getsockname()[1]
        srv.listen(16)
        srv.settimeout(1.0)  # wake periodically so _running flips take effect
        # [verify-1015 BUG-07] Loopback IPC auth. 127.0.0.1 is a
        # device-global namespace (Android does not isolate loopback per app),
        # so any other app on the phone can scan ephemeral ports and speak
        # this protocol. The port file was also world-readable and connections
        # carried no credential at all: a hostile app could call the user's
        # configured MCP tools (including remote servers holding credentials)
        # and could even send `shutdown`. Both files are now 0600 (app uid
        # only) and every request must carry the token; a request without a
        # matching token gets AUTH and is dropped.
        self.token = secrets.token_hex(16)
        for path, payload in ((self.port_file, str(port)), (self.token_file, self.token)):
            tmp = path + ".tmp"
            fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
            with os.fdopen(fd, "w", encoding="utf-8") as f:
                f.write(payload)
            os.replace(tmp, path)
        log.info("daemon listening on 127.0.0.1:%s pid=%s ttl=%ss", port, os.getpid(), TTL_SECONDS)

        def _sigterm(*_):
            log.info("SIGTERM received")
            self._running = False
        signal.signal(signal.SIGTERM, _sigterm)

        watchdog = threading.Thread(target=self._watchdog, daemon=True)
        watchdog.start()

        try:
            while self._running:
                try:
                    conn, _ = srv.accept()
                except socket.timeout:
                    continue
                except OSError:
                    break
                threading.Thread(target=self._handle_conn, args=(conn,), daemon=True).start()
        finally:
            self._cleanup(srv)

    def _cleanup(self, srv):
        log.info("daemon shutting down, stopping all servers")
        self.pool.shutdown()
        try:
            srv.close()
        except OSError:
            pass
        for path in (self.port_file, self.pid_file, self.token_file):
            try:
                os.unlink(path)
            except OSError:
                pass
        log.info("daemon exited cleanly")
