package com.appstractive.dnssd

import kotlinx.coroutines.flow.Flow

data class DiscoveredService(
    val name: String,
    val addresses: List<String>,
    val host: String,
    val type: String,
    val port: Int,
    val txt: Map<String, ByteArray?>,
)

val DiscoveredService.key: String
  get() = "${name}${type}".replace(".", "")

sealed interface DiscoveryEvent {
  val service: DiscoveredService

  data class Discovered(
      override val service: DiscoveredService,
      val resolve: () -> Unit,
  ) : DiscoveryEvent

  data class Removed(
      override val service: DiscoveredService,
  ) : DiscoveryEvent

  data class Resolved(
      override val service: DiscoveredService,
      val resolve: () -> Unit,
  ) : DiscoveryEvent
}

expect fun discoverServices(type: String): Flow<DiscoveryEvent>
