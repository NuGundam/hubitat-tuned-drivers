/**
 *  Third Reality Vibration Sensor (Tuned) - 3RVS01031Z
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *  The sensor reports on manufacturer cluster 0xFFF1 (unsolicited, ~1 report per hit):
 *    0x0000 vibration state (1 = vibrating, 0 = still, sent ~6 s after the last hit)
 *    0x0001 / 0x0002 / 0x0003 X / Y / Z acceleration, INT16, about 1000 = 1 g (only in "vibrating" reports)
 *  Battery: cluster 0x0001 attr 0x0021 (percent * 2) and 0x0020 (voltage * 10). Firmware string: 0x0000 attr 0x4000.
 *
 *  What this driver adds over the built-in one:
 *    - Knock detection: N hits inside a short window fire button 1 "pushed" once per burst, plus knockCount.
 *    - Strength filter: hits below a minimum vibration level are ignored.
 *    - Hold time: acceleration stays active for a set time after the last hit, so one burst is one event.
 *    - vibrationLevel and optional threeAxis (X/Y/Z) attributes.
 *    - No ContactSensor capability (the built-in driver declares it but never sets it).
 *
 *  ver. 1.0.0 2026-10-10 (erikh) - initial version
 */

import groovy.transform.Field
import hubitat.zigbee.zcl.DataType

@Field static final String VERSION = '1.0.0'
@Field static final int ONE_G = 1000

@Field static final Map HoldOpts = [
    defaultValue: 30,
    options     : [0: 'Device default (~6 s)', 10: '10 seconds', 30: '30 seconds', 60: '1 minute', 120: '2 minutes', 300: '5 minutes']
]

metadata {
    definition(name: 'Third Reality Vibration Sensor (Tuned)', namespace: 'erikh', author: 'erikh', importUrl: 'https://raw.githubusercontent.com/NuGundam/hubitat-tuned-drivers/main/drivers/thirdreality-vibration-3rvs01031z-tuned.groovy') {
        capability 'AccelerationSensor'
        capability 'ThreeAxis'
        capability 'PushableButton'
        capability 'Battery'
        capability 'Configuration'
        capability 'Refresh'
        capability 'Sensor'

        attribute 'vibrationLevel', 'number'    // strength of the last accepted hit (see parseVibration)
        attribute 'knockCount', 'number'        // hits in the current / last knock burst
        attribute 'batteryVoltage', 'number'

        fingerprint profileId: '0104', endpointId: '01', inClusters: '0000,0001,FFF1', outClusters: '0019', model: '3RVS01031Z', manufacturer: 'Third Reality, Inc', deviceJoinName: 'Third Reality Vibration Sensor'
    }

    preferences {
        input name: 'holdSecs', type: 'enum', title: '<b>Hold time</b>', options: HoldOpts.options, defaultValue: HoldOpts.defaultValue,
            description: '<i>Acceleration stays active this long after the last hit, so one burst of knocking is one active/inactive cycle.</i>'
        input name: 'minLevel', type: 'number', title: '<b>Minimum vibration level</b>', range: '0..5000', defaultValue: 0,
            description: '<i>Hits weaker than this are ignored (about 1000 = 1 g). 0 accepts every hit. Watch vibrationLevel while knocking to pick a value.</i>'
        input name: 'knockHits', type: 'number', title: '<b>Hits for a knock</b>', range: '1..10', defaultValue: 2,
            description: '<i>How many hits inside the knock window count as a knock (button 1 pushed). 1 = any accepted hit.</i>'
        input name: 'knockWindow', type: 'number', title: '<b>Knock window</b>, seconds', range: '1..30', defaultValue: 4,
            description: '<i>The hits must all fall inside this many seconds.</i>'
        input name: 'reportAxes', type: 'bool', title: '<b>Report X/Y/Z</b>', defaultValue: false,
            description: '<i>Send a threeAxis event with every hit. Off keeps Home Assistant quieter.</i>'
        input name: 'txtEnable', type: 'bool', title: '<b>Enable descriptionText logging</b>', defaultValue: true
        input name: 'logEnable', type: 'bool', title: '<b>Enable debug logging</b>', defaultValue: false,
            description: '<i>Turns itself off after 30 minutes.</i>'
    }
}

