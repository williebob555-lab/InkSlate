# InkSheets watch setup

On Windows, open Install-Watch.cmd. The wizard locates Android platform-tools or offers Google's official download, then guides pairing, connecting, and installing this release's watch app. Keep the APK next to the installer files.

1. Put the computer and watch on the same private Wi-Fi or hotspot. A network that isolates devices (such as eduroam) will block setup. This Wi-Fi connection is only needed to install/update; Bluetooth page turning works afterward.
2. On the watch, enable Developer options (tap the software/build number repeatedly), then ADB debugging and Wireless debugging. Wear OS 4+ offers Pair new device: the pairing address/port and temporary pairing code differ from the normal connection address/port.
3. Follow the wizard. If the watch only offers Debug over Wi-Fi (older Wear OS), skip pairing and enter its displayed IP:port (often 5555); approve the computer on the watch.
4. Open InkSheets on the watch once. On the Android phone paired to that watch, use the matching InkSheets release, enable Watch, then calibrate.

Run the wizard again for updates. It uses adb install -r and keeps existing watch calibrations. A signing-key mismatch is reported; the wizard never uninstalls your app or clears its data. Update the phone from the same InkSheets release to keep signing/version alignment. After setup, turn off Wireless debugging on the watch.

No Play Store publication is required. A phone alone cannot install the watch app through the InkSheets message channel before the watch app exists.

Official instructions and platform-tools license/download:
https://developer.android.com/training/wearables/get-started/debugging
https://developer.android.com/tools/releases/platform-tools
