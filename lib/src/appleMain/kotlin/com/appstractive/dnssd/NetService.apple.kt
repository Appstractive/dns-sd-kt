package com.appstractive.dnssd

import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSNetService
import platform.Foundation.NSNetServiceDelegateProtocol
import platform.darwin.NSObject

class IosNetService(
    private val nativeService: NSNetService,
) : NetService, NetServiceDelegate {

  override val name: String
    get() = nativeService.name

  override val domain: String
    get() = nativeService.domain

  override val type: String
    get() = nativeService.type

  override val port: Int
    get() = nativeService.port.toInt()

  override val isRegistered: MutableStateFlow<Boolean> = MutableStateFlow(false)

  private var pendingRegister: Continuation<Unit>? = null
  private var pendingUnregister: Continuation<Unit>? = null

  private val delegate = NetServiceListener(this)

  private val mutex = Mutex()

  init {
    nativeService.delegate = delegate
  }

  override suspend fun register(timeoutInMs: Long) =
      mutex.withLock {
        if (isRegistered.value) {
          return
        }

        try {
          withContext(Dispatchers.Main) {
            withTimeout(timeoutInMs) {
              suspendCancellableCoroutine<Unit> {
                pendingRegister = it

                nativeService.publish()
              }
            }
          }
        } catch (_: TimeoutCancellationException) {
          pendingRegister = null
          throw NetServiceRegisterException("NsdServiceInfo register timeoutInMs")
        }
      }

  override suspend fun unregister() =
      mutex.withLock {
        if (!isRegistered.value) {
          return
        }

        withContext(Dispatchers.Main) {
          suspendCancellableCoroutine {
            pendingUnregister = it
            nativeService.stop()
          }
        }
      }

  override fun onDidPublish(sender: NSNetService) {
    isRegistered.value = true
    pendingRegister?.let {
      it.resume(Unit)
      pendingRegister = null
    }
  }

  override fun onDidNotPublish(sender: NSNetService, errors: Map<Any?, *>) {
    pendingRegister?.let {
      pendingRegister = null
      it.resumeWithException(
          NetServiceRegisterException(
              "NsdServiceInfo register error ${
                errors.entries.joinToString { error ->
                  "${error.key}:${error.value}"
                }
              }",
          ),
      )
    }
  }

  override fun onDidStop(sender: NSNetService) {
    isRegistered.value = false
    pendingUnregister?.let {
      pendingUnregister = null
      it.resume(Unit)
    }
  }
}

internal interface NetServiceDelegate {
  fun onDidPublish(sender: NSNetService)

  fun onDidNotPublish(sender: NSNetService, errors: Map<Any?, *>)

  fun onDidStop(sender: NSNetService)
}

internal class NetServiceListener(
    private val listener: NetServiceDelegate,
) : NSObject(), NSNetServiceDelegateProtocol {

  override fun netServiceDidPublish(sender: NSNetService) {
    listener.onDidPublish(sender)
  }

  @Suppress("CONFLICTING_OVERLOADS", "PARAMETER_NAME_CHANGED_ON_OVERRIDE")
  @ObjCSignatureOverride
  override fun netService(sender: NSNetService, didNotPublish: Map<Any?, *>) {
    listener.onDidNotPublish(sender, didNotPublish)
  }

  override fun netServiceDidStop(sender: NSNetService) {
    listener.onDidStop(sender)
  }
}

actual fun createNetService(
    type: String,
    name: String,
    port: Int,
    priority: Int,
    weight: Int,
    addresses: List<String>?,
    txt: Map<String, String>,
): NetService =
    IosNetService(
        nativeService =
            NSNetService(
                    domain = "local.",
                    type = type.qualified,
                    name = name,
                    port = port,
                )
                .apply {
                  if (
                      !setTXTRecordData(
                          NSNetService.dataFromTXTRecordDictionary(txt.mapValues { it.value }),
                      )
                  ) {
                    throw NetServiceRegisterException("Failed to set txt attributes: $txt")
                  }
                },
    )
