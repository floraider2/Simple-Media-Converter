package com.simpleconverter.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.simpleconverter.app.ui.ConverterApp
import com.simpleconverter.app.ui.ConverterViewModel
import com.simpleconverter.app.ui.theme.SimpleConverterTheme

class MainActivity : ComponentActivity() {

    private val vm: ConverterViewModel by viewModels()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* optional, App läuft auch ohne */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) vm.handleIntent(intent)
        askForNotifications()
        setContent {
            SimpleConverterTheme {
                ConverterApp(vm)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.onAppVisible()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        vm.handleIntent(intent)
    }

    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
