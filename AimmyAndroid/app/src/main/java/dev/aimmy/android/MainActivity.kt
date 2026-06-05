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
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import rikka.shizuku.Shizuku
import java.io.File
import java.io.FileOutputStream
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationSearching
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.GpsFixed

// ─── Colors ────────────────────────────────────────────────────────────────────
val AimmyDark = Color(0xFF16161A)
val AimmyDarker = Color(0xFF0D0D11)
val AimmySurface = Color(0xFF1F1F28)
val AimmyPurple = Color(0xFFB24BF3)
val AimmyPurpleDark = Color(0xFF7B2FBE)
val AimmyPurpleLight = Color(0xFFD4A5FF)
val AimmyGray = Color(0xFF2A2A35)
val AimmyGrayLight = Color(0xFF8E8E9A)
val AimmyGreen = Color(0xFF4ADE80)
val AimmyRed = Color(0xFFEF4444)

// ─── Theme ─────────────────────────────────────────────────────────────────────
@Composable
fun AimmyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Color.Transparent,
            surface = AimmySurface,
            primary = AimmyPurple,
            onPrimary = Color.White,
            onBackground = Color.White,
            onSurface = Color.White,
            surfaceVariant = AimmyGray
        ),
        content = content
    )
}

// ─── Activity ──────────────────────────────────────────────────────────────────
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
                action = "START_PROJECTION"
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            isRunning = true
        } else {
            stopService(Intent(this, ScreenCaptureService::class.java))
            isRunning = false
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
                prefs.edit()
                    .putString("selectedModel", "custom")
                    .putBoolean("useCustomModel", true)
                    .apply()
                Toast.makeText(this, "Custom model imported!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val overlayReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "DEV_AIMMY_START" -> startAimbot()
                "DEV_AIMMY_STOP" -> stopAimbot()
            }
        }
    }

    private val shizukuListener = Shizuku.OnBinderReceivedListener {
        if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(0)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            try {
                val crashFile = File(getExternalFilesDir(null), "aimmy_crash.txt")
                throwable.printStackTrace(java.io.PrintStream(FileOutputStream(crashFile, true)))
            } catch (e: Exception) {}
            kotlin.system.exitProcess(1)
        }

        mediaProjectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        prefs = getSharedPreferences("AimmyPrefs", Context.MODE_PRIVATE)

        // Set default model if never set, or upgrade old default
        val currentModel = prefs.getString("selectedModel", null)
        if (currentModel == null || currentModel == "aio_v7_humanoid.onnx") {
            prefs.edit().putString("selectedModel", "Universal_Hamsta_v4.onnx").apply()
        }

        Shizuku.addBinderReceivedListener(shizukuListener)
        ShizukuTouchInjector.initialize(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val filter = IntentFilter().apply {
                addAction("DEV_AIMMY_START")
                addAction("DEV_AIMMY_STOP")
            }
            androidx.core.content.ContextCompat.registerReceiver(
                this, overlayReceiver, filter,
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
        try { unregisterReceiver(overlayReceiver) } catch (_: Exception) {}
        Shizuku.removeBinderReceivedListener(shizukuListener)
    }

    private fun requestShizuku() {
        try {
            if (Shizuku.pingBinder()) {
                if (Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Shizuku: Permission granted ✓", Toast.LENGTH_SHORT).show()
                } else {
                    Shizuku.requestPermission(0)
                }
            } else {
                Toast.makeText(this, "Shizuku is not running. Please start it first.", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Shizuku error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } else {
            startService(Intent(this, FloatingOverlayService::class.java))
            Toast.makeText(this, "Overlay activated ✓", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startAimbot() {
        screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private fun stopAimbot() {
        stopService(Intent(this, ScreenCaptureService::class.java))
        isRunning = false
    }
}

// ─── Main Scaffold ─────────────────────────────────────────────────────────────
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
    var selectedTab by remember { mutableIntStateOf(0) }

    data class TabItem(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)
    val tabs = listOf(
        TabItem("General", androidx.compose.material.icons.Icons.Filled.Home),
        TabItem("Aimbot", androidx.compose.material.icons.Icons.Filled.LocationSearching),
        TabItem("Visuals", androidx.compose.material.icons.Icons.Filled.Visibility),
        TabItem("Settings", androidx.compose.material.icons.Icons.Filled.Settings)
    )

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = androidx.compose.material.icons.Icons.Filled.GpsFixed,
                            contentDescription = null,
                            tint = AimmyPurple,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "AIMMY",
                            fontWeight = FontWeight.Black,
                            fontSize = 22.sp,
                            letterSpacing = 4.sp,
                            color = AimmyPurpleLight
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = AimmyDarker
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = AimmyDark,
                tonalElevation = 0.dp
            ) {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Icon(imageVector = tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label, fontSize = 11.sp, maxLines = 1) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedTextColor = AimmyPurple,
                            selectedIconColor = AimmyPurple,
                            unselectedTextColor = AimmyGrayLight,
                            unselectedIconColor = AimmyGrayLight,
                            indicatorColor = AimmyPurple.copy(alpha = 0.15f)
                        )
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFF1A0A2E), AimmyDarker)
                    )
                )
        ) {
            when (selectedTab) {
                0 -> GeneralTab(isRunning, onStartAimbot, onStopAimbot, onRequestShizuku, onRequestOverlay, onImportModel, prefs)
                1 -> AimbotTab(prefs)
                2 -> VisualsTab(prefs)
                3 -> SettingsTab(prefs)
            }
        }
    }
}

