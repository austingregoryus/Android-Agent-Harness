"""Offline regression checks for privacy and ambiguous device responses."""
import contextlib
import http.client
import io
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


if __name__ == "__main__":
    unittest.main()