/* ---------------------------------------------------------------- lifecycle */

void installed() {
    sendEvent(name: 'numberOfButtons', value: 1)
    sendEvent(name: 'acceleration', value: 'inactive')
    sendEvent(name: 'knockCount', value: 0)
}

void updated() {
    logInfo "preferences saved (driver ${VERSION})"
    unschedule('logsOff')
    if (logEnable) { runIn(1800, 'logsOff') }
    sendEvent(name: 'numberOfButtons', value: 1)
    if (!reportAxes) { device.deleteCurrentState('threeAxis') }
}

void logsOff() {
    device.updateSetting('logEnable', [value: 'false', type: 'bool'])
    logInfo 'debug logging disabled'
}

List<String> configure() {
    logInfo "configure (driver ${VERSION})"
    sendEvent(name: 'numberOfButtons', value: 1)
    state.remove('hits'); state.remove('knockFired')
    List<String> cmds = []
    cmds += "zdo bind 0x${device.deviceNetworkId} 0x01 0x01 0x0001 {${device.zigbeeId}} {}"
    cmds += 'delay 200'
    cmds += "zdo bind 0x${device.deviceNetworkId} 0x01 0x01 0xFFF1 {${device.zigbeeId}} {}"
    cmds += 'delay 200'
    cmds += zigbee.configureReporting(0x0001, 0x0021, DataType.UINT8, 3600, 21600, 2)     // battery %, at most hourly
    cmds += zigbee.readAttribute(0x0001, [0x0020, 0x0021], [:], 200)
    cmds += zigbee.readAttribute(0x0000, 0x4000, [:], 200)
    return cmds
}

List<String> refresh() {
    // sleepy device: the read is delivered on its next check-in (or right away after a hit)
    return zigbee.readAttribute(0x0001, [0x0020, 0x0021], [:], 200) + zigbee.readAttribute(0x0000, 0x4000, [:], 200)
}

void push(BigDecimal button) {    // PushableButton command: lets a dashboard test the knock automation
    fireKnock(0, true)
}

/* ---------------------------------------------------------------- parsing */

void parse(String description) {
    Map descMap = zigbee.parseDescriptionAsMap(description)
    logDebug "parse: ${descMap}"
    if (descMap == null || descMap.isEmpty()) { return }
    List<Map> attrs = [descMap] + ((descMap.additionalAttrs ?: []) as List<Map>)
    switch (descMap.clusterInt) {
        case 0xFFF1:
            Map<String, String> vals = [:]
            attrs.each { Map a -> if (a.attrId != null && a.value != null) { vals[(a.attrId as String).toUpperCase()] = a.value as String } }
            parseVibration(vals)
            break
        case 0x0001:
            attrs.each { Map a -> parseBattery(a) }
            break
        case 0x0000:
            attrs.each { Map a ->
                if (a.attrId == '4000' && a.value) { device.updateDataValue('firmware', a.value as String) }
            }
            break
        default:
            break
    }
}

private void parseBattery(Map a) {
    if (a.attrId == '0021' && a.value) {
        int pct = Math.min(100, Math.round(Integer.parseInt(a.value as String, 16) / 2.0d) as int)
        sendEvent(name: 'battery', value: pct, unit: '%', descriptionText: "${device.displayName} battery is ${pct}%")
        logInfo "battery is ${pct}%"
    }
    else if (a.attrId == '0020' && a.value) {
        BigDecimal v = Integer.parseInt(a.value as String, 16) / 10.0G
        sendEvent(name: 'batteryVoltage', value: v, unit: 'V')
    }
}

private static int s16(String hex) {
    int v = Integer.parseInt(hex, 16)
    return v > 32767 ? v - 65536 : v
}

