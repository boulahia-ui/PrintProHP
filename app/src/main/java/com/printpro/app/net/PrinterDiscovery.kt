package com.printpro.app.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress

data class DiscoveredPrinter(
    val name: String,
    val host: String,
    val port: Int,
    val resourcePath: String,
    val model: String,
    val isHp: Boolean,
    val manual: Boolean = false,
) {
    private val hostForUri: String
        get() = if (host.contains(':')) "[${host.replace("%", "%25")}]" else host
    val ippUri: String get() = "ipp://$hostForUri:$port/$resourcePath"
    val httpUrl: String get() = "http://$hostForUri:$port/$resourcePath"
    val id: String get() = "$host:$port/$resourcePath"
}

/** Détection des imprimantes IPP (AirPrint / IPP Everywhere / Mopria) sur le Wi-Fi via mDNS. */
class PrinterDiscovery(context: Context) {
    private val app = context.applicationContext
    private val nsd = app.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager

    fun discover(): Flow<List<DiscoveredPrinter>> = callbackFlow {
        val lock = wifi.createMulticastLock("printpro-mdns").apply { setReferenceCounted(false); acquire() }
        val sync = Any()
        val found = LinkedHashMap<String, DiscoveredPrinter>()
        val queue = ArrayDeque<NsdServiceInfo>()
        var resolving = false

        // Les anciennes versions d'Android ne résolvent qu'un service à la fois : file d'attente
        fun resolveNext() {
            val next: NsdServiceInfo = synchronized(sync) {
                if (resolving) return
                val n = queue.removeFirstOrNull() ?: return
                resolving = true
                n
            }
            @Suppress("DEPRECATION")
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    synchronized(sync) { resolving = false }
                    resolveNext()
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    val printer = toPrinter(info)
                    val list = synchronized(sync) {
                        if (printer != null) found[info.serviceName] = printer
                        resolving = false
                        found.values.toList()
                    }
                    trySend(list)
                    resolveNext()
                }
            })
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                close(IOException("Recherche Wi-Fi impossible (code $errorCode)"))
            }
            override fun onServiceFound(info: NsdServiceInfo) {
                synchronized(sync) { queue.addLast(info) }
                resolveNext()
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                val list = synchronized(sync) { found.remove(info.serviceName); found.values.toList() }
                trySend(list)
            }
        }

        trySend(emptyList())
        nsd.discoverServices("_ipp._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose {
            runCatching { nsd.stopServiceDiscovery(listener) }
            runCatching { lock.release() }
        }
    }

    private fun toPrinter(info: NsdServiceInfo): DiscoveredPrinter? {
        val addr: InetAddress = if (Build.VERSION.SDK_INT >= 34) {
            info.hostAddresses.firstOrNull { it is Inet4Address } ?: info.hostAddresses.firstOrNull()
        } else {
            @Suppress("DEPRECATION") info.host
        } ?: return null
        val host = addr.hostAddress ?: return null
        val txt = info.attributes.entries.associate { (k, v) -> k.lowercase() to (v?.toString(Charsets.UTF_8) ?: "") }
        val path = txt["rp"]?.trim('/') ?: "ipp/print"
        val model = txt["ty"] ?: txt["product"]?.trim('(', ')') ?: ""
        val mfg = txt["usb_mfg"] ?: ""
        val name = info.serviceName
        val isHp = listOf(name, model, mfg).any { it.contains("HP") || it.contains("Hewlett", ignoreCase = true) }
        return DiscoveredPrinter(name, host, info.port, path, model, isHp)
    }
}
