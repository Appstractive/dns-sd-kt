package com.appstractive.dnssd

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build.VERSION
import android.os.Build.VERSION_CODES
import java.util.concurrent.Semaphore
import kotlin.concurrent.thread
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

actual fun discoverServices(type: String): Flow<DiscoveryEvent> = callbackFlow {
  val multicastLock: WifiManager.MulticastLock by lazy {
    if (multicastPermissionGranted()) {
      wifiManager.createMulticastLock("nsdMulticastLock").also { it.setReferenceCounted(true) }
    } else {
      throw RuntimeException("Missing required permission CHANGE_WIFI_MULTICAST_STATE")
    }
  }
  val resolveSemaphore = Semaphore(1)

  multicastLock.acquire()

  fun resolveService(serviceInfo: NsdServiceInfo) {
    thread {
      resolveSemaphore.acquire()
      nsdManager.resolveService(
          serviceInfo,
          object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
              println("onResolveFailed: $errorCode")
              resolveSemaphore.release()
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
              resolveSemaphore.release()

              val service: DiscoveredService =
                  when {
                    VERSION.SDK_INT >= VERSION_CODES.M -> serviceInfo.toCommon()
                    else -> {
                      val resolvedData =
                          MDNSDiscover.resolve(
                              "${serviceInfo.serviceName}${serviceInfo.serviceType}".localQualified,
                              5000,
                          )

                      val txtRecords = resolvedData?.txt?.dict?.toByteMap()

                      serviceInfo.toCommon(txtRecords)
                    }
                  }

              trySend(
                  DiscoveryEvent.Resolved(
                      service = service,
                  ) {
                    resolveService(serviceInfo)
                  },
              )
            }
          },
      )
    }
  }

  val listener: NsdManager.DiscoveryListener =
      object : NsdManager.DiscoveryListener {
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
          println("onStartDiscoveryFailed($serviceType, $errorCode)")
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
          println("onStopDiscoveryFailed($serviceType, $errorCode)")
        }

        override fun onDiscoveryStarted(serviceType: String) {}

        override fun onDiscoveryStopped(serviceType: String) {}

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
          trySend(
              DiscoveryEvent.Discovered(
                  service = serviceInfo.toCommon(),
              ) {
                resolveService(serviceInfo)
              },
          )
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
          trySend(
              DiscoveryEvent.Removed(
                  service = serviceInfo.toCommon(),
              ),
          )
        }
      }

  nsdManager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)

  awaitClose {
    multicastLock.release()
    nsdManager.stopServiceDiscovery(listener)
  }
}

internal fun NsdServiceInfo.toCommon(txt: Map<String, ByteArray?>? = null): DiscoveredService =
    DiscoveredService(
        name = serviceName,
        addresses = getAddresses(),
        host = getHostName() ?: "",
        type = serviceType,
        port = port,
        txt = txt ?: attributes ?: emptyMap(),
    )

private fun NsdServiceInfo.getAddresses(): List<String> {
  if (VERSION.SDK_INT >= VERSION_CODES.UPSIDE_DOWN_CAKE) {
    return hostAddresses.mapNotNull { it.hostAddress }
  }

  return host?.hostAddress?.let { listOf(it) } ?: emptyList()
}

private fun NsdServiceInfo.getHostName(): String? {
  if (VERSION.SDK_INT >= VERSION_CODES.UPSIDE_DOWN_CAKE) {
    return hostAddresses.firstOrNull()?.canonicalHostName
  }

  return host?.canonicalHostName
}

fun Map<String?, String?>.toByteMap(): Map<String, ByteArray?> =
    filter { (key, _) -> key != null }.mapKeys { it.key!! }.mapValues { it.value?.toByteArray() }