private void parseVibration(Map<String, String> vals) {
    if (!vals.containsKey('0000')) { return }
    boolean vibrating = Integer.parseInt(vals['0000'], 16) != 0
    if (!vibrating) {
        if (holdSecsSetting() == 0) { endBurst() }    // device-timed mode: follow the sensor's own "still" report
        return
    }
    Integer x = vals['0001'] != null ? s16(vals['0001']) : null
    Integer y = vals['0002'] != null ? s16(vals['0002']) : null
    Integer z = vals['0003'] != null ? s16(vals['0003']) : null

    // Strength of the hit. The sensor only sends one acceleration sample per report, so use the larger of
    // (a) how far the total acceleration is from 1 g and (b) how much the vector moved since the previous sample
    // of this burst. Both grow with how hard the door is hit; neither depends on how the sensor is mounted.
    int level = 0
    if (x != null && y != null && z != null) {
        double mag = Math.sqrt(x * x + y * y + z * z)
        level = Math.abs(mag - ONE_G) as int
        List prev = state.lastAxes as List
        long lastMs = (state.lastAxesTime ?: 0L) as long
        if (prev != null && (now() - lastMs) < 10000L) {
            double dx = x - (prev[0] as int), dy = y - (prev[1] as int), dz = z - (prev[2] as int)
            level = Math.max(level, Math.sqrt(dx * dx + dy * dy + dz * dz) as int)
        }
        state.lastAxes = [x, y, z]
        state.lastAxesTime = now()
        if (reportAxes) { sendEvent(name: 'threeAxis', value: [x: x, y: y, z: z]) }
    }

    int min = (settings?.minLevel ?: 0) as int
    if (level < min) {
        logDebug "ignored hit: level ${level} is below ${min}"
        return
    }
    sendEvent(name: 'vibrationLevel', value: level)
    registerHit(level)
}

/* ---------------------------------------------------------------- burst / knock logic */

private int holdSecsSetting() {
    Object v = settings?.holdSecs
    return v == null ? (HoldOpts.defaultValue as int) : (v as String).toInteger()
}

private void registerHit(int level) {
    long nowMs = now()
    long windowMs = (((settings?.knockWindow ?: 4) as int) * 1000L)
    List<Long> hits = ((state.hits ?: []) as List).collect { it as long }.findAll { nowMs - it <= windowMs }
    hits << nowMs
    state.hits = hits

    if (device.currentValue('acceleration') != 'active') {
        sendEvent(name: 'acceleration', value: 'active', descriptionText: "${device.displayName} vibration detected (level ${level})")
        logInfo "vibration detected (level ${level})"
    }
    int hold = holdSecsSetting()
    if (hold > 0) { runIn(hold, 'endBurst', [overwrite: true]) }

    int need = Math.max(1, ((settings?.knockHits ?: 2) as int))
    if (state.knockFired) {
        state.burstHits = (state.burstHits ?: 0) + 1
        sendEvent(name: 'knockCount', value: state.burstHits)
    }
    else if (hits.size() >= need) {
        state.knockFired = true
        state.burstHits = hits.size()
        fireKnock(hits.size(), false)
    }
}

private void fireKnock(int count, boolean digital) {
    sendEvent(name: 'knockCount', value: count)
    sendEvent(name: 'pushed', value: 1, isStateChange: true, type: digital ? 'digital' : 'physical',
        descriptionText: "${device.displayName} knock${digital ? ' (test)' : ''} - button 1 pushed")
    logInfo "knock detected${count ? " (${count} hits)" : ' (test)'} - button 1 pushed"
}

void endBurst() {
    state.hits = []
    state.knockFired = false
    state.remove('lastAxes')
    if (device.currentValue('acceleration') != 'inactive') {
        sendEvent(name: 'acceleration', value: 'inactive', descriptionText: "${device.displayName} vibration stopped")
        logInfo 'vibration stopped'
    }
}

/* ---------------------------------------------------------------- logging */

private void logDebug(String msg) { if (logEnable) { log.debug "${device.displayName} ${msg}" } }
private void logInfo(String msg)  { if (txtEnable != false) { log.info "${device.displayName} ${msg}" } }
