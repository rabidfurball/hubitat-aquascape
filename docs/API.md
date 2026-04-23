# Aquascape Smart Control Hub — Reverse-Engineered API

This document is the protocol spec for the Aquascape Smart Control Hub
(model 84074, the WiFi controller for color-changing pond/fountain lights).

Aquascape doesn't publish an API. Everything below was reverse-engineered
in April 2026 by hitting the Blynk-based backend at
`smartcontrol.aquascapeinc.com` while comparing observed pin values to the
state of the official Aquascape Smart Control mobile app.

It's documented here so:

- Other community integrations don't have to redo the discovery work.
- Protocol changes are easy to spot if Aquascape ever updates the backend.

If you find the protocol has shifted, please open an issue or PR.

---

## Server & Endpoints

- **Server**: `https://smartcontrol.aquascapeinc.com` (white-labeled,
  self-hosted Blynk IoT server)
- **Auth token**: per-device. Find it in the Aquascape web dashboard
  (`smartcontrol.aquascapeinc.com/dashboard`) → *Device → Device Info*.
  This token also works for the mobile app.
- **Base path**: `/external/api/`

| Method | Path | Purpose |
|---|---|---|
| GET | `/getAll?token=<T>` | Returns JSON of all populated virtual pins |
| GET | `/get?token=<T>&V1&V2&...` | Returns JSON of specific pins |
| GET | `/update?token=<T>&V1=1&V2=80` | Writes one or more pins (multi-pin batch ok in one request) |
| GET | `/isHardwareConnected?token=<T>` | Returns `true` / `false` for whether the device's hardware-side socket is online |

There's no documented `getProperty` endpoint on this backend (the call
returns 404), so widget metadata isn't exposed.

### Multi-pin batch update

The `/update` endpoint accepts multiple pin assignments in one request:

```
GET /external/api/update?token=<T>&V1=1&V2=80&V8=5000
```

This is a Blynk feature; the integration doesn't need to use it, but it's
useful for short atomic state changes (e.g., turning the lights on at a
specific brightness in one call).

---

## Virtual Pin Map (Smart Control Hub model 84074)

| Pin | Type | Range / format | Meaning |
|---|---|---|---|
| `V1` | int | 0 / 1 | Power |
| `V2` | int | 0–100 | Brightness % |
| `V3` | string | null-byte (`\x00`) delimited (see below) | Color or animation state |
| `V5` | float | observed constant `64.0` | Unknown — never changes |
| `V8` | int | matches the app's animation-speed slider value (1–10000) | Animation speed |
| `V30` | float | dBm | WiFi RSSI |
| `V31` | int | observed constant `3` | Unknown — likely op-mode or schedule indicator |

`V5` and `V31` haven't been observed to change across any test (toggle,
brightness, color, scene, mode change). They appear to be device-internal
constants that can be safely ignored.

### V3 — color / animation format

V3 is a single string with fields separated by null bytes (`\x00`).
URL-encode null as `%00` for writes.

#### Solid color (4 fields)

```
R \x00 G \x00 B \x00 <rgb_mode>
```

`<rgb_mode>` is `true` to use the RGB channels, `false` to switch to the
dedicated white channel for a purer white than `(255,255,255)` over RGB.

```
255\x00255\x00255\x00false   → dedicated white channel
255\x000\x000\x00true        → solid red via RGB channels
```

#### Animation / cycle (5 + 3·N fields)

```
currR \x00 currG \x00 currB \x00 true \x00 <strobe> \x00 R1 \x00 G1 \x00 B1 \x00 R2 \x00 G2 \x00 B2 ...
```

- `currR/G/B` is the color the device is currently displaying. While the
  animation is fading, this updates continuously. When **writing**, set
  it to the first palette entry — the device will take over from there.
