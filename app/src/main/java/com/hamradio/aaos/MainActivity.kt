package com.hamradio.aaos

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hamradio.aaos.radio.transport.ConnectionState
import com.hamradio.aaos.service.RadioLinkService
import com.hamradio.aaos.ui.AppNavigation
import com.hamradio.aaos.ui.driving.CarDrivingState
import com.hamradio.aaos.ui.driving.DrivingScreen
import com.hamradio.aaos.ui.theme.HamRadioTheme
import com.hamradio.aaos.vm.MainViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()
    private lateinit var driving: CarDrivingState

    /** Runtime permissions the radio link needs: Bluetooth to connect (and for the foreground
     *  service), the microphone for PTT, and notifications for the link's status notification. */
    private val runtimePermissions: Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)
        }
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    private fun missingPermissions() = runtimePermissions.filter {
        ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        driving = CarDrivingState(applicationContext).also { it.start() }
        setContent {
            HamRadioTheme {
                // Ask once on launch for everything the link needs, then (re)connect. Before this the
                // only prompt was the scan button, so a manually entered radio never got Bluetooth or
                // mic access and the foreground service failed silently.
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) {
                    vm.connect()
                    if (vm.connectionState.value == ConnectionState.CONNECTED) RadioLinkService.start(this@MainActivity)
                }
                LaunchedEffect(Unit) {
                    val missing = missingPermissions()
                    if (missing.isEmpty()) vm.connect() else permissionLauncher.launch(missing.toTypedArray())
                }
                // Keep the process in the foreground while the radio is linked
                LaunchedEffect(Unit) {
                    vm.connectionState.collect { state ->
                        when (state) {
                            ConnectionState.CONNECTED -> RadioLinkService.start(this@MainActivity)
                            ConnectionState.DISCONNECTED, ConnectionState.ERROR -> RadioLinkService.stop(this@MainActivity)
                            else -> Unit
                        }
                    }
                }
                // While moving, the head unit only allows distraction-optimized UI.
                val restricted by driving.requiresDistractionOptimization.collectAsStateWithLifecycle()
                if (restricted) {
                    DrivingScreen(vm = vm)
                } else {
                    AppNavigation(vm = vm)
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // PTT is an on-screen control. Background mic capture isn't covered by the foreground service,
        // so never keep transmitting once the app leaves the screen.
        vm.pttUp()
    }

    override fun onDestroy() {
        super.onDestroy()
        driving.stop()
        // Only on a real exit. A recreate keeps the ViewModel and the link; disconnecting here raced
        // the new composition's connect() and could leave the radio disconnected.
        if (isFinishing) {
            vm.disconnect()
            RadioLinkService.stop(this)
        }
    }
}
