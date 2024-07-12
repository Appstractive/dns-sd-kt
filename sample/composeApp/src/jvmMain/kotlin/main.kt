import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.appstractive.dnssd.App
import com.appstractive.dnssd.SERVICE_TYPE
import com.appstractive.dnssd.createNetService
import java.awt.Dimension

val service by lazy {
  createNetService(
      type = SERVICE_TYPE,
      name = "jvm",
      port = 8080,
      txt =
          mapOf(
              "key1" to "value1",
              "key2" to "value2",
          ),
  )
}

fun main() = application {
  Window(
      title = "NSD Kt",
      state = rememberWindowState(width = 800.dp, height = 600.dp),
      onCloseRequest = ::exitApplication,
  ) {
    window.minimumSize = Dimension(350, 600)
    App(
        service = service,
    )
  }
}
