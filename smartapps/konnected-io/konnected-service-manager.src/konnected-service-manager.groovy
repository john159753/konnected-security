/**
 *  Konnected
 *
 *  Copyright 2017 konnected.io
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 */
definition(
  name:        "Konnected Service Manager",
  parent:      "konnected-io:Konnected (Connect)",
  namespace:   "konnected-io",
  author:      "konnected.io",
  description: "Konnected devices bridge wired things with SmartThings",
  category:    "Safety & Security",
  iconUrl:     "https://raw.githubusercontent.com/konnected-io/docs/master/assets/images/KonnectedSecurity.png",
  iconX2Url:   "https://raw.githubusercontent.com/konnected-io/docs/master/assets/images/KonnectedSecurity@2x.png",
  iconX3Url:   "https://raw.githubusercontent.com/konnected-io/docs/master/assets/images/KonnectedSecurity@3x.png",
  singleInstance: true
)

mappings {
  path("/device/:mac/:id/:deviceState") { action: [ PUT: "childDeviceStateUpdate"] }
  path("/ping") { action: [ GET: "devicePing"] }
}


preferences {
  page(name: "pageWelcome",       install: false, uninstall: true, content: "pageWelcome", nextPage: "pageConfiguration")
  page(name: "pageDiscovery",     install: false, content: "pageDiscovery" )
  page(name: "pageConfiguration", install: true, content: "pageConfiguration")
}

def installed() {
  log.info "installed(): Installing Konnected SmartApp"
  initialize()
  runEvery1Hour(discoverySearch)
}

def updated() {
  log.info "updated(): Updating Konnected SmartApp"
  initialize()
}

def uninstalled() {
  log.info "uninstall(): Uninstalling Konnected SmartApp"
  revokeAccessToken()

  def body = [
    token : "",
    apiUrl : "",
    sensors : [],
    actuators : []
  ]

  def device = state.device
  sendHubCommand(new physicalgraph.device.HubAction([
    method: "PUT",
    path: "/settings",
    headers: [ HOST: getDeviceIpAndPort(device), "Content-Type": "application/json" ],
    body : groovy.json.JsonOutput.toJson(body)
  ], getDeviceIpAndPort(device) ))
}

def initialize() {
  unsubscribe()
  unschedule()
  discoverySubscription(true)
  if (app.label != deviceName()) { app.updateLabel(deviceName()) }
  childDeviceConfiguration()
  deviceUpdateSettings()
  state.pageConfigurationRefresh = 2
}

def deviceName() {
  if (name) {
    return name
  } else if (state.device) {
    return "konnected-" + state.device.mac[-6..-1]
  } else {
    return "New Konnected device"
  }
}

// Page : 1 : Welcome page - Manuals & links to devices
def pageWelcome() {
  def device = state.device
  dynamicPage( name: "pageWelcome", title: deviceName(), nextPage: "pageConfiguration") {
    section() {
      if (device) {
        href(
          name:        "device_" + device.mac,
          image:       "https://docs.konnected.io/assets/favicons/apple-touch-icon.png",
          title:       "Device status",
          url:         "http://" + getDeviceIpAndPort(device)
        )
      } else {
        href(
          name:        "discovery",
          title:       "Tap here to start discovery",
          page:        "pageDiscovery"
        )
      }
    }

    section("Help & Support") {
      href(
        name:        "pageWelcomeManual",
        title:       "Instructions & Documentation",
        description: "Tap to view the online documentation at http://docs.konnected.io",
        required:    false,
        image:       "https://raw.githubusercontent.com/konnected-io/docs/master/assets/images/manual-icon.png",
        url:         "http://docs.konnected.io/security-alarm-system/"
      )
    }
  }
}

//Page : 2 : Discovery page - search and select devices
def pageDiscovery() {
  //create accessToken
  if(!state.accessToken) { createAccessToken() }

  //This is a workaround to prevent page to refresh too fast.
  if(!state.pageConfigurationRefresh) { state.pageConfigurationRefresh = 2 }

  dynamicPage(name: "pageDiscovery", nextPage: "pageConfiguration", refreshInterval: state.pageConfigurationRefresh) {
    state.pageConfigurationRefresh =  state.pageConfigurationRefresh + 3
    discoverySubscription()
    discoverySearch()
    discoveryVerification()

    if (state.device?.verified) {
      section() {
        href(
          name: "discoveryComplete",
          title: "Found konnected-" + state.device.mac[-6..-1] + "!",
          description: "Tap to continue",
          page: "pageConfiguration"
        )
      }
    } else {
      section("Please wait while we discover your device") {
        paragraph "This may take up to a minute."
      }
    }
  }
}

