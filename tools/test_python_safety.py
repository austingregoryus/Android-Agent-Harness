"""Offline regression checks for privacy and ambiguous device responses."""
import contextlib
import hashlib
import http.client
import http.server
import io
import json
import os
import shutil
import socket
import ssl
import subprocess
import tempfile
import threading
import unittest
from unittest.mock import Mock, patch

import converse
import inbound


class InboundSafetyTest(unittest.TestCase):
    def test_country_code_is_not_discarded(self):
        self.assertTrue(inbound.allowed("+1 (202) 555-0100", ["2025550100"]))
        self.assertFalse(inbound.allowed("+442025550100", ["+12025550100"]))
        self.assertFalse(inbound.allowed("+4723456789", ["+14723456789"]))
        self.assertFalse(inbound.allowed("004723456789", ["+14723456789"]))
        self.assertFalse(inbound.allowed("100", ["100"]))

    def test_passphrase_only_never_reaches_model_or_logs(self):
        with patch.object(inbound, "answer") as answer, patch.object(inbound, "log_line") as log:
            handle = inbound.make_handler(Mock(), None, ["+12025550100"], "private phrase")
            with contextlib.redirect_stdout(io.StringIO()) as output:
                handle({"from": "+12025550100", "text": "PRIVATE PHRASE", "message_id": "1"})
            answer.assert_not_called()
            log.assert_not_called()
            self.assertNotIn("PRIVATE PHRASE", output.getvalue())

    def test_dry_run_cannot_unlock_writes(self):
        rt = Mock()
        with patch.object(inbound, "answer", return_value="preview") as answer, \
                patch.object(inbound, "log_line"), contextlib.redirect_stdout(io.StringIO()):
            handle = inbound.make_handler(rt, None, ["+12025550100"], "phrase", dry_run=True)
            handle({"from": "+12025550100", "text": "phrase send a text", "message_id": "1"})
        self.assertFalse(answer.call_args.args[3])
        rt.call.assert_not_called()

    def test_verbose_event_logging_never_prints_passphrase(self):
        with patch.object(inbound, "answer", return_value="preview"), patch.object(inbound, "log_line"), \
                contextlib.redirect_stdout(io.StringIO()) as output:
            handle = inbound.make_handler(Mock(), None, ["+12025550100"], "private-phrase", dry_run=True)
            inbound.dispatch({"type": "event", "event": {"id": 1, "topic": "messaging.sms_received",
                "payload": {"from": "+12025550100", "text": "private-phrase read battery"}}}, handle, verbose=True)
        self.assertNotIn("private-phrase", output.getvalue())


