# Mercurio

Mercurio is an Android console for driving AI coding agents that run on your
own machines. It connects over SSH, works with [Herdr][herdr] on the host, and
puts the keys, notifications and approvals for agents such as Claude Code and
Codex on a phone.

Mercurio is a fork of [ConnectBot][connectbot], the Android SSH client by Kenny
Root and contributors. The SSH stack, the terminal emulator and host and key
management come from ConnectBot. Mercurio is maintained by
[Ormus Solutions][ormus].

## What it does

### Terminal and input

- **Compose bar.** Two rows of keys under the terminal: paging ↑ and ↓, Herdr,
  Needs you, Esc, Tab, a text field for the keyboard or the phone's voice
  input, and one ⏎ key that sends the field's text and then Enter, or Enter
  alone when the field is empty. Needs you shows how many agents wait for you
  in every Herdr session, in gold while any does; a tap jumps to the next one,
  cycling, and a long press (or a tap with none waiting) opens the Agents board.
- **Tray.** ⋯ or a swipe up on the bar opens more keys: arrows, jump to the
  latest output, Mode, clear the typed line, the read view, copy the latest
  URL (hold it to open the URL instead, whole even when it wrapped at the screen
  edge), copy the agent's last reply, paste a screenshot, Herdr's sidebar and
  the slash menu.
- **Read view.** Recent output in a full-screen view you can scroll and select.
  On a Herdr host it reads the focused pane with `herdr pane read`.
- **Draft view.** The text input button opens the whole draft in a large view,
  so a long dictated message can be read before it goes out.
- **Slash menu.** The agent's built-in slash commands (Claude Code, Codex,
  Grok) and your own commands and skills, read from the host.
- **Command palette.** One search over every action, from the console menu.
- **Paste screenshot.** Uploads the phone's newest screenshot to the host over
  SFTP and puts its path in the text field.
- **Session drawer.** Every open session on every host, with its state and the
  last line of output. Tap the host name or swipe in from the left edge.
- **Machines.** A list of your Tailscale machines, read from `tailscale status`
  on a connected host. One tap connects.

### Herdr

- **Found on any SSH host.** Herdr features turn on once Herdr answers on the
  host. There is no setting for it.
- **Workspace picker.** Connecting to a Herdr host offers its workspaces before
  attaching. The + beside a workspace opens it in a console of its own as well.
- **More consoles.** The session drawer of a Herdr host has Open a workspace:
  every workspace of every running Herdr session. A row opens it in a console of
  its own, and the open consoles stay where they are. Herdr gives each client its
  own workspace, so two consoles can show two workspaces of one session.
- **Consoles on connect.** A host can list the consoles to open when it
  connects, in `~/.config/mercurio/consoles`: one per line, a Herdr session and,
  after a space, the label of a workspace to land on. The first console attaches
  the first line and the others open beside it, with no picker. Sessions that do
  not run are left out; with no list, the picker runs as before.
- **Workspaces.** The host list opens with one key per line of that list, for
  every saved Herdr host that has connected once: named by the session, or by
  the workspace the line lands on. A tap connects the host with that one
  console and nothing else; while the host is connected, the key opens the
  console beside the others, or shows it when it is already open.
- **A tap from the launcher.** A host URI intent with the Herdr extras (the
  pane, the session, the view) may name the agent's machine as the tailnet
  names it (`HERD_HOSTS`: `name,dns,ip`). Mercurio then opens the saved host on
  that machine whose post-login attaches the session, or a plain `herdr` host
  with a console for it, whatever the phone calls the host. When no saved host
  there does either for the tap's account (none at all, or only hosts that run
  something else or attach another session), one is saved from the tap (the
  URI's account, a plain `herdr` post-login, named by the URI's fragment else the
  machine, a free name), so the tap lands on the session and every later tap
  resolves to it; only a tap that is not SSH or names no account or host falls to
  the URI as before.
- **Home feed.** Settings can name the URL of a sessions feed: one JSON
  document that lists every workboard with its machine, Herdr session, agents
  and workspaces. The phone reads it while the host list shows and keeps the
  last read with its time; with no URL set, nothing is read.
- **Workboards.** With a feed, the host list opens with one block per
  workboard (its name, its machine, how many of its agents wait on you) and
  one key per workspace. A tap opens that Herdr session on the saved host the
  machine's names pick, landed on the workspace; a key with no saved host on
  its machine is off and says so. The kept Workspaces keys for workspaces the
  feed already opens step aside; the rest stay beside the blocks.
- **Hosts by place.** A saved host reads, under its nickname, the machine the
  feed or the tailnet names and the Herdr session its post-login attaches
  (`lab · work`, with the port when it is not 22), instead of its address; a
  host nobody names keeps its address. The tailnet names every machine but the
  one its list was read from; the feed names them all. With a Home feed read,
  the rows go by machine, then session, then nickname, unless you sort by
  colour; without one the list keeps its order.
- **Herdr panel.** Every Herdr action, one group at a time, with numbered keys
  for the focused workspace's tabs. Long press a key to hide or change it, or
  add your own keystroke keys.
- **Keys from the host's config.** The prefix and every client key come from
  the host's Herdr config, with Herdr's defaults for anything it leaves out.
- **Gestures.** On the terminal of a Herdr session, one finger across switches
  tabs, two fingers across move pane focus, and two fingers up or down, or a
  fast one-finger flick up or down, step through workspaces. When the host's
  Herdr client has saved machines, that step goes through Herdr's own switcher
  (prefix+w, an arrow, Enter), so it reaches workspaces on the other machines
  too. A one-finger drag up or down that is not a flick still scrolls; a pinch
  still zooms.
- **Herd screen.** Every agent in the session by state (blocked, done, working,
  idle). Tap one to focus its pane.
