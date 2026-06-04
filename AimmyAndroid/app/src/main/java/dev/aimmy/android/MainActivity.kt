package dev.aimmy.android

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import rikka.shizuku.Shizuku
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var prefs: SharedPreferences
    
    private var isRunning by mutableStateOf(false)

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra("RESULT_CODE", result.resultCode)
                putExtra("DATA", result.data)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            isRunning = true
            Toast.makeText(this, "Aimbot started", Toast.LENGTH_SHORT).show()
        } else {
            isRunning = false
            Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    private val pickModelLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val inputStream = contentResolver.openInputStream(uri)
                val outFile = File(filesDir, "custom_model.onnx")
                val outputStream = FileOutputStream(outFile)
                inputStream?.copyTo(outputStream)
                inputStream?.close()
                outputStream.close()
                
                prefs.edit().putBoolean("useCustomModel", true).apply()
                Toast.makeText(this, "Model imported successfully!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this, "Failed to import model", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val overlayReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "DEV_AIMMY_START") {
                startAimbot()
            } else if (intent?.action == "DEV_AIMMY_STOP") {
                stopAimbot()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        prefs = getSharedPreferences("AimmyPrefs", Context.MODE_PRIVATE)
        
        ShizukuTouchInjector.initialize()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val filter = IntentFilter()
            filter.addAction("DEV_AIMMY_START")
            filter.addAction("DEV_AIMMY_STOP")
            androidx.core.content.ContextCompat.registerReceiver(
                this, 
                overlayReceiver, 
                filter, 
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }

        setContent {
            AimmyTheme {
                AimmyApp(
                    prefs = prefs,
                    isRunning = isRunning,
                    onRequestShizuku = { requestShizuku() },
                    onRequestOverlay = { requestOverlayPermission() },
                    onStartAimbot = { startAimbot() },
                    onStopAimbot = { stopAimbot() },
                    onImportModel = { pickModelLauncher.launch("*/*") }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(overlayReceiver)
    }

    private fun requestShizuku() {
        if (Shizuku.pingBinder()) {
            if (Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Shizuku permission already granted", Toast.LENGTH_SHORT).show()
            } else {
                Shizuku.requestPermission(0)
            }
        } else {
            Toast.makeText(this, "Shizuku is not running", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } else {
            startService(Intent(this, FloatingOverlayService::class.java))
            Toast.makeText(this, "Overlay enabled", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startAimbot() {
        screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private fun stopAimbot() {
        val intent = Intent(this, ScreenCaptureService::class.java)
        stopService(intent)
        isRunning = false
        Toast.makeText(this, "Aimbot stopped.", Toast.LENGTH_SHORT).show()
    }
}

val AimmyDark = Color(0xFF1E1E1E)
val AimmyDarker = Color(0xFF121212)
val AimmyPurple = Color(0xFF9C27B0)
val AimmyPurpleLight = Color(0xFFE1BEE7)
val AimmyGray = Color(0xFF2D2D2D)

@Composable
fun AimmyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = AimmyDarker,
            surface = AimmyDark,
            primary = AimmyPurple,
            onPrimary = Color.White,
            onBackground = Color.White,
            onSurface = Color.White
        ),
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AimmyApp(
    prefs: SharedPreferences,
    isRunning: Boolean,
    onRequestShizuku: () -> Unit,
    onRequestOverlay: () -> Unit,
    onStartAimbot: () -> Unit,
    onStopAimbot: () -> Unit,
    onImportModel: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("General", "Aimbot", "Visuals", "Settings")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Aimmy", fontWeight = FontWeight.Bold, color = AimmyPurpleLight) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AimmyDarker
                )
            )
        },
        bottomBar = {
            NavigationBar(containerColor = AimmyDark) {
                tabs.forEachIndexed { index, title ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { /* Icons can be added later */ },
                        label = { Text(title) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = AimmyPurple,
                            selectedTextColor = AimmyPurple,
                            indicatorColor = AimmyGray
                        )
                    )
                }
            }
        }
    ) { paddingValues ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            color = AimmyDarker
        ) {
            when (selectedTab) {
                0 -> GeneralTab(isRunning, {
                    if (it) onStartAimbot() else onStopAimbot()
                }, onRequestShizuku, onRequestOverlay, onImportModel, prefs)
                1 -> AimbotTab(prefs)
                2 -> VisualsTab()
                3 -> SettingsTab(prefs)
            }
        }
    }
}

@Composable
fun GeneralTab(
    isRunning: Boolean, 
    onToggle: (Boolean) -> Unit, 
    onRequestShizuku: () -> Unit,
    onRequestOverlay: () -> Unit,
    onImportModel: () -> Unit,
    prefs: SharedPreferences
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = AimmyDark)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Status", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = AimmyPurpleLight)
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Aimbot Active", fontSize = 18.sp)
                    Switch(
                        checked = isRunning,
                        onCheckedChange = onToggle,
                        colors = SwitchDefaults.colors(checkedThumbColor = AimmyPurple)
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Button(
            onClick = onRequestShizuku,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AimmyPurple)
        ) {
            Text("1. Request Shizuku Permission", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onRequestOverlay,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AimmyGray)
        ) {
            Text("2. Enable In-Game Overlay", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(24.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = AimmyDark)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Model", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = AimmyPurpleLight)
                Spacer(modifier = Modifier.height(8.dp))
                val isCustom = prefs.getBoolean("useCustomModel", false)
                Text("Current: ${if (isCustom) "Custom ONNX" else "Default Asset"}", color = Color.LightGray)
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onImportModel,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AimmyPurple)
                ) {
                    Text("Import Custom Model (.onnx)", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun AimbotTab(prefs: SharedPreferences) {
    var fov by remember { mutableStateOf(prefs.getFloat("fov", 150f)) }
    var speed by remember { mutableStateOf(prefs.getFloat("speed", 50f)) }
    var confidence by remember { mutableStateOf(prefs.getFloat("confidence", 60f)) }
    
    // New Advanced Offset Sliders exactly like PC
    var offsetX by remember { mutableStateOf(prefs.getFloat("offsetX", 0f)) }
    var offsetY by remember { mutableStateOf(prefs.getFloat("offsetY", 0f)) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        SettingSlider("FOV Size", fov, 10f, 300f) { 
            fov = it
            prefs.edit().putFloat("fov", it).apply()
        }
        Spacer(modifier = Modifier.height(8.dp))
        SettingSlider("Aim Speed", speed, 1f, 100f) { 
            speed = it
            prefs.edit().putFloat("speed", it).apply()
        }
        Spacer(modifier = Modifier.height(8.dp))
        SettingSlider("Confidence Threshold (%)", confidence, 10f, 100f) { 
            confidence = it
            prefs.edit().putFloat("confidence", it).apply()
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text("Advanced Targeting", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = AimmyPurpleLight)
        Spacer(modifier = Modifier.height(8.dp))
        
        SettingSlider("X-Axis Offset (Left/Right)", offsetX, -100f, 100f) { 
            offsetX = it
            prefs.edit().putFloat("offsetX", it).apply()
        }
        Spacer(modifier = Modifier.height(8.dp))
        SettingSlider("Y-Axis Offset (Up/Down)", offsetY, -100f, 100f) { 
            offsetY = it
            prefs.edit().putFloat("offsetY", it).apply()
        }
    }
}

@Composable
fun VisualsTab() {
    var showFov by remember { mutableStateOf(true) }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Show FOV Circle (Requires Overlay)", fontSize = 16.sp)
            Switch(
                checked = showFov,
                onCheckedChange = { showFov = it },
                colors = SwitchDefaults.colors(checkedThumbColor = AimmyPurple)
            )
        }
    }
}

@Composable
fun SettingsTab(prefs: SharedPreferences) {
    var ecoMode by remember { mutableStateOf(prefs.getBoolean("ecoMode", true)) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("Performance", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = AimmyPurpleLight)
        Spacer(modifier = Modifier.height(16.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Eco Mode", fontSize = 18.sp)
                Text("Reduces heat & battery drain by capping inference to 30 FPS", fontSize = 12.sp, color = Color.Gray)
            }
            Switch(
                checked = ecoMode,
                onCheckedChange = { 
                    ecoMode = it
                    prefs.edit().putBoolean("ecoMode", it).apply()
                },
                colors = SwitchDefaults.colors(checkedThumbColor = AimmyPurple)
            )
        }

        Spacer(modifier = Modifier.height(32.dp))
        Text("Version 1.2 (Exact Android Port)", color = Color.Gray)
        Spacer(modifier = Modifier.height(8.dp))
        Text("Powered by ONNX Runtime & Shizuku", color = Color.Gray)
    }
}

@Composable
fun SettingSlider(name: String, value: Float, min: Float, max: Float, onValueChange: (Float) -> Unit) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(name, fontSize = 16.sp)
            Text(String.format("%.0f", value), fontSize = 16.sp, color = AimmyPurpleLight)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = min..max,
            colors = SliderDefaults.colors(
                thumbColor = AimmyPurple,
                activeTrackColor = AimmyPurple
            )
        )
    }
}
