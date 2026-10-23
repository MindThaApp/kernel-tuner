@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.kerneltuner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import app.kerneltuner.BuildConfig
import app.kerneltuner.core.ClusterSetting
import app.kerneltuner.core.GpuSetting
import app.kerneltuner.core.Profile
import app.kerneltuner.hw.GpuKind
import app.kerneltuner.hw.Memory
import app.kerneltuner.hw.ThermalCategory
import app.kerneltuner.core.settingsSummary

@Composable
private fun Page(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

private fun mhz(khz: Int?) = if (khz == null) "-" else "${khz / 1000} MHz"
private fun kb(v: Long) = "%.0f MB".format(v / 1024.0)

// ------------------------------------------------------------------ banners

@Composable
fun TestBanner(vm: MainViewModel) {
    val t = vm.test ?: return
    val left = ((t.deadlineWallMs - vm.nowMs) / 1000).coerceAtLeast(0)
    Card(Modifier.fillMaxWidth().padding(8.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Test mode: reverting to stock in ${left / 60}:${"%02d".format(left % 60)}", style = MaterialTheme.typography.titleSmall)
            Text(t.summary.joinToString("; "), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.keepTest() }) { Text("Keep") }
                OutlinedButton(onClick = { vm.revertTest() }) { Text("Revert now") }
            }
        }
    }
}

@Composable
fun NoticeBanner(vm: MainViewModel) {
    val n = vm.notice ?: return
    Card(Modifier.fillMaxWidth().padding(8.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Unclean reboot detected", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
            Text("Not applied at boot: " + n.settings.joinToString("; "), style = MaterialTheme.typography.bodySmall)
            Text(n.reason, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                vm.profiles.firstOrNull { it.id == n.profileId }?.let { p ->
                    Button(onClick = { vm.stepDown(p); vm.dismissNotice() }) { Text("Step down one notch") }
                }
                TextButton(onClick = { vm.dismissNotice() }) { Text("Dismiss") }
            }
        }
    }
}

// ------------------------------------------------------------------ dashboard

@Composable
fun DashboardScreen(vm: MainViewModel) {
    val owner = LocalLifecycleOwner.current
    // Polls only while this screen is visible and the app is STARTED.
    LaunchedEffect(owner, vm.hw) {
        if (vm.hw == null) return@LaunchedEffect
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                withContext(Dispatchers.IO) { vm.sample() }
                delay(1500)
            }
        }
    }
    val d = vm.dash
    Page {
        if (vm.hw == null || d == null) { Text("Detecting hardware..."); return@Page }
        val hw = vm.hw!!
        if (vm.rootOk == false) Text("No root access: showing read-only data; controls are disabled.", color = MaterialTheme.colorScheme.error)
        Section("CPU") {
            d.clusters.forEach { c ->
                Text("${c.label}: ${c.governor ?: "?"}  (${mhz(c.minKhz)} - ${mhz(c.maxKhz)})")
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                d.cores.forEach { c ->
                    Text("cpu${c.cpu}: " + (if (!c.online) "off" else mhz(c.khz)), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                }
            }
        }
        d.gpu?.let { g ->
            Section("GPU (${if (hw.gpu?.kind == GpuKind.KGSL) "Adreno / kgsl" else "Mali"})") {
                Text("${g.governor ?: "?"}  limits ${g.minMhz}-${g.maxMhz} MHz")
                Text("Clock: ${g.curMhz ?: "-"} MHz   Load: ${g.loadPct ?: "-"} %")
                g.loadPct?.let { LinearProgressIndicator(progress = { it.coerceIn(0, 100) / 100f }, modifier = Modifier.fillMaxWidth()) }
            }
        }
        Section("Temperatures") {
            if (d.temps.isEmpty()) Text("No readable sensors")
            ThermalCategory.entries.forEach { cat ->
                val l = d.temps.filter { it.first.category == cat }
                if (l.isNotEmpty()) Text("${cat.name.lowercase().replaceFirstChar { it.uppercase() }}: max %.1f C  (%d sensors)".format(l.maxOf { it.second }, l.size))
            }
            var open by remember { mutableStateOf(false) }
            TextButton(onClick = { open = !open }) { Text(if (open) "Hide sensors" else "All sensors") }
            if (open) d.temps.sortedByDescending { it.second }.forEach { (z, t) -> Text("${z.type}: %.1f C".format(t), fontSize = 13.sp, fontFamily = FontFamily.Monospace) }
        }
        d.mem?.let { m ->
            Section("Memory") {
                Text("RAM: ${kb(m.usedKb)} used of ${kb(m.totalKb)}  (${kb(m.availKb)} available)")
                LinearProgressIndicator(progress = { m.usedKb.toFloat() / m.totalKb }, modifier = Modifier.fillMaxWidth())
                if (m.swapTotalKb > 0) {
                    Text("Swap/zram: ${kb(m.swapUsedKb)} used of ${kb(m.swapTotalKb)}")
                    LinearProgressIndicator(progress = { m.swapUsedKb.toFloat() / m.swapTotalKb }, modifier = Modifier.fillMaxWidth())
                }
                d.zramRatio?.let { (o, c) -> if (c > 0) Text("zram compression: %.2f : 1".format(o.toDouble() / c)) }
            }
        }
    }
}

