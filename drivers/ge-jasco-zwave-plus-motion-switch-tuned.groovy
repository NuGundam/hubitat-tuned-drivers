/**
 *  GE/Jasco Z-Wave Plus Motion Switch (Tuned) - 26931 / ZW4006
 *
 *  Tuned fork of "GE Z-Wave Plus Motion Switch" by Jason Bottjen (Botched1), itself a port of Matt Lebaugh's DTH.
 *  https://github.com/Botched1/Hubitat/tree/master/Drivers/GE-Jasco%20Z-Wave%20Plus%20Motion%20Switch
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
 *  Device parameters (26931):
 *    1  Light timeout (Occupancy/Vacancy only): 0 = 5 s, 1 / 5 / 15 / 30 = minutes, 255 = disabled
 *    3  Operating mode: 1 = Manual, 2 = Vacancy, 3 = Occupancy
 *    5  Invert buttons          6  Motion sensor enable
 *    13 Motion sensitivity: 1 = High, 2 = Medium, 3 = Low
 *    14 Light sensing (Occupancy only turns on when dark)
 *    15 Motion reset timer (2 bytes): 0 = disabled, 1 = 10 s, 2 = 20 s, 3 = 30 s, 4 = 45 s, 110 = 27 min
 *       How long after the last movement the switch reports "no motion". Shorter = finer-grained motion
 *       for a hub/HA automation to time from.
 *
 *  2.4.3   (05/24/2021) Botched1 - last upstream version
 *  2.4.3-t1 (2026-10-11) tuned fork:
 *    - Operating mode and light timeout are preferences again (sent on Save), so a switch can be put in Manual mode
 *      and the hub / Home Assistant does the motion-to-light logic.
 *    - New attribute lastSwitchSource (physical / digital): a press at the wall is "physical", a hub command is
 *      "digital", so an automation can respect a manual off.
 *    - Double-tap up / down reported as doubleTapped button 1 / 2 (two presses in the same direction within 2 s;
 *      the switch sends a group 3 Basic Set on every press, not only on a double-tap).
 *    - Light timeout 'disabled' removed: the 26931 firmware rejects 255 and stores 0 = 5 seconds (use Manual mode instead).
 *    - Fixes: 1-byte parameters are sent signed; setLightTimeout('15 minutes') reported '5 minutes'; '5 minutes' reported nothing; updated() checked a
 *      non-existent txtEnable; unconditional debug logs; debug logging now defaults off and turns itself off.
 *    - Configuration reports update attributes with stable names (operatingMode, lightTimeout, motionSensitivity,
 *      motionResetTimer, lightSensing, motionEnabled, invertButtons).
 */

import groovy.transform.Field

@Field static final String VERSION = '2.4.3-t1'
@Field static final Map CC_VERSIONS = [0x20: 1, 0x25: 1, 0x56: 1, 0x70: 2, 0x71: 3, 0x72: 2, 0x85: 2, 0x86: 1]
@Field static final long DIGITAL_WINDOW_MS = 4000L
@Field static final long DOUBLE_TAP_MS = 2000L       // two presses in the same direction this close together = double-tap    // a switch report this soon after a hub command is the result of that command

@Field static final Map LIGHT_TIMEOUT_OPTS = ['0': '5 seconds', '1': '1 minute', '5': '5 minutes (default)', '15': '15 minutes', '30': '30 minutes']    // 255 'disabled' is rejected by 26931 firmware (stored as 0 = 5 s)
@Field static final Map MODE_OPTS = ['1': 'Manual', '2': 'Vacancy', '3': 'Occupancy (default)']
@Field static final Map SENS_OPTS = ['1': 'High', '2': 'Medium (default)', '3': 'Low']
@Field static final Map RESET_OPTS = ['0': 'Disabled', '1': '10 seconds', '2': '20 seconds (default)', '3': '30 seconds', '4': '45 seconds', '110': '27 minutes']

