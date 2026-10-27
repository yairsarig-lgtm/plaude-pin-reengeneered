# Plaud NotePin (2024) BLE protocol — observed

Sources: Omi's open-source connector (MIT, see `PlaudProtocol.kt`) and an HCI snoop capture of the
official Plaud Android app talking to the owner's pin on 2026-10-27 (firmware unknown). The capture
itself is **not** in the repo: it contains the owner's Plaud bind token and account JWT.

Status legend: **confirmed** = seen in capture and/or PinLink works with it; *hypothesis* = fits the
data, not yet tested.

## Transport

- GATT service `1910`: write `2bb1` (handle 0x36, write + write-without-response), notify `2bb0`
  (handle 0x38). Plaud app uses write-without-response. Both work. **confirmed**
- Other chars (`b001` read = `01 02`, `b004` indicate, `fd44/4f86000x` write+indicate) — unused by
  the Plaud app.
- The pin only answers commands once it has been **bound to a Plaud account** by the official app.
  Unbound: writes are acked at ATT level, nothing comes back. After binding, a different app
  (PinLink) gets answers **without** sending cmd 1. **confirmed**
- Notifications go to every app connected to the pin on the same phone (Android shares the link).
- Pin drops the link (`status=19`) ~120 s after connect when idle. *hypothesis:* cmd 109 is a
  keep-alive.

## Framing — confirmed

- Command: `01 | cmdId u16 LE | payload` (all integers little-endian).
- Response: same layout. Most responses reuse the request's cmdId; exceptions: 24→33, 25→34,
  30→31, 112→113, 116→117.
- Audio: `02 | sessionId u32 | position u32 | length u8 | audio[length]`. Position `ffffffff` = end.
  Omi treated `sessionId` as unknown.

## Session IDs — confirmed

A recording's sessionId **is its start time as a Unix timestamp** (seconds, UTC), e.g. `0x6ae07d5f`
= 2026-10-27 10:30:55 UTC.

## Audio — confirmed

Opus, 16 kHz mono, CBR 32 kbps. Every audio packet carries 160 bytes = two 80-byte frames; each
frame starts with TOC `0xb8` (config 23: CELT wideband 20 ms, mono, code 0). 4000 bytes/s.

## Commands

| ID | Request payload | Response payload | Meaning |
|---|---|---|---|
| 1 | `02 00 00` + 32 ASCII chars (secret token) | `00 0a 00 xx 00 01 01 00 00 56 c0 00 00` | bind/auth (only needed to bind) |
| 3 | — | 15 bytes, byte0 = 1 idle / 3 recording; recording also carries sessionId at [8..12] | device state |
| 4 | `now u32` + `02 00` | echo | set clock; `02 00` *hypothesis* = UTC offset hours |
| 5 | `00` | — | ? |
| 6 | — | 24 bytes, shrinks after recording | storage info *hypothesis* |
| 8 | `01 id u16`... (`01 <id> 00`) | `<id> 00 value u32` | read setting. ids seen: 0f 11 13 14 17 18 19 1a 1b 1f 31 |
| 9 | — | `charging u8, percent u8` | battery |
| 20 | Omi: `u32 1, u32 0, u32 0` | `sessionId u32, u32 0, 00 01` | start record. **Also sent unsolicited when the pin's button starts a recording** |
| 22 | `sessionId u32, 00` | `sessionId u32, u32 0, 04 01` | recording info? |
| 23 | Omi: `sessionId u32, u32 0` | `sessionId u32, 01 01, bytes u32` | stop record. **Also unsolicited on button stop**, with file size |
| 24 / 25 | — / `00` | cmd 33 `00` / cmd 34 `00` | ? |
| **26** | `now u32` + `00 00 00 00 00` | `now u32, count u32, count × (sessionId u32, bytes u32, flags u16)` | **list stored files** |
| **28** | `sessionId u32, start u32, end u32` | `sessionId u32, status u8` then audio | **sync file**. end = 0: follow live recording from the current position |
| 29 | (pin → phone) | `sessionId u32, ff ff` | sync finished |
| 30 | `sessionId u32` | cmd 31 `sessionId u32, 00` | stop sync |
| 38 | `e8030000 00000000 e8030000` | — | sent at live start; ? |
| 108 | — | name, zero-padded ("PLAUD NotePin") | device name |
| 109 | `00` | `00` | keep-alive? |
| 112–117 | | | Plaud app uploads the owner's account token to the pin. **Never replicate.** |

Omi's cmd 28 payload (three u64s) does not match what this firmware's own app sends (three u32s).

## Plaud app flows seen

- **Connect:** CCCD → cmd 1 → 4 → 3 → 8×n → 108 → 25 → 3 → 24 → 8×n → 6 → 26 (list) → sync each listed file with 28 → 29 → 30/31.
- **Button recording while connected:** pin sends 20 → app sends 3, 22, 28 (sid, 0, 0) → live audio from position ≈800 → pin sends 23 (size) and 29 → app backfills with 28 (sid, 0, firstLivePosition) → 30/31.
- After a file is synced, it disappeared from the next list. Whether the pin deleted it or just hides synced files is unknown.