// ------------------------------------------------------------------ controls

@Composable
fun ControlsScreen(vm: MainViewModel) {
    val hw = vm.hw
    Page {
        if (hw == null) { Text("Detecting hardware..."); return@Page }
        if (vm.rootOk != true) Text("Root is required to change anything. Values below are read-only.", color = MaterialTheme.colorScheme.error)
        val d = vm.draft
        hw.clusters.forEach { c ->
            val cs = d.cpu[c.id] ?: ClusterSetting(null, c.hwMinKhz, c.hwMaxKhz)
            val stockMax = vm.stock?.cpu?.get(c.id)?.maxKhz ?: c.hwMaxKhz
            Section(c.label + if (c.layout == app.kerneltuner.hw.CpuLayout.PER_CPU) "  (per-CPU layout)" else "") {
                Text("Governor")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    c.governors.forEach { g ->
                        FilterChip(selected = cs.governor == g, onClick = { vm.draft = d.copy(cpu = d.cpu + (c.id to cs.copy(governor = g))) }, label = { Text(g) })
                    }
                }
                FreqSlider("Min", c.freqs, cs.minKhz ?: c.hwMinKhz, { mhz(it) }) { vm.draft = d.copy(cpu = d.cpu + (c.id to cs.copy(minKhz = it))) }
                FreqSlider("Max", c.freqs, cs.maxKhz ?: c.hwMaxKhz, { mhz(it) }) { vm.draft = d.copy(cpu = d.cpu + (c.id to cs.copy(maxKhz = it))) }
                Text("Stock max: ${mhz(stockMax)}", style = MaterialTheme.typography.bodySmall)
            }
        }
        hw.gpu?.let { g ->
            val gs = d.gpu ?: GpuSetting(null, g.minMhz, g.maxMhz)
            val table = g.freqs.map { it.mhz }
            Section("GPU (${if (g.kind == GpuKind.KGSL) "Adreno / kgsl" else "Mali"})") {
                Text("Governor")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    g.governors.forEach { n -> FilterChip(selected = gs.governor == n, onClick = { vm.draft = d.copy(gpu = gs.copy(governor = n)) }, label = { Text(n) }) }
                }
                FreqSlider("Min", table, gs.minMhz ?: g.minMhz, { "$it MHz" }) { vm.draft = d.copy(gpu = gs.copy(minMhz = it)) }
                FreqSlider("Max", table, gs.maxMhz ?: g.maxMhz, { "$it MHz" }) { vm.draft = d.copy(gpu = gs.copy(maxMhz = it)) }
                Text("Stock max: ${vm.stock?.gpu?.maxMhz ?: g.maxMhz} MHz", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (hw.blocks.isNotEmpty()) {
            Section("I/O scheduler") {
                hw.blocks.forEach { b ->
                    Text(b.name)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        b.schedulers.forEach { s -> FilterChip(selected = d.io[b.name] == s, onClick = { vm.draft = d.copy(io = d.io + (b.name to s)) }, label = { Text(s) }) }
                    }
                }
            }
        }
        val enabled = vm.rootOk == true && !vm.busy
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(enabled = enabled, onClick = { vm.requestApply(vm.draftTarget(), false) }) { Text("Apply") }
            OutlinedButton(enabled = enabled, onClick = { vm.requestApply(vm.draftTarget(), true) }) { Text("Test ${vm.testMinutes} min") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(enabled = enabled, onClick = { vm.revertToStock() }) { Text("Back to stock") }
            OutlinedButton(onClick = { vm.reload() }) { Text("Reset sliders") }
            OutlinedButton(onClick = { vm.redetect() }) { Text("Re-detect") }
        }
        Text("Changes revert on reboot unless a profile is set to apply on boot.", style = MaterialTheme.typography.bodySmall)
        var name by remember { mutableStateOf("") }
        Section("Save as profile") {
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { vm.saveProfile(name); name = "" }) { Text("Save current selection") }
        }
    }
}

@Composable
private fun FreqSlider(label: String, table: List<Int>, value: Int, fmt: (Int) -> String, onChange: (Int) -> Unit) {
    if (table.size < 2) { Text("$label: ${fmt(value)}"); return }
    val idx = table.indexOfFirst { it >= value }.let { if (it < 0) table.lastIndex else it }
    Text("$label: ${fmt(table[idx])}")
    Slider(
        value = idx.toFloat(), onValueChange = { onChange(table[it.toInt().coerceIn(0, table.lastIndex)]) },
        valueRange = 0f..table.lastIndex.toFloat(), steps = (table.size - 2).coerceAtLeast(0),
    )
}

// ------------------------------------------------------------------ memory

