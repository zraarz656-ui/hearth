package com.tradequest.app.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tradequest.app.ui.theme.TradeQuestTheme
import kotlinx.coroutines.delay

/**
 * Debug-only screen that shows the diagnostics log and lets it be copied off the
 * device without a cable.
 *
 * # Why this exists
 *
 * `DebugDiagnostics` writes a file, and reading it needed `adb pull`. That works
 * on a desk but not on a phone in hand, which is exactly where frame timing has
 * to be captured. This screen turns the file into something the tester can read,
 * copy, and paste.
 *
 * # Why a separate launcher icon
 *
 * A second `LAUNCHER` activity puts a "TradeQuest Debug" icon next to the app,
 * so the log is reachable without a settings entry or a hidden gesture. Nothing
 * in `main` has to grow a debug affordance. This class and its manifest entry
 * live only in `app/src/debug/`, so no release build contains either.
 *
 * # Removal
 *
 * Debug instrumentation, not a product screen. Delete with the rest of
 * `app/src/debug/` once Phase 15's import UI makes the seeder unnecessary.
 */
class DebugLogActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TradeQuestTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    DebugLogScreen(
                        loadLog = { DebugDiagnostics.readLog() },
                        logPath = { DebugDiagnostics.logPath() },
                        onCopy = { text -> copyToClipboard(text) },
                        onShare = { text -> share(text) },
                        onClear = { DebugDiagnostics.clear() },
                    )
                }
            }
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("TradeQuest debug log", text))
        // Android 13+ shows its own copy confirmation; a second toast on top of
        // that is noise, so only older versions get one.
        if (android.os.Build.VERSION.SDK_INT < 33) {
            Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show()
        }
    }

    private fun share(text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "TradeQuest debug log")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, "Share debug log"))
    }
}

@Composable
private fun DebugLogScreen(
    loadLog: () -> String,
    logPath: () -> String,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    onClear: () -> Unit,
) {
    // Refreshed on a timer rather than read once: the frame monitor appends every
    // few seconds while the tester is on the chart, and a frozen snapshot would
    // look like the log had stopped.
    var log by remember { mutableStateOf(loadLog()) }
    var path by remember { mutableStateOf(logPath()) }
    var autoRefresh by remember { mutableStateOf(true) }

    LaunchedEffect(autoRefresh) {
        while (autoRefresh) {
            delay(1_000)
            log = loadLog()
            path = logPath()
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Text(
            text = "Debug log",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = path,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = { log = loadLog() }) { Text("Refresh") }
            Button(onClick = { onCopy(log) }) { Text("Copy") }
            OutlinedButton(onClick = { onShare(log) }) { Text("Share") }
            OutlinedButton(onClick = { onClear(); log = loadLog() }) { Text("Clear") }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = { autoRefresh = !autoRefresh }) {
                Text(if (autoRefresh) "Pause auto-refresh" else "Resume auto-refresh")
            }
        }

        // A monospaced log body in a scroll view. The text is selected by the
        // Copy button rather than by long-press selection, which on a log this
        // long is unusable.
        Text(
            text = log,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            lineHeight = 13.sp,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        )
    }
}
