"""Tests for host/mercurio-push-watch against a fake Herdr API socket.

Run: python3 -m unittest discover -s host/tests
"""

from __future__ import annotations

import importlib.machinery
import importlib.util
import json
import os
import queue
import socket
import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest import mock

SCRIPT = Path(__file__).resolve().parent.parent / "mercurio-push-watch"


def load_watch():
    loader = importlib.machinery.SourceFileLoader("mercurio_push_watch", str(SCRIPT))
    spec = importlib.util.spec_from_loader(loader.name, loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


pw = load_watch()


def pane(pane_id, status, agent="claude", tab="w1:t1"):
    return {
        "pane_id": pane_id,
        "agent_status": status,
        "agent": agent,
        "workspace_id": "w1",
        "tab_id": tab,
        "terminal_title": "SECRET PANE TEXT",
    }


class FakeHerdr:
    """Answers session.snapshot and events.subscribe like Herdr's API socket."""

    def __init__(self, path):
        self.path = path
        self.panes = []
        self.subscribes = []
        self.streams = []
        self.server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.server.bind(path)
        self.server.listen()
        threading.Thread(target=self.serve, daemon=True).start()

    def snapshot(self):
        return {
            "panes": list(self.panes),
            "workspaces": [{"workspace_id": "w1", "label": "mercurio"}],
            "tabs": [{"tab_id": "w1:t1", "label": "build"}],
        }

    def serve(self):
        while True:
            try:
                conn, _ = self.server.accept()
            except OSError:
                return
            threading.Thread(target=self.handle, args=(conn,), daemon=True).start()

    def handle(self, conn):
        with conn:
            req = json.loads(conn.makefile().readline())
            if req["method"] == "session.snapshot":
                reply = {"id": req["id"], "result": {"type": "session_snapshot", "snapshot": self.snapshot()}}
                conn.sendall((json.dumps(reply) + "\n").encode())
                return
            events = queue.Queue()
            self.streams.append(events)
            self.subscribes.append(req["params"]["subscriptions"])
            conn.sendall((json.dumps({"id": req["id"], "result": {"type": "subscription_started"}}) + "\n").encode())
            while True:
                event = events.get()
                if event is None:
                    return  # drop the stream, as a Herdr restart does
                conn.sendall((json.dumps(event) + "\n").encode())

    def send(self, event):
        """Deliver to the newest subscription, the one the watcher is reading."""
        self.streams[-1].put(event)

    def status(self, pane_id, status):
        self.send(
            {
                "event": "pane.agent_status_changed",
                "data": {"pane_id": pane_id, "agent_status": status, "agent": "claude", "workspace_id": "w1"},
            }
        )

    def close(self):
        self.server.close()


def wait_for(check, timeout=5.0):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        if check():
            return True
        time.sleep(0.02)
    return False


class WatchTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.sock = os.path.join(self.tmp.name, "herdr.sock")
        self.endpoints = os.path.join(self.tmp.name, "push-endpoints")
        Path(self.endpoints).write_text("# phone\nhttps://push.example/upAbc?up=1\n\nnot-a-url\n")
        self.herdr = FakeHerdr(self.sock)
        self.posts = []
        self.clock = [100.0]

    def tearDown(self):
        if self.herdr.streams:
            self.herdr.send(None)
        self.herdr.close()
        self.tmp.cleanup()

    def start(self, states=("blocked",)):
        watcher = pw.Watcher(
            self.sock,
            "default",
            "sun",
            states,
            20.0,
            self.endpoints,
            post=lambda url, body: self.posts.append((url, json.loads(body))) or 200,
            clock=lambda: self.clock[0],
        )
        threading.Thread(target=watcher.run, daemon=True).start()
        self.assertTrue(wait_for(lambda: self.herdr.subscribes))
        return watcher

    def test_blocked_pushes_labels_and_no_pane_text(self):
        self.herdr.panes = [pane("w1:p1", "working")]
        self.start()
        self.herdr.status("w1:p1", "blocked")
        self.assertTrue(wait_for(lambda: self.posts))
        url, body = self.posts[0]
        self.assertEqual(url, "https://push.example/upAbc?up=1")
        self.assertEqual(body["event"], "pane.agent_status_changed")
        self.assertEqual(
            (body["state"], body["host"], body["session"], body["pane_id"], body["agent"]),
            ("blocked", "sun", "default", "w1:p1", "claude"),
        )
        self.assertEqual((body["workspace"], body["tab"]), ("mercurio", "build"))
        self.assertNotIn("SECRET", json.dumps(body))

    def test_already_blocked_at_start_is_silent(self):
        self.herdr.panes = [pane("w1:p1", "blocked")]
        self.start()
        self.herdr.status("w1:p1", "blocked")
        time.sleep(0.2)
        self.assertEqual(self.posts, [])

    def test_repeat_within_debounce_pushes_once(self):
        self.herdr.panes = [pane("w1:p1", "working")]
        self.start()
        for status in ("blocked", "working", "blocked"):
            self.herdr.status("w1:p1", status)
        time.sleep(0.2)
        self.assertEqual(len(self.posts), 1)
        self.clock[0] += 21
        self.herdr.status("w1:p1", "working")
        self.herdr.status("w1:p1", "blocked")
        self.assertTrue(wait_for(lambda: len(self.posts) == 2))

    def test_only_chosen_states_push(self):
        self.herdr.panes = [pane("w1:p1", "working")]
        self.start(states=("blocked", "done"))
        for status in ("idle", "working", "done"):
            self.herdr.status("w1:p1", status)
        self.assertTrue(wait_for(lambda: self.posts))
        time.sleep(0.2)
        self.assertEqual([b["state"] for _, b in self.posts], ["done"])

    def test_new_pane_resubscribes_and_is_watched(self):
        self.herdr.panes = [pane("w1:p1", "idle")]
        self.start()
        self.herdr.panes.append(pane("w1:p2", "idle"))
        self.herdr.send({"event": "pane_created", "data": {"pane": pane("w1:p2", "idle")}})
        self.assertTrue(wait_for(lambda: len(self.herdr.subscribes) == 2))
        watched = [s.get("pane_id") for s in self.herdr.subscribes[1] if s["type"] == "pane.agent_status_changed"]
        self.assertEqual(watched, ["w1:p1", "w1:p2"])
        self.herdr.status("w1:p2", "blocked")
        self.assertTrue(wait_for(lambda: self.posts))

    def test_reconnects_after_stream_drops(self):
        self.herdr.panes = [pane("w1:p1", "idle")]
        self.start()
        self.herdr.send(None)
        self.assertTrue(wait_for(lambda: len(self.herdr.subscribes) == 2))
        self.herdr.status("w1:p1", "blocked")
        self.assertTrue(wait_for(lambda: self.posts))


class SessionsTest(unittest.TestCase):
    """Every running Herdr session is followed, each push names its session."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.env = mock.patch.dict(os.environ, {"XDG_CONFIG_HOME": self.tmp.name})
        self.env.start()
        os.environ.pop("HERDR_SOCKET_PATH", None)
        self.herdr = os.path.join(self.tmp.name, "herdr")
        os.makedirs(os.path.join(self.herdr, "sessions"))
        self.endpoints = os.path.join(self.tmp.name, "push-endpoints")
        Path(self.endpoints).write_text("https://push.example/upAbc\n")
        self.servers = {}
        self.posts = []

    def tearDown(self):
        for server in self.servers.values():
            if server.streams:
                server.send(None)
            server.close()
        self.env.stop()
        self.tmp.cleanup()

    def serve(self, name):
        path = pw.herdr_socket(name)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        server = FakeHerdr(path)
        server.panes = [pane("w1:p1", "working")]
        self.servers[name] = server
        return server

    def make(self, sock_path, session):
        post = lambda url, body: self.posts.append(json.loads(body)) or 200  # noqa: E731
        return pw.Watcher(sock_path, session, "sun", ["blocked"], 20.0, self.endpoints, post=post)

    def test_finds_running_sessions_and_skips_a_stale_socket(self):
        self.serve("default")
        self.serve("work")
        stale = pw.herdr_socket("old")
        os.makedirs(os.path.dirname(stale))
        Path(stale).touch()
        self.assertEqual(sorted(pw.herdr_sessions()), ["default", "old", "work"])
        self.assertFalse(pw.running(stale))
        sessions = pw.Sessions(self.make)
        sessions.scan()
        self.assertEqual(sorted(sessions.threads), ["default", "work"])

    def test_each_session_pushes_under_its_own_name(self):
        default, work = self.serve("default"), self.serve("work")
        pw.Sessions(self.make).scan()
        self.assertTrue(wait_for(lambda: default.subscribes and work.subscribes))
        work.status("w1:p1", "blocked")
        self.assertTrue(wait_for(lambda: self.posts))
        default.status("w1:p1", "blocked")
        self.assertTrue(wait_for(lambda: len(self.posts) == 2))
        # The same pane id in two sessions: two pushes, told apart by the session.
        self.assertEqual([(b["session"], b["pane_id"]) for b in self.posts], [("work", "w1:p1"), ("default", "w1:p1")])

    def test_a_rescan_adds_a_new_session_once(self):
        self.serve("default")
        sessions = pw.Sessions(self.make)
        sessions.scan()
        first = sessions.threads["default"]
        late = self.serve("late")
        sessions.scan()
        self.assertIs(sessions.threads["default"], first)
        self.assertIn("late", sessions.threads)
        self.assertTrue(wait_for(lambda: late.subscribes))


class NoHerdrTest(unittest.TestCase):
    def test_exits_cleanly_without_herdr(self):
        with tempfile.TemporaryDirectory() as tmp:
            watcher = pw.Watcher(
                os.path.join(tmp, "missing", "herdr.sock"), "default", "sun", ["blocked"], 20.0, os.path.join(tmp, "e")
            )
            self.assertEqual(watcher.run(), 0)

    def test_sessions_exit_cleanly_without_herdr(self):
        with tempfile.TemporaryDirectory() as tmp, mock.patch.dict(os.environ, {"XDG_CONFIG_HOME": tmp}):
            self.assertEqual(pw.Sessions(lambda sock, name: None).run(), 0)

    def test_endpoints_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "e")
            self.assertEqual(pw.read_endpoints(path), [])
            Path(path).write_text("# c\n http://a/upX \nftp://b\n\n")
            self.assertEqual(pw.read_endpoints(path), ["http://a/upX"])


# --- herdr-core -------------------------------------------------------------
# Lines are what `hc events --json` prints (measured on an isolated core): first the
# snapshot object, then one JSON-RPC notification per line.


def term(address="t1", state=None, kind=None, turn=0, parent=None, labels=None, app="claude-code", msg="SECRET MSG"):
    status = None if state is None else {"app": app, "state": state, "msg": msg}
    if status and kind:
        status["kind"] = kind
    return {
        "address": address,
        "argv": ["SECRET-ARGV"],
        "labels": {"where": "alpha · ONE / build", **(labels or {})},
        "parent": parent,
        "status": status,
        "title": "SECRET TITLE",
        "screen": "exact",
        "turn": turn,
        "state": "live",
    }


def snap(*terminals, seq=0):
    return json.dumps({"channel": None, "seq": seq, "terminals": list(terminals)})


def ev(seq, kind, address, data):
    return json.dumps(
        {"jsonrpc": "2.0", "method": "event", "params": {"seq": seq, "type": kind, "terminal": address, "data": data}}
    )


def status_ev(seq, address, state, kind=None, app="claude-code"):
    status = {"app": app, "state": state, "msg": "SECRET MSG"}
    if kind:
        status["kind"] = kind
    return ev(seq, "status", address, {"records": {}, "reported": True, "status": status})


def turn_ev(seq, address, turn, state):
    return ev(seq, "turn", address, {"turn": turn, "state": state})


def missed(seq, *terminals):
    return json.dumps({"jsonrpc": "2.0", "method": "missed", "params": {"seq": seq, "terminals": list(terminals)}})


HEARTBEAT = json.dumps({"jsonrpc": "2.0", "method": "heartbeat", "params": {"seq": 1}})


class Timers:
    """A scheduler the test drives by hand: nothing fires until [advance]."""

    def __init__(self):
        self.now = 0.0
        self.waiting = []

    def schedule(self, delay, fn):
        self.waiting.append((self.now + delay, fn))

    def advance(self, seconds):
        self.now += seconds
        due = sorted((w for w in self.waiting if w[0] <= self.now), key=lambda w: w[0])
        self.waiting = [w for w in self.waiting if w[0] > self.now]
        for _, fn in due:
            fn()


class CoreWatchTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.endpoints = os.path.join(self.tmp.name, "push-endpoints")
        Path(self.endpoints).write_text("https://push.example/upAbc\n")
        self.posts = []
        self.timers = Timers()
        self.rounds = []

    def tearDown(self):
        self.tmp.cleanup()

    def watcher(self, **kw):
        def spawn():
            if not self.rounds:
                raise KeyboardInterrupt
            return iter(self.rounds.pop(0))

        return pw.CoreWatcher(
            "box",
            self.endpoints,
            kw.pop("debounce", 20.0),
            post=lambda url, body: self.posts.append(json.loads(body)) or 200,
            clock=lambda: self.timers.now,
            spawn=spawn,
            schedule=self.timers.schedule,
            sleep=lambda s: None,
            **kw,
        )

    def feed(self, *lines, w=None):
        w = w or self.watcher()
        self.last = w
        w.consume(iter(lines))
        return w

    # The rule, as the desktop app has it (herdr-app model.rs `alert`).
    def test_alert_rule(self):
        rule = pw.core_alert
        self.assertEqual(rule("working", "blocked"), "blocked")
        self.assertEqual(rule(None, "blocked"), "blocked")
        self.assertEqual(rule("working", "error"), "blocked")
        self.assertIsNone(rule("blocked", "blocked"))
        self.assertIsNone(rule("blocked", "error"))
        self.assertEqual(rule("working", "done"), "done")
        self.assertEqual(rule("working", "idle"), "done")
        self.assertEqual(rule("blocked", "idle"), "done")
        self.assertIsNone(rule("done", "idle"))
        self.assertIsNone(rule("idle", "done"))
        self.assertIsNone(rule(None, "idle"))
        self.assertIsNone(rule("working", None))
        self.assertIsNone(rule("working", "working"))

    def test_blocked_pushes_once_the_turn_is_known_and_carries_ids_only(self):
        self.feed(snap(term(state="working")), status_ev(1, "t1", "blocked", kind="permission"))
        self.assertEqual(self.posts, [])  # waits for the turn event, which comes after the status
        self.feed(turn_ev(2, "t1", 1, "blocked"), w=self.last)
        self.assertEqual(len(self.posts), 1)
        body = self.posts[0]
        self.assertEqual(
            (body["v"], body["event"], body["state"], body["host"], body["session"], body["pane_id"]),
            (1, "pane.agent_status_changed", "blocked", "box", "@core", "t1"),
        )
        self.assertEqual((body["agent"], body["kind"], body["turn"]), ("claude-code", "permission", 1))
        self.assertEqual((body["workspace"], body["tab"]), ("alpha · ONE", "build"))
        self.assertNotIn("SECRET", json.dumps(body))
        self.assertEqual(
            set(body),
            {
                "v",
                "event",
                "state",
                "host",
                "session",
                "pane_id",
                "agent",
                "kind",
                "turn",
                "workspace",
                "tab",
                "workspace_id",
                "tab_id",
                "ts",
            },
        )

    def test_blocked_without_a_turn_event_pushes_when_the_settle_time_runs_out(self):
        w = self.watcher()
        self.last = w
        w.consume(iter([snap(term(state="working")), status_ev(1, "t1", "blocked", kind="question")]))
        self.timers.advance(0.5)
        self.assertEqual(self.posts, [])
        self.timers.advance(1.0)
        self.assertEqual([(p["state"], p["turn"]) for p in self.posts], [("blocked", 0)])

    def test_a_block_already_there_at_start_is_silent(self):
        self.feed(snap(term(state="blocked", kind="permission", turn=3)), HEARTBEAT, turn_ev(1, "t1", 3, "blocked"))
        self.timers.advance(10)
        self.assertEqual(self.posts, [])

    def test_a_block_answered_before_the_push_goes_out_is_dropped(self):
        w = self.watcher()
        w.consume(iter([snap(term(state="working")), status_ev(1, "t1", "blocked"), status_ev(2, "t1", "working")]))
        self.timers.advance(5)
        self.assertEqual(self.posts, [])

    def test_a_helper_never_pushes(self):
        helper = term("t2", state="working", parent="t1")
        self.feed(
            snap(term("t1", state="idle"), helper),
            status_ev(1, "t2", "blocked", kind="permission"),
            turn_ev(2, "t2", 1, "blocked"),
            status_ev(3, "t2", "working"),
            status_ev(4, "t2", "done"),
            turn_ev(5, "t2", 2, "done"),
        )
        self.timers.advance(30)
        self.assertEqual(self.posts, [])

    def test_a_helper_created_later_never_pushes(self):
        self.feed(
            snap(),
            ev(1, "created", "t2", term("t2", parent="t1")),
            status_ev(2, "t2", "blocked"),
            turn_ev(3, "t2", 1, "blocked"),
        )
        self.timers.advance(30)
        self.assertEqual(self.posts, [])

    def test_done_nobody_looked_pushes_after_the_seen_grace(self):
        self.feed(snap(term(state="working")), status_ev(1, "t1", "done"), turn_ev(2, "t1", 4, "done"))
        self.timers.advance(1)
        self.assertEqual(self.posts, [])  # the desktop app may still mark it seen
        self.timers.advance(5)
        self.assertEqual([(p["state"], p["turn"], p["kind"]) for p in self.posts], [("done", 4, None)])
        self.assertNotIn("SECRET", json.dumps(self.posts[0]))

    def test_done_that_was_seen_does_not_push(self):
        self.feed(
            snap(term(state="working", turn=3, labels={"herdr.seen": "3"})),
            status_ev(1, "t1", "done"),
            turn_ev(2, "t1", 4, "done"),
            ev(3, "labels", "t1", {"labels": {"where": "alpha · ONE / build", "herdr.seen": "4"}}),
        )
        self.timers.advance(30)
        self.assertEqual(self.posts, [])

    def test_a_seen_mark_of_an_older_turn_does_not_hide_a_done(self):
        self.feed(
            snap(term(state="working", turn=3, labels={"herdr.seen": "3"})),
            status_ev(1, "t1", "done"),
            turn_ev(2, "t1", 4, "done"),
        )
        self.timers.advance(30)
        self.assertEqual(len(self.posts), 1)

    def test_done_then_working_again_before_the_grace_is_dropped(self):
        self.feed(
            snap(term(state="working")),
            status_ev(1, "t1", "done"),
            turn_ev(2, "t1", 1, "done"),
            status_ev(3, "t1", "working"),
        )
        self.timers.advance(30)
        self.assertEqual(self.posts, [])

    def test_done_then_idle_is_one_push_at_most(self):
        self.feed(
            snap(term(state="working")),
            status_ev(1, "t1", "done"),
            turn_ev(2, "t1", 1, "done"),
            status_ev(3, "t1", "idle"),
        )
        self.timers.advance(30)
        self.assertEqual(len(self.posts), 1)

    def test_error_counts_as_blocked(self):
        self.feed(snap(term(state="working")), status_ev(1, "t1", "error"), turn_ev(2, "t1", 1, "error"))
        self.assertEqual([p["state"] for p in self.posts], ["blocked"])

    def test_a_terminal_that_never_reported_a_status_never_pushes(self):
        self.feed(snap(term(state=None)), ev(1, "title", "t1", {"title": "x"}), ev(2, "foreground", "t1", {}))
        self.timers.advance(30)
        self.assertEqual(self.posts, [])

    def test_a_remote_machines_terminal_never_pushes(self):
        # Its own host's watcher says it; two watchers would ring twice for one block.
        self.feed(
            snap(term("rohan/t7", state="working")),
            status_ev(1, "rohan/t7", "blocked"),
            turn_ev(2, "rohan/t7", 1, "blocked"),
        )
        self.timers.advance(30)
        self.assertEqual(self.posts, [])

    def test_a_second_block_in_a_new_turn_pushes_even_inside_the_debounce(self):
        self.feed(
            snap(term(state="working")),
            status_ev(1, "t1", "blocked"),
            turn_ev(2, "t1", 1, "blocked"),
            status_ev(3, "t1", "working"),
            status_ev(4, "t1", "blocked"),
            turn_ev(5, "t1", 2, "blocked"),
        )
        self.assertEqual([p["turn"] for p in self.posts], [1, 2])

    def test_the_same_alert_of_the_same_turn_inside_the_debounce_pushes_once(self):
        w = self.feed(snap(term(state="working")), status_ev(1, "t1", "blocked"), turn_ev(2, "t1", 1, "blocked"))
        # No turn event this time: the turn is still 1.
        w.consume(iter([status_ev(3, "t1", "working"), status_ev(4, "t1", "blocked")]))
        self.timers.advance(2)
        self.assertEqual(len(self.posts), 1)
        self.timers.advance(21)
        w.consume(iter([status_ev(5, "t1", "working"), status_ev(6, "t1", "blocked")]))
        self.timers.advance(2)
        self.assertEqual(len(self.posts), 2)

    def test_a_removed_terminal_forgets_its_pending_push(self):
        self.feed(snap(term(state="working")), status_ev(1, "t1", "done"), ev(2, "removed", "t1", {}))
        self.timers.advance(30)
        self.assertEqual(self.posts, [])

    def test_missed_applies_the_fresh_snapshot_and_drops_older_events(self):
        w = self.watcher()
        w.consume(iter([snap(term(state="working"), seq=5)]))
        # The gap hid a block; the missed snapshot says t1 is blocked now.
        w.consume(iter([missed(60, term(state="blocked", kind="permission", turn=2))]))
        self.assertEqual([p["state"] for p in self.posts], ["blocked"])
        # An old status that was still queued (seq not above 60) changes nothing.
        w.consume(iter([status_ev(59, "t1", "working"), status_ev(61, "t1", "blocked")]))
        self.timers.advance(30)
        self.assertEqual(len(self.posts), 1)

    def test_missed_does_not_repeat_a_block_it_already_knew(self):
        w = self.watcher()
        w.consume(iter([snap(term(state="working")), status_ev(1, "t1", "blocked"), turn_ev(2, "t1", 1, "blocked")]))
        w.consume(iter([missed(30, term(state="blocked", kind="permission", turn=1))]))
        self.assertEqual(len(self.posts), 1)

    def test_missed_forgets_terminals_the_snapshot_no_longer_has(self):
        w = self.watcher()
        w.consume(iter([snap(term(state="working")), missed(9)]))
        self.assertEqual(w.terms, {})

    def test_a_dropped_stream_is_followed_again_and_only_news_pushes(self):
        self.rounds = [
            [snap(term(state="blocked", turn=1), term("t2", state="working"))],  # ends: hc went away
            [snap(term(state="blocked", turn=1), term("t2", state="blocked", turn=1), seq=0)],
        ]
        w = self.watcher()
        with self.assertRaises(KeyboardInterrupt):
            w.run()
        self.timers.advance(5)
        self.assertEqual([p["pane_id"] for p in self.posts], ["t2"])  # t1 was already blocked, t2 is new

    def test_a_failing_start_is_retried_with_backoff(self):
        sleeps = []
        calls = []

        def spawn():
            calls.append(1)
            if len(calls) < 4:
                raise OSError("no hc")
            raise KeyboardInterrupt

        w = pw.CoreWatcher("box", self.endpoints, 20.0, spawn=spawn, schedule=self.timers.schedule, sleep=sleeps.append)
        with self.assertRaises(KeyboardInterrupt):
            w.run()
        self.assertEqual(sleeps, [1.0, 2.0, 4.0])

    def test_garbage_lines_are_skipped(self):
        self.feed(
            "",
            "not json",
            "[1]",
            snap(term(state="working")),
            "{}",
            status_ev(1, "t1", "blocked"),
            turn_ev(2, "t1", 1, "blocked"),
        )
        self.assertEqual(len(self.posts), 1)

    def test_no_endpoints_means_no_push_and_no_crash(self):
        Path(self.endpoints).write_text("# nothing\n")
        self.feed(snap(term(state="working")), status_ev(1, "t1", "blocked"), turn_ev(2, "t1", 1, "blocked"))
        self.assertEqual(self.posts, [])

    def test_a_silent_stream_is_cut_after_the_stall_time(self):
        command = [
            "python3",
            "-c",
            'import time,sys;print(\'{"seq":0,"terminals":[]}\',flush=True);time.sleep(30)',
        ]
        started = time.monotonic()
        lines = list(pw.hc_lines(command, stall=0.5))
        self.assertEqual(len(lines), 1)
        self.assertLess(time.monotonic() - started, 10)

    def test_a_failing_post_or_odd_field_does_not_end_the_watcher(self):
        import http.client

        def post(url, body):
            raise http.client.BadStatusLine("x")

        w = pw.CoreWatcher("box", self.endpoints, 20.0, post=post, schedule=self.timers.schedule, sleep=lambda s: None)
        w.consume(iter([snap(term(state="working")), status_ev(1, "t1", "blocked"), turn_ev(2, "t1", 1, "blocked")]))
        self.rounds = [
            [
                snap(term(state="working")),
                json.dumps(
                    {
                        "method": "event",
                        "params": {"seq": 9, "type": "status", "terminal": "t1", "data": {"status": ["x"]}},
                    }
                ),
            ]
        ]
        calls = []

        def spawn():
            calls.append(1)
            if len(calls) > 2:
                raise KeyboardInterrupt
            return iter(self.rounds[0])

        w.spawn = spawn
        with self.assertRaises(KeyboardInterrupt):
            w.run()
        self.assertEqual(len(calls), 3)

    def test_the_push_goes_out_without_holding_the_lock(self):
        held = []
        w = pw.CoreWatcher(
            "box",
            self.endpoints,
            20.0,
            post=lambda url, body: held.append(w.lock._is_owned()) or 200,
            schedule=self.timers.schedule,
        )
        w.consume(iter([snap(term(state="working")), status_ev(1, "t1", "blocked"), turn_ev(2, "t1", 1, "blocked")]))
        self.assertEqual(held, [False])

    def test_a_terminal_that_comes_back_created_again_alerts_on_the_change(self):
        # A machine's line returns: its changed terminals arrive as created, with the news in the record.
        self.feed(snap(term("rohan/t7", state="working"), term(state="working")))
        self.feed(ev(1, "created", "t1", term(state="blocked", kind="permission", turn=2)), w=self.last)
        self.assertEqual([(p["pane_id"], p["state"]) for p in self.posts], [("t1", "blocked")])

    def test_a_new_terminal_created_idle_is_silent(self):
        self.feed(snap(), ev(1, "created", "t1", term(state="idle")))
        self.timers.advance(30)
        self.assertEqual(self.posts, [])

    def test_a_finish_waits_for_its_turn_before_it_is_judged_seen(self):
        # The last turn was seen (label 4 = turn 4); the finish is turn 5, but its event is late.
        w = self.feed(snap(term(state="working", turn=4, labels={"herdr.seen": "4"})), status_ev(1, "t1", "done"))
        self.timers.advance(3.1)
        self.assertEqual(self.posts, [])  # not judged against turn 4
        w.consume(iter([turn_ev(2, "t1", 5, "done")]))
        self.timers.advance(5)
        self.assertEqual([p["turn"] for p in self.posts], [5])

    def test_a_finish_whose_turn_never_comes_is_judged_after_a_second_wait(self):
        self.feed(snap(term(state="working", turn=4)), status_ev(1, "t1", "done"))
        self.timers.advance(3.1)
        self.assertEqual(self.posts, [])
        self.timers.advance(2)
        self.assertEqual(len(self.posts), 1)

    def test_only_words_the_core_defines_and_a_short_agent_name_reach_the_push(self):
        self.feed(
            snap(term(state="working", app="x" * 500)),
            status_ev(1, "t1", "blocked", kind="SECRET words", app="x" * 500),
        )
        self.timers.advance(3)
        body = self.posts[0]
        self.assertIsNone(body["kind"])
        self.assertLessEqual(len(body["agent"]), 64)

    def test_the_agent_is_never_taken_from_argv(self):
        t = term(state="working")
        t["status"]["app"] = ""
        self.feed(snap(t), status_ev(1, "t1", "blocked", app=""), turn_ev(2, "t1", 1, "blocked"))
        self.assertIsNone(self.posts[0]["agent"])

    def test_a_bool_is_no_seq_or_turn(self):
        w = self.feed(snap(term(state="working"), seq=5))
        w.consume(iter([ev(True, "title", "t1", {}), turn_ev(6, "t1", True, "done")]))
        self.assertEqual((w.floor, w.terms["t1"]["turn"]), (6, 0))

    def test_bytes_that_are_not_utf8_do_not_break_the_reader(self):
        command = ["python3", "-c", "import sys;sys.stdout.buffer.write(b'\\xff\\n{}\\n')"]
        self.assertEqual(len(list(pw.hc_lines(command, stall=5))), 2)

    def test_the_hc_to_run_can_be_named(self):
        self.assertEqual(pw.make_parser().parse_args(["--core", "--hc", "/opt/hc"]).hc, "/opt/hc")

    def test_the_core_watcher_is_off_unless_asked(self):
        parser = pw.make_parser()
        self.assertFalse(parser.parse_args([]).core)
        self.assertTrue(parser.parse_args(["--core"]).core)


if __name__ == "__main__":
    unittest.main()
