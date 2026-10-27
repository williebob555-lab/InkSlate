# Android recording interruptions — 2026-10-26

## Scope and contract

InkSheets recording playback now uses a focus-aware Android session. This does not change the
InkSlate editor, microphone recording, tuner DSP, sampled instruments, or desktop output.

- Audio focus is requested immediately before playback, after the media foreground service is
  established. Focus refusal leaves the recording paused and reports a reason to the shared UI.
- Temporary focus loss, including a duck request, pauses the recording at its current position.
  A practice track is never allowed to run silently underneath another app's sound.
- Focus gain resumes only the recording that was actually playing before the temporary loss.
  Explicit Pause, release/replacement, completion, permanent loss and headphone disconnection
  revoke that resume intent. Repeated temporary-loss callbacks do not forget the first interruption.
- A dynamic `ACTION_AUDIO_BECOMING_NOISY` receiver pauses on output disconnection. It remains
  registered during a temporary interruption, so unplugging during a call cannot later resume
  into the device speaker. It is removed when playback intent ends.
- All decoder access, callbacks, parameters, seeks and A–B checks share a dedicated HandlerThread.
  UI getters read volatile snapshots. A generation counter rejects stale queued loads/starts.
- `MediaPlayer.setWakeMode(PARTIAL_WAKE_LOCK)` keeps the CPU available only while playing.
  A small, InkSheets-only media-playback foreground service protects the session while the
  Activity is backgrounded or the screen is off. Its MediaSession notification exposes Pause/Stop,
  headset/lock-screen transport and a link back to the app. It is not sticky and never restarts
  playback after process death or an update.
- Service teardown is start-ID/revision guarded, so a delayed stop for an old track does not
  cancel a newer explicit play during a rapid file switch.

The shared `AudioPlayer.interruptionReason` reports why playback stopped. The shared Recording
monitor must continue observing the same session during an interruption; the lead owns that
integration and stopping/re-aligning the synchronized click.

## Compatibility review

The application's minimum SDK is 26. AudioFocusRequest, notification channels and MediaSession
are available at that minimum. Android 12 notification foreground behavior and Android 13 dynamic
receiver flags are version guarded. PendingIntents are immutable. Only application context is
retained, so Activity recreation does not leak or replace the audio session.

The InkSheets manifest declares FOREGROUND_SERVICE and FOREGROUND_SERVICE_MEDIA_PLAYBACK and a
non-exported mediaPlayback service. There is no new permission dialog. MediaSession notifications
are exempt from Android 13's POST_NOTIFICATIONS runtime permission; existing user channel/system
notification settings still apply. No playback is started at boot or by a sticky service restart.

Android may refuse a foreground-service start requested from a background state that has no
allowed exemption. That failure is caught, logged and surfaced as a paused reason; it does not
fall back to untracked sound or change Android's restrictions.

Primary platform guidance checked:

- [Audio focus](https://developer.android.com/media/optimize/audio-focus)
- [MediaPlayer background playback and wake mode](https://developer.android.com/media/platform/mediaplayer/background)
- [Media playback foreground-service type](https://developer.android.com/develop/background-work/services/fgs/service-types#media)
- [MediaSession notification exemption](https://developer.android.com/develop/ui/compose/notifications/notification-permission#media-sessions)

## Validation

`PlaybackInterruptionPolicyTest` contains seven silent JVM transition tests: temporary loss/gain,
manual pause during an interruption, permanent loss/new explicit play, unplug while suspended,
repeated losses, gain without previously audible playback, and completion/failure cancellation.
Android compilation and the flavor unit-test task are coordinated by the lead to avoid parallel
Gradle output/JAR races. Update this section with their final results before publishing.

No physical Android device or headset was exercised during implementation. On-device verification
still needs an incoming call/notification, wired/Bluetooth disconnect, manual pause during a call,
screen off/on, lock-screen Pause, quick file switching, and Android's foreground-start refusal.
The tests prove intent transitions, not the phone manufacturer's audio routing implementation.
