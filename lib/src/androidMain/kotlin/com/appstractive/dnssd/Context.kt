package com.appstractive.dnssd

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.net.wifi.WifiManager
import android.os.Build.VERSION
import android.os.Build.VERSION_CODES
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.getSystemService
import androidx.startup.Initializer

private const val LOG_TAG = "DNS_SD"

@SuppressLint("StaticFieldLeak")
// application context
internal lateinit var appContext: Context

internal class AppContextInitializer : Initializer<Context> {
  override fun create(context: Context): Context {
    return context.also { appContext = it }
  }

  override fun dependencies(): List<Class<out Initializer<*>>> {
    // No dependencies on other libraries.
    return emptyList()
  }
}

internal val nsdManager: NsdManager by lazy {
  getSystemService(appContext, NsdManager::class.java)!!
}
internal val wifiManager: WifiManager by lazy {
  getSystemService(appContext, WifiManager::class.java)!!
}

internal fun multicastPermissionGranted(): Boolean =
    ContextCompat.checkSelfPermission(
        appContext,
        Manifest.permission.CHANGE_WIFI_MULTICAST_STATE,
    ) == PackageManager.PERMISSION_GRANTED

internal fun checkLocalNetworkPermission() {
  if (VERSION.SDK_INT >= VERSION_CODES.BAKLAVA) {
    val localNetworkPermissionGranted =
        ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.NEARBY_WIFI_DEVICES,
        ) == PackageManager.PERMISSION_GRANTED

    if (!localNetworkPermissionGranted) {
      Log.w(
          LOG_TAG,
          "NEARBY_WIFI_DEVICES permission not granted. This will become an error in future Android releases. https://developer.android.com/about/versions/16/behavior-changes-16#local-network-permission",
      )
    }
  }
}
