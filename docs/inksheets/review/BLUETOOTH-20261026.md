# Standalone Bluetooth and connection evidence — 2026-10-26

## Implemented

Both connection panels offer a remembered Auto / Wi-Fi only / Bluetooth only choice. Bluetooth-only Remote hosting and Companion leading do not bind a TCP port or require an IP address. Classic remotes use Android system-paired devices and the existing pairing code. Android hides its own Bluetooth MAC, so an unusable empty-route QR is replaced by explicit paired-device/code instructions.

Companion leaders advertise a short device label and session in BLE beacons even without a song open. Nearby followers discover those labels directly over Bluetooth. Their join QR includes the Bluetooth session and may contain zero network addresses. A follower becomes connected only after receiving a newer beacon from the selected session. Followers acknowledge the beacon they actually heard; leaders count recent acknowledgments rather than counting a running advertiser as a connection. Repeated/stale beacons do not extend freshness. Existing page/message relaying remains intact.

Permission requests happen on explicit user actions. Quiet retries recover after Bluetooth is enabled or permissions are granted without repeatedly opening permission dialogs. Android 12+ BLE readiness requires CONNECT as well as SCAN and ADVERTISE, since reading adapter enabled state itself requires CONNECT. Older BLE scanning uses the existing location permission. Wi-Fi permission/state is not a readiness input. Late scanner/advertiser callbacks are ignored after stop/restart.

## Verification

Added silent Android BluetoothReadinessPolicyTest cases covering modern classic, modern BLE including CONNECT, denied permissions, and legacy behavior. Added fake-radio StandaloneBluetoothTest for a Bluetooth-only empty-IP leader QR, absence of false follower/connected claims, selected-session beacons, and denied Bluetooth permission. Parent coordinates compilation/test execution; no real radio or audio is used by these tests.

## Limits requiring device testing

No physical Android Bluetooth hardware is available in this workspace. Discovery range, vendor scan throttling, headset coexistence, and reconnect after system Bluetooth toggling require a test on actual devices. BLE heartbeat and small page/message frames are available offline; existing BLE protocol does not carry annotation files, full scores, or large library payloads. Original network sharing remains available through Auto or Wi-Fi only.

A dual-route follower may be observed on TCP and BLE simultaneously. The displayed count uses the larger live count to avoid counting that player twice. Mixed groups containing distinct TCP-only and BLE-only followers can therefore undercount; individual Bluetooth connection evidence is still based on actual inbound acknowledgments.

## Watch installation / updates

Added a user-initiated Windows installer flow from Watch settings. It uses the existing selected InkSheets update channel and downloads that release's watch ZIP, rather than asking the user to find a file manually. ZIP entries are a strict four-file allowlist with per-entry byte limits and no path components. The Windows wizard uses existing ADB when available or offers Google's official platform-tools HTTPS download and SDK terms; archive paths are checked before extraction. Pairing code entry is delegated interactively to ADB and not stored, logged, or placed in command-line arguments. Explicit device selection and `install -r` preserve app data. Invalid/signature-mismatched APK installation is surfaced without uninstalling or clearing data.

Both release workflows now include the wizard and README in the existing watch ZIP. The phone shows Windows-first setup guidance, not a nonexistent Play Store install path. Wear OS 4+ pairing ports differ from connection ports; older watches can skip pairing and use their Debug over Wi-Fi endpoint. The script explains private Wi-Fi/hotspot setup and switching Wireless debugging off afterward. This setup network is independent of Bluetooth-only use after installation.

Silent PowerShell fake-ADB tests passed for endpoint validation, argument boundaries (including APK paths with spaces), `install -r`, and failed command propagation. Added JVM policy tests for watch ZIP selection and extraction allowlist. Real watch install/update remains unverified without physical hardware.

Sources: https://developer.android.com/tools/releases/platform-tools and https://developer.android.com/training/wearables/get-started/debugging

Connected-device follow-up: TCP Hello and BLE Presence now share a stable hashed device identity. The Play together roster merges both transports per identity, prefers the full TCP device name, shows actual inbound heartbeat age/status, and counts distinct live followers. The earlier mixed-group count limitation is resolved. BLE-only Presence labels are necessarily short (four UTF-8 bytes in the existing twenty-byte frame); TCP supplies a full label when available. Fake-radio roster tests cover distinct devices with identical display labels, stale reconnecting rows, and expiry.

Leader restart follow-up: initial/disconnected BLE joins require a baseline beacon followed by a strictly newer direct beacon. A repeated cached advertisement cannot reconnect the stand, and a restarted leader can resume with a reset sequence. Radio generation tokens prevent queued callbacks from old sessions changing a new session. A fake-time test covers this complete sequence.
