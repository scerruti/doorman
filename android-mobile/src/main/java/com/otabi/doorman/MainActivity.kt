package com.otabi.doorman

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.otabi.doorman.platform.AndroidBluetoothManager
import com.otabi.doorman.domain.SwitchBotDoorController
import com.otabi.doorman.domain.SwitchBotProtocol
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    // TODO: Replace with your actual SwitchBot or Simulator MAC!
    private val targetMac = "3C:84:27:78:A3:B2"
    private val keyHex = "00000000000000000000000000000000"

    // A reactive list holding our terminal output
    private val logs = mutableStateListOf<String>()

    private lateinit var bluetoothManager: AndroidBluetoothManager
    private lateinit var protocol: SwitchBotProtocol
    private lateinit var controller: SwitchBotDoorController

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            log("Permissions granted. Ready to connect.")
        } else {
            log("Permissions denied! App cannot function.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize our KMP shared components exactly like the Mac CLI
        bluetoothManager = AndroidBluetoothManager(this)
        protocol = SwitchBotProtocol(keyHex)
        controller = SwitchBotDoorController(
            bluetoothManager = bluetoothManager,
            macAddress = targetMac,
            protocol = protocol,
            scope = lifecycleScope,
            travelTimeMs = 15000L
        )

        // Listen to state changes in the background
        lifecycleScope.launch {
            controller.state.collect { state ->
                log("Door State Changed: $state")
            }
        }

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    DoormanAppScreen()
                }
            }
        }

        checkAndRequestPermissions()
    }

    private fun log(message: String) {
        // Add to the top of the list so it behaves like a scrolling console
        logs.add(0, "> $message")
    }

    private fun checkAndRequestPermissions() {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            log("Requesting permissions...")
            requestPermissionLauncher.launch(missing.toTypedArray())
        } else {
            log("Permissions already granted.")
        }
    }

    @Composable
    fun DoormanAppScreen() {
        val scope = rememberCoroutineScope()
        var isConnected by remember { mutableStateOf(false) }

        Column(modifier = Modifier.padding(16.dp)) {
            Text("Doorman Mobile Test", style = MaterialTheme.typography.headlineMedium)
            Text("Target: $targetMac", style = MaterialTheme.typography.bodyMedium)
            
            Spacer(modifier = Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Button(onClick = {
                    scope.launch {
                        log("Connecting to $targetMac...")
                        val result = controller.connect()
                        if (result.isSuccess) {
                            isConnected = true
                            log("✅ Connected!")
                        } else {
                            log("❌ Connect failed: ${result.exceptionOrNull()?.message}")
                        }
                    }
                }) { Text("Connect") }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Button(onClick = {
                    scope.launch {
                        log("Opening door...")
                        val result = controller.openDoor()
                        if (result.isFailure) log("Open failed: ${result.exceptionOrNull()?.message}")
                    }
                }, enabled = isConnected) { Text("Open") }

                Button(onClick = {
                    scope.launch {
                        log("Closing door...")
                        val result = controller.closeDoor()
                        if (result.isFailure) log("Close failed: ${result.exceptionOrNull()?.message}")
                    }
                }, enabled = isConnected) { Text("Close") }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text("Console Output:", style = MaterialTheme.typography.titleMedium)
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Console Window
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(logs) { msg ->
                    Text(text = msg, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp))
                }
            }
        }
    }
}