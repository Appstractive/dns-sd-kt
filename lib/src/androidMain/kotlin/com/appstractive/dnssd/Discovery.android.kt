package com.appstractive.dnssd

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build.VERSION
import android.os.Build.VERSION_CODES
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Semaphore
import kotlin.concurrent.thread

private const val LOG_TAG = "DNS_SD_DISCOVERY"

actual fun discoverServices(type: String): Flow<DiscoveryEvent> = callbackFlow {
  checkLocalNetworkPermission()

  val multicastLock: WifiManager.MulticastLock by lazy {
    if (multicastPermissionGranted()) {
      wifiManager.createMulticastLock("nsdMulticastLock").also { it.setReferenceCounted(true) }
    } else {
      throw RuntimeException("Missing required permission CHANGE_WIFI_MULTICAST_STATE")
    }
  }

  multicastLock.acquire()

  val resolveSemaphore = Semaphore(1)
  val serviceInfoCallbacks: MutableStateFlow<Map<String, NsdManager.ServiceInfoCallback>> =
      MutableStateFlow(mapOf())

  fun resolveService(serviceInfo: NsdServiceInfo) {
    if (VERSION.SDK_INT >= VERSION_CODES.UPSIDE_DOWN_CAKE) {
      serviceInfoCallbacks.update { callbacks ->
        buildMap {
          putAll(callbacks)
          if (!callbacks.contains(serviceInfo.key)) {
            val callback =
                serviceInfo.registerCallback {
                  trySend(
                      DiscoveryEvent.Resolved(
                          service = it.toCommon(),
                      ) {
                        /* NOOP callback will sent future resolved */
                      },
                  )
                }

            put(serviceInfo.key, callback)
          }
        }
      }
    } else {
      thread {
        resolveSemaphore.acquire()
        nsdManager.resolveService(
            serviceInfo,
            object : NsdManager.ResolveListener {
              override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(LOG_TAG, "onResolveFailed: $errorCode")
                resolveSemaphore.release()
              }

              override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                resolveSemaphore.release()
                val service: DiscoveredService = serviceInfo.toCommon()

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
  }

  val listener: NsdManager.DiscoveryListener =
      object : NsdManager.DiscoveryListener {
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
          Log.e(LOG_TAG, "onStartDiscoveryFailed($serviceType, $errorCode)")
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
          Log.e(LOG_TAG, "onStopDiscoveryFailed($serviceType, $errorCode)")
        }

        override fun onDiscoveryStarted(serviceType: String) {
          Log.d(LOG_TAG, "onDiscoveryStarted($serviceType)")
        }

        override fun onDiscoveryStopped(serviceType: String) {
          Log.d(LOG_TAG, "onDiscoveryStopped($serviceType)")
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
          Log.d(LOG_TAG, "onServiceFound($serviceInfo)")
          trySend(
              DiscoveryEvent.Discovered(
                  service = serviceInfo.toCommon(),
              ) {
                resolveService(serviceInfo)
              },
          )
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
          Log.d(LOG_TAG, "onServiceLost($serviceInfo)")
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
    if (VERSION.SDK_INT >= VERSION_CODES.UPSIDE_DOWN_CAKE) {
      serviceInfoCallbacks.value.values.forEach { nsdManager.unregisterServiceInfoCallback(it) }
    }
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

private val NsdServiceInfo.key: String
  get() = "${serviceName}${serviceType}".replace(".", "")

@RequiresApi(VERSION_CODES.UPSIDE_DOWN_CAKE)
private fun NsdServiceInfo.registerCallback(
    onUpdated: (NsdServiceInfo) -> Unit,
): NsdManager.ServiceInfoCallback {
  val listener =
      object : NsdManager.ServiceInfoCallback {
        override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
          Log.e(
              LOG_TAG,
              "onServiceInfoCallbackRegistrationFailed(${this@registerCallback}, $errorCode)",
          )
        }

        override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
          Log.d(LOG_TAG, "onServiceUpdated($serviceInfo)")
          onUpdated(serviceInfo)
        }

        override fun onServiceLost() {
          Log.d(LOG_TAG, "onServiceLost(${this@registerCallback})")
        }

        override fun onServiceInfoCallbackUnregistered() {
          Log.d(LOG_TAG, "onServiceInfoCallbackUnregistered(${this@registerCallback})")
        }
      }

  nsdManager.registerServiceInfoCallback(
      this,
      { it.run() },
      listener,
  )

  return listener
}
