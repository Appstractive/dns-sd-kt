package com.appstractive.dnssd

import kotlinx.coroutines.flow.StateFlow

class NetServiceRegisterException(message: String) : RuntimeException(message)

interface NetService {
  val name: String
  val domain: String
  val type: String
  val port: Int
  val isRegistered: StateFlow<Boolean>

  suspend fun register(timeout: Long = 10000)

  suspend fun unregister()
}

expect fun createNetService(
    type: String,
    name: String,
    port: Int,
    priority: Int = 0,
    weight: Int = 1,
    addresses: List<String>? = null,
    txt: Map<String, String> = emptyMap(),
): NetService
