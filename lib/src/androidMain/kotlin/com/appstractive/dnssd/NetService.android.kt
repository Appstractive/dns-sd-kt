package com.appstractive.dnssd

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build.VERSION
import android.os.Build.VERSION_CODES
import java.net.InetAddress
import java.net.NetworkInterface
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

class AndroidNetService(
    private val nativeService: NsdServiceInfo,
) : NetService, NsdManager.RegistrationListener {

  override val name: String
    get() = nativeService.serviceName

  override val domain: String
    get() = nativeService.host.canonicalHostName

  override val type: String
    get() = nativeService.serviceType

  override val port: Int
    get() = nativeService.port

  override val isRegistered: MutableStateFlow<Boolean> = MutableStateFlow(false)

  private var pendingRegister: Continuation<Unit>? = null
  private var pendingUnregister: Continuation<Unit>? = null

  private val mutex = Mutex()

  override suspend fun register(timeoutInMs: Long) =
      mutex.withLock {
        if (isRegistered.value) {
          return
        }

        try {
          withTimeout(timeoutInMs) {
            suspendCancellableCoroutine<Unit> {
              pendingRegister = it
              nsdManager.registerService(
                  nativeService,
                  NsdManager.PROTOCOL_DNS_SD,
                  this@AndroidNetService,
              )
            }
          }
        } catch (_: TimeoutCancellationException) {
          pendingRegister = null
          throw NetServiceRegisterException("NsdServiceInfo register timeout")
        }
      }

  override suspend fun unregister() =
      mutex.withLock {
        if (!isRegistered.value) {
          return
        }

        suspendCancellableCoroutine {
          pendingUnregister = it
          nsdManager.unregisterService(this)
        }
      }

  override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
    isRegistered.value = true
    pendingRegister?.let {
      it.resume(Unit)
      pendingRegister = null
    }
  }

  override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
    pendingRegister?.let {
      pendingRegister = null
      it.resumeWithException(
          NetServiceRegisterException("NsdServiceInfo register error $errorCode"))
    }
  }

  override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
    isRegistered.value = false
    pendingUnregister?.let {
      pendingUnregister = null
      it.resume(Unit)
    }
  }

  override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
    pendingUnregister?.let {
      pendingUnregister = null
      it.resumeWithException(
          NetServiceRegisterException("NsdServiceInfo unregister error $errorCode"))
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
    AndroidNetService(
        nativeService =
            NsdServiceInfo().apply {
              serviceName = name
              serviceType = type.stripLocal
              if (VERSION.SDK_INT >= VERSION_CODES.UPSIDE_DOWN_CAKE) {
                hostAddresses = addresses?.map { InetAddress.getByName(it) } ?: getLocalAddresses()
              } else {
                host =
                    addresses?.firstOrNull()?.let { InetAddress.getByName(it) }
                        ?: getLocalAddresses().firstOrNull()
              }
              this.port = port
              txt.forEach { setAttribute(it.key, it.value) }
            },
    )

private fun getLocalAddresses(): List<InetAddress> = buildList {
  val interfaces = NetworkInterface.getNetworkInterfaces()

  for (netInterface in interfaces) {
    addAll(
        netInterface.inetAddresses.toList().filter { it.isSiteLocalAddress }.toList(),
    )
  }
}
