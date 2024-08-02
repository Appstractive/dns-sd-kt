package com.appstractive.dnssd

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

class NetServiceRegisterException(message: String) : RuntimeException(message)

/**
 * A DNS-SD service instance containing the platform specific implementation.
 *
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc6763">RFC-6763</a>
 */
interface NetService {
  val name: String
  val domain: String
  val type: String
  val port: Int
  val isRegistered: StateFlow<Boolean>
  val registered: Boolean
    get() = isRegistered.value

  /** Publish this service on the network. */
  suspend fun register(timeoutInMs: Long = 10000)

  /** Unpublish the service from the network. */
  suspend fun unregister()
}

/**
 * Create a new DNS-SD to publish on the network. Make sure to call this on the main thread.
 *
 * @param type the service type (e.g. _example._tcp)
 * @param name the name of the service to publish
 * @param port the port of the provided service
 * @param priority the priority of the created DNS entry
 * @param weight the weight of the created DNS entry relative to priority
 * @param addresses the address to advertise (if null, let the platform decide)
 * @param txt optional attributes to publish
 * @return the platform instance of the service to register or unregister
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc6763">RFC-6763</a>
 */
expect fun createNetService(
    type: String,
    name: String,
    port: Int = 0,
    priority: Int = 0,
    weight: Int = 1,
    addresses: List<String>? = null,
    txt: Map<String, String> = emptyMap(),
): NetService

class NetServiceConfig {
  /** the port of the provided service */
  var port: Int = 0
  /** the priority of the created DNS entry (only applied on JVM) */
  var priority: Int = 0
  /** the weight of the created DNS entry relative to priority (only applied on JVM) */
  var weight: Int = 1
  /** the address to advertise (if null, let the platform decide) */
  var addresses: List<String>? = null
  /** optional attributes to publish */
  var txt: Map<String, String> = emptyMap()
}

/**
 * Create a new DNS-SD to publish on the network. Make sure to call this on the main thread.
 *
 * @param type the service type (e.g. _example._tcp)
 * @param name the name of the service to publish
 * @return the platform instance of the service to register or unregister
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc6763">RFC-6763</a>
 */
fun netService(
    type: String,
    name: String,
    configure: NetServiceConfig.() -> Unit = {},
): NetService {
  val config = NetServiceConfig().apply(configure)
  return createNetService(
      type = type,
      name = name,
      port = config.port,
      priority = config.priority,
      weight = config.weight,
      addresses = config.addresses,
      txt = config.txt,
  )
}

/**
 * Create a new DNS-SD to publish on the network. The service will stay published until the calling
 * coroutine scope is cancelled.
 *
 * @param type the service type (e.g. _example._tcp)
 * @param name the name of the service to publish
 * @return the platform instance of the service to register or unregister
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc6763">RFC-6763</a>
 */
suspend fun publishService(
    type: String,
    name: String,
    timeoutInMs: Long = 10000,
    configure: NetServiceConfig.() -> Unit = {},
): Nothing =
    withContext(Dispatchers.Main) {
      val service =
          netService(
              type = type,
              name = name,
              configure = configure,
          )

      try {
        service.register(timeoutInMs = timeoutInMs)
        awaitCancellation()
      } finally {
        withContext(NonCancellable) { service.unregister() }
      }
    }
