# B865 Panda Warrior V2 — 209 authentic recordings (TESTING ONLY)

Source: the **existing verified B865 camera system**, not a new app. Official `main` is not modified.

The user provided `SG_Delivery_Panda_Warrior_V2_With_Camera_Alerts_209_Clips.zip`:
- **185 original navigation MP3 recordings**, compared byte-for-byte to the official B857 reference before importing.
- **24 recorded camera alert MP3s**, validated by manifest ID, exact MP3 bytes and audio duration.
- **24 corresponding 24 kHz / 16-bit mono PCM files**, derived locally from the original recordings to use the same offline Panda Warrior audio engine.
- Unified source manifest, CSV and README kept for audit.

The new Kotlin code in this branch installs the original named camera cues into the *live* `NavigationSession.scanCameraSafety` path. Previews alone are not proof of GPS integration. The voice scheduler is subordinate to urgent turning instructions; no Android TTS fallback is allowed. It performs deduplication and reset on reroute.

## Final missing step for real APK

Audio files are uploaded to the ChatGPT conversation, **not automatically into this GitHub repository**. They cannot be treated as bundled until the exact verified ZIP exists at:

`voice_upload/SG_Delivery_Panda_Warrior_V2_With_Camera_Alerts_209_Clips.zip`

Expected SHA256:
`e8e3b56d9b0a9adc1d8b541134af8ac97905ba66e65e48336783f06e1a355c24`

When that user-supplied ZIP is added **to this branch** under `voice_upload/`, GitHub Actions `build-b865-panda209-camera-testing.yml` will:
1. Verify SHA256 and the identity of all original 185 navigation recordings.
2. Import the 24 original MP3 files from their exact nested paths and prepare PCM.
3. Test, lint and compile an isolated `.b865voice209test` Android APK.
4. Validate 209 actual MP3 inside the APK, 24 camera PCM assets and the 341 camera records.
5. Publish **only** a GitHub *prerelease* `v1.4.53-b865-panda209-testing` if every step succeeds.

**Never publish a false 209-clip APK before the supplied real recordings are present.**

## Deliberate safety boundaries

- SPF camera data are *published locations*, not live enforcement or activation status.
- Camera alerts require sufficient GPS route and direction evidence.
- Existing SPF point records do not yet prove the start/end of average speed zones, so average-speed entry/exit audio stays blocked until verified zone geometry is available.
- Unverified camera-specific numeric speed limits stay null; no guessed voice speed limit or overspeed warning.
- The existing 185 voice recordings, cinematic map zoom, route calculation and delivery/payment features are preserved.
- Some exact spoken 300m/500m modular distance phrases are not present in the supplied audio set. Do **not** play incorrect distance fragments or claim that generic warning clips speak a precise distance.
- On-device driving, Bluetooth audio and flyover approach route-replay acceptance testing remain essential.

No Official merge is authorized.