//Page : 3 : Configure sensors and alarms connected to the panel
def pageConfiguration() {
  // Get all selected devices
  def device = state.device
  dynamicPage(name: "pageConfiguration") {
  	  section() {
  		input(
          name: "name",
          type: "text",
          title: "Device name",
          required: false,
          defaultValue: "konnected-" + device.mac[-6..-1]
        )
      }
      section(title: "Configure Things wired to each pin") {
        for ( i in [1, 2, 5, 6, 7, 8, 9]) {
          def deviceTypeDefaultValue = (settings."deviceType_${i}") ? settings."deviceType_${i}" : ""
          def deviceLabelDefaultValue = (settings."deviceLabel_${i}") ? settings."deviceLabel_${i}" : ""

          input(
            name: "deviceType_${i}",
            type: "enum",
            title: "Pin ${pinLabel(i)}",
            required: false,
            multiple: false,
            options: pageConfigurationGetDeviceType(i),
            defaultValue: deviceTypeDefaultValue,
            submitOnChange: true
          )

          if (settings."deviceType_${i}") {
            input(
              name: "deviceLabel_${i}",
              type: "text",
              title: "Pin ${pinLabel(i)} Name",
              description: "Name the device connected to ${pinLabel(i)}",
              required: (settings."deviceType_${i}" != null),
              defaultValue: deviceLabelDefaultValue
            )
          }
        }
      }

  }
}

private Map pageConfigurationGetDeviceType(Integer i) {
  def deviceTypes = [:]
  def sensorPins = [1,2,5,6,7,9]
  def actuatorPins = [1,2,5,6,7,8]

  if (sensorPins.contains(i)) {
    deviceTypes << sensorsMap()
  }

  if (actuatorPins.contains(i)) {
    deviceTypes << actuatorsMap()
  }

  return deviceTypes
}


def getDeviceIpAndPort(device) {
  "${convertHexToIP(device.networkAddress)}:${convertHexToInt(device.deviceAddress)}"
}

// Device Discovery : Device Type
def discoveryDeviceType() {
  return "urn:schemas-konnected-io:device:Security:1"
}

// Device Discovery : Send M-Search to multicast
def discoverySearch() {
  log.debug "Discovering Konnected devices on the network via SSDP"
  sendHubCommand(new physicalgraph.device.HubAction("lan discovery ${discoveryDeviceType()}", physicalgraph.device.Protocol.LAN))
}

// Device Discovery : Subscribe to SSDP events
def discoverySubscription(force=false) {
  if (force) {
    unsubscribe()
    state.subscribe = false
  }
  if(!state.subscribe) {
    subscribe(location, "ssdpTerm.${discoveryDeviceType()}", discoverySearchHandler, [filterEvents:false])
    state.subscribe = true
  }
}

// Device Discovery : Handle search response
def discoverySearchHandler(evt) {
  def event = parseLanMessage(evt.description)
  event << ["hub":evt?.hubId]
  String ssdpUSN = event.ssdpUSN.toString()
  def device = state.device
  if (device) {
    device.networkAddress = event.networkAddress
    device.deviceAddress = event.deviceAddress
    log.debug "Refreshed attributes of device $device"
  } else {
    state.device = event
    log.debug "Discovered new device $event"
    discoveryVerify(event)
  }
}

//Device Discovery : Verify search response by retrieving XML
def discoveryVerification() {
  if (state.device && !state.device.verified) {
    discoveryVerify(state.device)
  }
}

// Device Discovery : Verify a Device
def discoveryVerify(Map device) {
  log.debug "Verifying communication with device $device"
  String host = getDeviceIpAndPort(device)
  sendHubCommand(
    new physicalgraph.device.HubAction(
      """GET ${device.ssdpPath} HTTP/1.1\r\nHOST: ${host}\r\n\r\n""",
      physicalgraph.device.Protocol.LAN,
      host,
      [callback: discoveryVerificationHandler]
    )
  )
}

//Device Discovery : Handle verification response
def discoveryVerificationHandler(physicalgraph.device.HubResponse hubResponse) {
  def body = hubResponse.xml
  def device = state.device
  if (device?.ssdpUSN.contains(body?.device?.UDN?.text())) {
    log.debug "Verification Success: $body"
    device.name =  body?.device?.roomName?.text()
    device.model = body?.device?.modelName?.text()
    device.serialNumber = body?.device?.serialNum?.text()
    device.verified = true
  }
}

