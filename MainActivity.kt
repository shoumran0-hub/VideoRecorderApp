package com.example.backgroundrecorder

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri

object ProColors {
    val Purple = Color(0xFF7C3AED)
    val ElectricBlue = Color(0xFF0EA5E9)
    val Pink = Color(0xFFEC4899)
    val Orange = Color(0xFFF97316)
    val Green = Color(0xFF10B981)
    val DarkBg = Color(0xFF0F172A)
    val CardBg = Color(0xFF1E293B)
}

class MainActivity : ComponentActivity() {
    private var isRecording by mutableStateOf(false)
    private var selectedCameraFacing by mutableStateOf(CameraSelector.LENS_FACING_BACK)
    private var currentScreen by mutableStateOf("main")
    private var recordingTimeLeft by mutableStateOf(90 * 60)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (!allGranted) {
            Toast.makeText(this, "صلاحيات مرفوضة!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS
            )
        )

        setContent {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(ProColors.DarkBg, ProColors.CardBg)
                        )
                    )
            ) {
                when (currentScreen) {
                    "main" -> MainScreen(
                        isRecording = isRecording,
                        recordingTimeLeft = recordingTimeLeft,
                        onToggleRecording = { toggleRecording() },
                        onOpenSettings = { currentScreen = "settings" },
                        onOpenGallery = { currentScreen = "gallery" }
                    )
                    "settings" -> SettingsScreen(
                        currentFacing = selectedCameraFacing,
                        onCameraSelected = { facing ->
                            selectedCameraFacing = facing
                            currentScreen = "main"
                        },
                        onBack = { currentScreen = "main" }
                    )
                    "gallery" -> GalleryScreen(
                        context = this@MainActivity,
                        onBack = { currentScreen = "main" }
                    )
                }
            }
        }

        if (isRecording) {
            updateTimer()
        }
    }

    private fun updateTimer() {
        object : Thread() {
            override fun run() {
                while (isRecording) {
                    recordingTimeLeft--
                    if (recordingTimeLeft <= 0) {
                        toggleRecording()
                    }
                    Thread.sleep(1000)
                }
            }
        }.start()
    }

    private fun toggleRecording() {
        if (!isRecording && !hasRequiredPermissions()) {
            Toast.makeText(this, "الرجاء منح الصلاحيات", Toast.LENGTH_SHORT).show()
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO
                )
            )
            return
        }

        val intent = Intent(this, VideoRecorderService::class.java).apply {
            putExtra("CAMERA_FACING", selectedCameraFacing)
        }

        if (isRecording) {
            stopService(intent)
            isRecording = false
            recordingTimeLeft = 90 * 60
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            isRecording = true
            updateTimer()
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        return arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        ).all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}

@Composable
fun MainScreen(
    isRecording: Boolean,
    recordingTimeLeft: Int,
    onToggleRecording: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenGallery: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "")
    
    val pulsing by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = EaseInOutQuad),
            repeatMode = RepeatMode.Reverse
        ),
        label = ""
    )

    val minutes = recordingTimeLeft / 60
    val seconds = recordingTimeLeft % 60
    val timeText = String.format("%02d:%02d", minutes, seconds)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.VideoLibrary,
                contentDescription = null,
                tint = ProColors.ElectricBlue,
                modifier = Modifier.size(32.dp)
            )
            Text(
                text = "Smart Video Recorder",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(250.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(200.dp)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                ProColors.Purple.copy(alpha = 0.3f),
                                ProColors.Purple.copy(alpha = 0.1f)
                            )
                        ),
                        shape = CircleShape
                    )
                    .scale(if (isRecording) pulsing else 1f)
            )

            Button(
                onClick = onToggleRecording,
                modifier = Modifier
                    .size(150.dp)
                    .shadow(20.dp, shape = CircleShape),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRecording) ProColors.Pink else ProColors.ElectricBlue
                )
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(50.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (isRecording) "Stop" else "Start",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                }
            }
        }

        if (isRecording) {
            Card(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 80.dp)
                    .shadow(10.dp, shape = RoundedCornerShape(16.dp)),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = ProColors.CardBg)
            ) {
                Text(
                    text = timeText,
                    color = ProColors.ElectricBlue,
                    fontSize = 48.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    modifier = Modifier.padding(24.dp)
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onOpenGallery,
                modifier = Modifier
                    .size(70.dp)
                    .background(ProColors.Pink, shape = CircleShape)
                    .shadow(12.dp, shape = CircleShape)
            ) {
                Icon(
                    Icons.Default.VideoLibrary,
                    contentDescription = "Videos",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }

            Spacer(modifier = Modifier.width(1.dp))

            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .size(70.dp)
                    .background(ProColors.Orange, shape = CircleShape)
                    .shadow(12.dp, shape = CircleShape)
            ) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }

            Spacer(modifier = Modifier.width(1.dp))

            Box(
                modifier = Modifier
                    .size(70.dp)
                    .background(ProColors.Green, shape = CircleShape)
                    .shadow(12.dp, shape = CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("ℹ️", fontSize = 28.sp)
            }
        }
    }
}

@Composable
fun SettingsScreen(currentFacing: Int, onCameraSelected: (Int) -> Unit, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "Camera Settings",
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = { onCameraSelected(CameraSelector.LENS_FACING_BACK) },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (currentFacing == CameraSelector.LENS_FACING_BACK)
                    ProColors.ElectricBlue else ProColors.CardBg
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .shadow(8.dp, shape = RoundedCornerShape(12.dp))
        ) {
            Text("Back Camera", fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = { onCameraSelected(CameraSelector.LENS_FACING_FRONT) },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (currentFacing == CameraSelector.LENS_FACING_FRONT)
                    ProColors.ElectricBlue else ProColors.CardBg
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .shadow(8.dp, shape = RoundedCornerShape(12.dp))
        ) {
            Text("Front Camera", fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(32.dp))
        TextButton(onClick = onBack) {
            Text("← Back to Home", color = ProColors.ElectricBlue, fontSize = 18.sp)
        }
    }
}

data class VideoItem(val id: Long, val name: String, val uri: Uri)

@Composable
fun GalleryScreen(context: Context, onBack: () -> Unit) {
    var videoList by remember { mutableStateOf(listOf<VideoItem>()) }

    LaunchedEffect(Unit) {
        val videos = mutableListOf<VideoItem>()
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME)
        
        context.contentResolver.query(collection, projection, null, null, "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                videos.add(VideoItem(cursor.getLong(idCol), cursor.getString(nameCol) ?: "Video", ContentUris.withAppendedId(collection, cursor.getLong(idCol))))
            }
        }
        videoList = videos
    }

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
            Text(
                "Recorded Videos",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            )
            TextButton(onClick = onBack) {
                Text("← Back", color = ProColors.ElectricBlue, fontSize = 16.sp)
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        if (videoList.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("No recorded videos found", color = Color.Gray, fontSize = 16.sp)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(videoList) { video ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .shadow(8.dp, shape = RoundedCornerShape(12.dp)),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = ProColors.CardBg)
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(16.dp)
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                Icons.Default.VideoLibrary,
                                contentDescription = null,
                                tint = ProColors.ElectricBlue,
                                modifier = Modifier.size(24.dp)
                            )
                            Text(text = video.name, color = Color.White, fontSize = 16.sp)
                        }
                    }
                }
            }
        }
    }
}
