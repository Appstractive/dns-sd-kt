import androidx.compose.ui.window.ComposeUIViewController
import com.appstractive.dnssd.App
import com.appstractive.dnssd.SERVICE_TYPE
import com.appstractive.dnssd.createNetService
import platform.UIKit.UIViewController

val service by lazy {
  createNetService(
      type = SERVICE_TYPE,
      name = "iOS",
      port = 8080,
      txt =
      mapOf(
          "key1" to "value1",
          "key2" to "value2",
      ),
  )
}

fun MainViewController(): UIViewController = ComposeUIViewController {
  App(
      service = service,
  )
}
