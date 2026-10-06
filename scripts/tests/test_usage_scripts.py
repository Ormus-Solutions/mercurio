"""Tests for usage-report.py, inbox.py and mercurio_usage.py.

Run: python3 -m unittest discover -s scripts/tests
"""

from __future__ import annotations

import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS))

import mercurio_usage as mu  # noqa: E402

DEVICE = "0f1e2d3c-4b5a-4968-8776-655443322110"
NOW = time.time()
MS = int(NOW * 1000)


def load_report_module():
    spec = importlib.util.spec_from_file_location("usage_report", SCRIPTS / "usage-report.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def write_jsonl(path: Path, rows: list[dict]) -> None:
    path.write_text("".join(json.dumps(r) + "\n" for r in rows))
    os.chmod(path, 0o600)


class StoreFixture(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name) / "mercurio-usage"
        self.dir.mkdir(mode=0o700)
        os.chmod(self.dir, 0o700)
        events = []
        seq = 0
        for action, n, herdr in [("key:up", 41, True), ("key:esc", 5, True), ("reader:open", 12, True), ("key:left", 1, False)]:
            for _ in range(n):
                seq += 1
                events.append({"v": 1, "type": "event", "device": DEVICE, "seq": seq, "ts": MS - seq * 1000,
                               "action": action, "screen": "console", "herdr": herdr, "duration_ms": None, "reason": None})
        # A duplicate line, as from a retried append, must count once.
        events.append(dict(events[0]))
        events.append({"v": 1, "type": "event", "device": DEVICE, "seq": seq + 1, "ts": MS, "action": "session:end",
                       "screen": "console", "herdr": True, "duration_ms": 600000, "reason": "REMOTE_EOF"})
        events.append({"v": 1, "type": "daily", "device": DEVICE, "id": "d-1", "day": "2026-08-25", "action": "key:up",
                       "screen": "console", "herdr": True, "count": 9, "duration_ms": 0, "uploaded": False})
        write_jsonl(self.dir / f"{DEVICE}.jsonl", events)
        write_jsonl(self.dir / f"{DEVICE}-wishes.jsonl", [
            {"v": 1, "type": "wish", "device": DEVICE, "id": "aaaa1111-0000-0000-0000-000000000000", "rev": 1,
             "text": "I tend to use the top arrow key to scroll up", "created": MS - 5000, "updated": MS - 5000,
             "screen": "console", "herdr": True, "done": False, "note": None},
            {"v": 1, "type": "wish", "device": DEVICE, "id": "bbbb2222-0000-0000-0000-000000000000", "rev": 2,
             "text": "reduce the button clutter", "created": MS - 1000, "updated": MS - 1000,
             "screen": "console", "herdr": True, "done": False, "note": None},
            {"v": 1, "type": "error", "device": DEVICE, "id": "cccc3333-0000-0000-0000-000000000000", "rev": 1,
             "text": "Mercurio connection report\nreason AUTH_FAIL", "created": MS - 9000, "updated": MS - 9000,
             "screen": "console", "herdr": False, "done": False, "note": None},
            {"v": 1, "type": "wish", "device": DEVICE, "id": "dddd4444-0000-0000-0000-000000000000", "rev": 3,
             "text": "already shipped", "created": MS - 20000, "updated": MS - 20000,
             "screen": "hostlist", "herdr": None, "done": True, "note": "PR #50"},
        ])

    def tearDown(self) -> None:
        self.tmp.cleanup()

    def run_script(self, name: str, *args: str) -> subprocess.CompletedProcess:
        return subprocess.run([sys.executable, str(SCRIPTS / name), "--dir", str(self.dir), *args],
                              capture_output=True, text=True, check=False)


class ReportTest(StoreFixture):
    def test_report_shows_open_errors_then_wishes_newest_first_with_usage(self) -> None:
        out = load_report_module().report(self.dir, now=NOW)
        wish_section = out.split("## Open wish list (3)")[1].split("## Actions ranked")[0]
        error = wish_section.index("connection report")
        clutter = wish_section.index("reduce the button clutter")
        scroll = wish_section.index("top arrow key to scroll")
        self.assertLess(error, clutter)
        self.assertLess(clutter, scroll)  # newest wish first
        self.assertIn("key:up used 50x", wish_section)  # 41 raw + 9 rolled up, duplicate ignored
        self.assertNotIn("already shipped", wish_section)
        self.assertLess(out.index("## Open wish list"), out.index("## Actions ranked by use"))

    def test_report_ranks_actions_and_lists_never_used(self) -> None:
        out = load_report_module().report(self.dir, now=NOW)
        ranked = out.split("## Actions ranked by use")[1].split("## Never used")[0]
        self.assertLess(ranked.index("key:up"), ranked.index("reader:open"))
        self.assertIn("| key:up | 50 |", ranked)
        never = out.split("## Never used")[1].split("## Per screen")[0]
        self.assertIn("`key:mode`", never)
        self.assertIn("`herdr:goto`", never)
        self.assertNotIn("`key:up`", never)
        self.assertNotIn("session:start", never)

    def test_report_splits_screen_host_type_trends_and_sessions(self) -> None:
        out = load_report_module().report(self.dir, now=NOW)
        self.assertIn("| console |", out.split("## Per screen")[1])
        host = out.split("## Per host type")[1].split("## Trends")[0]
        self.assertIn("| herdr |", host)
        self.assertIn("| plain | 1 | key:left 1 |", host)
        self.assertIn("## Trends (last 7 days vs the 7 before)", out)
        self.assertIn("median session: 10.0 min", out)

    def test_cli_prints_markdown(self) -> None:
        result = self.run_script("usage-report.py")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(result.stdout.startswith("# Mercurio usage report"))


class InboxTest(StoreFixture):
    def test_list_shows_open_items_errors_first_with_usage(self) -> None:
        result = self.run_script("inbox.py", "list")
        self.assertEqual(result.returncode, 0, result.stderr)
        lines = result.stdout.splitlines()
        self.assertTrue(lines[0].startswith("cccc3333  error"))
        self.assertIn("bbbb2222", lines[2])
        self.assertIn("usage: key:up used 50x", result.stdout)
        self.assertNotIn("already shipped", result.stdout)

    def test_list_json(self) -> None:
        result = self.run_script("inbox.py", "list", "--json")
        items = json.loads(result.stdout)
        self.assertEqual([i["type"] for i in items], ["error", "wish", "wish"])
        self.assertEqual(items[2]["related_usage"]["key:up"], 50)

    def test_show_and_close(self) -> None:
        shown = self.run_script("inbox.py", "show", "aaaa")
        self.assertIn("I tend to use the top arrow key to scroll up", shown.stdout)
        closed = self.run_script("inbox.py", "close", "aaaa", "shipped", "in", "#61")
        self.assertEqual(closed.returncode, 0, closed.stderr)
        marker = json.loads((self.dir / "closed.json").read_text())["aaaa1111-0000-0000-0000-000000000000"]
        self.assertEqual(marker["note"], "shipped in #61")
        self.assertGreater(marker["closed_at_ms"], 0)
        self.assertEqual(os.stat(self.dir / "closed.json").st_mode & 0o777, 0o600)
        self.assertNotIn("aaaa1111", self.run_script("inbox.py", "list").stdout)

    def test_refuses_group_or_world_readable_store(self) -> None:
        os.chmod(self.dir / f"{DEVICE}-wishes.jsonl", 0o644)
        result = self.run_script("inbox.py", "list")
        self.assertEqual(result.returncode, 2)
        self.assertIn("want 600", result.stderr)
        self.assertIn(f"chmod 700 {self.dir} && chmod 600 {self.dir}/*", result.stderr)
        os.chmod(self.dir / f"{DEVICE}-wishes.jsonl", 0o600)
        os.chmod(self.dir, 0o750)
        result = self.run_script("inbox.py", "list")
        self.assertEqual(result.returncode, 2)
        self.assertIn("want 700", result.stderr)

    def test_report_refuses_too(self) -> None:
        os.chmod(self.dir, 0o755)
        self.assertEqual(self.run_script("usage-report.py").returncode, 2)

    def test_missing_store_is_not_an_error(self) -> None:
        result = subprocess.run([sys.executable, str(SCRIPTS / "inbox.py"), "--dir", str(self.dir / "nope"), "list"],
                                capture_output=True, text=True, check=False)
        self.assertEqual(result.returncode, 0)
        self.assertIn("No wish list uploaded yet", result.stdout)


class RelatedCountsTest(unittest.TestCase):
    def test_synonyms_and_id_parts(self) -> None:
        counts = mu.Counter({"key:up": 3, "reader:open": 2})
        related = dict(mu.related_counts("scroll the reader", counts))
        self.assertEqual(related["key:up"], 3)
        self.assertEqual(related["reader:open"], 2)

    def test_error_summary_and_session_counts(self) -> None:
        entry = {"type": "error", "text": "### Mercurio connection report\n\n- Host: Sun (ssh) alice@100.100.1.1:22\n\n"
                 "#### Failure\n```text\njava.io.IOException: hostkey not accepted\n    at x\n```"}
        self.assertEqual(mu.summary_line(entry), "Sun (ssh) alice@100.100.1.1:22 | java.io.IOException: hostkey not accepted")
        counts = mu.Counter({"session:fail": 2, "key:up": 9})
        self.assertEqual(dict(mu.related_for(entry, counts)), {"session:start": 0, "session:fail": 2, "session:reconnect": 0})


if __name__ == "__main__":
    unittest.main()
