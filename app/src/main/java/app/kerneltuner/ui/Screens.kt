@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.kerneltuner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.kerneltuner.BuildConfig
import app.kerneltuner.core.ClusterSetting
import app.kerneltuner.core.Fmt
import app.kerneltuner.core.GpuSetting
import app.kerneltuner.core.Help
import app.kerneltuner.core.OcInfo
import app.kerneltuner.core.OcLevel
import app.kerneltuner.core.Preset
import app.kerneltuner.hw.TempKind
import app.kerneltuner.core.Profile
import app.kerneltuner.core.Settings
import app.kerneltuner.core.settingsSummary
import app.kerneltuner.hw.CpuCluster
import app.kerneltuner.hw.GpuKind
import app.kerneltuner.hw.Hardware
import app.kerneltuner.hw.ThermalCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private fun clusterTitle(hw: Hardware, c: CpuCluster) =
    Help.clusterTitle(c.id, hw.clusters.associate { it.id to it.hwMaxKhz }, c.cpus)

private fun gpuTitle(kind: GpuKind) = if (kind == GpuKind.KGSL) "Graphics (Adreno GPU)" else "Graphics (Mali GPU)"

// ------------------------------------------------------------------ banners

@Composable
fun TestBanner(vm: MainViewModel) {
    val t = vm.test ?: return
    val left = ((t.deadlineWallMs - vm.nowMs) / 1000).coerceAtLeast(0)
    Card(Modifier.fillMaxWidth().padding(8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Test mode running: back to stock in ${Fmt.countdown(left)}", style = MaterialTheme.typography.titleSmall)
            Text(t.summary.joinToString("; "), style = MaterialTheme.typography.bodySmall)
            Text("Happy with it? Tap Keep. Otherwise it undoes itself.", style = MaterialTheme.typography.bodySmall)
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
    Card(Modifier.fillMaxWidth().padding(8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("The phone restarted unexpectedly", style = MaterialTheme.typography.titleSmall)
            Text("Your settings were NOT re-applied: " + n.settings.joinToString("; "), style = MaterialTheme.typography.bodySmall)
            Text("Reason: " + n.reason, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                vm.profiles.firstOrNull { it.id == n.profileId }?.let { p ->
                    Button(onClick = { vm.stepDown(p); vm.dismissNotice() }) { Text("Lower it one step") }
                }
                TextButton(onClick = { vm.dismissNotice() }) { Text("Dismiss") }
            }
        }
    }
}

// ------------------------------------------------------------------ dashboard

@Composable
fun DashboardScreen(vm: MainViewModel, go: (Int) -> Unit) {
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

        // Status chips
        val boot = vm.profiles.firstOrNull { it.applyOnBoot }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = {}, label = { Text(if (vm.rootOk == true) "Root: OK" else if (vm.rootOk == false) "No root (read-only)" else "Checking root") })
            AssistChip(onClick = { go(1) }, label = { Text(if (vm.test != null) "Test mode running" else if (vm.activeRecord != null) "Settings: modified" else "Settings: stock") })
            AssistChip(onClick = { go(2) }, label = { Text(if (boot != null) "Apply on boot: ON" else "Apply on boot: off") })
        }
        if (vm.rootOk == false) Text("No root access: showing read-only data; controls are disabled.", color = MaterialTheme.colorScheme.error)

        // Temperatures
        Section("Temperature", "Cooler is better. Phones slow themselves down when they get too hot.") {
            if (d.temps.isEmpty()) Text("No readable sensors")
            val hottest = d.temps.maxByOrNull { it.second }
            if (hottest != null) {
                Text("${Fmt.celsius(hottest.second)}  -  ${Fmt.heat(hottest.second)}", style = MaterialTheme.typography.headlineSmall,
                    color = if (hottest.second >= 45) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                Text("Hottest sensor: ${hottest.first.type}", style = MaterialTheme.typography.bodySmall)
            }
            ThermalCategory.entries.forEach { cat ->
                val l = d.temps.filter { it.first.category == cat }
                if (l.isNotEmpty()) {
                    val name = when (cat) { ThermalCategory.CPU -> "Processor"; ThermalCategory.GPU -> "Graphics"; ThermalCategory.BATTERY -> "Battery sensor"; ThermalCategory.OTHER -> "Other" }
                    val m = l.maxOf { it.second }
                    Text("$name: ${Fmt.celsius(m)} (${Fmt.heat(m)})")
                }
            }
            var open by remember { mutableStateOf(false) }
            TextButton(onClick = { open = !open }) { Text(if (open) "Hide all sensors" else "Show all sensors") }
            if (open) d.temps.sortedByDescending { it.second }.forEach { (z, t) -> Text("${z.type}: ${Fmt.celsius(t)}", fontSize = 13.sp, fontFamily = FontFamily.Monospace) }
        }

        // Battery
        d.battery?.let { b ->
            Section("Battery") {
                Text("${b.pct} %  -  " + (if (b.full) "Full" else if (b.charging) "Charging" else "Not charging / discharging"), style = MaterialTheme.typography.titleLarge)
                LinearProgressIndicator(progress = { b.pct / 100f }, modifier = Modifier.fillMaxWidth())
                Text(listOfNotNull(b.tempC?.let { "Temperature ${Fmt.celsius(it)}" }, b.voltageMv?.let { "%.2f V".format(it / 1000.0) }).joinToString("   |   "), style = MaterialTheme.typography.bodySmall)
            }
        }

        // Frequencies
        Section("Processor speed right now", "Higher bar = working harder = more battery and heat.") {
            hw.clusters.forEach { c ->
                val live = d.cores.filter { it.cpu in c.cpus && it.online && it.khz != null }
                val top = live.maxOfOrNull { it.khz!! }
                val cl = d.clusters.firstOrNull { it.label == c.label }
                Text(clusterTitle(hw, c), style = MaterialTheme.typography.titleSmall)
                Text("${Fmt.khz(top)} of ${Fmt.khz(c.hwMaxKhz)}   |   allowed ${Fmt.khz(cl?.minKhz)} - ${Fmt.khz(cl?.maxKhz)}   |   ${cl?.governor ?: "?"}", style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(progress = { ((top ?: 0).toFloat() / c.hwMaxKhz).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    d.cores.filter { it.cpu in c.cpus }.forEach { core ->
                        Text("cpu${core.cpu}: " + (if (!core.online) "off" else Fmt.khz(core.khz)), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                }
            }
            d.gpu?.let { g ->
                Text(gpuTitle(hw.gpu!!.kind), style = MaterialTheme.typography.titleSmall)
                Text("${Fmt.mhz(g.curMhz)}   |   load ${g.loadPct ?: "-"} %   |   allowed ${Fmt.mhz(g.minMhz)} - ${Fmt.mhz(g.maxMhz)}   |   ${g.governor ?: "?"}", style = MaterialTheme.typography.bodySmall)
                g.loadPct?.let { LinearProgressIndicator(progress = { it.coerceIn(0, 100) / 100f }, modifier = Modifier.fillMaxWidth()) }
            }
        }

        d.mem?.let { m ->
            Section("Memory", "How much RAM apps are using. Android keeps spare RAM busy on purpose, so look at \"available\".") {
                Text("${Fmt.mem(m.usedKb)} used of ${Fmt.mem(m.totalKb)}  (${Fmt.mem(m.availKb)} available)")
                LinearProgressIndicator(progress = { m.usedKb.toFloat() / m.totalKb }, modifier = Modifier.fillMaxWidth())
                if (m.swapTotalKb > 0) {
                    Text("Compressed RAM (zram): ${Fmt.mem(m.swapUsedKb)} used of ${Fmt.mem(m.swapTotalKb)}")
                    LinearProgressIndicator(progress = { m.swapUsedKb.toFloat() / m.swapTotalKb }, modifier = Modifier.fillMaxWidth())
                }
                d.zramRatio?.let { (o, c) -> if (c > 0) Text("zram squeezes data %.1f : 1".format(o.toDouble() / c), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

// ------------------------------------------------------------------ tune

@Composable
fun ControlsScreen(vm: MainViewModel) {
    val hw = vm.hw
    Page {
        if (hw == null) { Text("Detecting hardware..."); return@Page }
        if (vm.rootOk != true) Text("Root is required to change anything. Values below are read-only.", color = MaterialTheme.colorScheme.error)
        val d = vm.draft
        val enabled = vm.rootOk == true && !vm.busy

        Text(
            "Move a slider or pick an option, then press \"Try for ${vm.testMinutes} min\". Nothing changes until you press a button. " +
                "\"Stock\" means the value your phone had when it booted.",
            style = MaterialTheme.typography.bodySmall,
        )

        Section("Quick presets", "Fill in the controls with safe, stock-range values. Nothing is applied yet.") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Preset.values().forEach { pr ->
                    OutlinedButton(enabled = vm.stock != null, onClick = { vm.usePreset(pr) }) { Text(pr.title) }
                }
            }
            SettingHeader("What do they do?", Help.presets.subtitle, Help.presets)
        }

        val anyOc = hw.clusters.any { c -> c.freqs.any { OcInfo.cpuLevel(c, it, vm.stock?.cpu?.get(c.id)?.maxKhz) != OcLevel.STOCK } } ||
            hw.gpu?.let { g -> g.freqs.any { OcInfo.gpuLevel(g, it.mhz, vm.stock?.gpu?.maxMhz) != OcLevel.STOCK } } == true
        if (anyOc) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SettingHeader("This kernel has overclock steps", "Steps above stock are marked \"Overclock\". Go up one step at a time and use \"Try for ${vm.testMinutes} min\".", Help.overclock)
                }
            }
        }

        hw.clusters.forEach { c ->
            val cs = d.cpu[c.id] ?: ClusterSetting(null, c.hwMinKhz, c.hwMaxKhz)
            val stock = vm.stock?.cpu?.get(c.id)
            val cur = vm.current.cpu[c.id]
            Section(clusterTitle(hw, c), if (hw.clusters.size > 1) "A group of cores. Performance cores do the heavy work; efficiency cores handle light tasks." else "The phone's main processor cores.") {
                SettingHeader("Governor", Help.cpuGovernor.subtitle, Help.cpuGovernor)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    c.governors.forEach { g ->
                        FilterChip(selected = cs.governor == g, onClick = { vm.draft = d.copy(cpu = d.cpu + (c.id to cs.copy(governor = g))) }, label = { Text(g) })
                    }
                }
                cs.governor?.let { Text(Help.governor(it, false), style = MaterialTheme.typography.bodySmall) }
                NowStock(cur?.governor ?: "?", stock?.governor)

                SettingHeader("Minimum speed", Help.cpuMin.subtitle, Help.cpuMin)
                FreqSlider("Min", c.freqs, cs.minKhz ?: c.hwMinKhz, { Fmt.khz(it) }) { vm.draft = d.copy(cpu = d.cpu + (c.id to cs.copy(minKhz = it))) }
                NowStock(Fmt.khz(cur?.minKhz), stock?.minKhz?.let { Fmt.khz(it) })

                SettingHeader("Maximum speed", Help.cpuMax.subtitle, Help.cpuMax)
                FreqSlider("Max", c.freqs, cs.maxKhz ?: c.hwMaxKhz, { Fmt.khz(it) }, { OcInfo.cpuLevel(c, it, stock?.maxKhz) }) { vm.draft = d.copy(cpu = d.cpu + (c.id to cs.copy(maxKhz = it))) }
                NowStock(Fmt.khz(cur?.maxKhz), stock?.maxKhz?.let { Fmt.khz(it) })

                if (stock != null) ResetButton(enabled && cur != null && cur != stock) { vm.resetToStock(Settings(cpu = mapOf(c.id to stock))) }
            }
        }

        hw.gpu?.let { g ->
            val gs = d.gpu ?: GpuSetting(null, g.minMhz, g.maxMhz)
            val table = g.freqs.map { it.mhz }
            val stock = vm.stock?.gpu
            val cur = vm.current.gpu
            Section(gpuTitle(g.kind), "Draws the screen and runs games.") {
                SettingHeader("Governor", Help.gpuGovernor.subtitle, Help.gpuGovernor)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    g.governors.forEach { n -> FilterChip(selected = gs.governor == n, onClick = { vm.draft = d.copy(gpu = gs.copy(governor = n)) }, label = { Text(n) }) }
                }
                gs.governor?.let { Text(Help.governor(it, true), style = MaterialTheme.typography.bodySmall) }
                NowStock(cur?.governor ?: "?", stock?.governor)

                SettingHeader("Minimum speed", Help.gpuMin.subtitle, Help.gpuMin)
                FreqSlider("Min", table, gs.minMhz ?: g.minMhz, { Fmt.mhz(it) }) { vm.draft = d.copy(gpu = gs.copy(minMhz = it)) }
                NowStock(Fmt.mhz(cur?.minMhz), stock?.minMhz?.let { Fmt.mhz(it) })

                SettingHeader("Maximum speed", Help.gpuMax.subtitle, Help.gpuMax)
                FreqSlider("Max", table, gs.maxMhz ?: g.maxMhz, { Fmt.mhz(it) }, { OcInfo.gpuLevel(g, it, stock?.maxMhz) }) { vm.draft = d.copy(gpu = gs.copy(maxMhz = it)) }
                NowStock(Fmt.mhz(cur?.maxMhz), stock?.maxMhz?.let { Fmt.mhz(it) })

                if (stock != null) ResetButton(enabled && cur != null && cur != stock) { vm.resetToStock(Settings(gpu = stock)) }
            }
        }

        if (hw.tempGroups.isNotEmpty()) {
            Section("Temperature limit", "How warm the phone may get before it slows itself down.") {
                hw.tempGroups.forEach { tg ->
                    val stockC = vm.stock?.temp?.get(tg.id) ?: tg.detectedC
                    val info = if (tg.kind == TempKind.IPA) Help.tempIpa else Help.tempTrips
                    val top = if (tg.kind == TempKind.TRIPS) minOf(tg.maxC, stockC) else tg.maxC
                    val lo = minOf(tg.minC, top)
                    val value = (d.temp[tg.id] ?: vm.current.temp[tg.id] ?: stockC).coerceIn(lo, top)
                    SettingHeader(tg.label, if (tg.kind == TempKind.IPA) "Lower = cooler and slower sooner. Higher = faster for longer, hotter." else "Lower = slows down earlier, stays cooler. Never above stock.", info)
                    val warm = value > stockC
                    Text("Limit: $value \u00B0C" + if (value == stockC) " (stock)" else if (warm) " (hotter than stock)" else " (cooler than stock)",
                        style = MaterialTheme.typography.titleSmall,
                        color = if (warm) ocColor(OcLevel.OVERCLOCK) else MaterialTheme.colorScheme.onSurface)
                    if (top > lo) Slider(
                        value = value.toFloat(), onValueChange = { vm.draft = d.copy(temp = d.temp + (tg.id to it.toInt().coerceIn(lo, top))) },
                        valueRange = lo.toFloat()..top.toFloat(), steps = (top - lo - 1).coerceAtLeast(0),
                    )
                    val curT = vm.current.temp[tg.id]
                    NowStock(curT?.let { "$it \u00B0C" } ?: "?", "$stockC \u00B0C")
                    if (curT != null) ResetButton(enabled && curT != stockC) { vm.resetToStock(Settings(temp = mapOf(tg.id to stockC))) }
                }
                Text(
                    if (hw.tempGroups.any { it.kind == TempKind.IPA })
                        "Android's power manager changes this value when the power mode changes. Kernel Tuner puts your value back while it is applied."
                    else "Android's own thermal service may still slow the phone earlier than this limit. That is normal.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // Apply / test
        val dirty = d != vm.current
        Section("Apply", if (dirty) "You have changes that are not applied yet." else "Nothing to apply: the controls match the phone.") {
            Button(enabled = enabled && dirty, onClick = { vm.requestApply(vm.draftTarget(), true) }, modifier = Modifier.fillMaxWidth()) { Text("Try for ${vm.testMinutes} min (recommended)") }
            Text("Test mode applies the changes, then puts everything back to stock after ${vm.testMinutes} minutes unless you tap Keep. If anything goes wrong, a restart also undoes it.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(enabled = enabled && dirty, onClick = { vm.requestApply(vm.draftTarget(), false) }, modifier = Modifier.fillMaxWidth()) { Text("Apply until next restart") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(enabled = enabled, onClick = { vm.revertToStock() }) { Text("Reset everything to stock") }
                TextButton(onClick = { vm.reload() }) { Text("Undo my edits") }
            }
            Text("Changes disappear when the phone restarts, unless you save them in a profile and turn on Apply on boot.", style = MaterialTheme.typography.bodySmall)
        }

        var name by remember { mutableStateOf("") }
        Section("Save as profile", "Keep this combination so you can re-apply it with one tap.") {
            OutlinedTextField(name, { name = it }, label = { Text("Profile name, e.g. Battery saver") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { vm.saveProfile(name); name = "" }) { Text("Save current selection") }
        }

        Collapsible("Advanced", "Storage and memory settings. Most people never need these.") {
            if (hw.blocks.isNotEmpty()) {
                SettingHeader("Storage scheduler", Help.ioScheduler.subtitle, Help.ioScheduler)
                hw.blocks.forEach { b ->
                    Text(b.name, style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        b.schedulers.forEach { s -> FilterChip(selected = d.io[b.name] == s, onClick = { vm.draft = d.copy(io = d.io + (b.name to s)) }, label = { Text(s) }) }
                    }
                    d.io[b.name]?.let { Text(Help.scheduler(it), style = MaterialTheme.typography.bodySmall) }
                    val sIo = vm.stock?.io?.get(b.name)
                    val cIo = vm.current.io[b.name]
                    NowStock(cIo ?: "?", sIo)
                    if (sIo != null) ResetButton(enabled && cIo != null && cIo != sIo) { vm.resetToStock(Settings(io = mapOf(b.name to sIo))) }
                }
            }
            MemoryBlock(vm, hw)
        }
    }
}

@Composable
private fun ResetButton(enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(enabled = enabled, onClick = onClick) { Text("Reset to stock") }
}

@Composable
private fun MemoryBlock(vm: MainViewModel, hw: Hardware) {
    Text("Memory values", style = MaterialTheme.typography.titleMedium)
    Text("These belong to the ROM and are shown read-only. Overriding is a per-item choice, is lost at restart unless saved in a profile, and can cause lag or closed apps.", style = MaterialTheme.typography.bodySmall)
    hw.memItems.forEach { m ->
        val cur = vm.memValues[m.key]
        val info = Help.memory(m.key)
        val stockV = vm.stock?.mem?.get(m.key)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SettingHeader(Help.memoryTitle(m.key, m.label), info.subtitle, info)
            val shown = when {
                cur == null -> "n/a"
                m.key == "zram_disksize" -> cur.trim().toLongOrNull()?.let { Fmt.bytes(it) } ?: cur
                else -> cur
            }
            NowStock(shown, stockV?.toString())
            if (!m.overridable) {
                Text("Read-only (cannot be changed safely while running).", style = MaterialTheme.typography.bodySmall)
            } else {
                val on = vm.memOverrides.containsKey(m.key)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Override this value", Modifier.weight(1f))
                    Switch(checked = on, onCheckedChange = { want ->
                        if (!want) { vm.memOverrides.remove(m.key); vm.memText.remove(m.key) }
                        else vm.confirm = Confirm("Override ${Help.memoryTitle(m.key, m.label)}?",
                            "The ROM chose this value on purpose. Changing it can cause lag, closed background apps or storage stalls. It resets at restart.") {
                            val v = cur?.toLongOrNull() ?: m.min
                            vm.memOverrides[m.key] = v; vm.memText[m.key] = v.toString()
                        }
                    })
                }
                if (on) {
                    OutlinedTextField(
                        vm.memText[m.key] ?: "", { t -> vm.memText[m.key] = t; t.toLongOrNull()?.let { vm.memOverrides[m.key] = it.coerceIn(m.min, m.max) } },
                        label = { Text("Value (${m.min} to ${m.max})") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (stockV != null) ResetButton(vm.rootOk == true && !vm.busy && cur?.trim()?.toLongOrNull() != stockV) {
                    vm.memOverrides.remove(m.key); vm.memText.remove(m.key)
                    vm.resetToStock(Settings(mem = mapOf(m.key to stockV)))
                }
            }
        }
    }
    Button(enabled = vm.rootOk == true && vm.memOverrides.isNotEmpty() && !vm.busy, onClick = { vm.applyMemory() }) { Text("Apply memory overrides") }
}

// ------------------------------------------------------------------ profiles

@Composable
fun ProfilesScreen(vm: MainViewModel) {
    Page {
        val boot = vm.profiles.firstOrNull { it.applyOnBoot }
        Section("Profiles", "A profile is a saved set of Tune settings you can re-apply with one tap.") {
            SettingHeader(
                "Apply on boot: " + if (boot != null) "ON for \"${boot.name}\"" else "OFF",
                Help.applyOnBoot.subtitle, Help.applyOnBoot,
            )
        }
        if (vm.profiles.isEmpty()) Text("No profiles yet. Set things up on the Tune tab, then use \"Save as profile\".")
        vm.profiles.forEach { p -> ProfileCard(vm, p) }
    }
}

@Composable
private fun ProfileCard(vm: MainViewModel, p: Profile) {
    Section(p.name + if (p.unstable) "  [UNSTABLE]" else "") {
        Text(p.settingsSummary().joinToString("\n").ifEmpty { "(empty)" }, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        if (p.unstable) {
            Text("Marked unstable after an unexpected restart: ${p.unstableReason ?: ""}. It will not apply on boot.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.stepDown(p) }) { Text("Lower it one step") }
                OutlinedButton(onClick = { vm.clearUnstable(p) }) { Text("Mark stable") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Apply on boot")
                Text(if (p.applyOnBoot) "ON: applied after each clean restart" else "Off (default)", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = p.applyOnBoot, enabled = !p.unstable, onCheckedChange = { vm.setBoot(p, it) })
        }
        val en = vm.rootOk == true && !vm.busy
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = en, onClick = { vm.applyProfile(p, true) }) { Text("Try ${vm.testMinutes} min") }
            OutlinedButton(enabled = en, onClick = { vm.applyProfile(p, false) }) { Text("Apply") }
            TextButton(onClick = { vm.deleteProfile(p) }) { Text("Delete") }
        }
    }
}

// ------------------------------------------------------------------ safety

@Composable
private fun StatusRow(label: String, value: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(value, style = MaterialTheme.typography.bodySmall, color = if (ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
fun SafetyScreen(vm: MainViewModel) {
    Page {
        val boot = vm.profiles.firstOrNull { it.applyOnBoot }
        val t = vm.test
        Section("Safety status", "Everything here is on by default. Changes can never survive a restart on their own.") {
            StatusRow("Test mode",
                if (t != null) "Running now. Reverts to stock in ${Fmt.countdown(((t.deadlineWallMs - vm.nowMs) / 1000).coerceAtLeast(0))}." else "Ready. Used when you press \"Try for ${vm.testMinutes} min\".", true)
            StatusRow("Revert on bad reboot",
                if (vm.notice != null) "Attention: an unexpected restart happened and your settings were not re-applied. See the red banner above." else "Protected. No unexpected restart has been detected.",
                vm.notice == null)
            StatusRow("Apply on boot",
                when { boot == null -> "Off (default). Every restart starts from stock."; boot.unstable -> "Blocked: \"${boot.name}\" was marked unstable."; else -> "On for \"${boot.name}\"." },
                boot?.unstable != true)
            StatusRow("Current settings",
                vm.activeRecord?.let { "Modified: " + it.summary.joinToString("; ") } ?: "Stock. Nothing is changed right now.", true)
        }
        Section("Test mode", Help.testMode.subtitle) {
            InfoRow(Help.testMode)
            Text("Length of the test: ${vm.testMinutes} min (default 3)")
            Slider(value = vm.testMinutes.toFloat(), onValueChange = { vm.updateTestMinutes(it.toInt()) }, valueRange = 1f..15f, steps = 13)
            Text("Short is safer: a freeze or hot phone resolves itself quickly.", style = MaterialTheme.typography.bodySmall)
        }
        Section("Revert on bad reboot", "If the phone restarts unexpectedly, your settings are not applied again.") {
            Text(
                "Kernel Tuner notes whether the last shutdown was clean, and checks the crash records the kernel keeps. If the phone crashed while non-stock settings were active, " +
                    "the boot profile is skipped, marked unstable, and you are told. You can then lower it one step or turn it off.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Section("Apply on boot", Help.applyOnBoot.subtitle) { InfoRow(Help.applyOnBoot) }
        Section("Last crash log") {
            val l = vm.crashLog
            if (l == null) Text("None recorded.") else {
                Text(l, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                TextButton(onClick = { vm.clearCrashLog() }) { Text("Clear") }
            }
        }
    }
}

@Composable
private fun InfoRow(info: app.kerneltuner.core.Info) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("What is this?", Modifier.weight(1f))
        InfoButton(info)
    }
}

// ------------------------------------------------------------------ about

@Composable
fun AboutScreen(vm: MainViewModel) {
    Page {
        Section("Kernel Tuner ${BuildConfig.VERSION_NAME}") {
            Text("A tuning tool for rooted phones. Nothing runs in the background; the app only reads and writes while open, plus short receivers at boot and shutdown. No network access, no analytics, no ads.")
            Text("Root: " + when (vm.rootOk) { true -> "granted"; false -> "not available"; null -> "checking" })
            val hw = vm.hw
            if (hw != null) {
                Text("Detected: ${hw.clusters.size} CPU group(s), GPU ${hw.gpu?.kind ?: "none"}, ${hw.thermal.size} temperature sensors, ${hw.blocks.size} storage devices", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = { vm.redetect() }) { Text("Re-detect hardware") }
        }
        Section("Credits and licence") {
            Text("Kernel Tuner is a fork of RvKernel Manager by Rve (https://github.com/Rve27/RvKernel-Manager) and its contributors, licensed under the GNU General Public License v3.0 or later. This program comes with ABSOLUTELY NO WARRANTY. Modified versions must remain under the same licence and the source must be made available.")
            Text("Root access uses libsu by John Wu (Apache-2.0).", style = MaterialTheme.typography.bodySmall)
        }
    }
}