metadata {
    definition(name: 'GE Z-Wave Plus Motion Switch (Tuned)', namespace: 'erikh', author: 'Jason Bottjen / erikh',
            importUrl: 'https://raw.githubusercontent.com/NuGundam/hubitat-tuned-drivers/main/drivers/ge-jasco-zwave-plus-motion-switch-tuned.groovy') {
        capability 'Actuator'
        capability 'Switch'
        capability 'Light'
        capability 'MotionSensor'
        capability 'DoubleTapableButton'
        capability 'Configuration'
        capability 'Refresh'
        capability 'Sensor'

        attribute 'operatingMode', 'string'
        attribute 'lightTimeout', 'string'
        attribute 'motionSensitivity', 'string'
        attribute 'motionResetTimer', 'string'
        attribute 'lightSensing', 'string'
        attribute 'motionEnabled', 'string'
        attribute 'invertButtons', 'string'
        attribute 'lastSwitchSource', 'enum', ['physical', 'digital']

        command 'setLightTimeout', [[name: 'Light Timeout', type: 'ENUM', description: 'Only applies in Occupancy and Vacancy modes',
                constraints: ['5 seconds', '1 minute', '5 minutes (default)', '15 minutes', '30 minutes']]]
        command 'Occupancy'
        command 'Vacancy'
        command 'Manual'

        fingerprint mfr: '0063', prod: '494D', deviceId: '3032', inClusters: '0x5E,0x72,0x5A,0x73,0x27,0x25,0x2B,0x2C,0x70,0x86,0x71,0x60,0x8E,0x85,0x5C,0x59,0x7A,0x56', deviceJoinName: 'GE Z-Wave Plus Motion Switch'
    }

    preferences {
        input 'paramOperationMode', 'enum', title: '<b>Operating mode</b>', options: MODE_OPTS, defaultValue: '3',
            description: '<i>Manual: the switch never turns its light on or off by itself; motion is only reported, so the hub or Home Assistant decides. Occupancy: on with motion, off after the light timeout. Vacancy: manual on, automatic off.</i>'
        input 'paramLightTimer', 'enum', title: '<b>Light timeout</b>', options: LIGHT_TIMEOUT_OPTS, defaultValue: '5',
            description: '<i>Occupancy / Vacancy only. Ignored in Manual mode.</i>'
        input 'paramMotionEnabled', 'enum', title: '<b>Motion sensor</b>', options: ['0': 'Disabled', '1': 'Enabled (default)'], defaultValue: '1'
        input 'paramMotionSensitivity', 'enum', title: '<b>Motion sensitivity</b>', options: SENS_OPTS, defaultValue: '2',
            description: '<i>Lower if warm or cold air from a vent near the switch causes false motion; raise if small movements are missed.</i>'
        input 'paramMotionResetTimer', 'enum', title: '<b>Motion reset time</b>', options: RESET_OPTS, defaultValue: '2',
            description: '<i>How long after the last movement the switch reports "no motion". With the hub doing the light timing, 10 s gives the most precise picture.</i>'
        input 'paramLightSense', 'enum', title: '<b>Light sensing</b>', options: ['0': 'Disabled', '1': 'Enabled (default)'], defaultValue: '1',
            description: '<i>Occupancy only: turn the light on only when the room is dark.</i>'
        input 'paramInverted', 'enum', title: '<b>Switch buttons direction</b>', options: ['0': 'Normal (default)', '1': 'Inverted'], defaultValue: '0'
        input name: 'requestedGroup2', type: 'text', title: '<b>Association group 2 members</b> (max 5, hex node IDs, comma separated)', required: false
        input name: 'requestedGroup3', type: 'text', title: '<b>Association group 3 members</b> (max 4, hex node IDs, comma separated)', required: false
        input name: 'logDesc', type: 'bool', title: '<b>Enable descriptionText logging</b>', defaultValue: true
        input name: 'logEnable', type: 'bool', title: '<b>Enable debug logging</b>', defaultValue: false, description: '<i>Turns itself off after 30 minutes.</i>'
    }
}

/* ---------------------------------------------------------------- parse */

void parse(String description) {
    if (description == 'updated') { return }
    def cmd = zwave.parse(description, CC_VERSIONS)
    logDebug "parse: ${description} -> ${cmd}"
    if (cmd) { zwaveEvent(cmd) }
}

