package com.appstractive.dnssd

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener

actual fun discoverServices(type: String): Flow<DiscoveryEvent> = callbackFlow {
  val jmDns = withContext(Dispatchers.IO) { JmDNS.create() }

  val listener: ServiceListener =
      object : ServiceListener {
        override fun serviceAdded(event: ServiceEvent) {
          trySend(
              DiscoveryEvent.Discovered(
                  service = event.toCommon(),
              ) {
                jmDns.requestServiceInfo(event.type, event.name)
              },
          )
        }

        override fun serviceRemoved(event: ServiceEvent) {
          trySend(
              DiscoveryEvent.Removed(
                  service = event.toCommon(),
              ),
          )
        }

        override fun serviceResolved(event: ServiceEvent) {
          trySend(
              DiscoveryEvent.Resolved(
                  service = event.toCommon(),
              ) {
                jmDns.requestServiceInfo(event.type, event.name)
              },
          )
        }
      }

  jmDns.addServiceListener(type.localQualified, listener)

  awaitClose {
    jmDns.removeServiceListener(type.localQualified, listener)
    jmDns.close()
  }
}

private fun ServiceEvent.toCommon(): DiscoveredService =
    DiscoveredService(
        name = name,
        addresses = info?.hostAddresses?.toList() ?: emptyList(),
        host =
            info?.inetAddresses?.firstOrNull { it.canonicalHostName != null }?.canonicalHostName
                ?: "",
        type = type,
        port = info?.port ?: 0,
        txt =
            buildMap {
              info?.propertyNames?.iterator()?.forEach { key ->
                val value = info.getPropertyString(key)
                put(key.toString(), value?.encodeToByteArray())
              }
            },
    )