@Composable
fun MemoryScreen(vm: MainViewModel) {
    val hw = vm.hw
    Page {
        if (hw == null) { Text("Detecting hardware..."); return@Page }
        Text("These values belong to the ROM. They are shown read-only; overriding is a per-item opt-in and is lost at reboot unless saved in a profile.", style = MaterialTheme.typography.bodySmall)
        hw.memItems.forEach { m ->
            val cur = vm.memValues[m.key]
            Section(m.label) {
                Text("Current: ${cur ?: "n/a"}")
                if (!m.overridable) {
                    Text("Read-only (cannot be changed safely at runtime).", style = MaterialTheme.typography.bodySmall)
                } else {
                    val on = vm.memOverrides.containsKey(m.key)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Override", Modifier.weight(1f))
                        Switch(checked = on, onCheckedChange = { want ->
                            if (!want) { vm.memOverrides.remove(m.key); vm.memText.remove(m.key) }
                            else vm.confirm = Confirm("Override ${m.label}?",
                                "The ROM chose this value on purpose. Changing it can cause lag, kills of background apps or storage stalls. It reverts at reboot.") {
                                val v = cur?.toLongOrNull() ?: m.min
                                vm.memOverrides[m.key] = v; vm.memText[m.key] = v.toString()
                            }
                        })
                    }
                    if (on) {
                        OutlinedTextField(
                            vm.memText[m.key] ?: "", { t -> vm.memText[m.key] = t; t.toLongOrNull()?.let { vm.memOverrides[m.key] = it.coerceIn(m.min, m.max) } },
                            label = { Text("Value (${m.min}-${m.max})") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
        Button(enabled = vm.rootOk == true && vm.memOverrides.isNotEmpty() && !vm.busy, onClick = { vm.applyMemory() }) { Text("Apply enabled overrides") }
    }
}

// ------------------------------------------------------------------ profiles

@Composable
fun ProfilesScreen(vm: MainViewModel) {
    Page {
        if (vm.profiles.isEmpty()) Text("No profiles yet. Set up Controls and use \"Save as profile\".")
        vm.profiles.forEach { p -> ProfileCard(vm, p) }
    }
}

@Composable
private fun ProfileCard(vm: MainViewModel, p: Profile) {
    Section(p.name + if (p.unstable) "  [UNSTABLE]" else "") {
        Text(p.settingsSummary().joinToString("\n").ifEmpty { "(empty)" }, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        if (p.unstable) {
            Text("Marked unstable after an unclean reboot: ${p.unstableReason ?: ""}. It will not apply on boot.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.stepDown(p) }) { Text("Step down one notch") }
                OutlinedButton(onClick = { vm.clearUnstable(p) }) { Text("Mark stable") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Apply on boot", Modifier.weight(1f))
            Switch(checked = p.applyOnBoot, enabled = !p.unstable, onCheckedChange = { vm.setBoot(p, it) })
        }
        val en = vm.rootOk == true && !vm.busy
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = en, onClick = { vm.applyProfile(p, false) }) { Text("Apply") }
            OutlinedButton(enabled = en, onClick = { vm.applyProfile(p, true) }) { Text("Test") }
            TextButton(onClick = { vm.deleteProfile(p) }) { Text("Delete") }
        }
    }
}

// ------------------------------------------------------------------ about

@Composable
fun AboutScreen(vm: MainViewModel) {
    Page {
        Section("Kernel Tuner ${BuildConfig.VERSION_NAME}") {
            Text("A hardware-agnostic kernel tuning tool. Nothing runs in the background; the app only reads and writes while open, plus short receivers at boot and shutdown.")
            Text("Root: " + when (vm.rootOk) { true -> "granted"; false -> "not available"; null -> "checking" })
            val hw = vm.hw
            if (hw != null) {
                Text("Detected: ${hw.clusters.size} CPU cluster(s), GPU ${hw.gpu?.kind ?: "none"}, ${hw.thermal.size} sensors, ${hw.blocks.size} block devices", style = MaterialTheme.typography.bodySmall)
            }
        }
        Section("Test mode") {
            Text("Auto-revert after ${vm.testMinutes} min unless you tap Keep.")
            Slider(value = vm.testMinutes.toFloat(), onValueChange = { vm.updateTestMinutes(it.toInt()) }, valueRange = 1f..15f, steps = 13)
        }
        Section("Last crash log") {
            val l = vm.crashLog
            if (l == null) Text("None recorded.") else {
                Text(l, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                TextButton(onClick = { vm.clearCrashLog() }) { Text("Clear") }
            }
        }
        Section("Credits and licence") {
            Text("Kernel Tuner is a fork of RvKernel Manager by Rve (https://github.com/Rve27/RvKernel-Manager) and its contributors, licensed under the GNU General Public License v3.0 or later. This program comes with ABSOLUTELY NO WARRANTY. Modified versions must remain under the same licence and the source must be made available.")
            Text("Root access uses libsu by John Wu (Apache-2.0).", style = MaterialTheme.typography.bodySmall)
        }
    }
}
