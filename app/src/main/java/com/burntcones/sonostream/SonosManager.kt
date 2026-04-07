package com.burntcones.sonostream

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern
import javax.xml.parsers.DocumentBuilderFactory

data class SonosSpeaker(
    val name: String,
    val model: String,
    val ip: String,
    val port: Int,
    val controlUrl: String,
    val renderingUrl: String,
    val location: String,
    val uuid: String = "",
    val isCoordinator: Boolean = true,
    val groupMembers: List<String> = emptyList(),
    val groupId: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("model", model)
        put("ip", ip)
        put("port", port)
        put("control_url", controlUrl)
        put("rendering_url", renderingUrl)
        put("location", location)
        put("uuid", uuid)
        put("is_coordinator", isCoordinator)
        put("group_members", org.json.JSONArray(groupMembers))
        put("group_id", groupId)
    }
}

object SonosManager {

    private const val SSDP_ADDR = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val AVT = "urn:schemas-upnp-org:service:AVTransport:1"
    private const val RC = "urn:schemas-upnp-org:service:RenderingControl:1"

    var speakers: MutableMap<String, SonosSpeaker> = mutableMapOf()
        private set

    private const val TAG = "SonosManager"

    /**
     * Resolve the WiFi local address using ConnectivityManager (modern) with
     * WifiManager fallback (legacy). Returns null if WiFi is not connected.
     */
    private fun getWifiAddress(context: Context): InetAddress? {
        // Modern approach: ConnectivityManager (API 23+)
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = cm.activeNetwork
            if (activeNetwork != null) {
                val caps = cm.getNetworkCapabilities(activeNetwork)
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    val lp = cm.getLinkProperties(activeNetwork)
                    lp?.linkAddresses?.forEach { la ->
                        val addr = la.address
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            Log.d(TAG, "WiFi address (ConnectivityManager): ${addr.hostAddress}")
                            return addr
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "ConnectivityManager lookup failed: ${e.message}")
        }

        // Fallback: WifiManager
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val ip = wifi.connectionInfo.ipAddress
            if (ip != 0) {
                val ipBytes = byteArrayOf(
                    (ip and 0xff).toByte(),
                    (ip shr 8 and 0xff).toByte(),
                    (ip shr 16 and 0xff).toByte(),
                    (ip shr 24 and 0xff).toByte()
                )
                val addr = InetAddress.getByAddress(ipBytes)
                Log.d(TAG, "WiFi address (WifiManager fallback): ${addr.hostAddress}")
                return addr
            }
        } catch (e: Exception) {
            Log.w(TAG, "WifiManager lookup failed: ${e.message}")
        }