void zwaveEvent(hubitat.zwave.commands.crc16encapv1.Crc16Encap cmd) {
    Map ver = [112: 2, 113: 3, 114: 2, 133: 2]
    def encapsulated = zwave.getCommand(cmd.commandClass, cmd.command, cmd.data, ver[cmd.commandClass as Integer] ?: 1)
    if (encapsulated) { zwaveEvent(encapsulated) } else { log.warn "${device.displayName} unable to extract CRC16 command from ${cmd}" }
}

void zwaveEvent(hubitat.zwave.commands.switchbinaryv1.SwitchBinaryReport cmd) {
    String value = cmd.value ? 'on' : 'off'
    boolean digital = (now() - ((state.lastHubCmd ?: 0L) as long)) < DIGITAL_WINDOW_MS
    String type = digital ? 'digital' : 'physical'
    String desc = "${device.displayName} was turned ${value}${digital ? '' : ' at the switch'}"
    if (device.currentValue('switch') != value || !digital) {
        sendEvent(name: 'switch', value: value, descriptionText: desc, type: type, isStateChange: !digital)
        logInfo desc
    }
    sendEvent(name: 'lastSwitchSource', value: type)
    if (digital) { state.remove('lastHubCmd') }
}

void zwaveEvent(hubitat.zwave.commands.basicv1.BasicReport cmd) {
    logDebug "basic report ${cmd}"
}

// The 26931 sends a Basic Set to association group 3 on EVERY paddle press (seen 2026-10-11: one press down
// produced one Basic Set 0 together with the switch report), so a double-tap is two presses in the same
// direction within DOUBLE_TAP_MS.
void zwaveEvent(hubitat.zwave.commands.basicv1.BasicSet cmd) {
    int btn = cmd.value ? 1 : 2
    long nowMs = now()
    long last = (state."lastPress${btn}" ?: 0L) as long
    state."lastPress${btn}" = nowMs
    if (nowMs - last <= DOUBLE_TAP_MS) {
        state.remove("lastPress${btn}")
        sendEvent(name: 'doubleTapped', value: btn, isStateChange: true, type: 'physical',
                descriptionText: "${device.displayName} double-tapped ${btn == 1 ? 'up' : 'down'} (button ${btn})")
        logInfo "double-tapped ${btn == 1 ? 'up' : 'down'}"
    }
    else {
        logDebug "paddle ${btn == 1 ? 'up' : 'down'} pressed"
    }
}

void zwaveEvent(hubitat.zwave.commands.notificationv3.NotificationReport cmd) {
    if (cmd.notificationType != 0x07) { return }
    if (cmd.event == 0x08) {
        sendEvent(name: 'motion', value: 'active', descriptionText: "${device.displayName} detected motion", type: 'physical')
        logInfo 'detected motion'
    }
    else if (cmd.event == 0x00) {
        sendEvent(name: 'motion', value: 'inactive', descriptionText: "${device.displayName} motion has stopped", type: 'physical')
        logInfo 'motion has stopped'
    }
}

void zwaveEvent(hubitat.zwave.commands.configurationv2.ConfigurationReport cmd) {
    int v = cmd.scaledConfigurationValue as int
    String key = (v & 0xFF).toString()
    switch (cmd.parameterNumber) {
        case 1:  sendEvent(name: 'lightTimeout', value: LIGHT_TIMEOUT_OPTS[key] ?: "unknown (${v})"); break
        case 3:  sendEvent(name: 'operatingMode', value: MODE_OPTS[key] ?: "unknown (${v})"); break
        case 5:  sendEvent(name: 'invertButtons', value: v == 1 ? 'Inverted' : 'Normal'); break
        case 6:  sendEvent(name: 'motionEnabled', value: v == 1 ? 'Enabled' : 'Disabled'); break
        case 13: sendEvent(name: 'motionSensitivity', value: SENS_OPTS[key] ?: "unknown (${v})"); break
        case 14: sendEvent(name: 'lightSensing', value: v == 1 ? 'Enabled' : 'Disabled'); break
        case 15: sendEvent(name: 'motionResetTimer', value: RESET_OPTS[v.toString()] ?: "unknown (${v})"); break
        default: logDebug "configuration report ${cmd}"
    }
}

