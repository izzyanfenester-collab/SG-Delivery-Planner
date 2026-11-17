# SG DELIVERY #866 TESTING — CAMERA VOICE CRITICAL FIX (NOT OFFICIAL)

## Baseline
Source `feature/build-865-camera-voice-trigger-fix-testing`, commit `5f93e7b4bb3644395fadbc61741fb9fde41c03cf`.
All 341 SPF camera records and original 209 ElevenLabs MP3s preserved. No modifications to Official `main`.

## Investigated specific defect
In B865 `NavigationVoiceAudio.play(phrase):Boolean`, **true meant accepted/queued**, not that any sound was heard or even that `MediaPlayer.start()` was reached. The live session called `cameraVoicePlanner.markScheduled()` immediately after receiving that early true. An incoming ADVANCE/CRITICAL navigation cue could preempt the camera's PCM preparation, and the camera would be permanently marked as already announced, so actual GPS replay never retried it. Meanwhile SPF map icons render directly from the snapshot and therefore were visible even when the direction and route matching rejected the voice trigger.

## B866 corrective implementation
- Same real `NavigationSession.scanCameraSafety` → `CameraVoicePlanner865.due` → `NavigationVoiceAudio.play` → original PCM/MP3 → `MediaPlayer.start()` pipeline. Not a settings-only or simulated timer fix.
- Explicit `PandaPlaybackEvent866` with `STARTED`, `COMPLETED`, `INTERRUPTED`, `CANCELLED`, `FAILED`, session-specific logging and media failure reasons.
- Request accepted is tracked as *pending*. The warning is marked announced **only upon the asynchronous MediaPlayer.STARTED callback**; a cancellation before start or an interruption within 850ms is eligible for retry if the camera is still ahead and navigation has cleared.
- Navigation maneuver priorities retained. Voice playback never overlaps; urgent maneuver may preempt a camera warning. Refresh/reroute cancels obsolete *camera* audio without unnecessarily cancelling maneuver guidance.
- Real distance-based approach allows 650→470m (500m primary) and 340→260m (300m red-light primary); no equality trigger is required. No late playback once closer than 25m; default scan interval 1s. Reroute clears old alert state.
- Additional per-camera direction/category/route gating from B865 FIX1 retained, including conservative generic safety-only cue when published approach direction cannot be reliably corroborated. No guessed camera deployment, red signal phase, or speed limit.
- In-app **Live Voice Diagnosis** reports actual `PENDING`, `STARTED`, `COMPLETED`, `FAILED`, `INTERRUPTED`; logs include session token, category, camera ID, published road/direction, current road, previous/current distance, threshold, audio priority, actual start/error and suppression reason.
- After a camera has disappeared from route candidates for 90 seconds, approach suppression expires; same camera can announce on a genuinely later approach. Different camera IDs use independent state.
- Keep average-speed entry/exit and numeric speed-limit announcements blocked without independently verified zone geometry / direction-specific speed-limit evidence.
- Use separate Android package suffix `.b866test` (versionCode 71); the existing B865 TESTING and B864 Official can remain installed and their data untouched.

## Verification matrix
1. **Unit/replay CI:** 650→470, 340→260, pending-vs-started, cancelled/retry, too-short interruption retry, completed-no-duplicate, urgent maneuver deferral, three independent cameras, reroute invalidation, same camera later approach, wrong direction and passed-camera suppression.
2. **Artifact CI:** 185 original navigation MP3 SHA-identical and 24 additional original camera MP3 SHA-identical to uploaded ZIP; all 24 camera PCM; camera SPF snapshot record count 341; isolated APK package; lint and Android unit tests.
3. **Pending, not claimed complete:** Real driving/phone speaker, Bluetooth route and audio focus after calls, flyover opposite carriageway acceptance, offline in-car navigation, and long navigation sessions. STARTED only establishes that Android accepted the playback start call, not that a human heard it.

No female Android TTS. No Official merge until explicit authorization.
