/**
 *  Aquascape Smart Control Hub
 *
 *  Generic Hubitat driver for the Aquascape Smart Control Hub — the WiFi
 *  controller that ships with Aquascape's color-changing pond and fountain
 *  lights (model 84074). Talks to the Blynk-based backend at
 *  smartcontrol.aquascapeinc.com.
 *
 *  Each hub is a separate Hubitat device. Set the device's Blynk auth
 *  token in preferences. Find the token in the Aquascape web dashboard
 *  under Device > Device Info.
 *
 *  Capabilities: Switch, SwitchLevel, ColorControl, LightEffects,
 *                SignalStrength, Refresh
 *  Custom commands: setStrobe, setAnimationSpeed, setCustomPalette,
 *                   setWhiteMode, setSolid
 *
 *  Repo: https://github.com/rabidfurball/hubitat-aquascape
 *  Protocol spec: docs/API.md in the repo
 *  License: MIT
 */

import groovy.transform.Field
import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import hubitat.helper.ColorUtils

@Field static final String API_BASE = "https://smartcontrol.aquascapeinc.com/external/api"
@Field static final String DRIVER_VERSION = "0.1.1"

@Field static final String EFFECT_SOLID = "Solid"
@Field static final String EFFECT_WHITE_MODE = "White Mode"

@Field static final Map PRESETS = [
    "Red/Orange/Green":            [[255,0,0],[255,187,0],[48,255,0]],
    "Dark Blue/Light Blue/Green":  [[5,44,187],[0,165,238],[48,255,0]],
    "Blue/Purple":                 [[48,35,174],[200,109,215]],
    "Yellow/Orange":               [[255,202,0],[255,106,0]],
    "Red/White/Blue":              [[255,0,0],[255,255,255],[0,0,255]],
    "Red/Green/White":             [[255,0,0],[48,255,0],[255,255,255]],
    "Magenta/Blue/Orange":         [[255,0,255],[0,0,255],[255,125,0]],
    "Rainbow":                     [[255,0,0],[255,255,0],[0,255,0],[0,255,255],[0,0,255],[255,0,255]]
]

// Effect dropdown shown to the user. Order matches the LightEffects index.
@Field static final List EFFECT_LIST = [EFFECT_SOLID] + PRESETS.keySet().toList() + [EFFECT_WHITE_MODE]

metadata {
    definition(name: "Aquascape Smart Control Hub", namespace: "rabidfurball", author: "rabidfurball",
               importUrl: "https://raw.githubusercontent.com/rabidfurball/hubitat-aquascape/main/aquascape-smart-control-hub.groovy") {
        capability "Switch"
        capability "SwitchLevel"
        capability "ColorControl"
        capability "LightEffects"
        capability "SignalStrength"
        capability "Refresh"

        attribute "animationSpeed", "number"
        attribute "strobeMode", "string"      // "fade" / "strobe" / "n/a"
        attribute "v3Raw", "string"
        attribute "effectName", "string"

        command "setStrobe", [[name: "mode", type: "ENUM", constraints: ["fade", "strobe"]]]
        command "setAnimationSpeed", [[name: "speed", type: "NUMBER", description: "1-10000 (matches the Aquascape app's slider)"]]
        command "setCustomPalette", [
            [name: "palette", type: "STRING", description: "JSON array of [R,G,B] triplets, e.g. [[255,0,0],[0,255,0]]"],
            [name: "strobeMode", type: "ENUM", constraints: ["fade", "strobe"]]
        ]
        command "setWhiteMode"
        command "setSolid", [[name: "(no args)", type: "STRING", description: "Freezes lights on the current displayed color, drops any active animation"]]
    }
    preferences {
        input name: "token", type: "string", title: "Blynk Auth Token", required: true,
              description: "Find in the Aquascape web dashboard at smartcontrol.aquascapeinc.com → Device → Device Info"
        input name: "pollInterval", type: "number", title: "Poll Interval (seconds)", defaultValue: 60, range: "10..600"
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
    }
}

def installed() {
    log.info "Aquascape driver v${DRIVER_VERSION} installed"
    initialize()
}

def updated() {
    log.info "Aquascape: settings updated"
    initialize()
}

def initialize() {
    unschedule()
    sendEvent(name: "lightEffects", value: JsonOutput.toJson(EFFECT_LIST))
    if (settings.token) {
        refresh()
    } else {
        log.warn "Aquascape: no auth token set — set one in device preferences"
    }
}

def refresh() {
    if (!settings.token) return
    String url = "${API_BASE}/getAll?token=${settings.token}"
    asynchttpGet("processGetAll", [uri: url, contentType: "application/json"])
    Integer interval = (settings.pollInterval ?: 60) as Integer
    runIn(interval, "refresh")
}

