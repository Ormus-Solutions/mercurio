"""Shared reader for the Mercurio usage log and wish list on the usage host.

The phone uploads over its own SSH session to the usage host (Settings calls it Sun) into
~/.local/share/mercurio-usage (override with MERCURIO_USAGE_DIR):

  <device>.jsonl          usage events and daily roll-ups, appended
  <device>.cursor         last event sequence number this host holds
  <device>-wishes.jsonl   wish list snapshot (wishes and error reports)
  closed.json             entries closed here with `inbox.py close`

Standard library only. See docs/USAGE.md.
"""

from __future__ import annotations

import json
import os
import re
import stat
import sys
import time
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
DEFAULT_DIR = Path.home() / ".local" / "share" / "mercurio-usage"
ACTIONS_FILE = REPO / "docs" / "usage-actions.txt"

# Words in a wish or error, mapped to the action ids they are about. Any action id
# whose parts contain a word of the text also counts as related.
SYNONYMS = {
    "scroll": ["key:up", "key:down", "herdr:scroll", "herdr:pgup", "herdr:pgdn", "reader:pgup", "reader:pgdn"],
    "arrow": ["key:up", "key:down", "key:left", "key:right"],
    "button": ["key:mode", "key:esc", "key:stop", "key:y", "key:n", "key:tab", "key:enter"],
    "clutter": ["key:mode", "key:esc", "key:stop", "key:ctrl", "key:y", "key:n", "key:tab", "key:left", "key:right"],
    "keys": ["key:mode", "key:esc", "key:stop", "key:ctrl", "key:y", "key:n", "key:tab", "key:enter"],
    "read": ["reader:open"],
    "voice": ["dictation:mic", "send:ime"],
    "dictate": ["dictation:mic", "send:ime"],
    "dictation": ["dictation:mic", "send:ime"],
    "screenshot": ["paste-screenshot"],
    "switch": ["drawer:switch", "herdr:goto", "herdr:prev-tab", "herdr:next-tab"],
    "session": ["drawer:switch", "session:start", "session:fail"],
    "connect": ["session:start", "session:fail", "session:reconnect", "machines:connect"],
    "connection": ["session:start", "session:fail", "session:reconnect"],
    "reconnect": ["session:reconnect", "overlay:reconnect", "menu:reconnect"],
    "agent": ["herdr:needs-you", "herdr:goto"],
    "tab": ["key:tab", "herdr:prev-tab", "herdr:next-tab"],
}


class PermissionProblem(Exception):
    pass


def data_dir() -> Path:
    return Path(os.environ.get("MERCURIO_USAGE_DIR", DEFAULT_DIR)).expanduser()


def check_permissions(directory: Path) -> list[str]:
    """Problems that make the store readable by anyone but its owner."""
    problems = []
    mode = stat.S_IMODE(directory.stat().st_mode)
    if mode & 0o077:
        problems.append(f"{directory} is mode {mode:03o}, want 700")
    for path in sorted(directory.iterdir()):
        if path.is_file():
            fmode = stat.S_IMODE(path.stat().st_mode)
            if fmode & 0o077:
                problems.append(f"{path} is mode {fmode:03o}, want 600")
    return problems


def open_store(directory: Path | None = None) -> Path | None:
    """The store directory, or None when nothing was uploaded yet. Refuses unsafe modes."""
    directory = directory or data_dir()
    if not directory.exists():
        return None
    problems = check_permissions(directory)
    if problems:
        raise PermissionProblem(
            "Refusing to read the Mercurio usage store: other users could read it.\n  "
            + "\n  ".join(problems)
            + f"\nFix it with:\n  chmod 700 {directory} && chmod 600 {directory}/*"
        )
    return directory


def refuse_and_exit(error: PermissionProblem) -> None:
    print(error, file=sys.stderr)
    sys.exit(2)


def known_actions() -> list[str]:
    """Every control id the app can record, from docs/usage-actions.txt."""
    path = Path(os.environ.get("MERCURIO_ACTIONS_FILE", ACTIONS_FILE))
    if not path.exists():
        return []
    return [line.strip() for line in path.read_text().splitlines() if line.strip() and not line.startswith("#")]


def _jsonl(path: Path):
    with path.open() as handle:
        for line in handle:
            line = line.strip()
            if not line:
                continue
            try:
                yield json.loads(line)
            except json.JSONDecodeError:
                continue