// ─── General Tab ───────────────────────────────────────────────────────────────
@Composable
fun GeneralTab(
    isRunning: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRequestShizuku: () -> Unit,
    onRequestOverlay: () -> Unit,
    onImportModel: () -> Unit,
    prefs: SharedPreferences
) {
    val scrollState = rememberScrollState()
    val statusColor by animateColorAsState(
        targetValue = if (isRunning) AimmyGreen else AimmyRed,
        animationSpec = tween(500), label = "statusColor"
    )

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Status Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(16.dp)),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = AimmySurface.copy(alpha = 0.6f))
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Aimbot Status", fontSize = 14.sp, color = AimmyGrayLight)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.size(10.dp).clip(CircleShape).background(statusColor)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (isRunning) "ACTIVE" else "INACTIVE",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = statusColor
                        )
                    }
                }
                Switch(
                    checked = isRunning,
                    onCheckedChange = { if (it) onStart() else onStop() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = AimmyPurple,
                        uncheckedThumbColor = AimmyGrayLight,
                        uncheckedTrackColor = AimmyGray
                    )
                )
            }
        }

        // Model Selector Card
        SectionCard("Model Selection") {
            val selectedModel = prefs.getString("selectedModel", "aio_v7_humanoid.onnx") ?: "aio_v7_humanoid.onnx"
            val models = listOf(
                "Universal_Hamsta_v4.onnx" to "Universal Hamsta (V4)",
                "aio_v10.onnx" to "AIO V10 — Universal",
                "aio_v7_humanoid.onnx" to "AIO V7 — Humanoid Body"
            )

            models.forEach { (filename, label) ->
                val isSelected = selectedModel == filename
                val bgColor by animateColorAsState(
                    if (isSelected) AimmyPurple.copy(alpha = 0.2f) else Color.Transparent,
                    label = "modelBg"
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(bgColor)
                        .border(
                            width = if (isSelected) 1.dp else 0.dp,
                            color = if (isSelected) AimmyPurple else Color.Transparent,
                            shape = RoundedCornerShape(10.dp)
                        )
                        .clickable {
                            prefs.edit()
                                .putString("selectedModel", filename)
                                .putBoolean("useCustomModel", false)
                                .apply()
                        }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = isSelected,
                        onClick = null,
                        colors = RadioButtonDefaults.colors(selectedColor = AimmyPurple)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(label, fontWeight = FontWeight.SemiBold)
                        Text(filename, fontSize = 12.sp, color = AimmyGrayLight)
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // Custom model option
            val isCustom = prefs.getBoolean("useCustomModel", false)
            if (isCustom) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(AimmyPurple.copy(alpha = 0.2f))
                        .border(1.dp, AimmyPurple, RoundedCornerShape(10.dp))
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = true, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = AimmyPurple))
                    Spacer(Modifier.width(12.dp))
                    Text("Custom Model (Imported)", fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(8.dp))
            }

            GradientButton("Import Custom Model (.onnx)", onClick = onImportModel)
        }

        // Setup Steps
        SectionCard("Setup") {
            ActionButton("1. Request Shizuku Permission", AimmyPurple, onClick = onRequestShizuku)
            Spacer(Modifier.height(10.dp))
            ActionButton("2. Enable In-Game Overlay", AimmyGray, onClick = onRequestOverlay)
        }
    }
}