def processGetAll(response, data) {
    if (response.status != 200) {
        log.warn "Aquascape: getAll status ${response.status}"
        return
    }
    def json
    try {
        json = response.json
    } catch (e) {
        log.error "Aquascape: parse error: ${e.message}"
        return
    }
    if (logEnable) log.debug "getAll: ${json}"
    if (json?.v1 != null) sendEvent(name: "switch", value: (json.v1 as Integer) == 1 ? "on" : "off")
    if (json?.v2 != null) sendEvent(name: "level", value: json.v2 as Integer)
    if (json?.v3 != null) processV3(json.v3 as String)
    if (json?.v8 != null) sendEvent(name: "animationSpeed", value: json.v8 as Integer)
    if (json?.v30 != null) sendEvent(name: "rssi", value: (json.v30 as BigDecimal).intValue())
}

private void processV3(String v3) {
    sendEvent(name: "v3Raw", value: v3)
    def parts = v3.split('\u0000')
    if (parts.size() < 4) return

    Integer r = parts[0].isInteger() ? (parts[0] as Integer) : 0
    Integer g = parts[1].isInteger() ? (parts[1] as Integer) : 0
    Integer b = parts[2].isInteger() ? (parts[2] as Integer) : 0
    boolean rgbMode = parts[3]?.toLowerCase() == "true"

    def hsv = ColorUtils.rgbToHSV([r, g, b])
    sendEvent(name: "hue", value: hsv[0] as Integer)
    sendEvent(name: "saturation", value: hsv[1] as Integer)
    sendEvent(name: "color", value: ColorUtils.rgbToHEX([r, g, b]))

    // Reverse-match the displayed state against known effects so effectName
    // tracks both Hubitat-driven changes and edits made from the Aquascape app.
    String detectedEffect
    if (parts.size() <= 4) {
        // 4 fields = solid color (no strobe, no palette).
        detectedEffect = (!rgbMode) ? EFFECT_WHITE_MODE : EFFECT_SOLID
        sendEvent(name: "strobeMode", value: "n/a")
    } else {
        // animation: parts[4] = strobe flag, parts[5..] = palette
        sendEvent(name: "strobeMode", value: parts[4] == "1" ? "strobe" : "fade")
        List<List<Integer>> palette = []
        for (int i = 5; i + 2 < parts.size(); i += 3) {
            palette << [
                parts[i].isInteger()   ? (parts[i] as Integer)   : 0,
                parts[i+1].isInteger() ? (parts[i+1] as Integer) : 0,
                parts[i+2].isInteger() ? (parts[i+2] as Integer) : 0
            ]
        }
        detectedEffect = matchPalette(palette) ?: "Custom"
    }

    if (device.currentValue("effectName") != detectedEffect) {
        sendEvent(name: "effectName", value: detectedEffect)
    }
}

private String matchPalette(List<List<Integer>> palette) {
    PRESETS.each { name, preset -> if (palette == preset) return name }
    // Groovy each doesn't break early; do it properly:
    for (entry in PRESETS) {
        if (palette == entry.value) return entry.key
    }
    return null
}

// ---- Switch ----

def on()  { writePin("V1", "1"); sendEvent(name: "switch", value: "on")  }
def off() { writePin("V1", "0"); sendEvent(name: "switch", value: "off") }

// ---- SwitchLevel ----

def setLevel(level, duration = null) {
    Integer pct = Math.max(0, Math.min(100, level as Integer))
    writePin("V2", pct.toString())
    sendEvent(name: "level", value: pct)
}

// ---- ColorControl ----

def setColor(colorMap) {
    Integer h = (colorMap.hue ?: 0) as Integer
    Integer s = (colorMap.saturation ?: 100) as Integer
    def rgb = ColorUtils.hsvToRGB([h, s, 100])
    writeSolidRgb(rgb[0] as Integer, rgb[1] as Integer, rgb[2] as Integer)
    if (colorMap.level != null) setLevel(colorMap.level)
}

def setHue(hue) {
    Integer s = (device.currentValue("saturation") ?: 100) as Integer
    def rgb = ColorUtils.hsvToRGB([hue as Integer, s, 100])
    writeSolidRgb(rgb[0] as Integer, rgb[1] as Integer, rgb[2] as Integer)
}

def setSaturation(sat) {
    Integer h = (device.currentValue("hue") ?: 0) as Integer
    def rgb = ColorUtils.hsvToRGB([h, sat as Integer, 100])
    writeSolidRgb(rgb[0] as Integer, rgb[1] as Integer, rgb[2] as Integer)
}

