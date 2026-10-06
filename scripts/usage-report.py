#!/usr/bin/env python3
"""Markdown report of how Mercurio is used, from the logs the phone uploads to the usage host.

  scripts/usage-report.py            # report on ~/.local/share/mercurio-usage
  scripts/usage-report.py --days 14  # trend window

Open errors and wishes come first, then actions ranked by use, controls never
used, per-screen and per-host-type splits, trends and sessions. See docs/USAGE.md.
"""

from __future__ import annotations

import argparse
import statistics
import sys
import time
from collections import Counter, defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import mercurio_usage as mu  # noqa: E402

# Recorded by the app, not tapped: kept out of "never used".
NOT_CONTROLS = {
    "session:start",
    "session:end",
    "session:fail",
    "session:reconnect",
    "key:upload-refused-hostkey",
    "key:other",
}


def table(header: list[str], rows: list[list]) -> list[str]:
    out = ["| " + " | ".join(header) + " |", "|" + "|".join("---" for _ in header) + "|"]
    out += ["| " + " | ".join(str(c) for c in row) + " |" for row in rows]
    return out


def report(directory: Path, days: int = 7, now: float | None = None) -> str:
    now = now or time.time()
    usage = mu.load_usage(directory)
    inbox = mu.load_inbox(directory)
    counts = usage.counts()
    total = sum(counts.values())
    rows = list(usage.rows())
    lines = ["# Mercurio usage report", ""]
    devices = {e.get("device") for e in usage.events + usage.dailies} | {e.get("device") for e in inbox}
    span = sorted({r[0] for r in rows})
    lines.append(
        f"_{total} recorded actions from {len(devices - {None})} device(s)"
        + (f", {span[0]} to {span[-1]}" if span else "")
        + f". Generated {time.strftime('%Y-%m-%d %H:%M', time.localtime(now))}._"
    )
    lines.append("")

    open_items = [e for e in inbox if e["open"]]
    lines.append(f"## Open wish list ({len(open_items)})")
    lines.append("")
    if not open_items:
        lines.append("Nothing open.")
    for kind in ("error", "wish"):
        for entry in [e for e in open_items if e.get("type") == kind]:
            lines.append(f"- **{kind}** {mu.fmt_time(entry.get('created'))} `{entry['id'][:8]}`: {mu.summary_line(entry)}")
            related = mu.related_for(entry, counts)
            if related:
                lines.append(f"  - usage: {mu.fmt_related(related)}")
    lines.append("")

    lines.append("## Actions ranked by use")
    lines.append("")
    ranked = counts.most_common()
    lines += table(["action", "uses", "share"], [[a, n, f"{100 * n / total:.1f}%"] for a, n in ranked]) if ranked else ["No usage yet."]
    lines.append("")

    known = mu.known_actions()
    never = [a for a in known if a not in NOT_CONTROLS and counts.get(a, 0) == 0]
    lines.append(f"## Never used ({len(never)} of {len([a for a in known if a not in NOT_CONTROLS])} controls)")
    lines.append("")
    lines.append(", ".join(f"`{a}`" for a in never) if never else "Every known control was used at least once.")
    unknown = sorted(a for a in counts if known and a not in known)
    if unknown:
        lines.append("")
        lines.append("Recorded but missing from docs/usage-actions.txt: " + ", ".join(f"`{a}`" for a in unknown))
    lines.append("")

    lines.append("## Per screen")
    lines.append("")
    by_screen: dict[str, Counter] = defaultdict(Counter)
    for _, action, screen, _, n, _ in rows:
        by_screen[screen or "none"][action] += n
    screen_rows = sorted(by_screen.items(), key=lambda kv: -sum(kv[1].values()))
    lines += table(
        ["screen", "uses", "top actions"],
        [[s, sum(c.values()), ", ".join(f"{a} {n}" for a, n in c.most_common(4))] for s, c in screen_rows],
    ) if screen_rows else ["No usage yet."]
    lines.append("")

    lines.append("## Per host type")
    lines.append("")
    by_host: dict[str, Counter] = defaultdict(Counter)
    for _, action, _, herdr, n, _ in rows:
        by_host["herdr" if herdr is True else "plain" if herdr is False else "no session"][action] += n
    lines += table(
        ["host type", "uses", "top actions"],
        [[h, sum(c.values()), ", ".join(f"{a} {n}" for a, n in c.most_common(5))] for h, c in sorted(by_host.items())],
    ) if by_host else ["No usage yet."]
    lines.append("")

    lines.append(f"## Trends (last {days} days vs the {days} before)")
    lines.append("")
    today = time.strftime("%Y-%m-%d", time.localtime(now))
    recent_start = time.strftime("%Y-%m-%d", time.localtime(now - (days - 1) * 86400))
    prior_start = time.strftime("%Y-%m-%d", time.localtime(now - (2 * days - 1) * 86400))
    recent, prior = Counter(), Counter()
    per_day = Counter()
    for day, action, _, _, n, _ in rows:
        per_day[day] += n
        if recent_start <= day <= today:
            recent[action] += n
        elif prior_start <= day < recent_start:
            prior[action] += n
    movers = sorted(set(recent) | set(prior), key=lambda a: -abs(recent[a] - prior[a]))[:12]
    lines += table(["action", "recent", "before", "change"], [[a, recent[a], prior[a], f"{recent[a] - prior[a]:+d}"] for a in movers]) if movers else ["Not enough history yet."]
    lines.append("")
    if per_day:
        lines.append("Daily totals: " + ", ".join(f"{d} {per_day[d]}" for d in sorted(per_day)[-14:]))
        lines.append("")

    lines.append("## Sessions")
    lines.append("")
    durations = [e["duration_ms"] for e in usage.events if e["action"] == "session:end" and e.get("duration_ms")]
    failures = Counter(e.get("reason") or "?" for e in usage.events if e["action"] == "session:fail")
    lines.append(f"- started: {counts.get('session:start', 0)}, reconnects: {counts.get('session:reconnect', 0)}, failures: {counts.get('session:fail', 0)}")
    if durations:
        lines.append(f"- median session: {statistics.median(durations) / 60000:.1f} min over {len(durations)} sessions")
    if failures:
        lines.append("- failures by reason: " + ", ".join(f"{r} {n}" for r, n in failures.most_common()))
    if counts.get("key:upload-refused-hostkey"):
        lines.append(f"- uploads refused for a host key mismatch: {counts['key:upload-refused-hostkey']}")
    return "\n".join(lines) + "\n"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--days", type=int, default=7, help="trend window in days (default 7)")
    parser.add_argument("--dir", type=Path, help="store directory (default ~/.local/share/mercurio-usage)")
    args = parser.parse_args(argv)
    try:
        directory = mu.open_store(args.dir)
    except mu.PermissionProblem as error:
        mu.refuse_and_exit(error)
    if directory is None:
        print(f"No usage uploaded yet ({args.dir or mu.data_dir()} does not exist).")
        return 0
    sys.stdout.write(report(directory, args.days))
    return 0


if __name__ == "__main__":
    sys.exit(main())