void zwaveEvent(hubitat.zwave.commands.associationv2.AssociationReport cmd) {
    logDebug "association report group ${cmd.groupingIdentifier}: ${cmd.nodeId}"
    if (cmd.groupingIdentifier == 3) {
        if (!cmd.nodeId.contains(zwaveHubNodeId)) { log.warn "${device.displayName} hub is not in association group 3 - double-tap events will not arrive; run Configure" }
    }
}

void zwaveEvent(hubitat.zwave.commands.manufacturerspecificv2.ManufacturerSpecificReport cmd) {
    updateDataValue('MSR', String.format('%04X-%04X-%04X', cmd.manufacturerId, cmd.productTypeId, cmd.productId))
}

void zwaveEvent(hubitat.zwave.commands.versionv1.VersionReport cmd) {
    updateDataValue('firmware', "${cmd.applicationVersion}.${cmd.applicationSubVersion}")
}

void zwaveEvent(hubitat.zwave.Command cmd) {
    logDebug "unhandled ${cmd}"
}

/* ---------------------------------------------------------------- commands */

void on()  { switchCmd(0xFF) }
void off() { switchCmd(0x00) }

private void switchCmd(int value) {
    state.lastHubCmd = now()
    sendHubCommand(new hubitat.device.HubMultiAction(delayBetween([
            zwave.switchBinaryV1.switchBinarySet(switchValue: value).format(),
            zwave.switchBinaryV1.switchBinaryGet().format()], 500), hubitat.device.Protocol.ZWAVE))
}

void doubleTap(BigDecimal button) {    // DoubleTapableButton command: lets a dashboard test an automation
    sendEvent(name: 'doubleTapped', value: button as int, isStateChange: true, type: 'digital')
}

List<String> setLightTimeout(String value) {
    String key = LIGHT_TIMEOUT_OPTS.find { it.value == value }?.key
    if (key == null) { return [] }
    device.updateSetting('paramLightTimer', [value: key, type: 'enum'])
    return setParam(1, key.toInteger(), 1)
}

List<String> Occupancy() { return setMode('3') }
List<String> Vacancy()   { return setMode('2') }
List<String> Manual()    { return setMode('1') }

private List<String> setMode(String key) {
    device.updateSetting('paramOperationMode', [value: key, type: 'enum'])
    return setParam(3, key.toInteger(), 1)
}

// 1-byte parameters are signed: 255 ('disabled') must be sent as -1 or the switch stores 0 (5 seconds)
private static int signedValue(int value, int size) {
    return (size == 1 && value > 127) ? value - 256 : value
}

private List<String> setParam(int num, int value, int size) {
    return delayBetween([
            zwave.configurationV2.configurationSet(scaledConfigurationValue: signedValue(value, size), parameterNumber: num, size: size).format(),
            zwave.configurationV2.configurationGet(parameterNumber: num).format()], 500)
}

List<String> refresh() {
    state.lastHubCmd = now()    // the switch report this produces is a hub read, not a press at the wall
    List<String> cmds = [zwave.switchBinaryV1.switchBinaryGet().format(),
                         zwave.notificationV3.notificationGet(notificationType: 7).format()]
    [1, 3, 5, 6, 13, 14, 15].each { cmds << zwave.configurationV2.configurationGet(parameterNumber: it).format() }
    if (!getDataValue('MSR')) { cmds << zwave.manufacturerSpecificV2.manufacturerSpecificGet().format() }
    cmds << zwave.versionV1.versionGet().format()
    return delayBetween(cmds, 600)
}

void installed() {
    sendEvent(name: 'numberOfButtons', value: 2)
}

