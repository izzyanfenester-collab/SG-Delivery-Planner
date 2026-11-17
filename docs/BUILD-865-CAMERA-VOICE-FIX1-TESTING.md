# SG Delivery Build #865 Camera Voice Trigger Fix1 — TESTING

## Problem reproduced in source
Published camera markers were rendered directly from all 341 SPF database records. Actual camera voice required `CameraApproachLogic.find()` to confirm the current road and the SPF textual direction by exact matching against only five navigation step road names. SPF texts such as `twds Farrer Rd` do not usually occur as routing step names, while expressways such as `PIE` and `Pan Island Expressway` failed road matching. Consequently visual markers could appear with **zero actual GPS camera audio events**, even with a fully verified and installed 209-MP3 pack.

## Changes — NOT OFFICIAL
- Canonicalize common SPF and Valhalla expressway aliases (PIE, AYE, BKE, CTE, ECP, KPE, MCE, SLE, TPE and KJE); normalize `Rd`/`Road`, `Ave`/`Avenue`, `Jln`/`Jalan`, `Lor`/`Lorong`.
- Compare SPF published directional destination to up to 12 actual downstream route steps. When independent SPF positions document the target **junction** on the same road, corroborate the direction from real published geographic evidence; never write computed bearing to official source or claim published numeric speed limit.
- Never announce a camera in an unverified direction. Under unusually high-confidence road + GPS heading + accurate route-position constraints, play only an **authentic generic original Panda Warrior audio recording** about observing the signal or posted speed limit, *without* claiming a camera is on that carriageway.
- Add a live diagnostic message under `Built-in GPS Settings → Camera & Road Safety Alerts`: waiting for route, master OFF, audio unavailable, no matching road/route, unverified direction, turn-priority, audio not accepted, or successful scheduling. Keep existing priority and duplicate prevention.
- Added synthetic route-replay regression tests for aliases, safe generic warning, wrong heading/road/accuracy, target-junction corroboration, opposite road direction and voice priority.
- Package suffix `.b865voicefix1test` allows parallel installation, preserving the prior #865 testing and Official. Do not claim that manually replayed or real-world tests are complete solely because CI passes.
- Use all 209 byte-verified user-supplied Panda Warrior MP3s; do not replace any clip or restore Android TTS.

## Deliberate safety limits
- A generic safety reminder is **not** an official speed camera activation alert and does not imply that the camera enforces the current carriageway.
- GPS heading and matching route shape cannot always distinguish flyovers/parallel roads; ambiguous cases must remain without camera-specific voice until independently confirmed.
- Average speed enforcement entry/exit is still disabled where actual zone segment geometry is unavailable.
- No guessed speed limit; `null` retains until independent verification.
- Voice alerts require active Built-in GPS navigation and voice/settings enabled. A map icon alone never proves GPS approach eligibility.
- Future road/Bluetooth tests required before promotion.

**Do not merge into Official without user approval.**
