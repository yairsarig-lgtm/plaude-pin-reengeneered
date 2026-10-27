# Plaud Pin Re-engineered

Personal project. Turn an **original Plaud NotePin (2024 model)** into a dumb voice-memo courier for Claude, with **no Plaud app, no Plaud account, no Plaud cloud**.

## The goal in one paragraph

The owner presses the pin and dictates short memos in **English** ("idea: …", "put X on my calendar Thursday at 4", "add Y to the garden list"). An Android app on his phone collects those recordings over Bluetooth, transcribes them **offline on the phone**, and uploads each transcript as a text file to a Google Drive folder called `Pin Inbox`. That is the app's entire job. A Claude scheduled task (set up separately, outside this repo) reads `Pin Inbox` every hour, files ideas into a Google Sheet called **Pin Ideas**, creates Google Calendar events, and moves processed files to `Pin Done`. Latency of minutes is fine. Reliability matters more than speed.

## Non-goals

- No UI beyond a minimal status/settings screen. The app lives in the background.
- No AI/LLM calls from the phone. No paid APIs. No Plaud SDK.
- No interpretation of the memo on the phone. Transcribe and ship.

## Environment

- Owner's phone: Android, developer mode on, connected over USB to this computer.
- Tools: Android Studio, Claude Code in VS Code.
- Language: **Kotlin**. Min SDK 26+ is fine (personal device). Target the latest stable SDK.
- Repo: `plaude pin reengeneered` on GitHub.

## Architecture

```
[NotePin] --BLE--> [PinLinkService (foreground service)]
                        |  Opus frames
                        v
                   [OpusDecoder] -> 16 kHz mono PCM -> recordings/<timestamp>.wav
                        v
                   [TranscribeWorker (WorkManager)] -- whisper.cpp, base.en model, offline
                        v
                   transcripts/<timestamp>.txt
                        v
                   [UploadWorker (WorkManager, requires network)] -> Google Drive "Pin Inbox"
                        v
                   mark uploaded; keep local copy 7 days, then delete
```

Every stage writes to disk before the next starts, so a crash or reboot never loses a memo. Workers are idempotent and retry.

## The Bluetooth protocol (already reverse-engineered by Omi)

Source: Omi open-source wearable app, MIT license, Based Hardware Contributors.
- PR that added it: https://github.com/BasedHardware/omi/pull/3287 (Oct 2025)
- Code: `app/lib/services/devices/connectors/plaud_connection.dart` (Dart/Flutter), UUIDs in `app/lib/services/devices/models.dart`, discovery in `app/lib/services/devices/discovery/native_bluetooth_discoverer.dart`. Checked at commit `ae20a36dff00ccbcec90a3fdc71165505022f15d`.
- Port the logic to Kotlin; keep an attribution comment and the MIT notice for copied logic.

Summary of what Omi's code does (verify against the source, don't trust this summary blindly):

- **Discovery:** the pin does not reliably advertise a service UUID. Match by name: starts with `plaud` or contains `notepin` (case-insensitive), or advertises the service below.
- **GATT:**
  - Service `00001910-0000-1000-8000-00805f9b34fb`
  - Write characteristic `00002bb1-0000-1000-8000-00805f9b34fb`
  - Notify characteristic `00002bb0-0000-1000-8000-00805f9b34fb`
- Wait ~2 s after connecting before subscribing to notifications.
- **Command frame (phone → pin):** `[0x01, cmdId & 0xFF, (cmdId >> 8) & 0xFF, ...payload]`, all integers little-endian.
- **Notification (pin → phone):**
  - `data[0] == 2` → audio packet. Payload = `data[1:]`. Bytes 4..7 = position (uint32 LE; `0xFFFFFFFF` = end marker). Byte 8 = length. Audio bytes = `payload[9 : 9+length]`.
  - otherwise, if length ≥ 3 → command response. `cmdId = data[1] | data[2] << 8`, response payload = `data[3:]`.
- **Commands:**
  | Name | ID | Payload | Response |
  |---|---|---|---|
  | Get battery | 9 | none | `[isCharging, level%]` |
  | Start record | 20 | u32 1, u32 0, u32 0 | `sessionId` u32, `startTime` u32 (≥10 bytes) |
  | Stop record | 23 | u32 sessionId, u32 0 | — |
  | Sync file start | 28 | u64 sessionId, u64 start, u64 0x7FFFFFFF | any response = OK |
  | Stop sync | 30 | `[0x01]` (sent without awaiting) | — |
- **Live session recipe:** stop record (session 0) → wait 500 ms → start record → wait 1 s → sync file start with the returned sessionId and startTime → audio packets stream in. Omi retries this up to 3 times with backoff.
- **Codec:** Opus, 16 kHz mono, 320-sample frames (Omi calls it `opusFS320`). Omi re-chunks the stream into 80-byte pieces before decoding; figure out the real frame boundaries before relying on that.
- **Known quirks:** the pin drops the connection randomly. Reconnect immediately and automatically.

> **Update 2026-10-27:** Phase 1 tested this on the owner's 2024 NotePin and captured the official
> app's traffic. Several details above are wrong for this firmware (cmd 28 takes three **u32**s;
> stop sync takes the sessionId; the 4 "unknown" audio header bytes are the sessionId; the pin must
> be bound to a Plaud account once before it answers anything). **`docs/PROTOCOL.md` is the
> authoritative reference now.**

