package dev.aimmy.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
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

class MainActivity : ComponentActivity() {

    private lateinit var mediaProjectionManager: MediaProjectionManager

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
            Toast.makeText(this, "Aimbot started", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
            // Reset UI state if needed
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        
        ShizukuTouchInjector.initialize()

        setContent {
            AimmyTheme {
                AimmyApp(
                    onRequestShizuku = { requestShizuku() },
                    onStartAimbot = { startAimbot() },
                    onStopAimbot = { stopAimbot() }
                )
            }
        }
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

    private fun startAimbot() {
        screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private fun stopAimbot() {
        val intent = Intent(this, ScreenCaptureService::class.java)
        stopService(intent)
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
    onRequestShizuku: () -> Unit,
    onStartAimbot: () -> Unit,
    onStopAimbot: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("General", "Aimbot", "Visuals", "Settings")
    
    var isRunning by remember { mutableStateOf(false) }

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
                        icon = { /* Add icons if needed */ },
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
                    isRunning = it
                    if (it) onStartAimbot() else onStopAimbot()
                }, onRequestShizuku)
                1 -> AimbotTab()
                2 -> VisualsTab()
                3 -> SettingsTab()
            }
        }
    }
}

@Composable
fun GeneralTab(isRunning: Boolean, onToggle: (Boolean) -> Unit, onRequestShizuku: () -> Unit) {
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
            Text("Request Shizuku Permission", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun AimbotTab() {
    var fov by remember { mutableStateOf(100f) }
    var speed by remember { mutableStateOf(50f) }
    var confidence by remember { mutableStateOf(60f) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        SettingSlider("FOV Size", fov, 10f, 300f) { fov = it }
        Spacer(modifier = Modifier.height(16.dp))
        SettingSlider("Aim Speed", speed, 1f, 100f) { speed = it }
        Spacer(modifier = Modifier.height(16.dp))
        SettingSlider("Confidence Threshold", confidence, 10f, 100f) { confidence = it }
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
            Text("Show FOV Circle", fontSize = 18.sp)
            Switch(
                checked = showFov,
                onCheckedChange = { showFov = it },
                colors = SwitchDefaults.colors(checkedThumbColor = AimmyPurple)
            )
        }
    }
}

@Composable
fun SettingsTab() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Version 1.0 (Android Port)", color = Color.Gray)
        Spacer(modifier = Modifier.height(16.dp))
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
