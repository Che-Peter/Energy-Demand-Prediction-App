// main.kt
import com.prediction.app.App
import kotlinx.browser.document
import org.jetbrains.compose.web.renderComposable
import androidx.compose.material3.MaterialTheme
import com.prediction.app.App
import kotlinx.browser.document
import org.jetbrains.compose.web.renderComposable

fun main() {
    renderComposable(rootElementId = "root") {
        App() // just your Composable app
    }
    document.getElementById("loading")?.remove()
}