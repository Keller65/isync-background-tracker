package expo.modules.isyncbackgroundlocation

import android.content.Context
import android.location.Location
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject

object LocationRelay {
  private var socket: Socket? = null
  private var url: String = ""
  private var deviceId: String = ""
  private var deviceCode: String = ""
  private var deviceName: String = ""

  @Synchronized
  fun connect(context: Context, url: String, deviceId: String, deviceCode: String, deviceName: String) {
    if (url.isBlank()) return

    val sameConfig = url == this.url && deviceId == this.deviceId &&
      deviceCode == this.deviceCode && deviceName == this.deviceName
    if (sameConfig && socket?.connected() == true) return

    this.url = url
    this.deviceId = deviceId
    this.deviceCode = deviceCode
    this.deviceName = deviceName

    teardown()

    val options = IO.Options().apply {
      path = "/socket.io/"
      transports = arrayOf("websocket")
      reconnection = true
      reconnectionDelay = 1_000L
      reconnectionDelayMax = 60_000L
      timeout = 20000L
      auth = mapOf("deviceId" to deviceId, "deviceCode" to deviceCode, "deviceName" to deviceName)
    }

    try {
      socket = IO.socket(url, options).apply {
        on(Socket.EVENT_CONNECT) {
          emit("join", JSONObject().put("deviceId", deviceId))
        }
        connect()
      }
    } catch (_: Exception) {
      socket = null
    }
  }

  @Synchronized
  fun send(location: Location, clientUuid: String) {
    val s = socket ?: return
    if (!s.connected()) return
    try {
      s.emit("location", toPayloadProtobuf(location, clientUuid))
    } catch (_: Exception) {
    }
  }

  @Synchronized
  fun releaseResources() {
    teardown()
  }

  @Synchronized
  fun stop() {
    teardown()
    url = ""
    deviceId = ""
    deviceCode = ""
    deviceName = ""
  }

  private fun teardown() {
    socket?.let {
      it.off()
      it.disconnect()
      it.close()
    }
    socket = null
  }

  private fun toPayloadProtobuf(location: Location, clientUuid: String): ByteArray {
    val builder = LocationProto.LocationPoint.newBuilder()
      .setClientUuid(clientUuid)
      .setLatitude(location.latitude)
      .setLongitude(location.longitude)
      .setDeviceId(deviceId)
      .setDeviceCode(deviceCode)
      .setDeviceName(deviceName)
      .setTimestamp(location.time)

    if (location.hasAccuracy()) builder.setAccuracy(location.accuracy)
    if (location.hasAltitude()) builder.setAltitude(location.altitude)
    if (location.hasSpeed()) builder.setSpeed(location.speed)
    if (location.hasBearing()) builder.setHeading(location.bearing)

    return builder.build().toByteArray()
  }
}