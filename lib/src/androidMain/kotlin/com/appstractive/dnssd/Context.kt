package com.appstractive.dnssd

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.getSystemService
import androidx.startup.Initializer

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

internal fun multicastPermissionGranted() =
    ContextCompat.checkSelfPermission(
        appContext, Manifest.permission.CHANGE_WIFI_MULTICAST_STATE) ==
        PackageManager.PERMISSION_GRANTED
