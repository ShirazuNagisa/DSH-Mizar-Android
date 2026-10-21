package me.shirazu.dshmizar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import me.shirazu.dshmizar.ui.MizarTheme
import me.shirazu.dshmizar.ui.ThemeMode
import me.shirazu.dshmizar.ui.shell.MizarShell
import me.shirazu.mizar.kit.SessionGrouping
import me.shirazu.mizar.kit.SessionRow

/**
 * Debug-only design bench for the shell's depth work.
 *
 * The production shell needs a live host; the visual relationship between the
 * card and its two drawers does not. This activity renders the real shell and
 * the real drawer/card code against a small, fixed session list, so the depth
 * can be reviewed on a device without a pairing or a host connection.
 *
 * Launch: `adb shell am start -n me.shirazu.dshmizar/.DepthPreviewActivity`
 */
class DepthPreviewActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dark = intent?.getBooleanExtra("dark", false) == true
        val model = AppViewModel(application)
        val now = System.currentTimeMillis().toDouble()
        val rows = listOf(
            SessionRow("demo-1", now, running = false, blank = false, agentAvailable = true, cwd = "E:\\Projects\\Demo"),
            SessionRow("demo-2", now - 60_000, running = true, blank = false, agentAvailable = true, cwd = "E:\\Projects\\Demo"),
            SessionRow("demo-3", now - 120_000, running = false, blank = false, agentAvailable = true, cwd = "E:\\Projects\\Demo"),
            SessionRow("demo-4", now - 180_000, running = false, blank = false, agentAvailable = true, cwd = "E:\\Projects\\Notes"),
        )
        val titles = mapOf(
            "demo-1" to "Design the drawer depth",
            "demo-2" to "Running turn example",
            "demo-3" to "Contact shadow studies",
            "demo-4" to "Notes",
        )
        val paths = setOf("E:\\Projects\\Demo", "E:\\Projects\\Notes")
        val workspaceTitles = mapOf("E:\\Projects\\Demo" to "Demo", "E:\\Projects\\Notes" to "Notes")
        val state = UiState(
            phase = Phase.READY,
            sessions = rows,
            titles = titles,
            workspacePaths = paths,
            groups = SessionGrouping.groups(rows, paths, "demo-1", workspaceTitles),
            selectedSessionId = "demo-1",
        )
        setContent {
            MizarTheme(mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT) {
                Surface(Modifier.fillMaxSize()) {
                    MizarShell(model = model, state = state)
                }
            }
        }
    }
}
