package com.appstractive.dnssd

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import kotlin.uuid.Uuid

class AndroidApp : Application() {
  companion object {
    internal val service by lazy {
      createNetService(
          type = SERVICE_TYPE,
          name = "android-${Uuid.random()}",
          port = 8080,
          txt =
              mapOf(
                  "key1" to "value1",
                  "key2" to "value2",
              ),
      )
    }
  }
}

class AppActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      App(
          service = AndroidApp.service,
      )
    }
  }
}