class RuntimeSafetyTest(unittest.TestCase):
    def test_driver_requests_managed_dial_and_does_not_retry_ambiguous_response(self):
        for failure in (OSError("lost dial response"), {"error": "policy.denied"}):
            rt, channel, voice = Mock(), Mock(), Mock()
            voice.can_presynth.return_value = False
            if isinstance(failure, Exception):
                rt.call.side_effect = failure
            else:
                rt.call.return_value = failure
            with patch.object(converse, "Runtime", return_value=rt), \
                    patch.object(converse, "EventChannel", return_value=channel), \
                    patch.object(converse, "ws_connect", object()), \
                    contextlib.redirect_stdout(io.StringIO()):
                with self.assertRaises((OSError, SystemExit)):
                    converse.run("http://unused", "ws://unused", "test-token", lambda *_: "hello",
                                 "hello", False, {}, voice=voice, dial="+12025550100")
            rt.call.assert_called_once()
            self.assertTrue(rt.call.call_args.args[1]["driver_managed"])
            channel.close.assert_called_once()

    def test_custom_brain_preserves_endpoint_and_model(self):
        argv = ["converse.py", "--token", "test-token", "--brain", "custom",
                "--local-base", "http://127.0.0.1:12345/v1", "--local-model", "test-model",
                "--fallback-brain", "none", "--tts", "android", "--stt", "device"]
        with patch("sys.argv", argv), patch("builtins.open", side_effect=FileNotFoundError), \
                patch.object(converse, "claim_singleton"), \
                patch.object(converse, "local_responder") as responder, \
                patch.object(converse, "Voice"), patch.object(converse, "run"):
            converse.main()
        brain = responder.call_args.kwargs["brain"]
        self.assertEqual("http://127.0.0.1:12345/v1", brain.base)
        self.assertEqual("test-model", brain.model)
        self.assertIsNone(responder.call_args.kwargs["fallback_brain"])

    def runtime(self):
        rt = converse.Runtime("http://127.0.0.1:18765", "test-token")
        rt.session = "test-session"
        return rt

    def test_missing_or_error_result_is_never_success(self):
        for body in (None, {}, {"result": None}, {"result": {}},
                     {"result": {"isError": True, "content": [{"text": "denied"}]}}):
            with self.subTest(body=body):
                rt = self.runtime()
                if isinstance(body, dict):
                    body = dict(body, jsonrpc="2.0", id=2)
                rt._post = Mock(return_value=({}, body))
                self.assertTrue(rt.call("telephony.end_call", {}).get("error"))

    def test_valid_result_is_preserved(self):
        rt = self.runtime()
        rt._post = Mock(return_value=({}, {"jsonrpc": "2.0", "id": 2, "result": {"structuredContent": {"ended": True}}}))
        self.assertEqual({"ended": True}, rt.call("telephony.end_call", {}))

    def test_ambiguous_send_failure_is_not_retried(self):
        rt = self.runtime()
        conn = Mock()
        conn.request.side_effect = OSError("connection lost after write")
        rt._connection = Mock(return_value=conn)
        with self.assertRaises(OSError):
            rt._post({"method": "tools/call"})
        self.assertEqual(1, conn.request.call_count)

    def test_http_error_is_not_a_tool_result(self):
        rt = self.runtime()
        conn = Mock()
        conn.getresponse.return_value.status = 503
        conn.getresponse.return_value.read.return_value = b'{}'
        rt._connection = Mock(return_value=conn)
        with self.assertRaisesRegex(RuntimeError, "503"):
            rt.call("telephony.end_call", {})

    def test_session_header_is_case_insensitive(self):
        rt = self.runtime()
        rt._post = Mock(side_effect=[({"mcp-session-id": "new-session"}, {"jsonrpc": "2.0", "id": 1, "result": {}}), ({}, None)])
        self.assertEqual("new-session", rt.init())

    def test_mismatched_envelope_is_not_success(self):
        for version, rid in (("2.0", 999), ("1.0", 2), (None, 2)):
            rt = self.runtime()
            rt._post = Mock(return_value=({}, {"jsonrpc": version, "id": rid,
                                               "result": {"structuredContent": {"ended": True}}}))
            self.assertTrue(rt.call("telephony.end_call", {}).get("error"))

    def test_error_preserves_pending_approval_metadata(self):
        rt = self.runtime()
        detail = {"error": "approval required", "pending_action_id": "pending-test"}
        rt._post = Mock(return_value=({}, {"jsonrpc": "2.0", "id": 2,
                                           "result": {"isError": True, "structuredContent": detail}}))
        self.assertEqual(detail, rt.call("messaging.send_sms", {}))

    def test_judgement_eval_imports_shared_rules_without_requests(self):
        with patch("urllib.request.urlopen", side_effect=AssertionError("unexpected request")):
            import judgement_eval
        self.assertIn(converse.PHONE_RULES, judgement_eval.SYSTEM)


