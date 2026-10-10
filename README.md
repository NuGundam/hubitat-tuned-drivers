# Hubitat tuned drivers

Forks of community Hubitat Elevation drivers, tuned for a house that runs its fan, damper and
exhaust automations from Home Assistant through the Hubitat integration. Each fork keeps the
original author's license header; the changes are listed per driver below and in each file's
version history.

To install: Hubitat → Drivers code → New driver → Import, and paste the raw URL of the file.

| Driver | File | Based on |
|---|---|---|
| Zooz ZSE44 Temperature/Humidity (battery optimized) | [`drivers/zooz-zse44-temp-humidity-tuned.groovy`](drivers/zooz-zse44-temp-humidity-tuned.groovy) | jtp10181 Zooz ZSE44 |
| Zooz ZSE18 Motion | [`drivers/zooz-zse18-motion-tuned.groovy`](drivers/zooz-zse18-motion-tuned.groovy) | Zooz ZSE18 driver |
| IKEA VALLHORN E2134 Motion | [`drivers/ikea-vallhorn-e2134-motion-tuned.groovy`](drivers/ikea-vallhorn-e2134-motion-tuned.groovy) | community VALLHORN driver |
| IKEA PARASOLL E2013 Contact | [`drivers/ikea-parasoll-e2013-contact-tuned.groovy`](drivers/ikea-parasoll-e2013-contact-tuned.groovy) | community PARASOLL driver |
| IKEA VINDSTYRKA Air Quality Monitor | [`drivers/ikea-vindstyrka-air-quality-tuned.groovy`](drivers/ikea-vindstyrka-air-quality-tuned.groovy) | kkossev VINDSTYRKA 3.2.0 |
| Tuya Zigbee Metering Plug (also Third Reality 3RSP02028BZ) | [`drivers/tuya-zigbee-metering-plug-tuned.groovy`](drivers/tuya-zigbee-metering-plug-tuned.groovy) | kkossev Tuya Zigbee Metering Plug 2.1.1 |
| Third Reality Vibration Sensor 3RVS01031Z (knock sensor) | [`drivers/thirdreality-vibration-3rvs01031z-tuned.groovy`](drivers/thirdreality-vibration-3rvs01031z-tuned.groovy) | new driver (replaces the built-in one) |

## What changed

### Zooz ZSE44 — v1.3.0 "battery optimized"
- Temperature and humidity reporting is tuned so Home Assistant sees near-real-time changes
  for fan/damper control while the sensor still sleeps most of the time.
- `heartbeatEvents` setting: optionally re-send unchanged values on each report so HA's
  `last_reported` keeps moving.
- Detects a pending wake-up interval change and warns when the interval is outside the safe range.
- Hardware offsets accept decimals.

### Zooz ZSE18 — v1.2.2-t1
- Fixes the sensor getting stuck in `active`: every motion-active event arms a fail-safe
  (`motionFailsafe` preference, default 10 min, never shorter than 2× the configured clear
  time + 60 s) that clears motion if the device's own inactive report never arrives.
- Real Z-Wave fingerprint, no phone-home, `isLongRange` fix.

### IKEA VALLHORN E2134
- New preferences: `onDuration`, `onlyWhenDark`, `noMotionDelay` (motion clear delay, writes
  cluster 0x0406 attr 0x0010), `motionThreshold`, `motionDelay`.
- Commands `applyMotionSettings` and `probeSettings` (the device only accepts writes while awake,
  so settings are applied on the next wake / motion event).
- Correct reporting configuration for illuminance and occupancy; ZDP responses no longer logged
  as unknown messages.

### IKEA PARASOLL E2013
- Contact is taken from zone-status bit 0 (the original read the whole value).
- Zone-status notifications update contact and warn on low battery.
- Unknown bindings are kept and warned about rather than removed; the hub's own binding is skipped.
- ZDP responses ignored; reportable-change encoding fix; default log level Info.

### IKEA VINDSTYRKA — v3.2.0-t3
- PM2.5: negative-value guard and proper rounding; refresh covers the VINDSTYRKA; VOC duplicate
  check fixed; `logWarn` respects the logging switches.
- Humidity hysteresis: the sensor reports whole percentages, so a reversal must exceed two steps
  before an event is sent — this cut humidity events ~100×.
- Temperature: the firmware only reports whole degrees C (verified: every raw 0x0402 value is a
  multiple of 100 even with a 0.1 °C reportable change, and the Tolerance attribute is
  unsupported). A `Temperature Smoothing` preference (Off / 5 / 10 / 20 / 30 min, default 10)
  estimates a 0.1 °C value: when the reading changes the estimate snaps to the half-degree boundary
  just crossed, then drifts toward the reading with the chosen time constant. The unsmoothed
  reading is kept in `temperatureRaw`.

### Tuya Zigbee Metering Plug — v2.1.1-t1
- Energy (cluster 0x0702 attr 0x0000, UINT48) is parsed as a 64-bit value. Third Reality plugs
  count lifetime energy in watt-seconds; once the counter passes 2^31 (≈ 597 kWh) the original
  32-bit parse threw on every report and energy stayed at 0.
- **Power/amperage throttle.** The Third Reality 3RSP02028BZ accepts a 30 s minimum reporting
  interval in Configure Reporting and then keeps pushing 0x0B04 readings every ~5 s anyway, so a
  busy load floods the hub and Home Assistant. New preferences:
  - `Power/Amperage update throttle` (Off / 10 s / 30 s / 1 min / 2 min / 5 min, default 30 s):
    at most one power and one amperage event per interval. The latest held-back value is always
    sent when the interval ends, so the attribute never goes stale.
  - `Throttle bypass` (W, default 50): a power change at least this large is sent immediately.
    Amperage uses the same bypass converted at the present voltage.
- Amperage reportable change: the setting is in mA, but it was multiplied by the A→raw divisor,
  so "50 mA" was sent to the device as 50 A. It is now sent in raw units.

### Third Reality Vibration Sensor 3RVS01031Z — v1.0.0 (new)
Written from scratch to replace Hubitat's built-in driver, which reports only active/inactive and
battery, discards the X/Y/Z data in every report, and declares a ContactSensor it never sets.
- **Knock detection:** `Hits for a knock` (default 2) inside `Knock window` (default 4 s) fires
  button 1 `pushed` once per burst, with `knockCount`. A dashboard `push` sends a test knock.
- **Strength filter:** `Minimum vibration level` ignores weak hits. The level is the larger of how
  far the sample is from 1 g and how far it moved from the previous sample in the burst (≈1000 = 1 g).
  On a front door, visitor knocks measured 462–1410 and a light bump's first hit 200, so 300 plus
  2 hits separates them.
- **Hold time:** `acceleration` stays active for the chosen time after the last hit (default 30 s;
  the sensor itself clears after ~6 s), so one knocking burst is one active/inactive cycle.
- `vibrationLevel`, optional `threeAxis`, battery % and voltage. Configure binds 0x0001 and 0xFFF1 and
  sets hourly battery reporting.

## Notes
- Hubitat's sandbox does not allow one `@Field static` to reference another in its initializer;
  keep that in mind when editing the option maps.
- Numeric preferences are written on **Save Preferences**; VALLHORN/PARASOLL writes also need the
  device awake.
