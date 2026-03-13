package com.appstractive.dnssd

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.net.NetworkInterface
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import javax.jmdns.impl.ServiceInfoImpl
import javax.jmdns.impl.util.ByteWrangler

class JvmNetService(
    private val nativeService: ServiceInfo,
    address: InetAddress? = getLocalAddresses().firstOrNull(),
    hostName: String? = null,
) : NetService {
    override val name: String
        get() = nativeService.name

    override val domain: String
        get() = nativeService.domain

    override val type: String
        get() = nativeService.type

    override val port: Int
        get() = nativeService.port

    override val isRegistered: MutableStateFlow<Boolean> = MutableStateFlow(false)

    private val jmDns: JmDNS = JmDNS.create(address, hostName)

    private val mutex = Mutex()

    override suspend fun register(timeoutInMs: Long) {
        mutex.withLock {
            if (isRegistered.value) {
                return
            }

            withContext(Dispatchers.IO) {
                withTimeout(timeoutInMs) {
                    jmDns.registerService(nativeService)
                    isRegistered.value = (nativeService as? ServiceInfoImpl)?.dns != null
                }
            }
        }
    }

    override suspend fun unregister() {
        mutex.withLock {
            if (!isRegistered.value) {
                return
            }

            withContext(Dispatchers.IO) {
                jmDns.unregisterService(nativeService)
                isRegistered.value = (nativeService as? ServiceInfoImpl)?.dns == null
            }
        }
    }
}

actual fun createNetService(
    type: String,
    name: String,
    port: Int,
    priority: Int,
    weight: Int,
    addresses: List<String>?,
    txt: Map<String, String>
): NetService =
    JvmNetService(
        nativeService =
            ServiceInfo.create(
                type.localQualified,
                name,
                port,
                weight,
                priority,
                ByteWrangler.textFromProperties(txt),
            ),
        address = (addresses?.map { InetAddress.getByName(it) } ?: getLocalAddresses())
            .firstOrNull(),
    )

private fun getLocalAddresses(): List<InetAddress> =
    buildList {
        val interfaces = NetworkInterface.getNetworkInterfaces()

        for (netInterface in interfaces) {
            addAll(
                netInterface
                    .inetAddresses
                    .toList()
                    .filter { it.isSiteLocalAddress }
                    .toList(),
            )
        }
    }
        .also { println("createNetService with addresses: $it") }