- `true` is the same RGB-mode flag as the solid format.
- `<strobe>` is `0` for fade, `1` for strobe.
- The remaining fields are the palette as a flat list of RGB triplets.
  Any palette length ≥ 1 is accepted.

Example — yellow/orange fade cycle:

```
255 \x00 202 \x00 0 \x00 true \x00 0 \x00 255 \x00 202 \x00 0 \x00 255 \x00 106 \x00 0
└── current ──┘    │     │   └── palette: yellow, orange ──────────────┘
                 true   strobe=fade
```

---

## Built-in Presets

Captured 2026-04-23 by stepping through each preset in the official
Aquascape app and snapshotting V3 between each.

| # | App label | Palette (RGB triplets) |
|---|---|---|
| 1 | Red/Orange/Green | (255,0,0), (255,187,0), (48,255,0) |
| 2 | Dark Blue/Light Blue/Green | (5,44,187), (0,165,238), (48,255,0) |
| 3 | Blue/Purple | (48,35,174), (200,109,215) |
| 4 | Yellow/Orange | (255,202,0), (255,106,0) |
| 5 | Red/White/Blue | (255,0,0), (255,255,255), (0,0,255) |
| 6 | Red/Green/White | (255,0,0), (48,255,0), (255,255,255) |
| 7 | Magenta/Blue/Orange | (255,0,255), (0,0,255), (255,125,0) |
| 8 | Rainbow | (255,0,0), (255,255,0), (0,255,0), (0,255,255), (0,0,255), (255,0,255) |

Custom palettes with arbitrary colors and any count ≥ 1 also work — the
device runs whatever palette is in V3. The app's "presets" are just
convenience entries that write a known palette.

---

## What's NOT Available

A few things were investigated and ruled out — documented here so others
don't waste time on them.

### No local control

The hub establishes an outbound persistent connection to
`smartcontrol.aquascapeinc.com` and exposes nothing on the LAN. A full
TCP scan of all 65535 ports against the hub's local IP returned zero open
ports. There is no local API.

To get local-only operation, you'd either need to:

- Reflash the ESP module with ESPHome / Tasmota, or
- DNS-hijack `smartcontrol.aquascapeinc.com` to a self-hosted Blynk
  server you run locally.

Neither is supported by this integration.

### Public Blynk MCP doesn't apply

Blynk operates a hosted Model Context Protocol server at
`https://blynk.cloud/mcp`. Since Aquascape runs a separate self-hosted
Blynk instance with its own token namespace, that MCP server has no
visibility into Aquascape devices. Confirmed: hitting `blynk.cloud`
endpoints with an Aquascape token returns 400.

### MQTT push isn't currently usable

The Aquascape backend exposes the standard Blynk MQTT gateway on ports
1883 and 8883.

- TCP 1883 (cleartext) **does** accept connections with
  `username=device`, `password=<auth_token>`, MQTT 3.1.1.
- However, subscribing to wildcards or any plausible Blynk topic pattern
  (`dev/<token>/downlink/ds/#`, `<token>/in/#`, `out/#`, `ds/#`, etc.)
  yielded **zero messages** even when REST writes changed pin values in
  the background. Either the broker's ACLs silently drop non-matching
  topics for a device-token client, or connecting as `device` with the
  same token conflicts with the actual hardware's session and the broker
  brokers nothing.

If someone figures out the right topic pattern, we get real-time push
instead of polling. PRs welcome.

---

## Discovery Method (for protocol auditing)

If you want to verify or update this spec:

1. **Snapshot baseline state**:
   `curl 'https://smartcontrol.aquascapeinc.com/external/api/getAll?token=<T>'`
2. **Make a single change in the Aquascape app** (toggle, brightness,
   color, preset).
3. **Re-poll `/getAll`** and diff. The pin(s) that changed correspond to
   the action you took.
4. For V3 specifically, capture between every preset/mode/strobe change
   and decode the field structure by lining up known states.

The whole pin map above came from ~30 such poll cycles in one session.
