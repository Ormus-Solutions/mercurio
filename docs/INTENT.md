# Mercurio intent

Mercurio is a dictation-first SSH and terminal client for Android, forked from
ConnectBot. It exists for one workflow: driving AI coding agents — Claude Code,
Codex, Grok — running in Herdr on remote machines over Tailscale, from a phone,
mostly by voice (Wispr Flow).

That focus, not general-purpose SSH, decides every feature. The shorthand is
"vibe terminaling": you talk to an agent, watch it work, nudge it, switch to
another session, all one-handed.

## Who and how

One operator, many concurrent agent sessions across a personal fleet (a few home
and work machines, and ad-hoc hosts). The phone is a control surface for work happening
elsewhere, not where the work runs. Sessions are long-lived; the operator drops
in and out.

## Principles

- Dictation is the default input; the QWERTY keyboard is the fallback.
- Minimal chrome. Screen space goes to terminal output, not controls.
- Everything reachable with a thumb: large targets, left-edge gestures.
- Switching and starting sessions is fast — the session drawer, not menus.
- The app should tell the operator what the agents are doing without opening
  each session.

## Session activity summaries

Because Mercurio is mostly pointed at AI agents, the session list should say what
is going on inside each session, not just which host it is. A row that reads
`you@100.x.y.z` three times is useless; the operator needs to tell the
busy Claude session from the idle one at a glance.

Current implementation: each `TerminalBridge` keeps a rolling preview of the
last non-blank line of decoded output (escape and control sequences stripped),
exposed as `activityPreview` and shown as the drawer row's subtitle. It updates
live while the drawer is open.

Known limits: it is line-oriented, so a full-screen TUI that redraws in place
(rather than streaming lines) will show whatever last rendered, not a semantic
summary. It does not understand any specific agent's output.

Per-agent state now comes from Herdr, not from the output. On a Herdr host the
app reads each agent's state (blocked, done, working, idle) and shows it in the
Inbox, in the Spaces tiles, in the Needs you count and in the Needs you
notification, which can carry Approve and Deny for Claude Code and Codex. The
drawer's preview above is unchanged.

Not built: the current tool or command, and token or cost counters.

## Agent-attention notifications

The operator drives sessions from a phone that spends most of its time in a
pocket. The app must reach out when an agent wants attention, not wait to be
checked. `TerminalBridge` raises an attention signal on two heuristics — a
terminal bell (agents ring it on prompts) and output going quiet after a burst
(a turn finished) — and `TerminalManager` turns that into a high-priority
notification, but only when the user is away: the app is backgrounded or a
different session is on screen. Tapping the notification jumps to that host.

Sessions that drop while the app is away (network change, device sleep)
reconnect automatically when it returns to the foreground; user-closed sessions
stay closed.

Known limits: the idle heuristic is timing-based, not semantic — a long pause
mid-stream can read as "finished", and a chatty log can ping more than you'd
like. Bell depends on the agent/shell actually ringing it. On a Herdr host the
agent's own state replaces both guesses (see above).

## Non-goals

- Being a faithful upstream ConnectBot. Mercurio diverges toward the agent use case.
- General terminal power-user features that do not serve voice-driven agent work.
- Storing or transmitting session content anywhere off-device.
