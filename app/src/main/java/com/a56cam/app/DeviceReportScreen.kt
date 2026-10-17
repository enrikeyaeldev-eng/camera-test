package com.a56cam.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The Phase 0 diagnostics, kept as a "Device report" screen reached from Settings. */
@Composable
fun DeviceReportScreen(vm: ReportViewModel, onBack: () -> Unit, onShareLogs: () -> Unit) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, enabled = !state.running) { Text("← Back") }
            Text("Device report", style = MaterialTheme.typography.headlineSmall)
        }
        Text("Native: ${state.nativeInfo}", style = MaterialTheme.typography.bodySmall)

        if (state.crashLogs > 0) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "A previous crash was saved (${state.crashLogs}). Tap Share logs and send the file to Claude.",
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        Button(
            onClick = { vm.runReport() },
            enabled = !state.running,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (state.running) "Running..." else "Run device report") }
        Text(
            "Tests every camera, front and back: about 5 to 8 minutes. Prop the phone up so both sides see a " +
                "still, detailed scene, keep it steady, and keep the app open until it finishes.",
            style = MaterialTheme.typography.bodySmall,
        )

        if (state.running) CircularProgressIndicator()
        if (state.progress.isNotEmpty()) {
            Text(state.progress.takeLast(6).joinToString("\n"), style = MaterialTheme.typography.bodySmall)
        }
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        state.result?.let { result ->
            Text(
                if (result.exportedTo != null) "Saved to ${result.exportedTo}" else
                    "Saved in the app folder only (copy to Documents failed; see logs).",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                onClick = { Sharing.shareFiles(context, listOf(result.reportJson, result.reportTxt), "A56Cam report") },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Share report (report.json + report.txt)") }
            OutlinedButton(
                onClick = {
                    val files = result.runDir.listFiles()?.toList() ?: emptyList()
                    val zip = Sharing.zip(context, "a56cam-${result.runDir.name}.zip", files)
                    Sharing.shareFiles(context, listOf(zip), "A56Cam report with test images")
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Share report + test images (zip)") }
            Text(result.summary, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
        }

        OutlinedButton(onClick = onShareLogs, modifier = Modifier.fillMaxWidth()) { Text("Share logs") }
    }
}
