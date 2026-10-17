package dev.boottv.jellyfin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/** Finds Jellyfin servers on the LAN with the standard UDP "who is JellyfinServer?" broadcast on port 7359. */
object ServerDiscovery {
    private const val PORT = 7359
    private const val MESSAGE = "who is JellyfinServer?"

    suspend fun discover(timeoutMs: Long = 3000): List<DiscoveredServer> = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<String, DiscoveredServer>()
        runCatching {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 500
                val payload = MESSAGE.toByteArray()
                for (addr in broadcastAddresses()) {
                    runCatching { socket.send(DatagramPacket(payload, payload.size, addr, PORT)) }
                }
                val deadline = System.currentTimeMillis() + timeoutMs
                val buf = ByteArray(4096)
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    val server = runCatching { JfJson.decodeFromString<DiscoveredServer>(text) }.getOrNull() ?: continue
                    if (server.address.isBlank()) continue
                    val key = server.id ?: server.address
                    found[key] = server.copy(address = server.address.trimEnd('/'))
                }
            }
        }
        found.values.toList()
    }

    private fun broadcastAddresses(): List<InetAddress> {
        val list = mutableListOf<InetAddress>()
        runCatching {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (ia in nif.interfaceAddresses) ia.broadcast?.let { list += it }
            }
        }
        runCatching { list += InetAddress.getByName("255.255.255.255") }
        return list.distinct()
    }
}
