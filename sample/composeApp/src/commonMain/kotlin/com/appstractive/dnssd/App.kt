package com.appstractive.dnssd

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

const val SERVICE_TYPE = "_example._tcp"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun App(
    service: NetService,
) {
  MaterialTheme {
    var selectedTab by remember { mutableStateOf(0) }

    Scaffold { padding ->
      Column(
          modifier = Modifier.fillMaxSize().padding(padding),
      ) {
        PrimaryTabRow(
            selectedTabIndex = selectedTab,
        ) {
          Tab(
              selected = selectedTab == 0,
              onClick = { selectedTab = 0 },
              text = { Text(text = "Advertise") })

          Tab(
              selected = selectedTab == 1,
              onClick = { selectedTab = 1 },
              text = { Text(text = "Scan") })
        }

        Column(
            modifier = Modifier.weight(1f),
        ) {
          when (selectedTab) {
            0 -> AdvertiseView(service)
            else -> ScanView()
          }
        }
      }
    }
  }
}

@Composable
private fun AdvertiseView(service: NetService) {
  val scope = rememberCoroutineScope()
  val isRegistered by service.isRegistered.collectAsState()

  Column {
    Text("Service Registration")

    ElevatedButton(
        onClick = {
          scope.launch {
            if (isRegistered) {
              service.unregister()
            } else {
              service.register()
            }
          }
        },
    ) {
      Text(if (isRegistered) "Stop" else "Start")
    }
  }
}

@Composable
private fun ScanView() {
  val scope = rememberCoroutineScope()
  val scannedServices = remember { mutableStateMapOf<String, DiscoveredService>() }
  var scanJob: Job? by remember { mutableStateOf(null) }

  Column {
    Row {
      Text("Scan NSD Services")

      ElevatedButton(
          onClick = {
            if (scanJob != null) {
              scanJob?.cancel()
              scanJob = null
            } else {
              scannedServices.clear()
              scanJob =
                  scope.launch(Dispatchers.IO) {
                    discoverServices(SERVICE_TYPE).collect {
                      when (it) {
                        is DiscoveryEvent.Discovered -> {
                          scannedServices[it.service.key] = it.service
                          it.resolve()
                        }
                        is DiscoveryEvent.Removed -> {
                          scannedServices.remove(it.service.key)
                        }
                        is DiscoveryEvent.Resolved -> {
                          scannedServices[it.service.key] = it.service
                        }
                      }
                    }
                  }
            }
          },
      ) {
        Text(if (scanJob != null) "Stop" else "Start")
      }
    }

    LazyColumn {
      items(scannedServices.values.toList()) {
        ListItem(
            headlineContent = { Text("${it.name} (${it.host})") },
            supportingContent = {
              Column {
                Text("${it.type}:${it.port} (${it.addresses})")
                Text(it.txt.toList().joinToString { "${it.first}=${it.second?.decodeToString()}" })
              }
            },
        )
      }
    }
  }
}