List<String> configure() {
    logInfo "configure (driver ${VERSION})"
    sendEvent(name: 'numberOfButtons', value: 2)    // the hub is put in group 3 below; some units never answer the association get
    return delayBetween([
            zwave.associationV2.associationSet(groupingIdentifier: 1, nodeId: zwaveHubNodeId).format(),
            zwave.associationV2.associationRemove(groupingIdentifier: 2, nodeId: zwaveHubNodeId).format(),
            zwave.associationV2.associationSet(groupingIdentifier: 3, nodeId: zwaveHubNodeId).format(),
            zwave.associationV2.associationGet(groupingIdentifier: 3).format()], 500) + refresh()
}

List<String> updated() {
    logInfo "preferences saved (driver ${VERSION})"
    sendEvent(name: 'numberOfButtons', value: 2)
    unschedule('logsOff')
    if (logEnable) { runIn(1800, 'logsOff') }
    if (state.lastUpdated && now() - (state.lastUpdated as long) < 3000) { return [] }    // Hubitat can call updated() twice
    state.lastUpdated = now()

    List<String> cmds = []
    [[3, 'paramOperationMode', '3', 1], [1, 'paramLightTimer', '5', 1], [5, 'paramInverted', '0', 1],
     [6, 'paramMotionEnabled', '1', 1], [13, 'paramMotionSensitivity', '2', 1], [14, 'paramLightSense', '1', 1],
     [15, 'paramMotionResetTimer', '2', 2]].each { List p ->
        int value = ((settings[p[1]] ?: p[2]) as String).toInteger()
        cmds << zwave.configurationV2.configurationSet(scaledConfigurationValue: signedValue(value, p[3] as int), parameterNumber: p[0] as int, size: p[3] as int).format()
        cmds << zwave.configurationV2.configurationGet(parameterNumber: p[0] as int).format()
    }
    cmds << zwave.associationV2.associationSet(groupingIdentifier: 1, nodeId: zwaveHubNodeId).format()
    cmds << zwave.associationV2.associationSet(groupingIdentifier: 3, nodeId: zwaveHubNodeId).format()
    if (settings.requestedGroup2 != state.currentGroup2) {
        cmds << zwave.associationV2.associationRemove(groupingIdentifier: 2, nodeId: []).format()
        List nodes = parseAssocGroupList(settings.requestedGroup2, 2)
        if (nodes) { cmds << zwave.associationV2.associationSet(groupingIdentifier: 2, nodeId: nodes).format() }
        state.currentGroup2 = settings.requestedGroup2
    }
    if (settings.requestedGroup3 != state.currentGroup3) {
        cmds << zwave.associationV2.associationRemove(groupingIdentifier: 3, nodeId: []).format()
        cmds << zwave.associationV2.associationSet(groupingIdentifier: 3, nodeId: parseAssocGroupList(settings.requestedGroup3, 3)).format()
        state.currentGroup3 = settings.requestedGroup3
    }
    cmds << zwave.associationV2.associationGet(groupingIdentifier: 3).format()
    return delayBetween(cmds, 500)
}

private List parseAssocGroupList(String list, int group) {
    List nodes = group == 2 ? [] : [zwaveHubNodeId]
    if (!list) { return nodes }
    int max = group == 2 ? 5 : 4
    list.split(',').each { String raw ->
        String node = raw.trim()
        if (!node.matches('\\p{XDigit}+')) { log.warn "${device.displayName} group ${group}: invalid member ${node}"; return }
        int id = Integer.parseInt(node, 16)
        if (id == zwaveHubNodeId) { log.warn "${device.displayName} group ${group}: the hub is added automatically"; return }
        if (nodes.size() >= max + (group == 3 ? 1 : 0)) { log.warn "${device.displayName} group ${group}: more than ${max} members, ${node} ignored"; return }
        if (id > 0 && id < 256) { nodes << id }
    }
    return nodes
}

void logsOff() {
    device.updateSetting('logEnable', [value: 'false', type: 'bool'])
    log.info "${device.displayName} debug logging disabled"
}

private void logDebug(String msg) { if (logEnable) { log.debug "${device.displayName} ${msg}" } }
private void logInfo(String msg)  { if (logDesc != false) { log.info "${device.displayName} ${msg}" } }
