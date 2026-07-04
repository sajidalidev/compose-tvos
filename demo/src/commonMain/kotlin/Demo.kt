package demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import demo.generated.resources.Res
import demo.generated.resources.app_name
import demo.generated.resources.counter_label
import demo.generated.resources.logo
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import platform.UIKit.UIViewController

/**
 * End-to-end proof for the tvOS redirect plugin: a Material3 screen driven by
 * navigation-compose + lifecycle-viewmodel-compose, with a compose-resources drawable and
 * string, compiled/linked for tvosArm64/tvosSimulatorArm64 through the one-line
 * `dev.sajidali.compose-tvos` settings plugin (and, via iosArm64, proven to leave iOS
 * untouched).
 */
private class CounterViewModel : androidx.lifecycle.ViewModel() {
    var count by mutableIntStateOf(0)
        private set

    fun increment() {
        count++
    }
}

@Composable
fun App() {
    MaterialTheme {
        val navController = rememberNavController()
        NavHost(navController = navController, startDestination = "home") {
            composable("home") { HomeScreen() }
        }
    }
}

@Composable
private fun HomeScreen() {
    val viewModel = viewModel { CounterViewModel() }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            androidx.compose.foundation.Image(
                painter = painterResource(Res.drawable.logo),
                contentDescription = stringResource(Res.string.app_name)
            )
            Text(stringResource(Res.string.app_name))
            Text(stringResource(Res.string.counter_label, viewModel.count))
            Button(onClick = { viewModel.increment() }) {
                Text("Click")
            }
        }
    }
}

/**
 * tvOS/iOS entry point. Framework consumers (Xcode) call this from their own
 * `UIHostingController`/root view controller wiring.
 */
@Suppress("unused")
fun MainViewController(): UIViewController = ComposeUIViewController { App() }
