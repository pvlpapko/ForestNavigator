package app.forestnav

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import app.forestnav.ui.AppViewModel
import app.forestnav.ui.ForestNavRoot

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private var fineLocationGranted by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        fineLocationGranted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true || hasFineLocation()
        if (fineLocationGranted) vm.startForegroundSensors()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        fineLocationGranted = hasFineLocation()
        setContent {
            ForestNavRoot(
                vm = vm,
                hasFineLocation = fineLocationGranted,
                requestPermissions = ::requestPermissions
            )
        }
        if (fineLocationGranted) vm.startForegroundSensors()
    }

    override fun onResume() {
        super.onResume()
        fineLocationGranted = hasFineLocation()
        if (fineLocationGranted) vm.startForegroundSensors()
    }

    override fun onPause() {
        vm.stopForegroundSensors()
        super.onPause()
    }

    private fun hasFineLocation() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        val list = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
        permissionLauncher.launch(list.toTypedArray())
    }
}
