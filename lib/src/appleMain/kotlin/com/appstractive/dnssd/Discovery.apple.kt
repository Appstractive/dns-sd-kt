package com.appstractive.dnssd

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSNetService
import platform.Foundation.NSNetServiceBrowser
import platform.Foundation.NSNetServiceBrowserDelegateProtocol
import platform.Foundation.NSNetServiceDelegateProtocol
import platform.darwin.NSObject
import platform.darwin.inet_ntop
import platform.posix.AF_INET
import platform.posix.AF_INET6
import platform.posix.INET6_ADDRSTRLEN
import platform.posix.INET_ADDRSTRLEN
import platform.posix.memcpy
import platform.posix.sa_family_t
import platform.posix.sockaddr_in
import platform.posix.sockaddr_in6

internal val String.qualified
  get() = if (this.endsWith(".")) this else "$this."

actual fun discoverServices(type: String): Flow<DiscoveryEvent> = callbackFlow {
  val serviceBrowser = withContext(Dispatchers.Main) { NSNetServiceBrowser() }
  serviceBrowser.includesPeerToPeer = true

  fun resolve(service: NSNetService) {
    service.delegate =
        object : NSObject(), NSNetServiceDelegateProtocol {
          override fun netServiceDidResolveAddress(sender: NSNetService) {
            println("netServiceDidResolveAddress")
            service.delegate = null
            trySend(
                DiscoveryEvent.Resolved(
                    service = sender.toCommon(),
                ) {
                  resolve(service)
                },
            )
          }
        }
    service.resolve()
  }

  serviceBrowser.delegate =
      object : NSObject(), NSNetServiceBrowserDelegateProtocol {

        override fun netServiceBrowser(browser: NSNetServiceBrowser, didNotSearch: Map<Any?, *>) {
          println(
              "NSNetServiceBrowser search error ${didNotSearch.entries.joinToString { error -> "${error.key}:${error.value}" }}",
          )
        }

        override fun netServiceBrowserWillSearch(browser: NSNetServiceBrowser) {
          println("netServiceBrowserWillSearch")
        }

        override fun netServiceBrowserDidStopSearch(browser: NSNetServiceBrowser) {
          println("netServiceBrowserDidStopSearch")
        }

        @Suppress("CONFLICTING_OVERLOADS", "PARAMETER_NAME_CHANGED_ON_OVERRIDE")
        @ObjCSignatureOverride
        override fun netServiceBrowser(
            browser: NSNetServiceBrowser,
            didFindDomain: String,
            moreComing: Boolean
        ) {
          println("didFindDomain: $didFindDomain")
        }

        @Suppress("CONFLICTING_OVERLOADS", "PARAMETER_NAME_CHANGED_ON_OVERRIDE")
        @ObjCSignatureOverride
        override fun netServiceBrowser(
            browser: NSNetServiceBrowser,
            didRemoveDomain: String,
            moreComing: Boolean
        ) {
          println("didRemoveDomain: $didRemoveDomain")
        }

        @Suppress("CONFLICTING_OVERLOADS", "PARAMETER_NAME_CHANGED_ON_OVERRIDE")
        @ObjCSignatureOverride
        override fun netServiceBrowser(
            browser: NSNetServiceBrowser,
            didFindService: NSNetService,
            moreComing: Boolean
        ) {
          println("didFindService")
          trySend(
              DiscoveryEvent.Discovered(
                  service = didFindService.toCommon(),
              ) {
                resolve(didFindService)
              },
          )
        }

        @Suppress("CONFLICTING_OVERLOADS", "PARAMETER_NAME_CHANGED_ON_OVERRIDE")
        @ObjCSignatureOverride
        override fun netServiceBrowser(
            browser: NSNetServiceBrowser,
            didRemoveService: NSNetService,
            moreComing: Boolean
        ) {
          println("didRemoveService")
          trySend(
              DiscoveryEvent.Removed(
                  service = didRemoveService.toCommon(),
              ),
          )
        }
      }

  // serviceBrowser.searchForRegistrationDomains()
  println("Find ${type.qualified}")
  withContext(Dispatchers.Main) {
    serviceBrowser.searchForServicesOfType(type = type.qualified, inDomain = "local.")
  }

  awaitClose { serviceBrowser.stop() }
}

private fun NSNetService.toCommon(): DiscoveredService =
    DiscoveredService(
        name = name,
        addresses =
            addresses?.filterIsInstance<NSData>()?.mapNotNull { it.toIpString() } ?: emptyList(),
        host = hostName ?: "",
        type = type,
        port = port.toInt(),
        txt =
            buildMap {
              TXTRecordData()?.let {
                val data: Map<Any?, *>? = NSNetService.dictionaryFromTXTRecordData(it)

                data?.forEach { (key, value) ->
                  println("Service attributes: $key=$value")
                  if (key is String && value is NSData?) {
                    put(key, value?.toByteArray())
                  }
                }
              }
            },
    )

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toIpString(): String? = memScoped {
  return bytes?.getPointer(this)?.let {
    val serverAddr = it.reinterpret<sockaddr_in>()

    val addr =
        when (serverAddr.pointed.sin_family) {
          AF_INET.convert<sa_family_t>() -> serverAddr.pointed.sin_addr
          AF_INET6.convert<sa_family_t>() -> it.reinterpret<sockaddr_in6>().pointed.sin6_addr
          else -> return null
        }

    val len = maxOf(INET_ADDRSTRLEN, INET6_ADDRSTRLEN)
    val dst = allocArray<ByteVar>(len)
    inet_ntop(serverAddr.pointed.sin_family.convert(), addr.ptr, dst, len.convert())?.toKString()
  }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray? {
  if (length.toInt() == 0) {
    return null
  }

  return ByteArray(this@toByteArray.length.toInt()).apply {
    usePinned { memcpy(it.addressOf(0), this@toByteArray.bytes, this@toByteArray.length) }
  }
}
