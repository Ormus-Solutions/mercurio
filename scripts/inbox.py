#!/usr/bin/env python3
"""Read and close the Mercurio wish list (wishes and error reports) on the usage host.

  scripts/inbox.py list [--all] [--json]   open items, newest first
  scripts/inbox.py show <id>               one item in full (id prefix is enough)
  scripts/inbox.py close <id> [note...]    mark done; the phone picks it up next upload

`close` writes ~/.local/share/mercurio-usage/closed.json; the phone reads it on
its next upload and marks the item done there. See docs/USAGE.md.
"""

from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import mercurio_usage as mu  # noqa: E402


def find(entries: list[dict], prefix: str) -> dict:
    matches = [e for e in entries if e["id"].startswith(prefix)]
    if not matches:
        sys.exit(f"No wish list item {prefix}")
    if len(matches) > 1:
        sys.exit(f"{prefix} matches {len(matches)} items; give more of the id")
    return matches[0]


def cmd_list(directory: Path, show_all: bool, as_json: bool) -> None:
    counts = mu.load_usage(directory).counts()
    entries = [e for e in mu.load_inbox(directory) if show_all or e["open"]]
    # Errors first, then wishes; newest first within each.
    entries.sort(key=lambda e: (e.get("type") != "error", -e.get("created", 0)))
    if as_json:
        for e in entries:
            e["related_usage"] = dict(mu.related_for(e, counts))
        json.dump(entries, sys.stdout, indent=2)
        sys.stdout.write("\n")
        return
    if not entries:
        print("Wish list is empty." if show_all else "No open wish list items.")
        return
    for e in entries:
        state = "open" if e["open"] else "done"
        print(f"{e['id'][:8]}  {e.get('type', 'wish'):5}  {mu.fmt_time(e.get('created'))}  {state:4}  {mu.summary_line(e)}")
        related = mu.related_for(e, counts)
        if related:
            print(f"          usage: {mu.fmt_related(related)}")


def cmd_show(directory: Path, prefix: str) -> None:
    counts = mu.load_usage(directory).counts()
    e = find(mu.load_inbox(directory), prefix)
    print(f"id:      {e['id']}")
    print(f"kind:    {e.get('type', 'wish')}")
    print(f"state:   {'open' if e['open'] else 'done'}")
    print(f"created: {mu.fmt_time(e.get('created'))}   updated: {mu.fmt_time(e.get('updated'))}")
    print(f"screen:  {e.get('screen') or '-'}   herdr host: {e.get('herdr')}")
    print(f"device:  {e.get('device')}")
    if e.get("note"):
        print(f"note:    {e['note']}")
    if e.get("closed_here"):
        print(f"closed here: {e['closed_here'].get('closed_at')} {e['closed_here'].get('note') or ''}".rstrip())
    related = mu.related_for(e, counts, limit=8)
    if related:
        print(f"usage:   {mu.fmt_related(related)}")
    print()
    print(e.get("text", ""))


def cmd_close(directory: Path, prefix: str, note: str | None) -> None:
    e = find(mu.load_inbox(directory), prefix)
    closed = mu.load_closed(directory)
    now = time.time()
    closed[e["id"]] = {
        "closed_at_ms": int(now * 1000),
        "closed_at": time.strftime("%Y-%m-%dT%H:%M:%S%z", time.localtime(now)),
        "note": note,
    }
    mu.write_closed(directory, closed)
    print(f"Closed {e['id'][:8]}. The phone marks it done on its next upload.")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dir", type=Path, help="store directory (default ~/.local/share/mercurio-usage)")
    sub = parser.add_subparsers(dest="command", required=True)
    p_list = sub.add_parser("list", help="open items, newest first")
    p_list.add_argument("--all", action="store_true", help="include done items")
    p_list.add_argument("--json", action="store_true", help="machine-readable output")
    p_show = sub.add_parser("show", help="one item in full")
    p_show.add_argument("id")
    p_close = sub.add_parser("close", help="mark an item done")
    p_close.add_argument("id")
    p_close.add_argument("note", nargs="*")
    args = parser.parse_args(argv)

    try:
        directory = mu.open_store(args.dir)
    except mu.PermissionProblem as error:
        mu.refuse_and_exit(error)
    if directory is None:
        print(f"No wish list uploaded yet ({args.dir or mu.data_dir()} does not exist).")
        return 0
    if args.command == "list":
        cmd_list(directory, args.all, args.json)
    elif args.command == "show":
        cmd_show(directory, args.id)
    elif args.command == "close":
        cmd_close(directory, args.id, " ".join(args.note) or None)
    return 0


if __name__ == "__main__":
    sys.exit(main())