        return null
    }

    fun discover(context: Context? = null, timeoutMs: Int = 4000): Map<String, SonosSpeaker> {
        val found = mutableMapOf<String, SonosSpeaker>()
        val msg = "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: $SSDP_ADDR:$SSDP_PORT\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 3\r\n" +
            "ST: urn:schemas-upnp-org:device:ZonePlayer:1\r\n" +
            "\r\n"

        try {
            // Resolve WiFi address and interface
            val wifiAddr = if (context != null) getWifiAddress(context) else null
            val localAddr = wifiAddr ?: InetAddress.getByName("0.0.0.0")
            Log.d(TAG, "Discovery binding to: ${localAddr.hostAddress}")

            // ── Two-socket pattern (ConnectSDK proven approach) ──
            // DatagramSocket on ephemeral port bound to WiFi IP: sends M-SEARCH,
            // receives unicast responses. SSDP responses to M-SEARCH are unicast
            // back to the sender, so we do NOT need to join the multicast group.
            val sock = DatagramSocket(null).apply {
                reuseAddress = true
                soTimeout = timeoutMs
                bind(InetSocketAddress(localAddr, 0))  // ephemeral port on WiFi interface
            }

            Log.d(TAG, "Bound to ${sock.localAddress.hostAddress}:${sock.localPort}")

            val group = InetAddress.getByName(SSDP_ADDR)
            val sendData = msg.toByteArray()
            val packet = DatagramPacket(sendData, sendData.size, group, SSDP_PORT)

            // Send M-SEARCH 3 times — UDP is unreliable
            for (i in 1..3) {
                sock.send(packet)
                Log.d(TAG, "Sent M-SEARCH #$i (${sendData.size} bytes)")
                if (i < 3) Thread.sleep(200)
            }

            val deadline = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(4096)

            while (System.currentTimeMillis() < deadline) {
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    sock.receive(pkt)
                    val response = String(pkt.data, 0, pkt.length)
                    Log.d(TAG, "SSDP response from ${pkt.address?.hostAddress}:${pkt.port}")

                    val locMatch = Pattern.compile("LOCATION:\\s*(.*?)\\r?\\n", Pattern.CASE_INSENSITIVE).matcher(response)
                    if (locMatch.find()) {
                        val location = locMatch.group(1)!!.trim()
                        if (location !in found.values.map { it.location }) {
                            Log.d(TAG, "Fetching device info: $location")
                            fetchDeviceInfo(location)?.let {
                                Log.d(TAG, "Found: ${it.name} (${it.model}) @ ${it.ip}")
                                found[it.name] = it
                            }
                        }
                    }
                } catch (_: SocketTimeoutException) {
                    break
                }
            }
            sock.close()
        } catch (e: Exception) {
            Log.e(TAG, "SSDP discovery failed", e)
        }

        Log.d(TAG, "SSDP found ${found.size} speaker(s)")

        // Fallback: if SSDP found nothing, scan the local subnet for Sonos port 1400
        if (found.isEmpty() && context != null) {
            Log.d(TAG, "SSDP found nothing — falling back to subnet scan")
            val subnetResults = scanSubnetForSonos(context)
            found.putAll(subnetResults)
            Log.d(TAG, "Subnet scan found ${subnetResults.size} speaker(s)")
        }

        // Resolve groups: query ZoneGroupTopology from any speaker
        val grouped = resolveGroups(found)
        speakers = grouped
        return grouped
    }

    /**
     * Scan the local /24 subnet for Sonos speakers by probing port 1400.
     * Every Sonos speaker exposes its device description XML at
     * http://{ip}:1400/xml/device_description.xml
     */
    private fun scanSubnetForSonos(context: Context): Map<String, SonosSpeaker> {
        val wifiAddr = getWifiAddress(context) ?: return emptyMap()
        val ipParts = wifiAddr.hostAddress?.split(".") ?: return emptyMap()
        if (ipParts.size != 4) return emptyMap()
        val subnet = "${ipParts[0]}.${ipParts[1]}.${ipParts[2]}"
        Log.d(TAG, "Scanning subnet $subnet.0/24 on port 1400")

        val found = ConcurrentHashMap<String, SonosSpeaker>()
        val executor = Executors.newFixedThreadPool(24)

        for (i in 1..254) {
            executor.submit {
                val ip = "$subnet.$i"
                try {
                    val socket = Socket()
                    socket.connect(InetSocketAddress(ip, 1400), 300)
                    socket.close()
                    // Port 1400 is open — fetch device description
                    Log.d(TAG, "Port 1400 open on $ip — checking for Sonos")
                    val speaker = fetchDeviceInfo("http://$ip:1400/xml/device_description.xml")
                    if (speaker != null) {
                        Log.d(TAG, "Subnet scan found: ${speaker.name} @ $ip")
                        found[speaker.name] = speaker
                    }
                } catch (_: Exception) {
                    // Connection refused or timeout — not a Sonos speaker
                }
            }
        }

        executor.shutdown()
        executor.awaitTermination(8, TimeUnit.SECONDS)
        return found
    }

    private fun resolveGroups(discovered: Map<String, SonosSpeaker>): MutableMap<String, SonosSpeaker> {
        if (discovered.isEmpty()) return mutableMapOf()

        // Query ZoneGroupTopology from the first available speaker
        val anySpeaker = discovered.values.first()
        val zgtUrl = "/ZoneGroupTopology/Control"
        val (status, data) = soap(anySpeaker, zgtUrl,
            "urn:schemas-upnp-org:service:ZoneGroupTopology:1",
            "GetZoneGroupState", "")

        if (status != 200 || data.isEmpty()) {
            // Fallback: return all speakers as individual coordinators
            return discovered.toMutableMap()
        }

        // Parse group state XML embedded in SOAP response
        val result = mutableMapOf<String, SonosSpeaker>()
        try {
            // Extract ZoneGroupState content (it's XML-escaped inside SOAP)
            val stateMatch = Pattern.compile("<ZoneGroupState>(.*?)</ZoneGroupState>", Pattern.DOTALL).matcher(data)
            if (!stateMatch.find()) return discovered.toMutableMap()
            val stateXml = unescapeXml(stateMatch.group(1)!!)

            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(stateXml.byteInputStream())
            val groups = doc.getElementsByTagName("ZoneGroup")

            for (g in 0 until groups.length) {
                val group = groups.item(g)
                val coordinatorUuid = group.attributes?.getNamedItem("Coordinator")?.nodeValue ?: continue
                val groupId = group.attributes?.getNamedItem("ID")?.nodeValue ?: ""

                val members = group.childNodes
                val memberNames = mutableListOf<String>()
                var coordinatorSpeaker: SonosSpeaker? = null

                for (m in 0 until members.length) {
                    val member = members.item(m)
                    if (member.nodeName != "ZoneGroupMember") continue
                    val memberUuid = member.attributes?.getNamedItem("UUID")?.nodeValue ?: continue
                    val memberName = member.attributes?.getNamedItem("ZoneName")?.nodeValue ?: continue

                    // Find matching discovered speaker
                    val matchedSpeaker = discovered.values.find { it.uuid == memberUuid }

                    if (memberUuid == coordinatorUuid && matchedSpeaker != null) {
                        coordinatorSpeaker = matchedSpeaker
                    }
                    memberNames.add(memberName)
                }

                if (coordinatorSpeaker != null) {
                    // Build display name: "Living Room" or "Living Room + Kitchen"
                    val otherMembers = memberNames.filter { it != coordinatorSpeaker.name }
                    val displayName = if (otherMembers.isNotEmpty()) {
                        "${coordinatorSpeaker.name} + ${otherMembers.joinToString(", ")}"
                    } else {
                        coordinatorSpeaker.name
                    }

                    result[displayName] = coordinatorSpeaker.copy(
                        name = displayName,
                        isCoordinator = true,
                        groupMembers = memberNames,
                        groupId = groupId
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return discovered.toMutableMap()
        }

        // Add any discovered speakers not represented in any group
        discovered.values.forEach { sp ->
            val alreadyRepresented = result.values.any { it.uuid == sp.uuid || it.groupMembers.contains(sp.name) }
            if (!alreadyRepresented) result[sp.name] = sp
        }

        return if (result.isNotEmpty()) result else discovered.toMutableMap()
    }

    private fun fetchDeviceInfo(location: String): SonosSpeaker? {
        return try {
            val url = URL(location)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            val xml = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(xml.byteInputStream())

            val devices = doc.getElementsByTagName("device")
            if (devices.length == 0) return null
            val device = devices.item(0)

            var name = ""
            var model = ""
            var controlUrl = ""
            var renderingUrl = "/MediaRenderer/RenderingControl/Control"
            var uuid = ""

            for (i in 0 until device.childNodes.length) {
                val child = device.childNodes.item(i)
                when (child.nodeName) {
                    "friendlyName" -> name = child.textContent ?: ""
                    "modelName" -> model = child.textContent ?: ""
                    "UDN" -> {
                        val udn = child.textContent ?: ""
                        uuid = udn.removePrefix("uuid:")
                    }
                    "serviceList" -> {
                        for (j in 0 until child.childNodes.length) {
                            val svc = child.childNodes.item(j)
                            if (svc.nodeName != "service") continue
                            var svcType = ""
                            var svcCtrl = ""
                            for (k in 0 until svc.childNodes.length) {
                                val s = svc.childNodes.item(k)
                                when (s.nodeName) {
                                    "serviceType" -> svcType = s.textContent ?: ""
                                    "controlURL" -> svcCtrl = s.textContent ?: ""
                                }
                            }
                            if ("AVTransport" in svcType) controlUrl = svcCtrl
                            if ("RenderingControl" in svcType) renderingUrl = svcCtrl
                        }
                    }
                }
            }

            if (controlUrl.isEmpty()) return null

            SonosSpeaker(
                name = name,
                model = model,
                ip = url.host,
                port = url.port.let { if (it == -1) 80 else it },
                controlUrl = controlUrl,
                renderingUrl = renderingUrl,
                location = location,
                uuid = uuid
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // ── UPnP SOAP ───────────────────────────────────────────────────────

    private fun soap(speaker: SonosSpeaker, serviceUrl: String, serviceType: String, action: String, argsXml: String = ""): Pair<Int, String> {
        return try {
            val body = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body><u:$action xmlns:u="$serviceType">$argsXml</u:$action></s:Body>
</s:Envelope>"""

            val url = URL("http://${speaker.ip}:${speaker.port}$serviceUrl")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("Content-Type", """text/xml; charset="utf-8"""")
            conn.setRequestProperty("SOAPACTION", """"$serviceType#$action"""")
            conn.doOutput = true
            conn.outputStream.write(body.toByteArray())

            val status = conn.responseCode
            val data = try {
                conn.inputStream.bufferedReader().readText()
            } catch (_: Exception) {
                conn.errorStream?.bufferedReader()?.readText() ?: ""
            }
            conn.disconnect()
            status to data
        } catch (e: Exception) {
            500 to e.message.orEmpty()
        }
    }

    fun playUri(speaker: SonosSpeaker, uri: String, title: String): Boolean {
        val escUri = escapeXml(uri)
        val escTitle = escapeXml(title)
        val metadata = "&lt;DIDL-Lite xmlns:dc=&quot;http://purl.org/dc/elements/1.1/&quot; xmlns:upnp=&quot;urn:schemas-upnp-org:metadata-1-0/upnp/&quot; xmlns=&quot;urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/&quot;&gt;&lt;item id=&quot;1&quot; parentID=&quot;0&quot; restricted=&quot;1&quot;&gt;&lt;dc:title&gt;$escTitle&lt;/dc:title&gt;&lt;upnp:class&gt;object.item.audioItem.musicTrack&lt;/upnp:class&gt;&lt;res&gt;$escUri&lt;/res&gt;&lt;/item&gt;&lt;/DIDL-Lite&gt;"

        val args = "<InstanceID>0</InstanceID><CurrentURI>$escUri</CurrentURI><CurrentURIMetaData>$metadata</CurrentURIMetaData>"
        val (status, _) = soap(speaker, speaker.controlUrl, AVT, "SetAVTransportURI", args)
        if (status == 200) {
            soap(speaker, speaker.controlUrl, AVT, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
        }
        return status == 200
    }

    fun transportAction(speaker: SonosSpeaker, action: String): Boolean {
        var args = "<InstanceID>0</InstanceID>"
        if (action == "Play") args += "<Speed>1</Speed>"
        val (status, _) = soap(speaker, speaker.controlUrl, AVT, action, args)
        return status == 200
    }

    fun setVolume(speaker: SonosSpeaker, volume: Int): Boolean {
        val vol = volume.coerceIn(0, 100)
        val args = "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>$vol</DesiredVolume>"
        val (status, _) = soap(speaker, speaker.renderingUrl, RC, "SetVolume", args)
        return status == 200
    }

    fun getVolume(speaker: SonosSpeaker): Int {
        val args = "<InstanceID>0</InstanceID><Channel>Master</Channel>"
        val (status, data) = soap(speaker, speaker.renderingUrl, RC, "GetVolume", args)
        if (status == 200) {
            val m = Pattern.compile("<CurrentVolume>(\\d+)</CurrentVolume>").matcher(data)
            if (m.find()) return m.group(1)!!.toInt()
        }
        return 50
    }

    fun getTransportInfo(speaker: SonosSpeaker): String {
        val (status, data) = soap(speaker, speaker.controlUrl, AVT, "GetTransportInfo", "<InstanceID>0</InstanceID>")
        if (status == 200) {
            val m = Pattern.compile("<CurrentTransportState>(.*?)</CurrentTransportState>").matcher(data)
            if (m.find()) return m.group(1)!!
        }
        return "UNKNOWN"
    }

    fun getPositionInfo(speaker: SonosSpeaker): JSONObject {
        val (status, data) = soap(speaker, speaker.controlUrl, AVT, "GetPositionInfo", "<InstanceID>0</InstanceID>")
        val info = JSONObject().apply {
            put("track", "")
            put("duration", "00:00:00")
            put("position", "00:00:00")
            put("uri", "")
        }
        if (status == 200) {
            for ((tag, key) in listOf("TrackDuration" to "duration", "RelTime" to "position", "TrackURI" to "uri")) {
                val m = Pattern.compile("<$tag>(.*?)</$tag>").matcher(data)
                if (m.find()) info.put(key, m.group(1))
            }
            val metaMatch = Pattern.compile("<TrackMetaData>(.*?)</TrackMetaData>", Pattern.DOTALL).matcher(data)
            if (metaMatch.find()) {
                val meta = unescapeXml(metaMatch.group(1)!!)
                val titleMatch = Pattern.compile("<dc:title>(.*?)</dc:title>").matcher(meta)
                if (titleMatch.find()) info.put("track", titleMatch.group(1))
            }
        }
        return info
    }

    fun seek(speaker: SonosSpeaker, position: String): Boolean {
        val args = "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>$position</Target>"
        val (status, _) = soap(speaker, speaker.controlUrl, AVT, "Seek", args)
        return status == 200
    }

    private fun escapeXml(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    private fun unescapeXml(s: String): String = s
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'")
}