// ─── Aimbot Tab ────────────────────────────────────────────────────────────────
@Composable
fun AimbotTab(prefs: SharedPreferences) {
    var fov by remember { mutableFloatStateOf(prefs.getFloat("fov", 150f)) }
    var speed by remember { mutableFloatStateOf(prefs.getFloat("speed", 50f)) }
    var confidence by remember { mutableFloatStateOf(prefs.getFloat("confidence", 60f)) }
    var offsetX by remember { mutableFloatStateOf(prefs.getFloat("offsetX", 0f)) }
    var offsetY by remember { mutableFloatStateOf(prefs.getFloat("offsetY", 0f)) }

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SectionCard("Targeting") {
            AimmySlider("FOV Size", fov, 10f, 640f, "px") {
                fov = it; prefs.edit().putFloat("fov", it).apply()
            }
            AimmySlider("Aim Speed", speed, 1f, 100f, "%") {
                speed = it; prefs.edit().putFloat("speed", it).apply()
            }
            AimmySlider("Confidence", confidence, 10f, 100f, "%") {
                confidence = it; prefs.edit().putFloat("confidence", it).apply()
            }
        }

        SectionCard("Aim Offset") {
            AimmySlider("X Offset (Left ↔ Right)", offsetX, -200f, 200f, "px") {
                offsetX = it; prefs.edit().putFloat("offsetX", it).apply()
            }
            AimmySlider("Y Offset (Up ↕ Down)", offsetY, -200f, 200f, "px") {
                offsetY = it; prefs.edit().putFloat("offsetY", it).apply()
            }
        }
    }
}

// ─── Visuals Tab ───────────────────────────────────────────────────────────────
@Composable
fun VisualsTab(prefs: SharedPreferences) {
    var showFov by remember { mutableStateOf(prefs.getBoolean("showFov", false)) }
    var triggerSize by remember { mutableFloatStateOf(prefs.getFloat("triggerSize", 120f)) }

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SectionCard("Overlay Visuals") {
            ToggleRow("Show FOV Circle", "Draws the FOV radius on the overlay", showFov) {
                showFov = it; prefs.edit().putBoolean("showFov", it).apply()
            }
        }
        
        SectionCard("On-Screen Controls") {
            AimmySlider("Aim Trigger Size", triggerSize, 60f, 300f, "px") {
                triggerSize = it; prefs.edit().putFloat("triggerSize", it).apply()
            }
        }
    }
}

// ─── Settings Tab ──────────────────────────────────────────────────────────────
@Composable
fun SettingsTab(prefs: SharedPreferences) {
    var ecoMode by remember { mutableStateOf(prefs.getBoolean("ecoMode", true)) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SectionCard("Performance") {
            ToggleRow(
                "Eco Mode",
                "Caps inference to ~30 FPS. Reduces heat & battery drain.",
                ecoMode
            ) { ecoMode = it; prefs.edit().putBoolean("ecoMode", it).apply() }
        }

        SectionCard("About") {
            Text("Aimmy v1.2 — Android Port", color = AimmyGrayLight, fontSize = 13.sp)
            Text("Engine: ONNX Runtime + Shizuku", color = AimmyGrayLight, fontSize = 13.sp)
            Text("Models: AIO V7 & V10 pre-loaded", color = AimmyGrayLight, fontSize = 13.sp)
        }
    }
}

// ─── Reusable UI Components ────────────────────────────────────────────────────
@Composable
fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = AimmySurface.copy(alpha = 0.6f))
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AimmyPurpleLight)
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
fun AimmySlider(label: String, value: Float, min: Float, max: Float, unit: String, onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 14.sp, color = Color.White)
            Text("${value.toInt()}$unit", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AimmyPurpleLight)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = min..max,
            colors = SliderDefaults.colors(
                thumbColor = AimmyPurple,
                activeTrackColor = AimmyPurple,
                inactiveTrackColor = AimmyGray
            )
        )
    }
}

@Composable
fun ToggleRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, fontSize = 12.sp, color = AimmyGrayLight)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = AimmyPurple,
                uncheckedThumbColor = AimmyGrayLight,
                uncheckedTrackColor = AimmyGray
            )
        )
    }
}

@Composable
fun ActionButton(text: String, color: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color)
    ) {
        Text(text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

@Composable
fun GradientButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.horizontalGradient(listOf(AimmyPurpleDark, AimmyPurple)))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.White)
    }
}
