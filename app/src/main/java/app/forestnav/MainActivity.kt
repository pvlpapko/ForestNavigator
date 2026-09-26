package app.forestnav

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import app.forestnav.service.OfflineMapDownloadService
import app.forestnav.service.TrackRecordingService
import app.forestnav.ui.AppViewModel
import app.forestnav.ui.ForestNavRoot

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private var fineLocationGranted by mutableStateOf(false)
    private var lastBackPressAt = 0L

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        fineLocationGranted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true || hasFineLocation()
        if (fineLocationGranted) {
            vm.startForegroundSensors()
            requestNotificationPermissionIfNeeded()
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    handleDoubleBackExit()
                }
            }
        )
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
        if (fineLocationGranted) {
            vm.startForegroundSensors()
            requestNotificationPermissionIfNeeded()
        }
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

    private fun handleDoubleBackExit() {
        val now = SystemClock.elapsedRealtime()

        if (now - lastBackPressAt > BACK_EXIT_WINDOW_MS) {
            lastBackPressAt = now
            Toast.makeText(
                this,
                "Нажмите «Назад» ещё раз, чтобы закрыть приложение",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        Toast.makeText(
            this,
            "Лес Навигатор закрыт",
            Toast.LENGTH_SHORT
        ).show()

        vm.stopForegroundSensors()
        TrackRecordingService.stop(this)
        OfflineMapDownloadService.stopAll(this)

        finishAndRemoveTask()

        Handler(Looper.getMainLooper())
            .postDelayed(
                {
                    Process.killProcess(Process.myPid())
                },
                PROCESS_EXIT_DELAY_MS
            )
    }

    private fun hasFineLocation() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
    }

    private fun requestPermissions() {
        val list = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
        permissionLauncher.launch(list.toTypedArray())
    }

    companion object {
        private const val BACK_EXIT_WINDOW_MS = 2_000L
        private const val PROCESS_EXIT_DELAY_MS = 700L
    }
}
