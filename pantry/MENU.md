# Menu: Mercurio

Queue: 2026-10-03-pantry-queue.md
Counts: open 7, in flight 1, shipped 0, parked 0, dropped 0, needs fixing 0

## Steer

- up-next: prompt-above-keys

## Up next

**herd-home**: Herd home lists agents by who needs you (`herd-home`) (queue #2, high, repo, since 2026-10-03)

- Done when: Termish's `herdr/` package is copied under `solutions.ormus.logos.herd` with its MIT notice and both known fixes (Blocked to Done emits Unblocked; `notifiedBlocked` clears on unblock); `SSH.execDetailed` returns stdout, stderr and exit code together; a JUnit test parses a recorded `herdr api snapshot` fixture in `app/src/test/resources/herdr/` and orders its agents blocked, done, working, idle, newest `state_change_seq` first inside each state; a Robolectric test renders the Herd screen from that fixture and reads the cards by test tag in that same order; the four check commands pass
- Verify on: repo
- Evidence: Map: "Reads real agent state from Herdr" (Us P: the key row only checks `postLogin`; Termish, Collie, Heeler Y) and "Agents ordered by who needs you" (Us N; Heeler Blocked > Done > Working > Idle, Collie "Needs you" first, Pairlet attention inbox). X: NathanFlurry wish list "\"inbox\" for notifications"; Michaelzsguo on controlling the agents from a graphical app instead of the Termius keyboard
- Issue: #41
- Order: herd-home, ormus-reskin, herd-approve, herd-notify, reader-cleanup, tailscale-oauth, vibe-fold
- Tie: none

## Atoms

| Key | Title | State | Confidence | Class | Since | Queue # | Issue | Because |
|-----|-------|-------|------------|-------|-------|---------|-------|---------|
| herd-approve | Approve and deny guarded by Herdr's seq (`herd-approve`) | open | medium | repo | 2026-10-03 | 3 | - | - |
| herd-home | Herd home lists agents by who needs you (`herd-home`) | open | high | repo | 2026-10-03 | 2 | #41 | - |
| herd-notify | Notifications come from Herdr status (`herd-notify`) | open | medium | repo | 2026-10-03 | 4 | - | - |
| ormus-reskin | Ormus brand re-skin from tokens (`ormus-reskin`) | open | high | repo | 2026-10-03 | 5 | - | - |
| prompt-above-keys | Prompts sit above the key rows (`prompt-above-keys`) | in-flight | high | host | 2026-10-03 | 1 | #37 | PR #40 (menu/prompt-above-keys) |
| reader-cleanup | Reader shows clean agent output (`reader-cleanup`) | open | medium | repo | 2026-10-03 | 6 | - | - |
| tailscale-oauth | Tailscale list works with no host connected (`tailscale-oauth`) | open | medium | repo | 2026-10-03 | 7 | - | - |
| vibe-fold | Vibe fleet roster folded into Logos (`vibe-fold`) | open | medium | repo | 2026-10-03 | 8 | - | - |

## Retired

| Key | Title | State | Since | Issue | Because |
|-----|-------|-------|-------|-------|---------|
| none | | | | | |

## Notes

- steer up-next prompt-above-keys ignored: not eligible
