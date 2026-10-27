# InkSheets follow-up: adaptive workstation and reliability

This revision implements the approved Shelf/Studio, Home and editing demos while retaining the InkSlate annotation palette and original Fix interface. The previous test.298 release does not contain this revision.

## Player-facing changes

- Auto compares fitted score space for the shelf and studio rail; device-local Layout offers those two bounded views, widget ordering and visibility. Phone overflow has direct category shortcuts. The Layout control sits outside the footer divider. Wide floating editors temporarily borrow the widget area on small displays and restore it when closed.
- Timing has live beat lights, direct tempo/tap/count-in/tuner/follow actions. Recording shows a waveform decoded from the real audio, position, seek, loop markers, speed and recording controls. Connections shows named peers and their actual heartbeat state. Active transports retain stop controls when widgets are hidden or folded.
- The footer retains annotation shortcuts and the original full tool palette, page navigation, page read state, direct instrument changes and excerpt creation. Read-dependent controls are disabled on unread pages. The normal music sidebar is replaced with a grouped score directory; original Fix retains its previous interface and confidence colors.
- Clean visibly toggles without discarding edits. Mask brush reveals cleaned notation only under strokes, reuses existing brush settings, and supports erasing back to the original print and per-stroke Undo. Mask and notation geometry is refreshed when page dimensions change.
- Home uses consistent destinations/actions across orientations, prominent search, direct instruments, root setlists/folders, Continue reading and Continue setlist. Manual colors and alphabetical organization remain.
- Details, Part assignments and Play together use responsive grouped layouts. Broadcast presets/color choices wrap on narrow screens; wide movable panels use the available room. Short landscape puts transport and Lead/Stop before explanatory text, retaining Bluetooth error/retry feedback. Floating panels start each drag from their current position.
- Bookmarks can name an excerpt, retain its inclusive page range, reminder and notes, independently of the book's notes.
- Recording failures offer Retry, Locate and Choose actions. A cancelled slow decoder cannot restart playback or produce a stale error. Durable unfinished-take receipts recover interrupted recordings and repair WAV lengths; association starts with the first take, and quick repeated takes use distinct files.
- Independent synced list/property edits merge while respecting deliberate deletion and causal re-addition. Android recording playback handles audio focus, interruption, headphone removal and background service controls.
- Wi-Fi and Bluetooth transport can be chosen independently. Idle inbound heartbeats determine liveness, including reconnection/disconnection without sending a command. BLE heartbeats receive bounded airtime even during long messages.
- Watch setup offers the matching release package and guided Windows pairing/update, with update-in-place to retain calibration.

## Verification

Complete local regression: **1,070 passed, 78 gated/skipped, zero failures** across core (340), music core (307), desktop (164), InkSheets desktop (198), Android InkSheets (76) and Android InkSlate (63). Phone and watch debug APK assembly succeeded. The watch installation script passed its fake-ADB policy test.

All app-data fixtures are isolated; automated audio runs are silent. Shared UI audits cover 360×640 through 1600×1000, including phones/tablets in both orientations. The actual full-PDF workspace, original annotation palette, connections and settings pass all twelve sizes. Original Fix, real reading/rereading and page turns pass alongside new dragging, actual mask-rendering, docking, recording, sync and connectivity tests. The final compact-connection follow-up passes, including immediate Lead access with the actual dock present. GitHub publishing is pending.

Physical device checks remain necessary for OEM-specific BLE behavior, real headset/call interruption, screen-off audio, and watch pairing/calibration retention. Automated protocol, policy and installer simulations do not substitute for those checks.
