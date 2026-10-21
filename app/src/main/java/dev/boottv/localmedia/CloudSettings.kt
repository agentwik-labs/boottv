package dev.boottv.localmedia

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.boottv.core.ui.BootColors
import dev.boottv.livetv.TvTextField
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Settings › My Media › Cloud library: where the index lives, and how it's doing. */
@Composable
fun CloudLibrarySettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { CloudLibrary.start(context) }
    var input by remember(CloudLibrary.url) { mutableStateOf(CloudLibrary.url.orEmpty()) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun run(block: suspend () -> String) { if (busy) return; busy = true; scope.launch { status = block(); busy = false } }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Spacer(Modifier.height(12.dp))
        Text("Cloud library", style = MaterialTheme.typography.titleMedium, color = BootColors.TextPrimary)
        Text(
            "Your own videos in a bucket (Cloudflare R2, Amazon S3, Backblaze B2 or any web host), streamed straight to the TV; " +
                "no computer has to stay on. Make the index with tools/cloud-index.py (see the README), upload it with the videos, " +
                "and paste the address of boottv.json (or its folder) here.",
            style = MaterialTheme.typography.bodySmall, color = BootColors.TextSecondary,
        )
        TvTextField("Index address", input, { input = it }, "https://pub-xxxx.r2.dev/media/boottv.json")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { run { CloudLibrary.set(context, input) } }, enabled = !busy && input.isNotBlank()) { Text(if (CloudLibrary.url == null) "Connect" else "Save") }
            if (CloudLibrary.url != null) {
                Button(onClick = { run { CloudLibrary.refresh(context) } }, enabled = !busy) { Text("Refresh") }
                Button(onClick = { run { CloudLibrary.set(context, null) } }, enabled = !busy) { Text("Remove") }
            }
        }
        val line = status ?: CloudLibrary.error ?: CloudLibrary.url?.let {
            "${CloudLibrary.name}: ${CloudLibrary.videos.size} videos" +
                (CloudLibrary.refreshedAt.takeIf { t -> t > 0 }?.let { t -> " · index read ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(t))}" } ?: "")
        }
        line?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = if (CloudLibrary.error != null && status == null) BootColors.Live else BootColors.TextPrimary) }
    }
}
