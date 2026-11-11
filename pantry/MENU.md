# Menu: Mercurio

Queue: 2026-11-10-pantry-queue.md
Counts: open 17, in flight 0, shipped 12, parked 2, dropped 0, needs fixing 0

## Steer

- up-next: hcore-01-agent-model

## Up next

**hcore-01-agent-model**: One agent model under the board and the notices (`hcore-01-agent-model`) (queue #1, high, repo, since 2026-11-10)

- Done when: Characterization tests on the recorded Herdr TUI fixtures land first and fail when an expected row is changed; then the board, Jump to and the notices show the same rows and fire the same notices as before on those fixtures, through one engine-neutral `Agent` model (address, engine, machine, space, tab, title, kind, state, since, turn, unread, pinned, parent) and a pure `rank()`; no behavior changes and no new setting; the gate passes
- Verify on: repo
- Evidence: Operator, 2026-11-10 (E5): "There are a lot of things in the Herder app that are just way better, but there are a lot of things in the Mercurio app that I want to keep"; "Lets make a plan to execute all these changes to the mercurio app"; "merge and continue working". Plan "Mercurio on herdr-core" (2026-11-10), PR 1 (refactor, risk low, behavior none, depends on the spike only, stack core 1/4). Order: 1 of 15, first by the operator's plan. The spike (herdr-core over exec and PTY, never merged) passed on the emulator, so the model can take a second source (inferred). DNA P2 (one model, Herdr's vocabulary), P4, E5, G1, S2. Touches: `herd/AgentsBoard.kt`, `herd/HerdrAgentStateMachine.kt`, `herd/Agent.kt`
- Issue: none yet (promote after merge)
- Order: hcore-01-agent-model, hcore-02-design-pieces, hcore-03-core-source, needs-you-cards, hcore-11-key-bar-editor, priorities-screen, hcore-04-inbox-screen, hcore-05-inbox-unread-pinned, hcore-06-core-spaces, hcore-07-core-open-terminal, hcore-08-core-compose-keys, hcore-09-core-notices, hcore-10-core-push-watch, hcore-12-design-grouped-lists, hcore-13-design-chat-bubbles, hcore-14-design-compose-groups, hcore-15-inbox-first-home
- Tie: none

## Atoms

| Key | Title | State | Confidence | Class | Since | Queue # | Issue | Because |
|-----|-------|-------|------------|-------|-------|---------|-------|---------|
| hcore-01-agent-model | One agent model under the board and the notices (`hcore-01-agent-model`) | open | high | repo | 2026-11-10 | 1 | - | - |
| hcore-02-design-pieces | Grouped sections and chat bubbles as shared pieces (`hcore-02-design-pieces`) | open | high | repo | 2026-11-10 | 2 | - | - |
| hcore-03-core-source | Read herdr-core agents on a host, off by default (`hcore-03-core-source`) | open | high | host | 2026-11-10 | 3 | - | - |
| hcore-04-inbox-screen | One Inbox of every agent, sorted by what it needs (`hcore-04-inbox-screen`) | open | medium | host | 2026-11-10 | 4 | - | - |
| hcore-05-inbox-unread-pinned | Unread and pinned, shared with the Herdr app (`hcore-05-inbox-unread-pinned`) | open | medium | host | 2026-11-10 | 5 | - | - |
| hcore-06-core-spaces | Spaces list with a box map per tab (`hcore-06-core-spaces`) | open | medium | host | 2026-11-10 | 6 | - | - |
| hcore-07-core-open-terminal | Open a core terminal as a console that only watches (`hcore-07-core-open-terminal`) | open | medium | host | 2026-11-10 | 7 | - | - |
| hcore-08-core-compose-keys | Compose and keys reach a core terminal (`hcore-08-core-compose-keys`) | open | medium | host | 2026-11-10 | 8 | - | - |
| hcore-09-core-notices | Notices and Approve or Deny for core agents (`hcore-09-core-notices`) | open | medium | host | 2026-11-10 | 9 | - | - |
| hcore-10-core-push-watch | The host watcher follows herdr-core events (`hcore-10-core-push-watch`) | open | medium | host | 2026-11-10 | 10 | - | - |
| hcore-11-key-bar-editor | A key bar the operator edits (`hcore-11-key-bar-editor`) | open | medium | repo | 2026-11-10 | 11 | - | - |
| hcore-12-design-grouped-lists | Lists in grouped sections with clear separation (`hcore-12-design-grouped-lists`) | open | medium | host | 2026-11-10 | 12 | - | - |
| hcore-13-design-chat-bubbles | Chat messages as bubbles by author (`hcore-13-design-chat-bubbles`) | open | medium | host | 2026-11-10 | 13 | - | - |
| hcore-14-design-compose-groups | The compose box and key bar as separate groups (`hcore-14-design-compose-groups`) | open | medium | host | 2026-11-10 | 14 | - | - |
| hcore-15-inbox-first-home | Open the Inbox first when herdr-core answers (`hcore-15-inbox-first-home`) | open | medium | host | 2026-11-10 | 15 | - | - |
| needs-you-cards | Unblock view: swipe from the home into one question at a time, tap an option or speak the answer (`needs-you-cards`) | open | medium | repo | 2026-10-12 | 17 | - | - |
| priorities-screen | Priorities screen: three rows, what each needs, one tap to its chat (`priorities-screen`) | open | medium | host | 2026-10-12 | 16 | #237 | - |

