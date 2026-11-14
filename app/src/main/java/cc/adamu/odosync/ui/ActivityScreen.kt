package cc.adamu.odosync.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.adamu.odosync.data.EventLog
import android.content.Context

/** Newest-first event log with a category icon per line. */
@Composable
fun ActivityScreen() {
    val log by EventLog.flow.collectAsStateWithLifecycle()
    val entries = log.asReversed()
    if (entries.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.History, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(40.dp))
                Spacer(Modifier.size(8.dp))
                Text("No activity yet", style = MaterialTheme.typography.titleMedium)
                Hint("Connections, trips and uploads will show up here.")
            }
        }
        return
    }
    LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
        itemsIndexed(entries) { i, line ->
            val time = line.take(14).trim()
            val msg = line.drop(14).trim()
            val (icon, tint) = classify(msg)
            Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp).padding(top = 1.dp))
                Column(Modifier.weight(1f)) {
                    Text(msg, style = MaterialTheme.typography.bodyMedium)
                    Text(time.replace("  ", " "), style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (i < entries.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        }
    }
}

@Composable
private fun classify(msg: String): Pair<ImageVector, Color> {
    val m = msg.lowercase()
    return when {
        "fail" in m || "error" in m || "refused" in m || "rejected" in m || "missing" in m ->
            Icons.Outlined.ErrorOutline to MaterialTheme.colorScheme.error
        m.startsWith("uploaded") -> Icons.Outlined.CloudDone to toneColor(Tone.GOOD)
        m.startsWith("trip") -> Icons.Outlined.Speed to MaterialTheme.colorScheme.primary
        "android auto" in m || "car bluetooth" in m -> Icons.Outlined.DirectionsCar to MaterialTheme.colorScheme.onSurfaceVariant
        m.startsWith("ble") || m.startsWith("elm") || "dongle" in m -> Icons.Outlined.Sensors to MaterialTheme.colorScheme.onSurfaceVariant
        else -> Icons.Outlined.Info to MaterialTheme.colorScheme.outline
    }
}

fun shareLog(context: Context) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, "OdoSync log")
        .putExtra(Intent.EXTRA_TEXT, EventLog.dump())
    context.startActivity(Intent.createChooser(send, "Share log"))
}
