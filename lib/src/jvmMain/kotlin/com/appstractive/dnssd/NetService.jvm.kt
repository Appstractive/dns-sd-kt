package com.appstractive.dnssd

import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import javax.jmdns.impl.ServiceInfoImpl
import javax.jmdns.impl.util.ByteWrangler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class JvmNetService(
    private val nativeService: ServiceInfo,
    address: InetAddress? = InetAddress.getLocalHost(),
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

  override suspend fun register(timeout: Long) {
    if (isRegistered.value) {
      return
    }

    withContext(Dispatchers.IO) {
      withTimeout(timeout) {
        jmDns.registerService(nativeService)
        isRegistered.value = (nativeService as? ServiceInfoImpl)?.dns != null
      }
    }
  }

  override suspend fun unregister() {
    if (!isRegistered.value) {
      return
    }

    withContext(Dispatchers.IO) {
      jmDns.unregisterService(nativeService)
      isRegistered.value = (nativeService as? ServiceInfoImpl)?.dns == null
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
                "$type.local.",
                name,
                port,
                weight,
                priority,
                ByteWrangler.textFromProperties(txt),
            ),
    )
