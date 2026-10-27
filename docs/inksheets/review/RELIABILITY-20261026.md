# Synced setlists and recordings — 26 October 2026

## Reproduced design fault

`SetlistEdit.entries` and `SongEdit.audio` wrote one last-writer-wins JSON list. If a laptop and tablet each added a different item while apart, only the later whole list survived. Editing a recording's speed on one device could similarly erase a label, volume setting or another part's learned page turns from another device. Reordering a set while another device removed an entry could bring that entry back.

## Change

- New list operations keep the ordinary complete `value` for existing log readers and add optional `base` (the list the editor saw) plus observed removal stamps. Existing logs without these fields remain readable.
- New readers derive independent membership, metadata and ordering deltas. Set entries are keyed by their stable entry ID; recordings are keyed by library-relative file path.
- Removal stamps are separate from metadata edits. Editing a stale recording cannot resurrect it. A deliberate re-add must observe the removal, so even an unrelated same-file add from a device with an ahead-of-time clock cannot override deletion accidentally.
- Nested objects merge by leaf: each part's learned page-turn array is independent. Competing edits to the same property still use the established hybrid-clock order.
- Order uses stable predecessor links, with deterministic sibling order and cycle breaking. Concurrent additions, removal and reorder converge without losing live entries. A re-add after a known removal rewrites the observed order to avoid old tombstone-anchor cycles.
- Compaction retains the operations still providing any winning item membership/property/order or removal tombstone, not only the latest full list. Unknown future fields remain intact during compaction and ordinary typed list edits.

## Compatibility boundary

All upgraded devices participate in item-level merging. An edit authored by an older app has no observed baseline or causal removal context; it retains its original authoritative full-snapshot behavior. Its intent cannot be reconstructed perfectly after a concurrent edit. Older clients can still read the complete list snapshots written by this version. Upgrade devices before relying on independent offline edits across them.

## Coverage

`SyncedListsTest` covers independent offline additions, remove versus metadata/add, per-entry fields, per-track speed/volume, per-part taught turns, explicit re-add, reordered sets with concurrent additions/removals, re-add order, duplicated/reversed delivery, compaction/reload, legacy loading, future metadata and duplicate recording paths. Execution is coordinated by the lead to avoid rebuilding a dependency JAR while a desktop test worker is reading it.

No tests or edits touch the real music library.

The coordinated targeted core run passed all tests before the final generation-reset regression. Restoring a removed recording now starts fresh settings instead of inheriting its old volume/pitch; metadata and order use the observed removal generation. The added regression still needs the next coordinated test run.

Removal context is a per-item, per-author vector: an explicit restore must observe every independent removal, including simultaneous removals whose clocks differ. Regression covers two independent deletes and partial restores followed by a deliberate restore after sync.

## Connection reliability follow-up

The remote already sent periodic pings; its connected state was previously set by any raw line, including malformed or refused replies. Companion followers previously announced connected as soon as a socket opened, and only started a read timeout after receiving the first ping. A silent TCP connection could therefore look healthy without receiving anything useful.

Core now provides a shared inbound-evidence `LinkLiveness` tracker with monotonic age, reconnecting/disconnected thresholds and generation tokens. Remote and Companion followers expose `connectionStatus`; stale socket callbacks cannot revive a replacement session. Parsed inbound protocol traffic is required before connected. Initial Companion silence has a bounded timeout, and leaders remove followers that stop acknowledging heartbeats. Existing Boolean callback APIs remain for compatibility.

Bluetooth-only listener startup no longer needs a successful TCP bind. Companion join links can carry an explicit Bluetooth session with no network addresses. Named Beacon and participant Presence frames fit the existing 20-byte radio limit, independently of page changes. The UI/radio owner handles broadcast scheduling, last-leader acknowledgments and participant freshness; advertising alone is not connection evidence.

`LinkLivenessTest` adds fake-time state/generation coverage, Bluetooth-only join/frame checks, and a host loopback test with its configured TCP port deliberately occupied. New tests await the lead's coordinated Gradle run.

## Home and Continue follow-up — 27 October

Home now keeps one library hierarchy across orientations: the full sidebar narrows to a 48dp icon rail, with the same direct destinations. Compact layouts retain a horizontal strip of root setlists and folders regardless of the current tab. Search is a prominent tinted 56dp field for both songs and setlists. Blue reading and purple setlist Continue surfaces sit beneath search, beside each other when room permits. Quick instrument selection remains direct, with its own row on the narrowest screens so transport, tuner, add and device actions remain available.

Setlist Continue saves a device-local library-scoped entry ID, part ID and page. Reordering resolves the saved entry by ID, including duplicate song entries; deleting that entry removes Continue instead of substituting a stale list index. Missing files produce an actionable notice and do not open another entry. Changing instrument starts that new part at its first page rather than applying a page from a different part. Focused HomeSetResumeTest covers reorder/restart/page preservation and missing/deleted entries. Existing HomeRedesignTest accepts the compact rail's accessible icon names.

The obsolete companion-file migration option is hidden only for InkSheets settings on desktop and Android. InkSlate retains the existing migration UI and storage code is unchanged.

## Named connection snapshots

ConnectedPeer exposes immutable per-device identity, real protocol name, transport, heartbeat state and last inbound timestamp. Hosts exclude sockets that have not completed admission and peers with an incorrect key. RemoteControl exposes host peers and its controlled stand; merely opening the route does not mark the stand connected. Companion transport identity uses the same stable 32-bit device hash in TCP Hello and BLE Presence, allowing the UI to union overlapping routes without collapsing distinct players that share a name. Presence optionally carries four UTF-8 bytes of discovery name within the existing 20-byte budget; older unnamed 16-byte frames still decode. Full TCP names take precedence when available.

ConnectedPeerTest covers fake-time aging, same-person mixed-route union versus distinct same-name devices, backward-compatible radio payloads, and loopback proof that silent or wrong-key sockets never appear as connected devices. Runs remain coordinated by the lead.

## Final Home/dock QA follow-up

Reviewed generated Home screenshots at laptop, phone portrait/landscape, smallest landscape phone and seven-inch tablet sizes. A real phone issue was the search placeholder wrapping into two lines and expanding its field beyond the intended 56dp; song/setlist placeholders now stay one line with ellipsis. Medium-width headers now show the app title in the available left-hand space instead of leaving it blank.

HomeRedesignTest now targets the actual song-title dialog's Part assignments section and Save action. WorkstationAppAuditTest targets the new Return to music workspace action. Existing app-test cleanup resets Studio geometry as well as the former shelf/lane globals. New MusicDockLifecycleTest verifies Studio reservations clear on Home and disposal, return when reopened, and that Reset restores Auto/default widgets. These tests await the lead's coordinated run; no Gradle process was started by this agent.
