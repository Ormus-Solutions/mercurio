# Menu: Mercurio

Queue: 2026-11-10-pantry-queue.md
Counts: open 2, in flight 0, shipped 19, parked 10, dropped 0, needs fixing 0

## Steer

- up-next: prompt-above-keys

## Up next

**needs-you-cards**: Unblock view: swipe from the home into one question at a time, tap an option or speak the answer (`needs-you-cards`) (queue #17, medium, repo, since 2026-10-12)

- Done when: With a store address set in Settings (blank by default; no address in the tree), a JUnit or Robolectric test against a MockWebServer on 127.0.0.1 with synthetic fixtures in the store's shapes shows: a horizontal swipe on the home (right to left) opens the Unblock view and the back gesture or a swipe left to right returns to the home; a swipe that starts in the left-edge zone opens the session drawer as today (the edge zone wins, DNA P3); the view shows one open ask at a time from `GET <address>/api/asks` in the order the store gives (oldest first), with `k of n` in the mono micro-label style, the question in the heading style, its context, its pictures from `GET <address>/api/asks/<id>/pictures/<n>`, one `OrmusKey` per option in order (the first gold-filled, the others ghost), a ghost Later key and one text field that takes the keyboard's dictation (DNA P3; no speech library and no `RECORD_AUDIO` permission); a tap on an option of a single-choice ask sends exactly one `POST <address>/api/asks/<id>/answer` with the body `{"choice":["<label>"],"via":"mercurio"}` and the next ask shows; on an ask with `multi` true a tap only marks an option, and one Send key posts the marked labels in one call; a dictated or typed answer posts `{"text":"...","via":"mercurio"}` the same way; Later moves the ask to the end of the pass and the next shows, and the ask's state in the store does not change; when every ask was seen once the view reads how many were answered and how many were put off, with an Again key (the ones put off, in order) and a Home key; a `409` reads one line saying the question is already closed and the next ask shows; a `403` reads one line saying the store refused this phone and starts no retry loop; with no address set the swipe opens nothing and no call is made; the list is read every 30 s while the view is visible and never in the background, and an ask that closed elsewhere leaves the pass without moving the one on screen; no question, context, answer, picture or address reaches a log or a usage event (the usage tests pass), and the actions have the usage ids `needs:open`, `needs:answer`, `needs:send`, `needs:later` and `needs:again` with `UsageSchemaTest` passing; no new dependency (the platform HTTP stack, as `home/HomeFeed.kt` reads its feed) and no new manifest permission; no new color literal under `app/src/main`; the gate passes
- Verify on: repo
- Evidence: Code lives in `home/` or `herd/`, `ui/` and Settings. Operator's ask, 2026-11-09 (E5), on the questions sessions send him on WhatsApp: "I need it to be more intuitive, and maybe whatsapp is not the place for this. Maybe we need to make a custom app or a section of the mecurio app that is dedicated to quickly unblocking sessions"; his plan reply the same day: "Yes plan it". Reshaped 2026-11-09 on the operator's words in the session: "I visualize a being able to change view by swiping in the main mercurio screen and move into a view focused on unblocking sessions one by one by either multiple choice or voice" and "things get lost in whatsapp"; the same day a session's question went unseen on WhatsApp and the session went on with its default. The reference flow is the store's own page, `apps/needs-you/web/pass.js` (one pass: order, Later, closed elsewhere, again) on the operator's always-on host; 32 open asks were cards there at 2026-11-09 20:00Z (measured). Voice is the keyboard's dictation into the text field (INTENT: dictation is the default input), the same as every other field in the app. Store contract shipped and live on the operator's always-on host since 2026-11-09: Ormus-Solutions/ormus-platform `apps/needs-you` (PRs #199 store, #202 cards page, #203 pictures, #204 CLI): `GET /api/asks` gives `{"asks": [{id, question, context, options [{label, detail}], multi, pictures, state, expires}]}`; `POST /api/asks/<id>/answer` takes `{choice, text, via}` and refuses a closed ask with `409`; `via: mercurio` is accepted only on the store's web listener, which lets in only the allowed tailnet node (the operator's phone) and refuses every other caller with `403`. Different from atom 4 (`board-one-tap-choices`): that one answers a Herdr agent's own menu in its pane; this one answers the questions a session posted to the store, which no pane shows. DNA P4 (reaches out when one is blocked), P3 (one thumb), R2 (the store is a service the host already runs; nothing is installed on the host or the phone), G3 (the address is the operator's setting; fixtures synthetic), G4, G5, E5. Split note (DNA G1, ~200 logic lines; size a guess): cook it as two PRs, PR 1 the view, single choice, Later and the 409/403 lines; PR 2 multi, text and the end-of-pass summary
- Issue: none yet (promote after merge)
- Order: needs-you-cards, priorities-screen
- Tie: none

## Atoms

| Key | Title | State | Confidence | Class | Since | Queue # | Issue | Because |
|-----|-------|-------|------------|-------|-------|---------|-------|---------|
| needs-you-cards | Unblock view: swipe from the home into one question at a time, tap an option or speak the answer (`needs-you-cards`) | open | medium | repo | 2026-10-12 | 17 | - | - |
| priorities-screen | Priorities screen: three rows, what each needs, one tap to its chat (`priorities-screen`) | open | medium | host | 2026-10-12 | 16 | #237 | - |

## Retired

| Key | Title | State | Since | Issue | Because |
|-----|-------|-------|-------|-------|---------|
| auto-reconnect | A dropped link reconnects once on its own (`auto-reconnect`) | shipped | 2026-10-12 | #160 | closed completed #160 |
| board-one-tap-choices | Agents board ask with one-tap option chips (`board-one-tap-choices`) | shipped | 2026-10-12 | #145 | closed completed #145 |
| drag-arrows | Long-press and drag sends arrow keys (`drag-arrows`) | shipped | 2026-10-12 | #154 | closed completed #154 |
| hcore-01-agent-model | One agent model under the board and the notices (`hcore-01-agent-model`) | shipped | 2026-11-10 | #242 | bullet: `hcore-01-agent-model`: shipped, PR #239 (Jump order on the model: PR #245) |
| hcore-02-design-pieces | Grouped sections and chat bubbles as shared pieces (`hcore-02-design-pieces`) | shipped | 2026-11-10 | - | bullet: `hcore-02-design-pieces`: shipped, PR #241 |
| hcore-03-core-source | Read herdr-core agents on a host, off by default (`hcore-03-core-source`) | shipped | 2026-11-10 | - | bullet: `hcore-03-core-source`: shipped, PR #243 |
| hcore-04-inbox-screen | One Inbox of every agent, sorted by what it needs (`hcore-04-inbox-screen`) | parked | 2026-11-10 | - | bullet: `hcore-04-inbox-screen`: parked, built now by a session agent (Ormus 2026-11-10: "open more agents to drive deveopment faster"); its PR merges on the gate |
| hcore-05-inbox-unread-pinned | Unread and pinned, shared with the Herdr app (`hcore-05-inbox-unread-pinned`) | parked | 2026-11-10 | - | bullet: `hcore-05-inbox-unread-pinned`: parked, waits for hcore-04; the session's agents build it next |
| hcore-06-core-spaces | Spaces list with a box map per tab (`hcore-06-core-spaces`) | parked | 2026-11-10 | - | bullet: `hcore-06-core-spaces`: parked, built now by a session agent; its PR merges on the gate |
| hcore-07-core-open-terminal | Open a core terminal as a console that only watches (`hcore-07-core-open-terminal`) | parked | 2026-11-10 | - | bullet: `hcore-07-core-open-terminal`: parked, waits for hcore-04; the session's agents build it next |
| hcore-08-core-compose-keys | Compose and keys reach a core terminal (`hcore-08-core-compose-keys`) | parked | 2026-11-10 | - | bullet: `hcore-08-core-compose-keys`: parked, waits for hcore-07; the session's agents build it |
| hcore-09-core-notices | Notices and Approve or Deny for core agents (`hcore-09-core-notices`) | parked | 2026-11-10 | - | bullet: `hcore-09-core-notices`: parked, built now by a session agent; its PR merges on the gate |
| hcore-10-core-push-watch | The host watcher follows herdr-core events (`hcore-10-core-push-watch`) | parked | 2026-11-10 | - | bullet: `hcore-10-core-push-watch`: parked, waits for hcore-09; the session's agents build it |
| hcore-11-key-bar-editor | A key bar the operator edits (`hcore-11-key-bar-editor`) | shipped | 2026-11-10 | - | bullet: `hcore-11-key-bar-editor`: shipped, PR #240 |
| hcore-12-design-grouped-lists | Lists in grouped sections with clear separation (`hcore-12-design-grouped-lists`) | shipped | 2026-11-10 | - | bullet: `hcore-12-design-grouped-lists`: shipped, PR #246 and #250 |
| hcore-13-design-chat-bubbles | Chat messages as bubbles by author (`hcore-13-design-chat-bubbles`) | shipped | 2026-11-10 | - | bullet: `hcore-13-design-chat-bubbles`: shipped, PR #247 |
| hcore-14-design-compose-groups | The compose box and key bar as separate groups (`hcore-14-design-compose-groups`) | shipped | 2026-11-10 | - | bullet: `hcore-14-design-compose-groups`: shipped, PR #244 and #249 |
| hcore-15-inbox-first-home | Open the Inbox first when herdr-core answers (`hcore-15-inbox-first-home`) | parked | 2026-11-10 | - | bullet: `hcore-15-inbox-first-home`: parked, waits for hcore-04, 05, 07, 09; the session's agents build it |
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

- steer up-next prompt-above-keys ignored: not eligible