// Child Devices : create/delete child devices from SmartThings app selection
def childDeviceConfiguration() {
  def device = state.device
  settings.each { name , value ->
    def nameValue = name.split("\\_")
    if (nameValue[0] == "deviceType") {
      def deviceDNI = [ device.mac, "${nameValue[1]}"].join('|')
      def deviceLabel = settings."deviceLabel_${nameValue[1]}"
      def deviceType = value
      def deviceChild = getChildDevice(deviceDNI)

      if (!deviceChild) {
        if (deviceType != "") {
          addChildDevice("konnected-io", deviceType, deviceDNI, device.hub, [ "label": deviceLabel ? deviceLabel : deviceType , "completedSetup": true ])
        }
      } else {
        // Change name if it's set here
        if (deviceChild.label != deviceLabel)
          deviceChild.label = deviceLabel

        // Change Type, you will lose the history of events. delete and add back the child
        if (deviceChild.name != deviceType) {
          deleteChildDevice(deviceDNI)
          if (deviceType != "") {
            addChildDevice("konnected-io", deviceType, deviceDNI, device.hub, [ "label": deviceLabel ? deviceLabel : deviceType , "completedSetup": true ])
          }
        }
      }
    }
  }

  def deleteChildDevices = getAllChildDevices().findAll {
    settings."deviceType_${it.deviceNetworkId.split("\\|")[1]}" == null
  }

  deleteChildDevices.each {
    log.debug "Deleting device $it.deviceNetworkId"
    deleteChildDevice(it.deviceNetworkId)
  }
}
// Child Devices : update state of child device sent from nodemcu
def childDeviceStateUpdate() {
  def deviceId = params.mac.toUpperCase() + "|" + params.id
  log.debug "Received sensor update from Konnected device: $deviceId = $params.deviceState"
  def device = getChildDevice(deviceId)
  if (device) {
    device.setStatus(params.deviceState)
  } else {
    log.warn "Device $deviceId not found!"
  }
}

//Device: Ping from device
def devicePing() {
  return ""
}

//Device : update NodeMCU with token, url, sensors, actuators from SmartThings
def deviceUpdateSettings() {
  if(!state.accessToken) { createAccessToken() }
  updateSettingsOnDevice(state.device)
}

def updateSettingsOnDevice(device) {
  def sensors   = []
  def actuators = []
  def ip        = getDeviceIpAndPort(device)
  def mac       = device.mac

  getAllChildDevices().each {
    def pin = it.deviceNetworkId.split("\\|")[1]

    if (sensorsMap()[it.name]) {
      sensors = sensors + [ pin : pin ]
    } else {
      actuators = actuators + [ pin : pin, trigger : it.triggerLevel() ]
    }
  }

  log.debug "Configured sensors on $mac: $sensors"
  log.debug "Configured actuators on $mac: $actuators"

  def body = [
    token : state.accessToken,
    apiUrl : apiServerUrl + "/api/smartapps/installations/" + app.id,
    sensors : sensors,
    actuators : actuators
  ]

  log.debug "Updating settings on device $mac at $ip"
  sendHubCommand(new physicalgraph.device.HubAction([
    method: "PUT",
    path: "/settings",
    headers: [ HOST: ip, "Content-Type": "application/json" ],
    body : groovy.json.JsonOutput.toJson(body)
  ], ip ))
}

def getChildDevicesByDeviceId(mac) {
  return getAllChildDevices().findAll { mac == it.deviceNetworkId.split("\\|")[0] }
}

def updateSettingsOnChildDevice(deviceDNI) {
  def device = state.device
  updateSettingsOnDevice(device)
}

// Device: update NodeMCU with state of device changed from SmartThings
def deviceUpdateDeviceState(deviceDNI, deviceState, Map actuatorOptions = [:]) {
  def deviceId = deviceDNI.split("\\|")[1]
  def deviceMac = deviceDNI.split("\\|")[0]
  def body = [ pin : deviceId, state : deviceState ] << actuatorOptions
  def device = state.device

  if (device && device.mac == deviceMac) {
    log.debug "Updating device $deviceMac pin $deviceId to $deviceState at " + getDeviceIpAndPort(device)
    sendHubCommand(new physicalgraph.device.HubAction([
      method: "PUT",
      path: "/device",
      headers: [ HOST: getDeviceIpAndPort(device), "Content-Type": "application/json" ],
      body : groovy.json.JsonOutput.toJson(body)
    ], getDeviceIpAndPort(device), [callback: "syncChildPinState"]))
  }
}

void syncChildPinState(physicalgraph.device.HubResponse hubResponse) {
  def device = getAllChildDevices().find { it.deviceNetworkId == hubResponse.mac + '|' + hubResponse.json.pin }
  device?.updatePinState(hubResponse.json.state)
}

private String pinLabel(Integer i) {
  if (i == 9) {
    return "RX"
  } else {
    return "D$i"
  }
}

private Map actuatorsMap() {
  return [
    "Konnected Siren/Strobe"      : "Siren/Strobe",
    "Konnected Switch"            : "Switch",
    "Konnected Momentary Switch"  : "Momentary Switch",
    "Konnected Beep/Blink"        : "Beep/Blink Switch"
  ]
}

private Map sensorsMap() {
  return [
    "Konnected Contact Sensor" : "Open/Close Sensor",
    "Konnected Motion Sensor"  : "Motion Sensor",
    "Konnected Smoke Sensor"   : "Smoke Detector",
    "Konnected Panic Button"   : "Panic Button"
  ]
}

private Integer convertHexToInt(hex) { Integer.parseInt(hex,16) }
private String convertHexToIP(hex) { [convertHexToInt(hex[0..1]),convertHexToInt(hex[2..3]),convertHexToInt(hex[4..5]),convertHexToInt(hex[6..7])].join(".") }