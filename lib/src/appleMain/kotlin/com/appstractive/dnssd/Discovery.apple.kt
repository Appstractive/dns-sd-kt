package com.appstractive.dnssd

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import nativeBridge.NWBrowserBridge
import platform.Foundation.NSData
import platform.Foundation.NSLog
import platform.posix.memcpy

@OptIn(ExperimentalForeignApi::class)
actual fun discoverServices(type: String): Flow<DiscoveryEvent> = callbackFlow {
    val browser = NWBrowserBridge()

    fun resolve(serviceName: String, serviceType: String, serviceDomain: String) { // cinterop binding available in platform-specific compilations
        browser.resolveServiceWithName(
            name = serviceName,
            type = serviceType,
            domain = serviceDomain,
            onResolved = { name: String?, addresses: List<*>?, port: Long, hostname: String?, txtRecords: Map<Any?, *>? ->
                NSLog("Service resolved: $name at $hostname:$port with ${addresses?.size ?: 0} addresses")

                val addressList = addresses?.mapNotNull { it?.toString() } ?: emptyList()
                val txtMap = txtRecords?.mapNotNull { (key, value) ->
                    if (key is String && value is NSData) {
                        key to value.toByteArray()
                    } else null
                }?.toMap() ?: emptyMap()

                trySend(
                    DiscoveryEvent.Resolved(
                        service = DiscoveredService(
                            name = name ?: serviceName,
                            addresses = addressList,
                            host = hostname ?: "",
                            type = serviceType,
                            port = port.toInt(),
                            txt = txtMap
                        ),
                    ) {
                        resolve(serviceName, serviceType, serviceDomain)
                    },
                )
            },
            onError = { error: String? ->
                NSLog("Service resolution error: $error")
            }
        )
    }

    browser.startBrowsingWithServiceType(
        serviceType = type.stripLocal.qualified,
        domain = "local.",
        onServiceFound = { name, serviceType, domain ->
            NSLog("NWBrowser found service: $name of type $serviceType in domain $domain")

            val serviceName = name ?: ""
            val serviceDomain = domain ?: "local."
            val serviceTypeValue = serviceType ?: type.stripLocal.qualified

            trySend(
                DiscoveryEvent.Discovered(
                    service = DiscoveredService(
                        name = serviceName,
                        addresses = emptyList(),
                        host = "",
                        type = serviceTypeValue,
                        port = 0,
                        txt = emptyMap()
                    ),
                ) {
                    resolve(serviceName, serviceTypeValue, serviceDomain)
                },
            )
        },
        onServiceRemoved = { name ->
            NSLog("NWBrowser removed service: $name")
            val serviceName = name ?: ""

            trySend(
                DiscoveryEvent.Removed(
                    service = DiscoveredService(
                        name = serviceName,
                        addresses = emptyList(),
                        host = "",
                        type = type,
                        port = 0,
                        txt = emptyMap()
                    ),
                ),
            )
        },
        onError = { error ->
            NSLog("NWBrowser error: $error")
        },
        triggerPermissionPrompt = true,
        waitingStateTimeout = 5.0,
        onPermissionStateChanged = { state ->
            val permission = PermissionState.fromValue(state)
            NSLog("NWBrowser permission state changed: $permission")
        }
    )

    awaitClose {
        browser.stop()
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

enum class PermissionState(val value: Long) {
    UNDETERMINED(0L),
    GRANTED(1L),
    DENIED(2L);

    companion object {
        fun fromValue(value: Long): PermissionState = entries.find { it.value == value } ?: UNDETERMINED
    }
}
