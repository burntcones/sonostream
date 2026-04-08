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

    /** WiFi Network object, used to route SOAP calls over WiFi when cellular is default */
    private var wifiNet: android.net.Network? = null

    private const val TAG = "SonosManager"

    /** Diagnostic info from the last discovery attempt */
    var lastDiagnostics: String = "No scan attempted yet"
        private set

    /**
     * Find the WiFi Network and IPv4 address. Does NOT require WiFi to be
     * the "active" (default) network — scans ALL networks for TRANSPORT_WIFI.
     * This is critical for cafe/IoT WiFi networks that have no internet,
     * where Android makes cellular the default network.
     */
    data class WifiInfo(val address: InetAddress, val network: android.net.Network?)

    private fun getWifiInfo(context: Context): WifiInfo? {
        val diag = StringBuilder()

        // Approach 1: ConnectivityManager — scan ALL networks, not just active
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            for (network in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    val lp = cm.getLinkProperties(network)
                    lp?.linkAddresses?.forEach { la ->
                        val addr = la.address
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            diag.append("WiFi found via ConnectivityManager: ${addr.hostAddress}\n")
                            Log.d(TAG, "WiFi via CM (allNetworks): ${addr.hostAddress}")
                            lastDiagnostics = diag.toString()
                            return WifiInfo(addr, network)
                        }
                    }
                }
            }
            diag.append("ConnectivityManager: no WiFi network with IPv4 found\n")
        } catch (e: Exception) {
            diag.append("ConnectivityManager failed: ${e.message}\n")
            Log.w(TAG, "ConnectivityManager lookup failed: ${e.message}")
        }

        // Approach 2: WifiManager (works even when WiFi isn't default)
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
                diag.append("WiFi found via WifiManager: ${addr.hostAddress}\n")
                Log.d(TAG, "WiFi via WifiManager: ${addr.hostAddress}")
                lastDiagnostics = diag.toString()
                return WifiInfo(addr, null)
            } else {
                diag.append("WifiManager: ipAddress is 0 (not connected?)\n")
            }
        } catch (e: Exception) {
            diag.append("WifiManager failed: ${e.message}\n")
            Log.w(TAG, "WifiManager lookup failed: ${e.message}")
        }

        // Approach 3: Enumerate all NetworkInterfaces, find private IPv4
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                        diag.append("WiFi found via NetworkInterface(${iface.name}): ${addr.hostAddress}\n")
                        Log.d(TAG, "WiFi via NetworkInterface(${iface.name}): ${addr.hostAddress}")
                        lastDiagnostics = diag.toString()
                        return WifiInfo(addr, null)
                    }
                }
            }
            diag.append("NetworkInterface: no private IPv4 found on any interface\n")
        } catch (e: Exception) {
            diag.append("NetworkInterface enumeration failed: ${e.message}\n")
        }

        diag.append("FAILED: Could not determine WiFi address by any method\n")
        lastDiagnostics = diag.toString()
        Log.e(TAG, "Could not determine WiFi address:\n$diag")
        return null
    }

    fun discover(context: Context? = null, timeoutMs: Int = 4000): Map<String, SonosSpeaker> {
        val found = mutableMapOf<String, SonosSpeaker>()
        val diag = StringBuilder()

        val wifiInfo = if (context != null) getWifiInfo(context) else null
        diag.append(lastDiagnostics)

        if (wifiInfo == null) {
            diag.append("ABORT: No WiFi address detected\n")
            lastDiagnostics = diag.toString()
            speakers = mutableMapOf()
            return emptyMap()
        }

        val localAddr = wifiInfo.address
        val wifiNetwork = wifiInfo.network
        wifiNet = wifiNetwork  // Store for SOAP calls
        diag.append("Using WiFi IP: ${localAddr.hostAddress}\n")

        // If we have a Network object, bind the process to it so ALL sockets
        // (discovery + subsequent SOAP calls) go over WiFi
        val cm = context?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (wifiNetwork != null && cm != null) {
            cm.bindProcessToNetwork(wifiNetwork)
            diag.append("Bound process to WiFi network\n")
            Log.d(TAG, "Bound process to WiFi network")
        }

        // ── SSDP Discovery ──
        val msg = "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: $SSDP_ADDR:$SSDP_PORT\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 3\r\n" +
            "ST: urn:schemas-upnp-org:device:ZonePlayer:1\r\n" +
            "\r\n"

        try {
            val sock = DatagramSocket(null).apply {
                reuseAddress = true
                soTimeout = timeoutMs
                bind(InetSocketAddress(localAddr, 0))
            }

            diag.append("SSDP socket bound to ${sock.localAddress.hostAddress}:${sock.localPort}\n")
            Log.d(TAG, "SSDP bound to ${sock.localAddress.hostAddress}:${sock.localPort}")

            val group = InetAddress.getByName(SSDP_ADDR)
            val sendData = msg.toByteArray()
            val packet = DatagramPacket(sendData, sendData.size, group, SSDP_PORT)

            for (i in 1..3) {
                sock.send(packet)
                if (i < 3) Thread.sleep(200)
            }
            diag.append("Sent 3 M-SEARCH packets\n")
            Log.d(TAG, "Sent 3 M-SEARCH packets")

            val deadline = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(4096)
            var responseCount = 0

            while (System.currentTimeMillis() < deadline) {
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    sock.receive(pkt)
                    responseCount++
                    val response = String(pkt.data, 0, pkt.length)
                    Log.d(TAG, "SSDP response #$responseCount from ${pkt.address?.hostAddress}")

                    val locMatch = Pattern.compile("LOCATION:\\s*(.*?)\\r?\\n", Pattern.CASE_INSENSITIVE).matcher(response)
                    if (locMatch.find()) {
                        val location = locMatch.group(1)!!.trim()
                        if (location !in found.values.map { it.location }) {
                            fetchDeviceInfo(location)?.let {
                                Log.d(TAG, "SSDP found: ${it.name} @ ${it.ip}")
                                found[it.name] = it
                            }
                        }
                    }
                } catch (_: SocketTimeoutException) {
                    break
                }
            }
            sock.close()
            diag.append("SSDP: $responseCount responses, ${found.size} speakers\n")
        } catch (e: Exception) {
            diag.append("SSDP failed: ${e.message}\n")
            Log.e(TAG, "SSDP failed", e)
        }

        // ── Subnet scan fallback ──
        if (found.isEmpty()) {
            diag.append("SSDP found nothing — scanning subnet\n")
            Log.d(TAG, "Falling back to subnet scan")

            val ipParts = localAddr.hostAddress?.split(".") ?: emptyList()
            if (ipParts.size == 4) {
                val subnet = "${ipParts[0]}.${ipParts[1]}.${ipParts[2]}"
                diag.append("Scanning $subnet.1-254 on port 1400\n")

                val subnetFound = ConcurrentHashMap<String, SonosSpeaker>()
                val openPorts = ConcurrentHashMap<String, Boolean>()
                val executor = Executors.newFixedThreadPool(30)

                for (i in 1..254) {
                    executor.submit {
                        val ip = "$subnet.$i"
                        try {
                            val socket = Socket()
                            if (wifiNetwork != null) {
                                wifiNetwork.bindSocket(socket)
                            }
                            socket.connect(InetSocketAddress(ip, 1400), 400)
                            socket.close()
                            openPorts[ip] = true
                            Log.d(TAG, "Port 1400 open: $ip")
                            val speaker = fetchDeviceInfo("http://$ip:1400/xml/device_description.xml")
                            if (speaker != null) {
                                Log.d(TAG, "Subnet found: ${speaker.name} @ $ip")
                                subnetFound[speaker.name] = speaker
                            }
                        } catch (_: Exception) {}
                    }
                }

                executor.shutdown()
                executor.awaitTermination(10, TimeUnit.SECONDS)
                found.putAll(subnetFound)
                diag.append("Subnet scan: ${openPorts.size} hosts with port 1400 open, ${subnetFound.size} Sonos speakers\n")
            } else {
                diag.append("Could not parse subnet from IP: ${localAddr.hostAddress}\n")
            }
        }

        diag.append("Total found: ${found.size} speaker(s)\n")
        lastDiagnostics = diag.toString()
        Log.d(TAG, "Discovery complete:\n$diag")

        // Resolve groups
        val grouped = resolveGroups(found)
        speakers = grouped
        return grouped
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

            // Get name, model, UUID from the root device (first <device>)
            val devices = doc.getElementsByTagName("device")
            if (devices.length == 0) return null
            val rootDevice = devices.item(0)

            var friendlyName = ""
            var roomName = ""
            var model = ""
            var uuid = ""

            for (i in 0 until rootDevice.childNodes.length) {
                val child = rootDevice.childNodes.item(i)
                when (child.nodeName) {
                    "friendlyName" -> friendlyName = child.textContent ?: ""
                    "roomName" -> roomName = child.textContent ?: ""
                    "modelName" -> model = child.textContent ?: ""
                    "UDN" -> uuid = (child.textContent ?: "").removePrefix("uuid:")
                }
            }
            // Prefer roomName (user-set name like "Living Room") over
            // friendlyName (which contains IP + model + RINCON UUID)
            val name = roomName.ifEmpty { friendlyName }

            // Search ALL <service> elements across ALL nested devices for
            // AVTransport and RenderingControl. Sonos puts these in a
            // sub-device (MediaRenderer) under deviceList, not the root device.
            var controlUrl = ""
            var renderingUrl = "/MediaRenderer/RenderingControl/Control"

            val services = doc.getElementsByTagName("service")
            for (i in 0 until services.length) {
                val svc = services.item(i)
                var svcType = ""
                var svcCtrl = ""
                for (j in 0 until svc.childNodes.length) {
                    val s = svc.childNodes.item(j)
                    when (s.nodeName) {
                        "serviceType" -> svcType = s.textContent ?: ""
                        "controlURL" -> svcCtrl = s.textContent ?: ""
                    }
                }
                if ("AVTransport" in svcType) controlUrl = svcCtrl
                if ("RenderingControl" in svcType) renderingUrl = svcCtrl
            }

            Log.d(TAG, "Parsed device: name=$name model=$model uuid=${uuid.take(12)} controlUrl=$controlUrl")

            if (controlUrl.isEmpty()) {
                Log.w(TAG, "No AVTransport service found in device XML at $location")
                return null
            }

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
            Log.e(TAG, "fetchDeviceInfo failed for $location: ${e.message}")
            null
        }
    }

    // ── UPnP SOAP ───────────────────────────────────────────────────────

    private fun soap(speaker: SonosSpeaker, serviceUrl: String, serviceType: String, action: String, argsXml: String = "", timeoutMs: Int = 5000): Pair<Int, String> {
        return try {
            val body = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body><u:$action xmlns:u="$serviceType">$argsXml</u:$action></s:Body>
</s:Envelope>"""

            val url = URL("http://${speaker.ip}:${speaker.port}$serviceUrl")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
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
        Log.d(TAG, "SetVolume: ${speaker.name} → $vol, url=${speaker.renderingUrl}, ip=${speaker.ip}:${speaker.port}")
        val (status, data) = soap(speaker, speaker.renderingUrl, RC, "SetVolume", args)
        Log.d(TAG, "SetVolume result: status=$status, response=${data.take(200)}")
        return status == 200
    }

    fun getVolume(speaker: SonosSpeaker): Int {
        val args = "<InstanceID>0</InstanceID><Channel>Master</Channel>"
        val (status, data) = soap(speaker, speaker.renderingUrl, RC, "GetVolume", args, timeoutMs = 2000)
        if (status == 200) {
            val m = Pattern.compile("<CurrentVolume>(\\d+)</CurrentVolume>").matcher(data)
            if (m.find()) return m.group(1)!!.toInt()
        }
        return 50
    }

    fun getTransportInfo(speaker: SonosSpeaker): String {
        val (status, data) = soap(speaker, speaker.controlUrl, AVT, "GetTransportInfo", "<InstanceID>0</InstanceID>", timeoutMs = 2000)
        if (status == 200) {
            val m = Pattern.compile("<CurrentTransportState>(.*?)</CurrentTransportState>").matcher(data)
            if (m.find()) return m.group(1)!!
        }
        return "UNKNOWN"
    }

    fun getPositionInfo(speaker: SonosSpeaker): JSONObject {
        val (status, data) = soap(speaker, speaker.controlUrl, AVT, "GetPositionInfo", "<InstanceID>0</InstanceID>", timeoutMs = 2000)
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
