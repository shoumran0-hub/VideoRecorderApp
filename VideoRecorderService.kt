package com.example.backgroundrecorder

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.content.ContentValues
import android.provider.MediaStore

class VideoRecorderService : LifecycleService() {
    
    private lateinit var cameraExecutor: ExecutorService
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private val recordingMutex = Mutex()
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var recordingTimer: Job? = null
    
    companion object {
        private const val CHANNEL_ID = "VideoRecorderChannel"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "VideoRecorder"
        private const val MAX_RECORDING_TIME_MS = 90 * 60 * 1000
    }

    override fun onCreate() {
        super.onCreate()
        cameraExecutor = Executors.newSingleThreadExecutor()
        Log.d(TAG, "Service Created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        
        val lensFacing = intent?.getIntExtra("CAMERA_FACING", CameraSelector.LENS_FACING_BACK) 
            ?: CameraSelector.LENS_FACING_BACK

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        
        serviceScope.launch {
            startVideoRecordingSafe(lensFacing)
        }

        return START_STICKY
    }

    private suspend fun startVideoRecordingSafe(lensFacing: Int) {
        recordingMutex.withLock {
            try {
                startVideoRecording(lensFacing)
                startRecordingTimer()
            } catch (e: Exception) {
                Log.e(TAG, "Error starting recording", e)
                stopSelf()
            }
        }
    }

    private fun startVideoRecording(lensFacing: Int) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                
                if (!hasRequiredPermissions()) {
                    Log.e(TAG, "Missing permissions!")
                    return@addListener
                }

                val name = "Video_${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(System.currentTimeMillis())}.mp4"
                
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/SmartVideoRecorder")
                }

                val mediaStoreOutputOptions = MediaStoreOutputOptions.Builder(
                    contentResolver, 
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                )
                    .setContentValues(contentValues)
                    .build()

                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                    .build()

                videoCapture = VideoCapture.withOutput(recorder)

                cameraProvider.unbindAll()
                val cameraSelector = CameraSelector.Builder()
                    .requireLensFacing(lensFacing)
                    .build()
                
                cameraProvider.bindToLifecycle(
                    this@VideoRecorderService,
                    cameraSelector,
                    videoCapture
                )

                val pendingRecording = videoCapture?.output?.prepareRecording(
                    this@VideoRecorderService,
                    mediaStoreOutputOptions
                )
                
                recording = pendingRecording?.start(ContextCompat.getMainExecutor(this@VideoRecorderService)) { event ->
                    when (event) {
                        is VideoRecordEvent.Start -> {
                            Log.d(TAG, "Recording started - 90 minutes max")
                        }
                        is VideoRecordEvent.Finalize -> {
                            if (event.hasError()) {
                                Log.e(TAG, "Recording error: ${event.error}")
                            } else {
                                Log.d(TAG, "Recording saved: ${event.outputResults.outputUri}")
                            }
                        }
                        else -> {}
                    }
                }

                Log.d(TAG, "Video recording started with file: $name")

            } catch (exc: Exception) {
                Log.e(TAG, "Error starting recording", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startRecordingTimer() {
        recordingTimer?.cancel()
        
        recordingTimer = serviceScope.launch {
            delay(MAX_RECORDING_TIME_MS.toLong())
            Log.d(TAG, "90 minutes reached - stopping recording automatically")
            stopRecordingSafe()
        }
    }

    private suspend fun stopRecordingSafe() {
        recordingMutex.withLock {
            stopRecordingInternal()
        }
    }

    private fun stopRecordingInternal() {
        try {
            recordingTimer?.cancel()
            recording?.stop()
            recording = null
            videoCapture = null
            Log.d(TAG, "Recording stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording", e)
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

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🎬 Smart Video Recorder")
            .setContentText("جاري التسجيل... (90 دقيقة)")
            .setSmallIcon(android.R.drawable.stat_sys_secure)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "تسجيل الفيديو",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(serviceChannel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        
        serviceScope.launch {
            stopRecordingSafe()
            cameraExecutor.shutdown()
            serviceScope.cancel()
        }
        
        Log.d(TAG, "Service Destroyed")
    }
}
