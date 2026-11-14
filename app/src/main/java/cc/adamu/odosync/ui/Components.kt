package cc.adamu.odosync.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A titled card; the standard building block of every screen. */
@Composable
fun Section(
    title: String,
    icon: ImageVector? = null,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (subtitle != null) {
                        Text(subtitle, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                trailing?.invoke()
            }
            Spacer(Modifier.size(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
        }
    }
}

@Composable
fun StatusDot(tone: Tone, size: Int = 8) {
    Box(Modifier.size(size.dp).background(toneColor(tone), CircleShape))
}

/** Pill with a coloured dot, e.g. "● Driving · +12 km". */
@Composable
fun StatusPill(text: String, tone: Tone, container: Color = MaterialTheme.colorScheme.surfaceContainerHighest) {
    Surface(shape = CircleShape, color = container) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusDot(tone)
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Icon · label ............ ● value */
@Composable
fun StatusRow(icon: ImageVector, label: String, value: String, tone: Tone? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (tone != null) {
            StatusDot(tone)
            Spacer(Modifier.width(8.dp))
        }
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier)
}

/** Read-only text field that opens a menu - a dropdown that looks like the other inputs. */
@Composable
fun <T> SelectField(
    label: String,
    value: String,
    options: List<T>,
    optionLabel: (T) -> String,
    enabled: Boolean = true,
    placeholder: String = "",
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedTextField(
            value = value, onValueChange = {}, readOnly = true, enabled = enabled,
            label = { Text(label) }, placeholder = { Text(placeholder) }, singleLine = true,
            trailingIcon = { Icon(Icons.Outlined.ArrowDropDown, null) },
            modifier = Modifier.fillMaxWidth(),
        )
        // Transparent overlay so the whole field opens the menu.
        Box(Modifier.matchParentSize().padding(top = 8.dp).clickable(enabled = enabled && options.isNotEmpty()) { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o ->
                DropdownMenuItem(text = { Text(optionLabel(o)) }, onClick = { onSelect(o); open = false })
            }
        }
    }
}

/** Current time that ticks every 30 s so "5 min ago" labels stay fresh. */
@Composable
fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    return now
}

fun relativeTime(t: Long, now: Long): String {
    if (t <= 0) return "never"
    val d = (now - t).coerceAtLeast(0)
    return when {
        d < 60_000 -> "just now"
        d < 3_600_000 -> "${d / 60_000} min ago"
        d < 86_400_000 -> "${d / 3_600_000} h ago"
        else -> SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(t))
    }
}

fun clockTime(t: Long): String =
    if (t <= 0) "—" else SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t))

fun sourceName(id: String): String = when (id) {
    "KOMBI" -> "instrument cluster"
    "DME" -> "engine ECU"
    "OBD-A6" -> "OBD PID A6"
    else -> id
}