class FakeMcpServer:
    """
    Just enough of the phone's /mcp to forget sessions. It answers an unknown session exactly as McpHandler.kt
    does: HTTP 404 with {"error": "unknown session; initialize again"}, before looking at the method.
    """

    def __init__(self, unknown_body=None):
        self.sessions: set = set()
        self.methods: list = []
        self.minted = 0
        self.unknown_body = unknown_body or {"error": "unknown session; initialize again"}
        server = self

        class Handler(http.server.BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"   # keep-alive: Runtime holds one persistent connection

            def log_message(self, *args):
                pass

            def reply(self, status, body, headers=None):
                data = json.dumps(body).encode() if body is not None else b""
                self.send_response(status)
                for k, v in (headers or {}).items():
                    self.send_header(k, v)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def do_POST(self):
                msg = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                server.methods.append(msg.get("method"))
                if msg.get("method") == "initialize":
                    server.minted += 1
                    sid = f"mcp_{server.minted}"
                    server.sessions.add(sid)
                    return self.reply(200, {"jsonrpc": "2.0", "id": msg["id"], "result": {}}, {"Mcp-Session-Id": sid})
                if "id" not in msg:
                    return self.reply(202, None)
                if self.headers.get("Mcp-Session-Id") not in server.sessions:
                    return self.reply(404, server.unknown_body)
                return self.reply(200, {"jsonrpc": "2.0", "id": msg["id"],
                                        "result": {"structuredContent": {"level": 80}}})

        self.httpd = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.base = f"http://127.0.0.1:{self.httpd.server_address[1]}"
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()

    def close(self):
        self.httpd.shutdown()
        self.httpd.server_close()


class StaleSessionTest(unittest.TestCase):
    """The phone drops sessions on app restart, after a day, and past 8 per peer. Every call then got a 404
    and inbound.py answered nothing while looking healthy."""

    def setUp(self):
        self.server = FakeMcpServer()
        self.addCleanup(self.server.close)

    def runtime(self):
        rt = converse.Runtime(self.server.base, "test-token")
        self.addCleanup(lambda: rt._conn and rt._drop(rt._conn))
        return rt

    def test_forgotten_session_is_reinitialized_once(self):
        rt = self.runtime()
        rt.init()
        self.server.sessions.clear()                  # the phone app restarted
        self.assertEqual({"level": 80}, rt.call("device.get_battery", {}))
        self.assertEqual("mcp_2", rt.session)
        self.assertEqual(2, self.server.methods.count("tools/call"))
        self.assertEqual(2, self.server.methods.count("initialize"))

    def test_first_call_initializes_without_a_404(self):
        rt = self.runtime()
        self.assertEqual({"level": 80}, rt.call("device.get_battery", {}))
        self.assertEqual(1, self.server.methods.count("tools/call"))

    def test_resource_read_is_reinitialized_too(self):
        rt = self.runtime()
        rt.init()
        self.server.sessions.clear()
        body = rt.request("resources/read", {"uri": "phone://policy"}, 9)
        self.assertEqual(9, body["id"])

    def test_retries_only_once(self):
        rt = self.runtime()
        rt.init()
        real_init = rt.init

        def init_then_forget():
            sid = real_init()
            self.server.sessions.clear()              # forgotten again before the resend lands
            return sid
        rt.init = init_then_forget
        self.server.sessions.clear()
        with self.assertRaisesRegex(RuntimeError, "404"):
            rt.call("device.get_battery", {})
        self.assertEqual(2, self.server.methods.count("tools/call"))

    def test_other_404_is_not_retried(self):
        self.server.close()
        self.server = FakeMcpServer(unknown_body={"error": "not found"})
        self.addCleanup(self.server.close)
        rt = self.runtime()
        rt.init()
        self.server.sessions.clear()
        with self.assertRaisesRegex(RuntimeError, "404"):
            rt.call("device.get_battery", {})
        self.assertEqual(1, self.server.methods.count("tools/call"))
        self.assertEqual(1, self.server.methods.count("initialize"))


@unittest.skipUnless(shutil.which("openssl"), "needs the openssl command to mint a test certificate")
class PinnedTlsTest(unittest.TestCase):
    """The phone's cert is self-signed with no CA extensions. Python 3.13's VERIFY_X509_STRICT refused it as an
    "invalid CA certificate" although the pin matched."""

    @classmethod
    def setUpClass(cls):
        cls.dir = tempfile.mkdtemp()
        key, csr, crt = (os.path.join(cls.dir, n) for n in ("k.pem", "r.csr", "c.pem"))
        # `x509 -req` without an extensions file mints an X.509 v1 certificate: the extension-less kind.
        subprocess.run(["openssl", "req", "-new", "-newkey", "rsa:2048", "-nodes", "-keyout", key, "-out", csr,
                        "-subj", "/CN=127.0.0.1"], check=True, capture_output=True)
        subprocess.run(["openssl", "x509", "-req", "-in", csr, "-signkey", key, "-out", crt, "-days", "2"],
                       check=True, capture_output=True)
        with open(crt, encoding="ascii") as f:
            cls.pin = hashlib.sha256(ssl.PEM_cert_to_DER_cert(f.read())).hexdigest().upper()
        server_ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        server_ctx.load_cert_chain(crt, key)
        cls.sock = socket.create_server(("127.0.0.1", 0))
        cls.base = f"https://127.0.0.1:{cls.sock.getsockname()[1]}"

        def serve():
            while True:
                try:
                    conn, _ = cls.sock.accept()
                except OSError:
                    return
                try:
                    with server_ctx.wrap_socket(conn, server_side=True) as tls:
                        tls.recv(1)
                except (OSError, ssl.SSLError):
                    pass
        threading.Thread(target=serve, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.sock.close()
        shutil.rmtree(cls.dir, ignore_errors=True)

    def test_extensionless_self_signed_cert_is_accepted_by_pin(self):
        ctx = converse.pinned_ssl_context(self.base, self.pin)
        self.assertFalse(ctx.verify_flags & getattr(ssl, "VERIFY_X509_STRICT", 0))
        self.assertEqual(ssl.CERT_REQUIRED, ctx.verify_mode)
        with socket.create_connection(("127.0.0.1", int(self.base.rsplit(":", 1)[1])), timeout=10) as raw:
            with ctx.wrap_socket(raw, server_hostname="127.0.0.1") as tls:
                self.assertTrue(tls.getpeercert(binary_form=True))

    def test_wrong_pin_is_refused(self):
        with self.assertRaisesRegex(SystemExit, "pin mismatch"):
            converse.pinned_ssl_context(self.base, "00" * 32)

    def test_context_trusts_only_the_pinned_cert(self):
        ctx = converse.pinned_ssl_context(self.base, self.pin)
        self.assertEqual(1, len(ctx.get_ca_certs()))


class GapReplayTest(unittest.TestCase):
    """A `gap` frame arriving as the socket closes: ws.send raised websockets' ConnectionClosed (not a
    ConnectionError), which escaped recv(), hung up a live call and ended the inbound daemon."""

    def test_send_failure_on_gap_reconnects_and_replays(self):
        try:
            from websockets.exceptions import ConnectionClosed
            closed = ConnectionClosed(None, None)
        except Exception:   # pragma: no cover - websockets is a core requirement
            closed = RuntimeError("socket closing")

        class Dying:
            def recv(self, timeout=None):
                return json.dumps({"type": "gap", "from_id": 5, "to_id": 7})

            def send(self, raw):
                raise closed

            def close(self):
                pass

        class Fresh:
            def __init__(self):
                self.out = []

            def send(self, raw):
                m = json.loads(raw)
                if m["type"] == "replay":
                    self.out.append({"type": "replay_result", "subscription_id": "sub_1", "next_since_id": 6,
                                     "events": [{"id": 5, "topic": "messaging.sms_received", "payload": {}}]})

            def recv(self, timeout=None):
                if self.out:
                    return json.dumps(self.out.pop(0))
                raise TimeoutError

            def close(self):
                pass

        ch = converse.EventChannel("ws://fake", "tok", None, "messaging.*", reconnect_for=5.0)
        ch.ws, ch.sub_id, ch.last_id, ch.baseline = Dying(), "sub_1", 4, True
        with patch.object(converse, "ws_connect", return_value=Fresh()), \
                contextlib.redirect_stdout(io.StringIO()) as out:
            frame = ch.recv()
        self.assertEqual(5, frame["event"]["id"])
        self.assertEqual(1, ch.reconnects)
        self.assertIn("event socket dropped", out.getvalue())


class BrainFlagsTest(unittest.TestCase):
    """A preset silently replaced --local-*, and --llm-api-key / LLM_API_KEY then went to the preset's host."""

    KEYS = "OPENAI_API_KEY=openai-key\nCEREBRAS_API_KEY=cerebras-key\n"

    def main(self, *flags, env=None):
        argv = ["converse.py", "--token", "test-token", "--tts", "android", "--stt", "device", *flags]

        def fake_open(path, *args, **kwargs):
            if str(path).endswith("api_keys"):
                return io.StringIO(self.KEYS)
            raise FileNotFoundError(path)
        with patch("sys.argv", argv), patch("builtins.open", side_effect=fake_open), \
                patch.dict(os.environ, env or {}, clear=False), \
                patch.object(converse, "claim_singleton"), \
                patch.object(converse, "local_responder") as responder, \
                patch.object(converse, "Voice"), patch.object(converse, "run") as run, \
                contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            if env is None:
                os.environ.pop("LLM_API_KEY", None)
            converse.main()
        return responder.call_args.kwargs, run.call_args.kwargs

    def test_explicit_local_flags_with_a_preset_are_refused(self):
        for flags in (["--local-base", "https://api.cerebras.ai/v1"], ["--local-model", "m"],
                      ["--llm-reasoning", "low"], ["--llm-api-key", "k"],
                      ["--brain", "luna", "--local-base", "http://x/v1", "--local-model", "m"]):
            with self.subTest(flags=flags):
                with self.assertRaises(SystemExit) as e:
                    self.main(*flags)
                self.assertIn("--brain custom", str(e.exception.code))
                self.assertIn(flags[-2] if flags[0] == "--brain" else flags[0], str(e.exception.code))

    def test_defaults_are_unchanged(self):
        kw, _ = self.main("--brain", "custom", "--fallback-brain", "none")
        self.assertEqual((converse.LOCAL_ROUTER.rstrip("/"), converse.LOCAL_DEFAULT_MODEL, "none"),
                         (kw["brain"].base, kw["brain"].model, kw["brain"].reasoning))
        kw, _ = self.main()
        base, model, _ = converse.BRAINS["luna"]
        self.assertEqual((base, model), (kw["brain"].base, kw["brain"].model))

    def test_preset_uses_its_own_hosts_key_not_llm_api_key(self):
        kw, _ = self.main("--brain", "luna", env={"LLM_API_KEY": "env-key"})
        self.assertEqual("openai-key", kw["brain"].api_key)

    def test_custom_key_goes_only_to_the_custom_primary(self):
        for flags, env, want in ((["--llm-api-key", "flag-key"], {}, "flag-key"),
                                 ([], {"LLM_API_KEY": "env-key"}, "env-key")):
            with self.subTest(want=want):
                kw, run_kw = self.main("--brain", "custom", "--local-base", "https://api.cerebras.ai/v1",
                                       "--local-model", "m", "--fallback-brain", "luna",
                                       "--outcome-brain", "luna", *flags, env=env or None)
                self.assertEqual(want, kw["brain"].api_key)
                self.assertEqual("openai-key", kw["fallback_brain"].api_key)
                self.assertEqual("openai-key", run_kw["outcome_brain"].api_key)

    def test_off_reasoning_is_omitted_by_other_drivers(self):
        self.assertIsNone(inbound.preset_brain("haiku").reasoning)
        self.assertEqual("none", inbound.preset_brain("luna").reasoning)
        import dry_call
        with patch.object(dry_call.C, "Brain", side_effect=StopIteration) as brain, \
                contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaises(StopIteration):
                dry_call.drive("x", "task", [], brain_name="haiku")
        self.assertIsNone(brain.call_args.args[2])


if __name__ == "__main__":
    unittest.main()
