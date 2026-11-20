#!/usr/bin/env python3
"""Regression tests for STDIO handshake ownership and cleanup (R7-F01).

Run with the stdlib only:
    python3 test_handshake_cleanup.py

The child process is synthetic and contains no credentials or network access.
"""

import importlib.util
import json
import select
import sys
import tempfile
import time
import unittest
from pathlib import Path


HERE = Path(__file__).resolve().parent
DAEMON_PATH = HERE / "daemon.py"
CHILD_PROGRAM = r'''
import json
import select
import sys

mode = sys.argv[1]
sys.stdin.readline()
if mode == "error":
    print(json.dumps({
        "jsonrpc": "2.0", "id": 1,
        "error": {"code": -32000, "message": "synthetic initialize failure"},
    }), flush=True)
elif mode == "success":
    print(json.dumps({
        "jsonrpc": "2.0", "id": 1,
        "result": {
            "protocolVersion": "2025-06-18",
            "capabilities": {},
            "serverInfo": {"name": "synthetic"},
        },
    }), flush=True)
while True:
    ready, _, _ = select.select([sys.stdin], [], [], 0.1)
    if ready and not sys.stdin.readline():
        break
'''


def load_daemon():
    spec = importlib.util.spec_from_file_location("mcp_daemon_cleanup_test", str(DAEMON_PATH))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class HandshakeCleanupTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.daemon = load_daemon()

    def _wait_for_cleanup(self, session):
        deadline = time.monotonic() + 2.0
        reader = session._reader
        while time.monotonic() < deadline:
            if (not session.is_alive()
                    and reader is not None
                    and not reader._thread.is_alive()
                    and not reader._stderr_thread.is_alive()):
                return
            time.sleep(0.02)
        self.assertFalse(session.is_alive(), "child still alive after failed handshake")
        self.assertIsNotNone(reader)
        self.assertFalse(reader._thread.is_alive(), "reader still alive after failed handshake")
        self.assertFalse(reader._stderr_thread.is_alive(), "stderr reader still alive after failed handshake")

    def _run_case(self, mode):
        cfg = {
            "command": sys.executable,
            "args": ["-u", "-c", CHILD_PROGRAM, mode],
            "enabled": True,
        }
        if mode == "timeout":
            cfg["startupTimeoutSeconds"] = 1

        module = self.daemon
        old_get_server = module.config.get_server
        module.config.get_server = lambda name: cfg if name == "synthetic" else None
        pool = module.MCPPool(on_empty=lambda: None)
        created = []

        def make_and_record(name, server_cfg):
            session = module.MCPServerProcess(name, server_cfg)
            created.append(session)
            session.start()
            return session

        pool._make_session = make_and_record
        try:
            if mode == "success":
                session = pool.get("synthetic")
                self.assertTrue(session.is_alive())
                self.assertEqual(pool.alive_servers(), ["synthetic"])
                self.assertEqual(created, [session])
                return

            with self.assertRaises(module.MCPError) as raised:
                pool.get("synthetic")
            expected = "MCP_ERROR" if mode == "error" else "TIMEOUT"
            self.assertEqual(raised.exception.code, expected)
            self.assertEqual(pool.alive_servers(), [])
            self.assertEqual(len(created), 1)
            self._wait_for_cleanup(created[0])
        finally:
            for session in created:
                if session.is_alive():
                    session.stop()
                for stream_name in ("stdin", "stdout", "stderr"):
                    stream = getattr(session.proc, stream_name, None)
                    if stream is not None:
                        try:
                            stream.close()
                        except (OSError, ValueError):
                            pass
            pool.shutdown()
            module.config.get_server = old_get_server

    def test_initialize_error_cleans_child_and_reader_and_preserves_code(self):
        self._run_case("error")

    def test_timeout_cleans_child_and_reader_and_preserves_code(self):
        self._run_case("timeout")

    def test_successful_handshake_enters_pool(self):
        self._run_case("success")


if __name__ == "__main__":
    unittest.main(verbosity=2)
