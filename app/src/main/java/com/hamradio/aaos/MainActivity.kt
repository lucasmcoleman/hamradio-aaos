package com.hamradio.aaos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import com.hamradio.aaos.ui.AppNavigation
import com.hamradio.aaos.ui.theme.HamRadioTheme
import com.hamradio.aaos.vm.MainViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HamRadioTheme {
                // Auto-connect on launch
                LaunchedEffect(Unit) { vm.connect() }
                // Restart activity when transport mode changes
                LaunchedEffect(Unit) {
                    vm.restartEvent.collect { recreate() }
                }
                AppNavigation(vm = vm)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Only on a real exit. A recreate keeps the ViewModel and the link; disconnecting here raced
        // the new composition's connect() and could leave the radio disconnected.
        if (isFinishing) vm.disconnect()
    }
}
