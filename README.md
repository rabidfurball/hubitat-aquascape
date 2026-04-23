# Aquascape Smart Control — Hubitat Driver

A Hubitat driver for the [Aquascape Smart Control Hub][hub] — the WiFi
controller that ships with Aquascape's color-changing pond and fountain
lights (model 84074).

Aquascape doesn't publish an API. This driver uses a reverse-engineered
HTTPS interface to the Blynk-based backend at
`smartcontrol.aquascapeinc.com`. See [docs/API.md](docs/API.md) for the
full protocol spec.

[hub]: https://www.aquascapeinc.com/smart-control-hub

## Features

- **Switch** — on/off
- **SwitchLevel** — brightness 0–100
- **ColorControl** — RGB / hue / saturation
- **LightEffects** — 10-entry dropdown:
  - `Solid` (freezes lights on the current displayed color, drops animation)
  - The 8 built-in palette presets (Red/Orange/Green, Rainbow, Blue/Purple, …)
  - `White Mode` (dedicated white channel — purer than RGB white)
- **SignalStrength** — WiFi RSSI sensor
- **Refresh** — manual poll trigger

### Custom commands

| Command | Args | Purpose |
|---|---|---|
| `setStrobe` | `mode: fade \| strobe` | Toggle strobe vs fade for the current animation |
| `setAnimationSpeed` | `speed: 1–10000` | Animation speed (matches the Aquascape app's slider) |
| `setCustomPalette` | `palette: JSON [[R,G,B], …]`, `strobeMode: fade \| strobe` | Activate any custom palette |
| `setWhiteMode` | — | Switch to dedicated white channel |
| `setSolid` | — | Freeze lights on the currently displayed color |

### Multi-device

The driver is fully generic — install once, then create a Hubitat virtual
device for each Aquascape Hub you have. Each device has its own auth
token in preferences.

## Install

### Hubitat Package Manager (recommended)

1. In HPM, choose **Install** → **Search by Keywords**
2. Search for `Aquascape`
3. *(Until the manifest is in HPM's repository list)* you can install via
   the manifest URL directly:
   - HPM → Install → **From a URL** → paste:
     `https://cdn.jsdelivr.net/gh/rabidfurball/hubitat-aquascape@main/packageManifest.json`

### Manual

1. In Hubitat: **Drivers Code** → **+ New Driver** → **Import**
2. Paste this URL:
   `https://cdn.jsdelivr.net/gh/rabidfurball/hubitat-aquascape@main/aquascape-smart-control-hub.groovy`
3. Click **Import** → **Save**

## Setup

1. Get your hub's auth token from the
   [Aquascape web dashboard](https://smartcontrol.aquascapeinc.com) →
   *Device → Device Info*
2. **Devices** → **+ Add Device** → **Virtual**
3. **Type** → `Aquascape Smart Control Hub`
4. Set a **Device Label** (e.g. "Front Yard Fountain") and click **Save Device**
5. On the new device's page, paste the token into **Blynk Auth Token** →
   **Save Preferences**

The driver will immediately poll the device and populate all attributes.

To add another hub, repeat from step 2.

## Preferences

| Preference | Default | Notes |
|---|---|---|
| Blynk Auth Token | (required) | Per-device, from the Aquascape web dashboard |
| Poll Interval | 60 seconds | Range 10–600. Lower = faster UI updates, higher = less network traffic |
| Enable debug logging | off | Logs every poll + every write |

## Notes

- The driver also forces a quick poll ~2 s after every write so attributes
  reflect changes promptly without waiting for the next regular poll.
- `effectName` is reverse-matched from the device's V3 string on every poll,
  so it tracks both Hubitat-driven changes and edits made from the Aquascape
  mobile app.
- The hub is **cloud-only** — there's no local control path without
  reflashing the firmware. See [docs/API.md](docs/API.md#no-local-control)
  for the investigation details.

## Hardware tested

| Model | Result |
|---|---|
| Smart Control Hub model 84074 (rev 11/24) — color-changing pond/fountain lights | ✅ Working |

If you've tested another Aquascape Smart Control product (Pump Receiver,
Smart Plug), please open an issue or PR.

## Companion Home Assistant integration

There's also a [Home Assistant custom integration][hass-repo] using the
same protocol. Both can run side-by-side against the same hub if you want.

[hass-repo]: https://github.com/rabidfurball/hass-aquascape

## Acknowledgements

- The protocol was reverse-engineered by inspecting the unauthenticated
  Blynk HTTPS API exposed by `smartcontrol.aquascapeinc.com`. Aquascape
  are not affiliated with this project.
- Built with [Claude Code](https://claude.com/claude-code).

## License

MIT — see [LICENSE](LICENSE).
