package com.simpleconverter.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simpleconverter.app.data.ThemeMode
import com.simpleconverter.app.ui.ConverterApp
import com.simpleconverter.app.ui.ConverterViewModel
import com.simpleconverter.app.ui.LocalShowThumbnails
import com.simpleconverter.app.ui.theme.SimpleConverterTheme

class MainActivity : ComponentActivity() {

    private val vm: ConverterViewModel by viewModels()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* optional, App läuft auch ohne */ }

    private var debugFontScale by mutableStateOf<Float?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readDebugFontScale(intent)
        enableEdgeToEdge()
        if (savedInstanceState == null) vm.handleIntent(intent)
        askForNotifications()
        setContent {
            val settings by vm.appSettings.collectAsStateWithLifecycle()
            val dark = when (settings.theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // Statusleisten-Symbole passend zum App-Design (nicht zum System) einfärben.
            DisposableEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            SimpleConverterTheme(settings.theme) {
                // Nur Debug-Build: Schriftgröße für Barrierefreiheits-Tests überschreiben
                // (adb shell am start … --ef debugFontScale 2.0), ohne die Systemeinstellung zu ändern.
                val density = LocalDensity.current
                val scaled = debugFontScale?.let { Density(density.density, it) } ?: density
                CompositionLocalProvider(LocalShowThumbnails provides settings.showThumbnails, LocalDensity provides scaled) {
                    ConverterApp(vm)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.onAppVisible()
    }

    private fun readDebugFontScale(intent: Intent?) {
        if (BuildConfig.DEBUG && intent?.hasExtra(EXTRA_DEBUG_FONT_SCALE) == true) {
            debugFontScale = intent.getFloatExtra(EXTRA_DEBUG_FONT_SCALE, 1f).takeIf { it != 1f }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readDebugFontScale(intent)
        vm.handleIntent(intent)
    }

    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private companion object {
        const val EXTRA_DEBUG_FONT_SCALE = "debugFontScale"
    }
}
