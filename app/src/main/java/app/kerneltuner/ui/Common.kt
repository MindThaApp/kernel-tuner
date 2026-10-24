@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.kerneltuner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.kerneltuner.core.Info
import app.kerneltuner.core.OcInfo
import app.kerneltuner.core.OcLevel
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.SliderDefaults
import androidx.compose.ui.graphics.Color

@Composable
fun Page(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

@Composable
fun Section(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}

/** A card the user can expand. Used to tuck advanced items away. */
@Composable
fun Collapsible(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    var open by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { open = !open }) { Text(if (open) "Hide" else "Show") }
            }
            if (open) content()
        }
    }
}

/** Small "?" button that opens the long explanation. */
@Composable
fun InfoButton(info: Info) {
    var show by remember { mutableStateOf(false) }
    FilledTonalIconButton(
        onClick = { show = true },
        modifier = Modifier.size(32.dp).semantics { contentDescription = "Explain: ${info.title}" },
    ) { Text("?", fontWeight = FontWeight.Bold) }
    if (show) {
        ModalBottomSheet(onDismissRequest = { show = false }, sheetState = rememberModalBottomSheetState()) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(info.title, style = MaterialTheme.typography.titleLarge)
                Text(info.subtitle, style = MaterialTheme.typography.titleSmall)
                Text(info.details, style = MaterialTheme.typography.bodyMedium)
                info.hint?.let {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                        Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                TextButton(onClick = { show = false }) { Text("Got it") }
            }
        }
    }
}

/** Title + one-line plain-English subtitle + "?" sheet. */
@Composable
fun SettingHeader(title: String, subtitle: String, info: Info?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (info != null) InfoButton(info)
    }
}

/** "Now: X   Stock: Y" line, with the stock value highlighted when it differs. */
@Composable
fun NowStock(now: String, stock: String?) {
    val differs = stock != null && stock != now
    Text(
        "Now: $now" + if (stock != null) "   |   Stock: $stock" + if (differs) "  (changed)" else "" else "",
        style = MaterialTheme.typography.bodySmall,
        color = if (differs) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Warning colour for steps above stock: amber for overclock, red for "likely unstable". */
@Composable
fun ocColor(level: OcLevel) = when (level) {
    OcLevel.STOCK -> MaterialTheme.colorScheme.onSurface
    OcLevel.OVERCLOCK -> if (isSystemInDarkTheme()) Color(0xFFFFB74D) else Color(0xFFB45F00)
    OcLevel.UNSTABLE -> MaterialTheme.colorScheme.error
}

@Composable
fun FreqSlider(
    label: String,
    table: List<Int>,
    value: Int,
    fmt: (Int) -> String,
    level: (Int) -> OcLevel = { OcLevel.STOCK },
    onChange: (Int) -> Unit,
) {
    if (table.size < 2) { Text("$label: ${fmt(value)}"); return }
    val idx = table.indexOfFirst { it >= value }.let { if (it < 0) table.lastIndex else it }
    val lv = level(table[idx])
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$label: ${fmt(table[idx])}", style = MaterialTheme.typography.titleSmall, color = ocColor(lv))
        OcInfo.label(lv)?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = ocColor(lv), fontWeight = FontWeight.Bold) }
    }
    Slider(
        value = idx.toFloat(), onValueChange = { onChange(table[it.toInt().coerceIn(0, table.lastIndex)]) },
        valueRange = 0f..table.lastIndex.toFloat(), steps = (table.size - 2).coerceAtLeast(0),
        colors = SliderDefaults.colors(
            thumbColor = if (lv == OcLevel.STOCK) MaterialTheme.colorScheme.primary else ocColor(lv),
            activeTrackColor = if (lv == OcLevel.STOCK) MaterialTheme.colorScheme.primary else ocColor(lv),
        ),
    )
}