## Retired

| Key | Title | State | Since | Issue | Because |
|-----|-------|-------|-------|-------|---------|
| auto-reconnect | A dropped link reconnects once on its own (`auto-reconnect`) | shipped | 2026-10-12 | #160 | closed completed #160 |
| board-one-tap-choices | Agents board ask with one-tap option chips (`board-one-tap-choices`) | shipped | 2026-10-12 | #145 | closed completed #145 |
| drag-arrows | Long-press and drag sends arrow keys (`drag-arrows`) | shipped | 2026-10-12 | #154 | closed completed #154 |
| herd-approve | Approve and deny guarded by Herdr's seq (`herd-approve`) | shipped | 2026-10-03 | #130 | closed completed #130 |
| herd-home | Herd home lists agents by who needs you (`herd-home`) | shipped | 2026-10-03 | #41 | closed completed #41 |
| herd-notify | Notifications come from Herdr status (`herd-notify`) | shipped | 2026-10-03 | - | merged PR #57 |
| herdr-drag-scroll | Drag to scroll a Herdr pane (`herdr-drag-scroll`) | shipped | 2026-10-12 | #152 | closed completed #152 |
| needs-you-poll | Needs you count stays fresh for other sessions (`needs-you-poll`) | shipped | 2026-10-12 | #156 | closed completed #156 |
| ormus-reskin | Ormus brand re-skin from tokens (`ormus-reskin`) | shipped | 2026-10-03 | #58 | bullet: Ormus brand re-skin from tokens (`ormus-reskin`): shipped, PR #55 |
| prompt-above-keys | Prompts sit above the key rows (`prompt-above-keys`) | shipped | 2026-10-03 | #37 | closed completed #37 |
| reader-cleanup | Reader shows clean agent output (`reader-cleanup`) | parked | 2026-10-03 | - | bullet: Reader shows clean agent output (`reader-cleanup`): parked, Chat View (PR #111, #141) shows Claude and Codex panes clean; the reader was opened 7 times in 4,247 actions and its scroll keys were never used; Linear ORM-168 says keep for non-agent panes or close |
| tailscale-oauth | Tailscale list works with no host connected (`tailscale-oauth`) | shipped | 2026-10-03 | #162 | closed completed #162 |
| title-pull-drawer | Pull down on the title opens the session drawer (`title-pull-drawer`) | shipped | 2026-10-12 | #158 | closed completed #158 |
| vibe-fold | Vibe fleet roster folded into Logos (`vibe-fold`) | parked | 2026-10-03 | - | bullet: Vibe fleet roster folded into Logos (`vibe-fold`): parked, DNA G3 keeps machine names out of the tree and PR #100 removed the built-in machines; Linear ORM-167 |

## Notes

- none
