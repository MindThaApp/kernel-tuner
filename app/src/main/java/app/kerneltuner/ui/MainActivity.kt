package app.kerneltuner.ui

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        vm.init()
        setContent {
            val ctx = LocalContext.current
            val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            MaterialTheme(colorScheme = scheme) { AppRoot(vm) }
        }
    }
}

private val TABS = listOf("Home", "Tune", "Profiles", "Safety", "About")

@Composable
private fun AppRoot(vm: MainViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snack = remember { SnackbarHostState() }
    val owner = LocalLifecycleOwner.current

    // The countdown ticks only while the app is on screen. The AlarmManager revert covers the rest.
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.refreshOnShow()
            while (true) { vm.tick(); delay(1000) }
        }
    }
    LaunchedEffect(vm.message) {
        vm.message?.let { snack.showSnackbar(it); vm.message = null }
    }

    Scaffold(snackbarHost = { SnackbarHost(snack) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
                TABS.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
            }
            TestBanner(vm)
            NoticeBanner(vm)
            when (tab) {
                0 -> DashboardScreen(vm) { tab = it }
                1 -> ControlsScreen(vm)
                2 -> ProfilesScreen(vm)
                3 -> SafetyScreen(vm)
                else -> AboutScreen(vm)
            }
        }
    }

    vm.confirm?.let { c ->
        AlertDialog(
            onDismissRequest = { vm.confirm = null },
            title = { Text(c.title) },
            text = { Text(c.body) },
            confirmButton = { TextButton(onClick = { vm.confirm = null; c.action() }) { Text("Continue") } },
            dismissButton = { TextButton(onClick = { vm.confirm = null }) { Text("Cancel") } },
        )
    }
}