- **Reattach.** When the Herdr client drops with `lost connection to server`,
  Mercurio runs `herdr` again and the session comes back.

### Notifications and approvals

- **Needs you.** A notification when an agent is blocked on you, and one when
  it finishes, from Herdr's agent state, in the default session and in the
  session the console attached. The title names the agent, the host and, for
  a named session, the session; the text the workspace, the tab and the
  agent's title; a "Needs you" expanded shows the end of the pane, and its
  time is when the agent was first seen blocked. Host rows with the same
  user, host name and port share one notice. Once Herdr answers on a host,
  the terminal's own idle and bell guesses stay quiet for every console of
  that host, including a shell there that runs an agent outside Herdr.
- **Approve or deny.** Answer a blocked Claude Code or Codex agent from its
  notification. This works with no session open: Mercurio logs in with the
  host's saved key, sends the answer and logs out. Other agents get the
  notification without the buttons.
- **Push.** While Android has the app frozen, a watcher on the host can wake
  it through [UnifiedPush][unifiedpush] and an [ntfy][ntfy] server you run.
  See [docs/PUSH.md](docs/PUSH.md).

### When something fails

- **Fix prompt.** A failed connection, a Herdr host that does not answer, or a
  failed Herdr action offers Copy fix prompt: the error and its
  diagnostics, with secrets removed, framed as a prompt for a coding agent.

## Requirements

- Android 7.0 (API 24) or newer.
- An SSH server on each host. Telnet hosts also work, without the Herdr
  features.
- [Herdr][herdr] on the host for the agent features. Without it, Mercurio is a
  plain SSH client and the Herdr keys stay dimmed. The key defaults follow
  Herdr 0.9.0.
- For push (optional): an ntfy server you run, the ntfy app on the phone, and
  `python3` on each Herdr host.

## Build from source

Mercurio is not on Google Play. Build it from source:

- A JDK, version 17 or newer.
- The Android SDK. Gradle finds it through `ANDROID_HOME` or `sdk.dir` in
  `local.properties`.
- A full clone with tags. The version name comes from the latest `v*` tag, and
  the versioning plugin fails inside a git worktree.

```sh
git clone https://github.com/Ormus-Solutions/mercurio.git
cd mercurio
./gradlew :app:assembleOssDebug
adb install -r app/build/outputs/apk/oss/debug/app-oss-debug.apk
```

`oss` is the only build flavor. The debug build's application id ends in
`.debug`, so it installs beside a release build.

### Checks

This repo runs no hosted CI. Before a merge, run the gate in a full clone:

```sh
./gradlew testOssDebugUnitTest spotlessCheck :app:assembleOssDebug
./gradlew :app:lintOssDebug
```

Run lint on its own: the lint analyzer can crash when it shares a Gradle run
with other tasks. To prove a change on an emulator, follow
[`.grok/skills/verify-mercurio/SKILL.md`](.grok/skills/verify-mercurio/SKILL.md).

## Host setup

1. Install [Herdr][herdr] on the host and check that `herdr` runs in an SSH
   session.
2. In Mercurio, add the host and set its **Post-login automation** to `herdr`.
   Each connection then attaches to the host's Herdr session, which keeps its
   panes and agents between connections.
3. For push alerts while the phone sleeps, set up the ntfy server, the ntfy app
   and the host watcher in [docs/PUSH.md](docs/PUSH.md).
4. To open several Herdr sessions at once on connect, list them in
   `~/.config/mercurio/consoles` on the host, for example:

   ```text
   # one console per line: <herdr session> [workspace label]
   work
   lab
   review checks
   ```

## Privacy

- **No Google services.** The build has no Google Play services, Firebase or
  analytics library. Push goes through UnifiedPush to a server you run.
- **Push messages carry ids only.** A push holds pane, tab and workspace ids
  and labels, never pane text, prompts or secrets. Mercurio reads the pane over
  SSH after it wakes.
- **Usage log.** Mercurio keeps a log of which controls you use, to guide
  design. It records action ids, never typed text, terminal output, hostnames,
  usernames, keys or passwords. It is stored in the app's private files.
  Recording is on by default. Turn off Record my usage in Settings, Usage and
  wish list, and nothing is recorded.
- **Upload only over your own SSH.** The log and the wish list leave the phone
  only over an SSH session to a host named in that same settings section, and
  only when the session's host key matches the key pinned there. There is no
  HTTP endpoint and no third party.
- **Diagnostics on request.** A fix prompt is built on the phone with private
  keys and saved passwords removed. It goes to the clipboard only when you tap
  Copy fix prompt, and into the wish list only when you tap Send to wish list.

## License and credit

Mercurio is licensed under the [Apache License 2.0](LICENSE). See
[NOTICE](NOTICE) for what was changed and for third-party code.

Mercurio is built on the work of the ConnectBot project:

- [ConnectBot][connectbot] by Kenny Root and contributors, the app this is a
  fork of.
- [ConnectBot Terminal][termlib], the terminal emulator, by Kenny Root.
- [ConnectBot's fork of Trilead SSH-2][sshlib], the SSH library, originally by
  Christian Plattner.

The Herdr models and client in `solutions/ormus/logos/herd/` are adapted from
[Termish][termish] (MIT).

Mercurio is not affiliated with or endorsed by the ConnectBot project. Report
Mercurio problems in this repository, not upstream.

[connectbot]: https://github.com/connectbot/connectbot
[termlib]: https://github.com/connectbot/termlib
[sshlib]: https://github.com/connectbot/sshlib
[herdr]: https://github.com/herdrdev/herdr
[termish]: https://github.com/farlume/termish
[unifiedpush]: https://unifiedpush.org
[ntfy]: https://ntfy.sh
[ormus]: https://ormus.solutions