private void writeSolidRgb(int r, int g, int b) {
    String v3 = "${r}\u0000${g}\u0000${b}\u0000true"
    writePin("V3", URLEncoder.encode(v3, "UTF-8"))
}

// ---- LightEffects ----

def setEffect(effect) {
    String name
    if (effect instanceof Number) {
        Integer idx = effect as Integer
        if (idx < 0 || idx >= EFFECT_LIST.size()) {
            log.warn "setEffect: index ${idx} out of range"
            return
        }
        name = EFFECT_LIST[idx]
    } else {
        name = effect as String
    }

    if (name == EFFECT_WHITE_MODE) { setWhiteMode(); return }
    if (name == EFFECT_SOLID)      { setSolid();     return }

    def palette = PRESETS[name]
    if (!palette) { log.warn "setEffect: unknown '${name}'"; return }
    boolean strobe = device.currentValue("strobeMode") == "strobe"
    applyPalette(palette, strobe)
    sendEvent(name: "effectName", value: name)
}

def setNextEffect() {
    Integer idx = EFFECT_LIST.indexOf(device.currentValue("effectName") ?: EFFECT_LIST[0])
    setEffect(EFFECT_LIST[(idx + 1) % EFFECT_LIST.size()])
}

def setPreviousEffect() {
    Integer idx = EFFECT_LIST.indexOf(device.currentValue("effectName") ?: EFFECT_LIST[0])
    setEffect(EFFECT_LIST[(idx - 1 + EFFECT_LIST.size()) % EFFECT_LIST.size()])
}

// ---- Custom commands ----

def setStrobe(mode) {
    sendEvent(name: "strobeMode", value: mode)
    String current = device.currentValue("effectName")
    if (current && PRESETS[current]) {
        applyPalette(PRESETS[current], mode == "strobe")
    }
}

def setAnimationSpeed(speed) {
    Integer s = Math.max(1, Math.min(10000, speed as Integer))
    writePin("V8", s.toString())
    sendEvent(name: "animationSpeed", value: s)
}

def setCustomPalette(paletteJson, strobeMode = "fade") {
    def palette
    try {
        palette = new JsonSlurper().parseText(paletteJson)
    } catch (e) {
        log.error "setCustomPalette: bad JSON: ${e.message}"
        return
    }
    if (!(palette instanceof List) || palette.isEmpty()) {
        log.error "setCustomPalette: must be a non-empty JSON array of [R,G,B] arrays"
        return
    }
    applyPalette(palette, strobeMode == "strobe")
    sendEvent(name: "effectName", value: "Custom")
}

def setWhiteMode() {
    writePin("V3", "255%00255%00255%00false")
    sendEvent(name: "effectName", value: EFFECT_WHITE_MODE)
}

def setSolid() {
    // Freeze on whatever color the device is currently displaying.
    String v3 = device.currentValue("v3Raw") ?: ""
    def parts = v3.split('\u0000')
    Integer r = (parts.size() >= 1 && parts[0].isInteger()) ? (parts[0] as Integer) : 255
    Integer g = (parts.size() >= 2 && parts[1].isInteger()) ? (parts[1] as Integer) : 255
    Integer b = (parts.size() >= 3 && parts[2].isInteger()) ? (parts[2] as Integer) : 255
    writeSolidRgb(r, g, b)
    sendEvent(name: "effectName", value: EFFECT_SOLID)
}

private void applyPalette(palette, boolean strobe) {
    def first = palette[0]
    String strobeFlag = strobe ? "1" : "0"
    StringBuilder v3 = new StringBuilder()
    v3.append("${first[0]}\u0000${first[1]}\u0000${first[2]}\u0000true\u0000${strobeFlag}")
    palette.each { c ->
        v3.append("\u0000${c[0]}\u0000${c[1]}\u0000${c[2]}")
    }
    writePin("V3", URLEncoder.encode(v3.toString(), "UTF-8"))
}

// ---- HTTP helpers ----

private void writePin(String pin, String value) {
    if (!settings.token) {
        log.warn "Aquascape: no token configured"
        return
    }
    String url = "${API_BASE}/update?token=${settings.token}&${pin}=${value}"
    asynchttpGet("writeCallback", [uri: url], [pin: pin, value: value])
}

def writeCallback(response, data) {
    if (response.status != 200) {
        log.warn "Aquascape: write ${data.pin}=${data.value} returned ${response.status}"
    } else if (logEnable) {
        log.debug "Aquascape: wrote ${data.pin}=${data.value}"
    }
    // Force a quick poll so attributes reflect the write within seconds.
    runIn(2, "refresh", [overwrite: true])
}
