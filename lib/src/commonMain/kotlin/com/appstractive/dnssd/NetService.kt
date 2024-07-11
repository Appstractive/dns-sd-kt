package com.appstractive.dnssd

import kotlinx.coroutines.flow.StateFlow

class NetServiceRegisterException(message: String) : RuntimeException(message)

/**
 * A DNS-SD service instance containing the platform specific implementation.
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc6763">RFC-6763</a>
 */
interface NetService {
    val name: String
    val domain: String
    val type: String
    val port: Int
    val isRegistered: StateFlow<Boolean>

    /**
     * Publish this service on the network.
     */
    suspend fun register(timeout: Long = 10000)

    /**
     * Unpublish the service from the network.
     */
    suspend fun unregister()
}

/**
 * Create a new DNS-SD to publish on the network.
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc6763">RFC-6763</a>
 *
 * @param type the service type (e.g. _example._tcp)
 * @param name the name of the service to publish
 * @param port the port of the provided service
 * @param priority the priority of the created DNS entry
 * @param weight the weight of the created DNS entry relative to priority
 * @param addresses the address to advertise (if null, let the platform decide)
 * @param txt optional attributes to publish
 *
 * @return the platform instance of the service to register or unregister
 */
expect fun createNetService(
    type: String,
    name: String,
    port: Int,
    priority: Int = 0,
    weight: Int = 1,
    addresses: List<String>? = null,
    txt: Map<String, String> = emptyMap(),
): NetService