## The big open question (Phase 1 must answer it) — ANSWERED

1. Yes: the pin sends cmd 20/23 events when its button starts/stops a recording, and the phone can
   stream it live (cmd 28 with end=0) or pull it afterwards. 2. Yes: cmd 26 lists stored files,
   cmd 28 pulls them. 3. Answered via HCI snoop. Binding: yes, the pin must be bound once via the
   official app; after that PinLink works without the Plaud app running.

Omi's flow is **phone-initiated live streaming**: the phone tells the pin to start recording and audio streams while the phone is connected. The owner wants **pin-initiated** memos: press the pin's own control, talk, done, phone possibly out of range.

Find out, in this order:
1. When the phone is connected and the owner starts a recording **on the pin itself**, does anything arrive on the notify characteristic? Log every raw notification as hex.
2. Can recordings already stored on the pin be pulled? Command 28 (sync file start) takes a sessionId, which suggests stored sessions can be synced if we know their IDs. We need the **list files** command, which Omi does not use.
3. If (2) is unknown, get it by:
   - capturing Bluetooth HCI snoop logs from the official Plaud app while it syncs (Android developer options → "Enable Bluetooth HCI snoop log"; method in https://docs.omi.me/doc/integrations), and/or
   - inspecting the Plaud Android APK with REA (https://github.com/morluto/rea, only from that repo or the `rea-agents` npm package). Plaud's official SDK docs list "file listing, resumable downloads" as capabilities, so the command exists.

Fallback if pin-initiated never works: phone-initiated recording triggered from a Quick Settings tile or a notification button, with live streaming. Acceptable but worse.

Also check: the pin may only accept one bound app. It may need to be unbound/reset from the official Plaud app first.

## Roadmap

- [x] **Phase 1 — Prove the link (do first, highest risk).** Bare app: scan, connect, subscribe, log raw notifications as hex, read battery (cmd 9), run the live-session recipe, save raw Opus to a file. Then decode to WAV and play it back. Answer the open question above.
- [x] **Phase 2 — Stored-file sync.** List (cmd 26), sync (cmd 28), auto-sync on button stop and on every (re)connect, resume interrupted transfers from the last byte. The pin's list only shows un-pulled files, so it is the "what's left" record.
- [x] **Phase 3 — Offline transcription.** whisper.cpp via JNI (or an existing Android binding), English `base.en` model bundled or downloaded on first run. Fallback: Vosk English small model.
- [x] **Phase 4 — Drive upload.** Google Sign-In with Drive scope `drive.file`; create/find folder `Pin Inbox`; upload `YYYY-MM-DD_HH-MM-SS.txt` containing the transcript plus a header line with the recording timestamp in ISO 8601 with the phone's offset (Asia/Jerusalem).
- [ ] **Phase 5 — Background robustness.** Foreground service with persistent notification, auto-reconnect with backoff, boot receiver, battery-optimization exemption prompt, WorkManager for transcription and upload.
- [ ] **Phase 6 — Polish.** Status screen: connection state, battery, queue counts, last upload. Notification when a memo is uploaded.

## Device notes (owner's phone: Xiaomi, HyperOS)

- HyperOS kills background apps and drops broadcasts (boot, app-update) to apps that aren't
  running unless **Autostart** is on and **Battery saver → No restrictions** is set for PinLink.
  The app shows a setup checklist for both plus Android's battery-optimization exemption.
  Neither Xiaomi setting can be read by apps, so the owner confirms them with "Done".
- Verified 2026-10-27: with Autostart off, MY_PACKAGE_REPLACED never reached the app.

## Drive contract

- PinLink only ever **creates** files in `Pin Inbox`. After upload a transcript belongs to the
  downstream Claude task, which moves it to `Pin Done`. The app must never move, rename or
  re-upload a file it has already uploaded (a 2026-10-27 "placement repair" did, and pulled
  processed files back into the inbox).

## Backlog

- Google Cloud: OAuth client is the Android type for `com.yairsarig.pinlink` + this PC's debug
  keystore SHA-1. Building on another machine (different debug key) breaks Drive auth until that
  SHA-1 is added to the same client. The app's Drive folder is the one it created (drive.file scope).

- Later: find a command to change or soften the pin's vibration.
- Low priority: phone-initiated "Start live" is slow and also makes the pin record. Works.
- The pin drops the link ~2 min after every connect (cmd 109 is not a keep-alive). Harmless now
  (auto-reconnect + resumable sync). Could test whether sending cmd 1 per connection stops it, but
  that needs the bind token stored on the phone — owner's call.

## Working agreements for Claude Code

- Build and install with Gradle to the USB-connected phone; read logs with `adb logcat`. Tag all app logs `PINLINK`.
- Small steps. After each phase, stop and tell the owner exactly what to test on the device and what result to expect.
- Never upload audio anywhere. Only transcripts leave the phone, and only to the owner's own Drive.
- Keep secrets (OAuth client IDs etc.) out of git.
- Commit after each working step with a clear message.