@dataclass
class Usage:
    events: list[dict] = field(default_factory=list)
    dailies: list[dict] = field(default_factory=list)

    def rows(self):
        """(day, action, screen, herdr, count, duration_ms) for raw events and roll-ups alike."""
        for e in self.events:
            day = time.strftime("%Y-%m-%d", time.localtime(e["ts"] / 1000))
            yield day, e["action"], e.get("screen"), e.get("herdr"), 1, e.get("duration_ms") or 0
        for d in self.dailies:
            yield d["day"], d["action"], d.get("screen"), d.get("herdr"), d["count"], d.get("duration_ms") or 0

    def counts(self) -> Counter:
        counter = Counter()
        for _, action, _, _, count, _ in self.rows():
            counter[action] += count
        return counter


def load_usage(directory: Path) -> Usage:
    """Events deduplicated by (device, seq); roll-ups by (device, id), last one wins."""
    events: dict[tuple, dict] = {}
    dailies: dict[tuple, dict] = {}
    for path in sorted(directory.glob("*.jsonl")):
        if path.name.startswith(".") or path.name.endswith("-wishes.jsonl"):
            continue
        for row in _jsonl(path):
            device = row.get("device", path.stem)
            if row.get("type") == "event":
                events[(device, row["seq"])] = row
            elif row.get("type") == "daily":
                dailies[(device, row["id"])] = row
    return Usage(list(events.values()), list(dailies.values()))


def load_closed(directory: Path) -> dict:
    path = directory / "closed.json"
    if not path.exists():
        return {}
    try:
        return json.loads(path.read_text())
    except json.JSONDecodeError:
        return {}


def load_inbox(directory: Path) -> list[dict]:
    """Every wish and error, newest first, with `open` set from the phone and closed.json."""
    closed = load_closed(directory)
    entries = {}
    for path in sorted(directory.glob("*-wishes.jsonl")):
        if path.name.startswith("."):
            continue
        for row in _jsonl(path):
            current = entries.get(row["id"])
            if current is None or row.get("rev", 0) >= current.get("rev", 0):
                entries[row["id"]] = row
    for entry in entries.values():
        marker = closed.get(entry["id"])
        entry["closed_here"] = marker
        entry["open"] = not entry.get("done") and marker is None
    return sorted(entries.values(), key=lambda e: e.get("created", 0), reverse=True)


def write_closed(directory: Path, closed: dict) -> None:
    """Atomically write closed.json with mode 600."""
    tmp = directory / ".closed.json.tmp"
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as handle:
        json.dump(closed, handle, indent=2, sort_keys=True)
        handle.write("\n")
    os.chmod(tmp, 0o600)
    os.replace(tmp, directory / "closed.json")


ERROR_RELATED = ["session:start", "session:fail", "session:reconnect"]


def summary_line(entry: dict) -> str:
    """One line for a list: a wish's first line, or an error's host and failure."""
    text = (entry.get("text") or "").strip()
    if entry.get("type") != "error":
        return text.splitlines()[0] if text else ""
    lines = text.splitlines()
    host = next((line.removeprefix("- Host:").strip() for line in lines if line.startswith("- Host:")), "")
    failure = ""
    if "#### Failure" in lines:
        after = [line for line in lines[lines.index("#### Failure") + 1:] if line.strip() and not line.startswith("```")]
        failure = after[0].strip() if after else ""
    return " | ".join(part for part in (host, failure) if part) or (lines[0] if lines else "")


def related_for(entry: dict, counts: Counter, limit: int = 4) -> list[tuple[str, int]]:
    """Usage related to an entry: session counts for an error, keyword matches for a wish."""
    if entry.get("type") == "error":
        return [(a, counts.get(a, 0)) for a in ERROR_RELATED]
    return related_counts(entry.get("text", ""), counts, limit)


def related_counts(text: str, counts: Counter, limit: int = 4) -> list[tuple[str, int]]:
    """Usage counts for action ids the text talks about."""
    words = {w for w in re.findall(r"[a-z]+", text.lower()) if len(w) >= 3}
    related = set()
    for word in words:
        related.update(SYNONYMS.get(word, []))
        for action in counts:
            if word in re.split(r"[:\-]", action):
                related.add(action)
    ranked = sorted(related, key=lambda a: (-counts.get(a, 0), a))
    return [(a, counts.get(a, 0)) for a in ranked[:limit]]


def fmt_related(related: list[tuple[str, int]]) -> str:
    return ", ".join(f"{action} used {n}x" if n else f"{action} never used" for action, n in related)


def fmt_time(ms: int | None) -> str:
    if not ms:
        return "?"
    return time.strftime("%Y-%m-%d %H:%M", time.localtime(ms / 1000))
