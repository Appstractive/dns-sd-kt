package com.appstractive.dnssd

import kotlinx.coroutines.flow.Flow

/**
 * A DNS_SD service that was discovered in the network.
 *
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc6763">RFC-6763</a>
 */
data class DiscoveredService(
    val name: String,
    /** The specific ip addresses to reach the service at. May be empty, if not resolved yet. */
    val addresses: List<String>,
    /** The host to reach the service at. May be empty on the very first discovery. */
    val host: String,
    val type: String,
    val port: Int,
    val txt: Map<String, ByteArray?>,
)

/** A combination of a services name and type to uniquely identify it */
val DiscoveredService.key: String
  get() = "${name}${type}".replace(".", "")

sealed interface DiscoveryEvent {
  val service: DiscoveredService

  /**
   * Emitted when a service was first discovered. Host and addresses may be empty at that point. Use
   * resolve() to get associated ip addresses. They will be delivered via DiscoveryEvent.Resolved.
   */
  data class Discovered(
      override val service: DiscoveredService,
      val resolve: () -> Unit,
  ) : DiscoveryEvent

  /** A service was unpublished from the network. */
  data class Removed(
      override val service: DiscoveredService,
  ) : DiscoveryEvent

  /** Addresses of a service where resolved. */
  data class Resolved(
      override val service: DiscoveredService,
      val resolve: () -> Unit,
  ) : DiscoveryEvent
}

/** Returns publish, unpublish and resolve events for the specified service type in the network. */
expect fun discoverServices(type: String): Flow<DiscoveryEvent>
