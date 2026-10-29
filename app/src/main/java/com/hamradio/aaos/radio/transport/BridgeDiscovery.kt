package com.hamradio.aaos.radio.transport

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

private const val TAG = "BridgeDiscovery"
private const val PROBE_TIMEOUT_MS = 400
private const val PROBE_PARALLELISM = 64

/** Widest local network scanned; the truck hotspot hands out a /24. */
private const val MIN_PREFIX = 22

/**
 * Finds the ESP32 bridge on the head unit's own networks (the truck hotspot hands it a DHCP
 * address nobody can see from the car) by trying the control port on every neighbour.
 */
object BridgeDiscovery {

    /** Every other host address on [address]'s network, or nothing for networks wider than /22. */
    fun candidateHosts(address: ByteArray, prefixLength: Int): List<String> {
        if (address.size != 4 || prefixLength !in MIN_PREFIX..30) return emptyList()
        val ip = address.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
        val mask = (0xFFFFFFFFL shl (32 - prefixLength)) and 0xFFFFFFFFL
        val network = ip and mask
        val broadcast = network or (mask.inv() and 0xFFFFFFFFL)
        return ((network + 1) until broadcast)
            .filter { it != ip }
            .map { "${(it shr 24) and 0xFF}.${(it shr 16) and 0xFF}.${(it shr 8) and 0xFF}.${it and 0xFF}" }
    }

    /** Hosts on every up, private IPv4 network this device is attached to. */
    fun localCandidates(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.interfaceAddresses }
            .filter { (it.address as? Inet4Address)?.isSiteLocalAddress == true }
            .flatMap { candidateHosts(it.address.address, it.networkPrefixLength.toInt()) }
            .distinct()
    } catch (e: IOException) {
        Log.e(TAG, "Listing networks failed: ${e.message}")
        emptyList()
    }

    /** First of [hosts] (in list order) accepting a TCP connection on [port], or null. */
    suspend fun find(hosts: List<String>, port: Int): String? = withContext(Dispatchers.IO) {
        val gate = Semaphore(PROBE_PARALLELISM)
        val open = coroutineScope {
            hosts.map { host -> async { gate.withPermit { probe(host, port) } } }.awaitAll()
        }
        hosts.zip(open).firstOrNull { it.second }?.first
            .also { Log.i(TAG, "Scanned ${hosts.size} hosts for port $port: ${it ?: "no bridge"}") }
    }

    private fun probe(host: String, port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), PROBE_TIMEOUT_MS) }
        true
    } catch (_: IOException) {
        false
    }
}
